//! Archives: zips made, and every kind the app opens - zip, 7z, RAR and tar,
//! plain or compressed - listed and extracted.

use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::{FileError, Result};
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;
use zip::{AesMode, CompressionMethod, ZipArchive, ZipWriter};

#[derive(Debug, Clone, uniffi::Record)]
pub struct ArchiveEntry {
    /// Path as stored inside the archive, always with forward slashes.
    pub name: String,
    pub size: u64,
    pub compressed_size: u64,
    pub is_dir: bool,
    pub modified_ms: u64,
}

/// List an archive's contents without extracting it - what extracting it
/// would write, so it can be shown before anything is.
///
/// Every kind the app extracts. Names that extraction would skip - climbing
/// out of the folder, absolute, or links - are left out here too, so the list
/// is what will arrive, under the names it will arrive as.
///
/// A zip never encrypts its index, so it lists without a password; done with
/// the decrypting reader instead, listing a protected zip failed outright. A
/// 7z or RAR can encrypt its names as well, and one of those lists only with
/// `password` - without it the answer is PasswordRequired.
#[uniffi::export]
pub fn archive_list(archive_path: String, password: Option<String>) -> Result<Vec<ArchiveEntry>> {
    let path = Path::new(&archive_path);
    match detect(path)? {
        Format::Zip => list_zip(path),
        Format::SevenZ => list_7z(path, password),
        Format::Rar => list_rar(path, password),
        Format::Tar => {
            let file = File::open(path).map_err(|e| FileError::from_io(e, path))?;
            list_tar(BufReader::new(file))
        }
        Format::Compressed(kind) => list_compressed(path, kind),
    }
}

/// One entry as listed: under the name extraction would write it as, or None
/// for one extraction would skip.
fn listed(name: &str, size: u64, compressed_size: u64, is_dir: bool, modified_ms: u64) -> Option<ArchiveEntry> {
    let safe = sanitize_entry_name(name)?;
    Some(ArchiveEntry {
        name: safe.to_string_lossy().into_owned(),
        size,
        compressed_size,
        is_dir,
        modified_ms,
    })
}

/// Milliseconds since the epoch for a calendar date and wall-clock time, read
/// as UTC: the formats that store one this way store no zone with it.
fn civil_ms(year: i64, month: i64, day: i64, hour: i64, minute: i64, second: i64) -> u64 {
    // Days from 1970-01-01 to the date, by the usual civil-calendar formula.
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * ((month + 9) % 12) + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;

    let seconds = days * 86_400 + hour * 3_600 + minute * 60 + second;
    u64::try_from(seconds).map_or(0, |s| s * 1_000)
}

/// A zip timestamp as milliseconds since the epoch.
///
/// Every entry used to be stamped with the moment it was listed. A zip stores
/// a wall-clock time and no zone, so this is that time read as UTC: formatted
/// in UTC it shows what the archive says, which is all there is to know.
fn zip_time_ms(time: zip::DateTime) -> u64 {
    civil_ms(
        i64::from(time.year()),
        i64::from(time.month()),
        i64::from(time.day()),
        i64::from(time.hour()),
        i64::from(time.minute()),
        i64::from(time.second()),
    )
}

/// An MS-DOS date and time, as RAR stores them, in milliseconds since the epoch.
fn dos_time_ms(stamp: u32) -> u64 {
    let (date, time) = (i64::from(stamp >> 16), i64::from(stamp & 0xFFFF));
    civil_ms(
        1980 + ((date >> 9) & 0x7F),
        (date >> 5) & 0x0F,
        date & 0x1F,
        time >> 11,
        (time >> 5) & 0x3F,
        (time & 0x1F) * 2,
    )
}

