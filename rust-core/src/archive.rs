//! Archives: zips made, listed and extracted, and 7z, RAR and tar archives
//! - plain or compressed - extracted.

use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::{FileError, Result};
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
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

/// List a zip's contents without extracting it. Zips only: nothing in the app
/// lists any other kind.
///
/// Reads the index only, so an encrypted archive lists without a password -
/// which is what the format allows, since a zip never encrypts its index. Done
/// with the decrypting reader instead, listing a protected archive failed
/// outright with "Password required to decrypt file", and the app had no way
/// to show what was inside one it had just written.
#[uniffi::export]
pub fn archive_list(archive_path: String) -> Result<Vec<ArchiveEntry>> {
    let file = File::open(&archive_path)
        .map_err(|e| FileError::from_io(e, Path::new(&archive_path)))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;

    let mut entries = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        let entry = zip.by_index_raw(i)?;
        entries.push(ArchiveEntry {
            name: entry.name().to_string(),
            size: entry.size(),
            compressed_size: entry.compressed_size(),
            is_dir: entry.is_dir(),
            modified_ms: entry.last_modified().map_or(0, zip_time_ms),
        });
    }
    Ok(entries)
}

/// A zip timestamp as milliseconds since the epoch.
///
/// Every entry used to be stamped with the moment it was listed. A zip stores
/// a wall-clock time and no zone, so this is that time read as UTC: formatted
/// in UTC it shows what the archive says, which is all there is to know.
fn zip_time_ms(time: zip::DateTime) -> u64 {
    // Days from 1970-01-01 to the date, by the usual civil-calendar formula.
    let (y, m, d) = (i64::from(time.year()), i64::from(time.month()), i64::from(time.day()));
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * ((m + 9) % 12) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;

    let seconds = days * 86_400
        + i64::from(time.hour()) * 3_600
        + i64::from(time.minute()) * 60
        + i64::from(time.second());
    u64::try_from(seconds).map_or(0, |s| s * 1_000)
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
    std::fs::create_dir_all(dest).map_err(|e| FileError::from_io(e, dest))?;

    let job = Job { dest, listener: &listener, cancel: &cancel };
    match format {
        Format::Zip => extract_zip(archive, password, &job),
        Format::SevenZ => extract_7z(archive, password, &job),
        Format::Rar => extract_rar(archive, password, &job),
        Format::Tar => {
            let file = File::open(archive).map_err(|e| FileError::from_io(e, archive))?;
            extract_tar(BufReader::new(file), &job)
        }
        Format::Compressed(kind) => extract_compressed(archive, kind, &job),
    }
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

/// Where an extraction is going, and who is watching it.
struct Job<'a> {
    dest: &'a Path,
    listener: &'a Option<Arc<dyn ProgressListener>>,
    cancel: &'a Option<Arc<CancelToken>>,
}

impl Job<'_> {
    /// Where `name` from inside the archive lands, or None for a name that
    /// would land outside the destination.
    fn out_path(&self, name: &str) -> Option<PathBuf> {
        sanitize_entry_name(name).map(|rel| self.dest.join(rel))
    }

    fn check(&self) -> Result<()> {
        match self.cancel {
            Some(token) => token.check(),
            None => Ok(()),
        }
    }

    /// A file written: count it and say so. `total` is 0 when not known.
    fn wrote(&self, done: u64, total: u64, path: &Path) {
        if let Some(l) = self.listener {
            l.on_progress(done, total, path.to_string_lossy().into_owned());
        }
    }
}

fn make_dir(path: &Path) -> Result<()> {
    std::fs::create_dir_all(path).map_err(|e| FileError::from_io(e, path))
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
fn copy_entry(from: &mut dyn Read, out: &Path) -> std::result::Result<(), CopyError> {
    if let Some(parent) = out.parent() {
        make_dir(parent).map_err(CopyError::Write)?;
    }
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
            make_dir(&out_path)?;
            continue;
        }
        if let Some(parent) = out_path.parent() {
            make_dir(parent)?;
        }

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
            if let Err(e) = make_dir(&out) {
                stopped = Some(e);
                return Ok(false);
            }
            return Ok(true);
        }
        match copy_entry(data, &out) {
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
                make_dir(&out)?;
                header.skip()
            }
            Some(out) => {
                if let Some(parent) = out.parent() {
                    make_dir(parent)?;
                }
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

fn extract_tar(reader: impl Read, job: &Job) -> Result<u64> {
    let mut tar = tar::Archive::new(reader);
    let mut done = 0u64;

    for entry in tar.entries().map_err(damaged)? {
        job.check()?;
        let mut entry = entry.map_err(damaged)?;
        let kind = entry.header().entry_type();
        let name = entry.path().map_err(damaged)?.to_string_lossy().into_owned();
        let Some(out) = job.out_path(&name) else { continue };

        if kind.is_dir() {
            make_dir(&out)?;
            continue;
        }
        if kind.is_file() {
            match copy_entry(&mut entry, &out) {
                Ok(()) => {}
                Err(CopyError::Read(e)) => return Err(damaged(e)),
                Err(CopyError::Write(e)) => return Err(e),
            }
        } else if kind.is_hard_link() {
            // A second name for a file unpacked earlier, which is how tar
            // stores a duplicate. Copied rather than linked - shared storage
            // on a phone cannot hold a hard link - and only from inside the
            // folder, like everything else.
            let target = entry.link_name().map_err(damaged)?;
            let Some(from) = target.and_then(|t| job.out_path(&t.to_string_lossy())) else {
                continue;
            };
            if !from.is_file() {
                continue;
            }
            std::fs::copy(&from, &out).map_err(|e| FileError::from_io(e, &out))?;
        } else {
            // Symbolic links, devices, pipes: not written.
            continue;
        }
        done += 1;
        job.wrote(done, 0, &out);
    }
    Ok(done)
}

/// A gzip, bzip2, xz or zstd stream: a compressed tar, or one file on its own.
fn extract_compressed(archive: &Path, kind: Compression, job: &Job) -> Result<u64> {
    let file = BufReader::new(File::open(archive).map_err(|e| FileError::from_io(e, archive))?);
    let mut stream: Box<dyn Read> = match kind {
        Compression::Gzip => Box::new(flate2::read::MultiGzDecoder::new(file)),
        Compression::Bzip2 => Box::new(bzip2::read::MultiBzDecoder::new(file)),
        Compression::Xz => Box::new(lzma_rust2::XzReader::new(file, true)),
        Compression::Zstd => Box::new(
            ruzstd::decoding::StreamingDecoder::new(file)
                .map_err(|e| FileError::Archive { detail: e.to_string() })?,
        ),
    };

    // Looked at rather than trusted from the name: "backup.gz" is as often a
    // tar as "backup.tar.gz" is.
    let head = read_head(&mut stream).map_err(damaged)?;
    let tar = is_tar(&head);
    let mut whole = std::io::Cursor::new(head).chain(stream);
    if tar {
        return extract_tar(whole, job);
    }

    // One file, named after the archive without its compression extension.
    let name = archive
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "file".into());
    let out = job.dest.join(name);
    job.check()?;
    match copy_entry(&mut whole, &out) {
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
