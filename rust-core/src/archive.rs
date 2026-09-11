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

/// Extract an archive into `dest_dir`.
#[uniffi::export]
pub fn archive_extract(
    archive_path: String,
    dest_dir: String,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let dest = Path::new(&dest_dir);
    std::fs::create_dir_all(dest).map_err(|e| FileError::from_io(e, dest))?;

    let file = File::open(&archive_path)
        .map_err(|e| FileError::from_io(e, Path::new(&archive_path)))?;
    let mut zip = ZipArchive::new(BufReader::new(file))?;
    let total = zip.len() as u64;
    let mut done = 0u64;

    for i in 0..zip.len() {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let mut entry = zip.by_index(i)?;
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