fn system_time_ms(time: SystemTime) -> u64 {
    time.duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

/// Zip `sources` into `dest_path`. Directories go in recursively.
///
/// A non-empty `password` encrypts every file in the archive with AES-256.
/// That is the strong option rather than the zip format's original scheme,
/// which is broken and recoverable in seconds; the trade is that Windows
/// Explorer cannot open an AES archive on its own, while 7-Zip, WinRAR, and
/// this app all can.
///
/// Only the contents are protected. A zip's index is not encrypted by either
/// scheme, so the names and sizes of the files inside stay readable to anyone
/// holding the archive - worth knowing before trusting one with something
/// whose *name* is the secret.
#[uniffi::export]
pub fn archive_create(
    sources: Vec<String>,
    dest_path: String,
    password: Option<String>,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let dest = Path::new(&dest_path);
    if dest.exists() {
        return Err(FileError::AlreadyExists { path: dest_path });
    }

    let total = crate::scanner::tree_stats(sources.clone(), cancel.clone())?.file_count;
    let file = File::create(dest).map_err(|e| FileError::from_io(e, dest))?;

    // Nothing is left behind unless it is the whole archive. A half-written
    // zip is not an archive of anything, and one abandoned by a failure or a
    // cancel sat beside the originals looking like a backup of them.
    let written = write_archive(file, &sources, password, total, &listener, &cancel);
    if written.is_err() {
        let _ = std::fs::remove_file(dest);
    }
    written
}

fn write_archive(
    file: File,
    sources: &[String],
    password: Option<String>,
    total: u64,
    listener: &Option<Arc<dyn ProgressListener>>,
    cancel: &Option<Arc<CancelToken>>,
) -> Result<u64> {
    let mut zip = ZipWriter::new(BufWriter::new(file));
    let dir_options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    // An empty string is not a password. It arrives as one when a dialog is
    // confirmed with the field untouched, and encrypting with it would produce
    // an archive nothing can open without knowing to type nothing.
    let secret = password.filter(|p| !p.is_empty());
    let options = match secret.as_deref() {
        // Directory entries are left in the clear, which is what every other
        // tool writes - they carry no data, and encrypting a zero-byte entry
        // only adds a header for readers to trip over.
        Some(pw) => dir_options.with_aes_encryption(AesMode::Aes256, pw),
        None => dir_options,
    };
    let mut done = 0u64;

    for source in sources {
        let src = Path::new(source);
        // Everything is stored relative to the source's parent, so zipping
        // /sdcard/DCIM gives you "DCIM/photo.jpg", not the whole path.
        let base = src.parent().unwrap_or(Path::new(""));

        for entry in WalkDir::new(src).follow_links(false) {
            if let Some(token) = cancel {
                token.check()?;
            }
            // Not filter_map(ok): a folder that cannot be read has to fail the
            // archive. Leaving it out reported success for an archive missing
            // it, and compressing is often the step before deleting.
            let entry = entry.map_err(walk_error)?;
            let path = entry.path();
            let Ok(rel) = path.strip_prefix(base) else { continue };
            let name = rel.to_string_lossy().replace('\\', "/");

            if entry.file_type().is_dir() {
                zip.add_directory(format!("{name}/"), dir_options)?;
                continue;
            }

            zip.start_file(&name, options)?;
            let mut reader =
                File::open(path).map_err(|e| FileError::from_io(e, path))?;
            std::io::copy(&mut reader, &mut zip)?;

            done += 1;
            if let Some(l) = listener {
                l.on_progress(done, total, name);
            }
        }
    }

    // Flushed here rather than on drop, which would swallow a write error
    // - a full disk, say - and report a truncated archive as made.
    zip.finish()?.into_inner().map_err(|e| FileError::from(e.into_error()))?.sync_all()?;
    Ok(done)
}

fn walk_error(err: walkdir::Error) -> FileError {
    let path = err.path().map(Path::to_path_buf).unwrap_or_default();
    match err.into_io_error() {
        Some(io) => FileError::from_io(io, &path),
        None => FileError::Io { detail: format!("{}: loops back on itself", path.display()) },
    }
}

/// Whether extracting the archive will need a password.
///
/// Checked before extracting so the app can ask for a password up front,
/// rather than starting, failing partway and leaving a half-unpacked folder.
#[uniffi::export]
pub fn archive_is_encrypted(archive_path: String) -> Result<bool> {
    let path = Path::new(&archive_path);
    match detect(path)? {
        Format::Zip => zip_is_encrypted(path),
        Format::SevenZ => sevenz_is_encrypted(path),
        Format::Rar => rar_is_encrypted(path),
        // Neither format has any encryption of its own.
        Format::Tar | Format::Compressed(_) => Ok(false),
    }
}

/// Extract an archive into `dest_dir`: a zip, 7z, RAR or tar, the tar plain
/// or compressed, or a single file compressed on its own.
///
/// `password` is required for encrypted entries and ignored otherwise, so the
/// caller can pass one speculatively without checking first.
///
/// Only files and folders are written. Links in a tar or RAR are skipped: one
/// pointing outside `dest_dir` would carry every later entry written through
/// it out there too - zip-slip by another route.
#[uniffi::export]
pub fn archive_extract(
    archive_path: String,
    dest_dir: String,
    password: Option<String>,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let archive = Path::new(&archive_path);
    // Before anything is created, so a file that is not an archive at all
    // leaves nothing behind.
    let format = detect(archive)?;
    let dest = Path::new(&dest_dir);
    let job = Job {
        dest,
        listener: &listener,
        cancel: &cancel,
        made: RefCell::new(Vec::new()),
        writing: RefCell::new(None),
    };
    job.make_dir(dest)?;

    let result = match format {
        Format::Zip => extract_zip(archive, password, &job),
        Format::SevenZ => extract_7z(archive, password, &job),
        Format::Rar => extract_rar(archive, password, &job),
        Format::Tar => {
            let file = File::open(archive).map_err(|e| FileError::from_io(e, archive))?;
            extract_tar(BufReader::new(file), &job)
        }
        Format::Compressed(kind) => extract_compressed(archive, kind, &job),
    };
    if let Err(e) = &result {
        job.clean_up(matches!(e, FileError::WrongPassword | FileError::PasswordRequired));
    }
    result
}

/// What an archive is, going by its first bytes rather than its name.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Format {
    Zip,
    SevenZ,
    Rar,
    Tar,
    /// One compressed stream: a tar inside, usually, or a single file.
    Compressed(Compression),
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Compression {
    Gzip,
    Bzip2,
    Xz,
    Zstd,
}

/// Where a tar says what it is: "ustar" at byte 257 of its first header.
const TAR_MAGIC_AT: usize = 257;

fn detect(path: &Path) -> Result<Format> {
    let file = File::open(path).map_err(|e| FileError::from_io(e, path))?;
    let head = read_head(file).map_err(|e| FileError::from_io(e, path))?;

    let format = if head.starts_with(b"PK\x03\x04") || head.starts_with(b"PK\x05\x06") {
        Format::Zip
    } else if head.starts_with(&[b'7', b'z', 0xBC, 0xAF, 0x27, 0x1C]) {
        Format::SevenZ
    } else if head.starts_with(b"Rar!\x1A\x07") {
        Format::Rar
    } else if head.starts_with(&[0x1F, 0x8B]) {
        Format::Compressed(Compression::Gzip)
    } else if head.starts_with(b"BZh") {
        Format::Compressed(Compression::Bzip2)
    } else if head.starts_with(&[0xFD, b'7', b'z', b'X', b'Z', 0x00]) {
        Format::Compressed(Compression::Xz)
    } else if head.starts_with(&[0x28, 0xB5, 0x2F, 0xFD]) {
        Format::Compressed(Compression::Zstd)
    } else if is_tar(&head) {
        Format::Tar
    } else {
        // The name, where the contents do not say. A zip can have anything in
        // front of it - a self-extractor's code - and a tar from before POSIX
        // carries no magic at all.
        let name = path.to_string_lossy().to_lowercase();
        if name.ends_with(".zip") {
            Format::Zip
        } else if name.ends_with(".tar") {
            Format::Tar
        } else {
            return Err(FileError::Archive { detail: "not an archive this app can open".into() });
        }
    };
    Ok(format)
}

fn is_tar(head: &[u8]) -> bool {
    head.get(TAR_MAGIC_AT..TAR_MAGIC_AT + 5) == Some(b"ustar")
}

/// The first 512 bytes, or all of them if there are fewer - one tar header's
/// worth, which is the most any of the checks above look at.
fn read_head(reader: impl Read) -> std::io::Result<Vec<u8>> {
    let mut head = Vec::with_capacity(512);
    reader.take(512).read_to_end(&mut head)?;
    Ok(head)
}

/// Where an extraction is going, who is watching it, and what it has made.
struct Job<'a> {
    dest: &'a Path,
    listener: &'a Option<Arc<dyn ProgressListener>>,
    cancel: &'a Option<Arc<CancelToken>>,
    /// Files and folders this extraction made, in the order it made them.
    made: RefCell<Vec<PathBuf>>,
    /// The file being written now, until it is finished.
    writing: RefCell<Option<PathBuf>>,
}

