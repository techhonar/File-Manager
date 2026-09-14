//! Zip creation, listing and extraction.

use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::{FileError, Result};
use crate::types::now_millis;
use std::fs::File;
use std::io::{BufReader, BufWriter};
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use walkdir::WalkDir;
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

#[derive(Debug, Clone, uniffi::Record)]
pub struct ArchiveEntry {
    /// Path as stored inside the archive, always with forward slashes.
    pub name: String,
    pub size: u64,
    pub compressed_size: u64,
    pub is_dir: bool,
    pub modified_ms: u64,
}

/// List an archive's contents without extracting it.
#[uniffi::export]
pub fn archive_list(archive_path: String) -> Result<Vec<ArchiveEntry>> {
    let file = File::open(&archive_path)
        .map_err(|e| FileError::from_io(e, Path::new(&archive_path)))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;

    let mut entries = Vec::with_capacity(zip.len());
    for i in 0..zip.len() {
        let entry = zip.by_index(i)?;
        entries.push(ArchiveEntry {
            name: entry.name().to_string(),
            size: entry.size(),
            compressed_size: entry.compressed_size(),
            is_dir: entry.is_dir(),
            modified_ms: now_millis(),
        });
    }
    Ok(entries)
}

/// Zip `sources` into `dest_path`. Directories go in recursively.
#[uniffi::export]
pub fn archive_create(
    sources: Vec<String>,
    dest_path: String,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let dest = Path::new(&dest_path);
    if dest.exists() {
        return Err(FileError::AlreadyExists { path: dest_path });
    }

    let total = crate::scanner::tree_stats(sources.clone(), cancel.clone())?.file_count;
    let file = File::create(dest).map_err(|e| FileError::from_io(e, dest))?;
    let mut zip = ZipWriter::new(BufWriter::new(file));
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    let mut done = 0u64;

    for source in &sources {
        let src = Path::new(source);
        // Everything is stored relative to the source's parent, so zipping
        // /sdcard/DCIM gives you "DCIM/photo.jpg", not the whole path.
        let base = src.parent().unwrap_or(Path::new(""));

        for entry in WalkDir::new(src).follow_links(false).into_iter().filter_map(|e| e.ok()) {
            if let Some(ref token) = cancel {
                token.check()?;
            }
            let path = entry.path();
            let Ok(rel) = path.strip_prefix(base) else { continue };
            let name = rel.to_string_lossy().replace('\\', "/");

            if entry.file_type().is_dir() {
                zip.add_directory(format!("{name}/"), options)?;
                continue;
            }

            zip.start_file(&name, options)?;
            let mut reader =
                File::open(path).map_err(|e| FileError::from_io(e, path))?;
            std::io::copy(&mut reader, &mut zip)?;

            done += 1;
            if let Some(ref l) = listener {
                l.on_progress(done, total, name);
            }
        }
    }

    zip.finish()?;
    Ok(done)
}

/// Archive formats this app can unpack.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ArchiveFormat {
    Zip,
    Rar,
    /// Recognised as an archive by name, but not one we can open.
    Unsupported,
}

/// Which format an archive is, by extension.
///
/// By name rather than by magic bytes: opening every tapped file to sniff it
/// would mean a read before the user has confirmed they want anything to
/// happen, and an archive with the wrong extension is a rarity next to that.
#[uniffi::export]
pub fn archive_format(archive_path: String) -> ArchiveFormat {
    let lower = archive_path.to_lowercase();
    if lower.ends_with(".zip") || lower.ends_with(".apk") || lower.ends_with(".aab") {
        ArchiveFormat::Zip
    } else if lower.ends_with(".rar") {
        ArchiveFormat::Rar
    } else {
        ArchiveFormat::Unsupported
    }
}