impl Job<'_> {
    /// Where `name` from inside the archive lands, or None for a name that
    /// would land outside the destination.
    fn out_path(&self, name: &str) -> Option<PathBuf> {
        sanitize_entry_name(name).map(|rel| self.dest.join(rel))
    }

    /// Where a file named `name` is written: as [`Self::out_path`], but never
    /// over anything already there. Extracting only ever went into a new,
    /// empty folder; now it can go into one the user chose, where a file with
    /// the same name is theirs. It stays, and the one from the archive gets a
    /// number - "notes (1).txt" - as a clash does everywhere else in the app.
    fn file_path(&self, name: &str) -> Option<PathBuf> {
        self.out_path(name).map(|path| free_path(&path))
    }

    fn check(&self) -> Result<()> {
        match self.cancel {
            Some(token) => token.check(),
            None => Ok(()),
        }
    }

    /// A file written: count it and say so. `total` is 0 when not known.
    fn wrote(&self, done: u64, total: u64, path: &Path) {
        self.writing.borrow_mut().take();
        if let Some(l) = self.listener {
            l.on_progress(done, total, path.to_string_lossy().into_owned());
        }
    }

    /// Make `path` and whatever above it is missing, noting each folder made.
    fn make_dir(&self, path: &Path) -> Result<()> {
        let missing: Vec<PathBuf> = path
            .ancestors()
            .take_while(|p| std::fs::symlink_metadata(p).is_err())
            .map(Path::to_path_buf)
            .collect();
        std::fs::create_dir_all(path).map_err(|e| FileError::from_io(e, path))?;
        self.made.borrow_mut().extend(missing.into_iter().rev());
        Ok(())
    }

    /// A file is about to be written at `path`: its parent made, and the file
    /// noted, so a failure can take it away again.
    fn start_file(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            self.make_dir(parent)?;
        }
        self.made.borrow_mut().push(path.to_path_buf());
        *self.writing.borrow_mut() = Some(path.to_path_buf());
        Ok(())
    }

    /// After a failure. The file it cut short goes, always: half a file looks
    /// like a whole one. Everything else this extraction made goes too when
    /// the password was the problem, since none of it is right - a wrong one
    /// decrypts into rubbish - and the retry must find the folder as it was,
    /// or every file it writes lands beside the rubbish as "name (1)".
    /// Otherwise the rest stays: part of a damaged archive is worth seeing.
    fn clean_up(&self, everything: bool) {
        if let Some(partial) = self.writing.borrow_mut().take() {
            let _ = std::fs::remove_file(partial);
        }
        if everything {
            for made in self.made.borrow().iter().rev() {
                // Folders only once empty, which those made here are, once the
                // files made in them are gone.
                if made.is_dir() {
                    let _ = std::fs::remove_dir(made);
                } else {
                    let _ = std::fs::remove_file(made);
                }
            }
        }
    }
}

/// `path` if nothing is there, else the first "name (n).ext" beside it that is
/// free. Anything counts as there, a dangling link included: writing to a
/// link would write wherever it points.
fn free_path(path: &Path) -> PathBuf {
    let taken = |p: &Path| std::fs::symlink_metadata(p).is_ok();
    if !taken(path) {
        return path.to_path_buf();
    }
    let parent = path.parent().unwrap_or_else(|| Path::new(""));
    let stem = path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let extension = path
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    (1u32..)
        .map(|n| parent.join(format!("{stem} ({n}){extension}")))
        .find(|candidate| !taken(candidate))
        .unwrap_or_else(|| path.to_path_buf())
}


/// Why copying one entry out stopped.
enum CopyError {
    /// Reading the archive failed: it is damaged, or - when it is encrypted -
    /// the password was wrong and decrypted into nonsense.
    Read(std::io::Error),
    /// Writing the file failed, which is the device's problem, not the archive's.
    Write(FileError),
}

/// Copy one entry's contents to `out`, telling a read failure from a write one.
fn copy_entry(job: &Job, from: &mut dyn Read, out: &Path) -> std::result::Result<(), CopyError> {
    job.start_file(out).map_err(CopyError::Write)?;
    let file = File::create(out).map_err(|e| CopyError::Write(FileError::from_io(e, out)))?;
    let mut writer = BufWriter::new(file);
    let mut buffer = vec![0u8; 64 * 1024];
    loop {
        let n = match from.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(CopyError::Read(e)),
        };
        writer.write_all(&buffer[..n]).map_err(|e| CopyError::Write(FileError::from_io(e, out)))?;
    }
    writer.flush().map_err(|e| CopyError::Write(FileError::from_io(e, out)))
}