/// Extract a RAR archive into `dest_dir`.
///
/// Separate from the zip path because the two libraries have nothing in
/// common: unrar walks a cursor that is consumed and handed back on each step,
/// so the archive value is reassigned as it goes rather than indexed.
fn extract_rar(
    archive_path: &str,
    dest_dir: &Path,
    password: Option<&str>,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let opened = match password {
        Some(pw) => unrar::Archive::with_password(archive_path, pw).open_for_processing(),
        None => unrar::Archive::new(archive_path).open_for_processing(),
    };
    let mut archive = opened.map_err(rar_error)?;
    let mut extracted = 0u64;

    while let Some(header) = archive.read_header().map_err(rar_error)? {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let entry = header.entry();
        let name = entry.filename.to_string_lossy().into_owned();
        let is_file = entry.is_file();

        archive = if is_file {
            let next = header.extract_with_base(dest_dir).map_err(rar_error)?;
            extracted += 1;
            if let Some(ref l) = listener {
                l.on_progress(extracted, 0, name);
            }
            next
        } else {
            header.skip().map_err(rar_error)?
        };
    }
    Ok(extracted)
}

/// Map unrar's errors onto the ones the app already understands.
fn rar_error(err: unrar::error::UnrarError) -> FileError {
    use unrar::error::Code;
    match err.code {
        // Both mean the password was missing or wrong; the library does not
        // distinguish, so neither can we.
        Code::MissingPassword | Code::BadPassword => FileError::WrongPassword,
        Code::BadArchive | Code::UnknownFormat => FileError::Archive {
            detail: "the archive is damaged or not a RAR file".into(),
        },
        other => FileError::Archive { detail: format!("{other:?}") },
    }
}

/// Whether any entry in the archive is encrypted.
///
/// Checked before extracting so the app can ask for a password up front,
/// rather than starting, failing partway and leaving a half-unpacked folder.
#[uniffi::export]
pub fn archive_is_encrypted(archive_path: String) -> Result<bool> {
    if archive_format(archive_path.clone()) == ArchiveFormat::Rar {
        // Listing a RAR whose headers are encrypted fails outright, which is
        // itself the answer: it needs a password.
        return Ok(unrar::Archive::new(&archive_path).open_for_listing().is_err());
    }

    let file = File::open(&archive_path)
        .map_err(|e| FileError::from_io(e, Path::new(&archive_path)))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;

    for i in 0..zip.len() {
        if zip.by_index_raw(i)?.encrypted() {
            return Ok(true);
        }
    }
    Ok(false)
}

/// Extract an archive into `dest_dir`.
///
/// `password` is required for encrypted entries and ignored otherwise, so the
/// caller can pass one speculatively without checking first.
#[uniffi::export]
pub fn archive_extract(
    archive_path: String,
    dest_dir: String,
    password: Option<String>,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let dest = Path::new(&dest_dir);
    std::fs::create_dir_all(dest).map_err(|e| FileError::from_io(e, dest))?;

    if archive_format(archive_path.clone()) == ArchiveFormat::Rar {
        return extract_rar(&archive_path, dest, password.as_deref(), listener, cancel);
    }

    let file = File::open(&archive_path)
        .map_err(|e| FileError::from_io(e, Path::new(&archive_path)))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;
    let total = zip.len() as u64;
    let mut done = 0u64;

    for i in 0..zip.len() {
        if let Some(ref token) = cancel {
            token.check()?;
        }
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
        let Some(rel) = sanitize_entry_name(entry.name()) else {
            // Zip-slip: an entry named "../../secret" would otherwise write
            // outside dest_dir. Skip it rather than failing the extraction.
            continue;
        };
        let out_path = dest.join(rel);

        if entry.is_dir() {
            std::fs::create_dir_all(&out_path)
                .map_err(|e| FileError::from_io(e, &out_path))?;
            continue;
        }
        if let Some(parent) = out_path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| FileError::from_io(e, parent))?;
        }

        let mut out = BufWriter::new(
            File::create(&out_path).map_err(|e| FileError::from_io(e, &out_path))?,
        );
        std::io::copy(&mut entry, &mut out)?;

        done += 1;
        if let Some(ref l) = listener {
            l.on_progress(done, total, out_path.to_string_lossy().into_owned());
        }
    }
    Ok(done)
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