fn damaged(err: std::io::Error) -> FileError {
    FileError::Archive { detail: err.to_string() }
}

// --- Zip ------------------------------------------------------------------------

fn list_zip(path: &Path) -> Result<Vec<ArchiveEntry>> {
    let file = File::open(path).map_err(|e| FileError::from_io(e, path))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;

    let mut entries = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        let entry = zip.by_index_raw(i)?;
        entries.extend(listed(
            entry.name(),
            entry.size(),
            entry.compressed_size(),
            entry.is_dir(),
            entry.last_modified().map_or(0, zip_time_ms),
        ));
    }
    Ok(entries)
}

fn zip_is_encrypted(path: &Path) -> Result<bool> {
    let file = File::open(path).map_err(|e| FileError::from_io(e, path))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;

    for i in 0..zip.len() {
        if zip.by_index_raw(i)?.encrypted() {
            return Ok(true);
        }
    }
    Ok(false)
}

fn extract_zip(archive: &Path, password: Option<String>, job: &Job) -> Result<u64> {
    let file = File::open(archive).map_err(|e| FileError::from_io(e, archive))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;
    let total = zip.len() as u64;
    let mut done = 0u64;

    for i in 0..zip.len() {
        job.check()?;
        // by_index cannot read an encrypted entry at all, so the decrypting
        // call is used whenever a password was supplied.
        let mut entry = match password.as_deref() {
            Some(pw) => zip.by_index_decrypt(i, pw.as_bytes())?,
            None => {
                // Fail with something the user can act on rather than the
                // zip crate's generic unsupported-feature error.
                if zip.by_index_raw(i)?.encrypted() {
                    return Err(FileError::PasswordRequired);
                }
                zip.by_index(i)?
            }
        };
        let Some(out_path) = job.out_path(entry.name()) else {
            // Zip-slip: an entry named "../../secret" would otherwise write
            // outside dest_dir. Skip it rather than failing the extraction.
            continue;
        };

        if entry.is_dir() {
            job.make_dir(&out_path)?;
            continue;
        }
        let out_path = free_path(&out_path);
        job.start_file(&out_path)?;

        let mut out = BufWriter::new(
            File::create(&out_path).map_err(|e| FileError::from_io(e, &out_path))?,
        );
        std::io::copy(&mut entry, &mut out)?;

        done += 1;
        job.wrote(done, total, &out_path);
    }
    Ok(done)
}

// --- 7z -------------------------------------------------------------------------

fn sevenz_is_encrypted(path: &Path) -> Result<bool> {
    // Opened with no password: an archive whose file list is encrypted too
    // refuses right here, and one with only its contents encrypted says so in
    // the methods its blocks were packed with.
    match sevenz_rust2::Archive::open(path) {
        Ok(archive) => Ok(archive.blocks.iter().any(|block| {
            block
                .coders
                .iter()
                .any(|c| c.encoder_method_id() == sevenz_rust2::EncoderMethod::ID_AES256_SHA256)
        })),
        Err(sevenz_rust2::Error::PasswordRequired) => Ok(true),
        Err(e) => Err(sevenz_error(e, path)),
    }
}

fn list_7z(path: &Path, password: Option<String>) -> Result<Vec<ArchiveEntry>> {
    let secret = match password.as_deref() {
        Some(pw) => sevenz_rust2::Password::from(pw),
        None => sevenz_rust2::Password::empty(),
    };
    let reader =
        sevenz_rust2::ArchiveReader::open(path, secret).map_err(|e| sevenz_error(e, path))?;
    Ok(reader
        .archive()
        .files
        .iter()
        // An anti-item marks a deletion in an update archive: nothing is written.
        .filter(|f| !f.is_anti_item)
        .filter_map(|f| {
            let modified = if f.has_last_modified_date {
                system_time_ms(SystemTime::from(f.last_modified_date))
            } else {
                0
            };
            listed(&f.name, f.size, f.compressed_size, f.is_directory, modified)
        })
        .collect())
}

fn extract_7z(archive: &Path, password: Option<String>, job: &Job) -> Result<u64> {
    let secret = match password.as_deref() {
        Some(pw) => sevenz_rust2::Password::from(pw),
        None => sevenz_rust2::Password::empty(),
    };
    let mut reader =
        sevenz_rust2::ArchiveReader::open(archive, secret).map_err(|e| sevenz_error(e, archive))?;
    let total = reader.archive().files.iter().filter(|f| !f.is_directory).count() as u64;
    let mut done = 0u64;
    // Our own errors, carried out of the callback, which can only return the
    // library's.
    let mut stopped: Option<FileError> = None;

    let walked = reader.for_each_entries(|entry, data| {
        if let Err(e) = job.check() {
            stopped = Some(e);
            return Ok(false);
        }
        let out = job.out_path(&entry.name).filter(|_| !entry.is_anti_item);
        let Some(out) = out else {
            // Read to the end all the same. The library does not skip what is
            // left of an entry, so the next one would start with the rest of it.
            std::io::copy(data, &mut std::io::sink())?;
            return Ok(true);
        };
        if entry.is_directory {
            if let Err(e) = job.make_dir(&out) {
                stopped = Some(e);
                return Ok(false);
            }
            return Ok(true);
        }
        let out = free_path(&out);
        match copy_entry(job, data, &out) {
            Ok(()) => {}
            // Handed back to the library, which knows whether the archive is
            // encrypted and so whether bad data means a bad password.
            Err(CopyError::Read(e)) => return Err(e.into()),
            Err(CopyError::Write(e)) => {
                stopped = Some(e);
                return Ok(false);
            }
        }
        done += 1;
        job.wrote(done, total, &out);
        Ok(true)
    });

    if let Some(e) = stopped {
        return Err(e);
    }
    walked.map_err(|e| sevenz_error(e, archive))?;
    Ok(done)
}

fn sevenz_error(err: sevenz_rust2::Error, path: &Path) -> FileError {
    use sevenz_rust2::Error as E;
    match err {
        E::PasswordRequired => FileError::PasswordRequired,
        E::MaybeBadPassword(_) => FileError::WrongPassword,
        E::FileOpen(e, _) => FileError::from_io(e, path),
        other => FileError::Archive { detail: other.to_string() },
    }
}

// --- RAR ------------------------------------------------------------------------

fn rar_is_encrypted(path: &Path) -> Result<bool> {
    let listing = unrar::Archive::new(path)
        .open_for_listing()
        .map_err(|e| rar_error(e, false))?;
    // Names and all: nothing can even be listed without the password.
    if listing.has_encrypted_headers() {
        return Ok(true);
    }
    for entry in listing {
        if entry.map_err(|e| rar_error(e, false))?.is_encrypted() {
            return Ok(true);
        }
    }
    Ok(false)
}

fn list_rar(path: &Path, password: Option<String>) -> Result<Vec<ArchiveEntry>> {
    let has_password = password.is_some();
    let opened = match password.as_deref() {
        Some(pw) => unrar::Archive::with_password(path, pw),
        None => unrar::Archive::new(path),
    };
    let listing = opened.open_for_listing().map_err(|e| rar_error(e, has_password))?;
    let mut entries = Vec::new();
    for entry in listing {
        let entry = entry.map_err(|e| rar_error(e, has_password))?;
        // Not written on extraction; see extract_rar.
        if is_rar_link(&entry) {
            continue;
        }
        entries.extend(listed(
            &entry.filename.to_string_lossy(),
            entry.unpacked_size,
            0,
            entry.is_directory(),
            dos_time_ms(entry.file_time),
        ));
    }
    Ok(entries)
}

fn extract_rar(archive: &Path, password: Option<String>, job: &Job) -> Result<u64> {
    let has_password = password.is_some();
    let opened = match password.as_deref() {
        Some(pw) => unrar::Archive::with_password(archive, pw),
        None => unrar::Archive::new(archive),
    };
    let mut cursor = opened.open_for_processing().map_err(|e| rar_error(e, has_password))?;
    let mut done = 0u64;

    loop {
        job.check()?;
        let Some(header) = cursor.read_header().map_err(|e| rar_error(e, has_password))? else {
            break;
        };
        let entry = header.entry();
        let out = job
            .out_path(&entry.filename.to_string_lossy())
            .filter(|_| !is_rar_link(entry));

        cursor = match out {
            None => header.skip(),
            Some(out) if entry.is_directory() => {
                job.make_dir(&out)?;
                header.skip()
            }
            Some(out) => {
                let out = free_path(&out);
                job.start_file(&out)?;
                let next = header.extract_to(&out);
                if next.is_ok() {
                    done += 1;
                    job.wrote(done, 0, &out);
                }
                next
            }
        }
        .map_err(|e| rar_error(e, has_password))?;
    }
    Ok(done)
}

/// Whether a RAR entry is a symbolic link, which UnRAR would recreate as one.
///
/// Its attributes are the Unix mode when the archive was made on Unix. Made on
/// Windows they are Windows attributes instead, whose bits never add up to the
/// link type, so the one check serves both.
fn is_rar_link(entry: &unrar::FileHeader) -> bool {
    const S_IFMT: u32 = 0o170000;
    const S_IFLNK: u32 = 0o120000;
    entry.file_attr & S_IFMT == S_IFLNK
}

fn rar_error(err: unrar::error::UnrarError, password_given: bool) -> FileError {
    use unrar::error::Code;
    match err.code {
        Code::MissingPassword => FileError::PasswordRequired,
        Code::BadPassword => FileError::WrongPassword,
        // RAR 4 has no password check of its own: a wrong one decrypts into
        // data that fails its checksum.
        Code::BadData if password_given => FileError::WrongPassword,
        _ => FileError::Archive { detail: err.to_string() },
    }
}

// --- Tar, and single compressed files -------------------------------------------

fn list_tar(reader: impl Read) -> Result<Vec<ArchiveEntry>> {
    let mut tar = tar::Archive::new(reader);
    let mut entries = Vec::new();
    // Files listed so far, for the hard links among them: extraction copies
    // one only from a file the archive itself holds, earlier in it.
    let mut files: std::collections::HashSet<PathBuf> = std::collections::HashSet::new();

    for entry in tar.entries().map_err(damaged)? {
        let entry = entry.map_err(damaged)?;
        let kind = entry.header().entry_type();
        let modified = entry.header().mtime().map_or(0, |s| s.saturating_mul(1_000));
        let name = entry.path().map_err(damaged)?.to_string_lossy().into_owned();
        let Some(rel) = sanitize_entry_name(&name) else { continue };

        let item = if kind.is_dir() {
            listed(&name, 0, 0, true, modified)
        } else if kind.is_file() {
            files.insert(rel);
            listed(&name, entry.size(), 0, false, modified)
        } else if kind.is_hard_link() {
            let target = entry
                .link_name()
                .ok()
                .flatten()
                .and_then(|t| sanitize_entry_name(&t.to_string_lossy()));
            if target.is_some_and(|t| files.contains(&t)) {
                files.insert(rel);
                listed(&name, 0, 0, false, modified)
            } else {
                None
            }
        } else {
            // Symbolic links, devices, pipes: not written, so not listed.
            None
        };
        entries.extend(item);
    }
    Ok(entries)
}

fn extract_tar(reader: impl Read, job: &Job) -> Result<u64> {
    let mut tar = tar::Archive::new(reader);
    let mut done = 0u64;
    // Where each name in the archive was written by this extraction, which is
    // not always where the name says: a file already there keeps its place.
    let mut written: HashMap<PathBuf, PathBuf> = HashMap::new();

    for entry in tar.entries().map_err(damaged)? {
        job.check()?;
        let mut entry = entry.map_err(damaged)?;
        let kind = entry.header().entry_type();
        let name = entry.path().map_err(damaged)?.to_string_lossy().into_owned();
        let Some(rel) = sanitize_entry_name(&name) else { continue };

        if kind.is_dir() {
            job.make_dir(&job.dest.join(&rel))?;
            continue;
        }
        let out = if kind.is_file() {
            let Some(out) = job.file_path(&name) else { continue };
            match copy_entry(job, &mut entry, &out) {
                Ok(()) => {}
                Err(CopyError::Read(e)) => return Err(damaged(e)),
                Err(CopyError::Write(e)) => return Err(e),
            }
            out
        } else if kind.is_hard_link() {
            // A second name for a file unpacked earlier, which is how tar
            // stores a duplicate. Copied rather than linked - shared storage
            // on a phone cannot hold a hard link - and only from a file this
            // extraction wrote: never one that was in the folder already, and
            // never from outside it.
            let target = entry.link_name().map_err(damaged)?;
            let Some(from) = target
                .and_then(|t| sanitize_entry_name(&t.to_string_lossy()))
                .and_then(|t| written.get(&t).cloned())
            else {
                continue;
            };
            let Some(out) = job.file_path(&name) else { continue };
            job.start_file(&out)?;
            std::fs::copy(&from, &out).map_err(|e| FileError::from_io(e, &out))?;
            out
        } else {
            // Symbolic links, devices, pipes: not written.
            continue;
        };
        written.insert(rel, out.clone());
        done += 1;
        job.wrote(done, 0, &out);
    }
    Ok(done)
}

/// The archive, decompressed as it is read.
fn decompressed(archive: &Path, kind: Compression) -> Result<Box<dyn Read>> {
    let file = BufReader::new(File::open(archive).map_err(|e| FileError::from_io(e, archive))?);
    Ok(match kind {
        Compression::Gzip => Box::new(flate2::read::MultiGzDecoder::new(file)),
        Compression::Bzip2 => Box::new(bzip2::read::MultiBzDecoder::new(file)),
        Compression::Xz => Box::new(lzma_rust2::XzReader::new(file, true)),
        Compression::Zstd => Box::new(
            ruzstd::decoding::StreamingDecoder::new(file)
                .map_err(|e| FileError::Archive { detail: e.to_string() })?,
        ),
    })
}

/// What a single compressed file comes out as: the archive's name without
/// its compression extension.
fn single_name(archive: &Path) -> String {
    archive
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "file".into())
}

/// A compressed tar's entries, or the one file a compressed file holds. Its
/// size is not known without decompressing it all, so it is listed as 0.
fn list_compressed(archive: &Path, kind: Compression) -> Result<Vec<ArchiveEntry>> {
    let mut stream = decompressed(archive, kind)?;
    let head = read_head(&mut stream).map_err(damaged)?;
    if is_tar(&head) {
        return list_tar(std::io::Cursor::new(head).chain(stream));
    }
    let modified = std::fs::metadata(archive)
        .and_then(|m| m.modified())
        .map_or(0, system_time_ms);
    Ok(listed(&single_name(archive), 0, 0, false, modified).into_iter().collect())
}

/// A gzip, bzip2, xz or zstd stream: a compressed tar, or one file on its own.
fn extract_compressed(archive: &Path, kind: Compression, job: &Job) -> Result<u64> {
    let mut stream = decompressed(archive, kind)?;

    // Looked at rather than trusted from the name: "backup.gz" is as often a
    // tar as "backup.tar.gz" is.
    let head = read_head(&mut stream).map_err(damaged)?;
    let tar = is_tar(&head);
    let mut whole = std::io::Cursor::new(head).chain(stream);
    if tar {
        return extract_tar(whole, job);
    }

    // One file, named after the archive without its compression extension.
    let out = free_path(&job.dest.join(single_name(archive)));
    job.check()?;
    match copy_entry(job, &mut whole, &out) {
        Ok(()) => {}
        Err(CopyError::Read(e)) => return Err(damaged(e)),
        Err(CopyError::Write(e)) => return Err(e),
    }
    job.wrote(1, 1, &out);
    Ok(1)
}

/// Reject absolute paths and any `..` component, so an archive can only ever
/// write inside the destination directory.
fn sanitize_entry_name(name: &str) -> Option<PathBuf> {
    let path = Path::new(name);
    let mut safe = PathBuf::new();

    for component in path.components() {
        match component {
            Component::Normal(part) => safe.push(part),
            Component::CurDir => {}
            // Anything else (RootDir, ParentDir, Prefix) means the archive is
            // trying to escape.
            _ => return None,
        }
    }
    if safe.as_os_str().is_empty() {
        None
    } else {
        Some(safe)
    }
}
