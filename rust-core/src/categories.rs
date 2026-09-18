//! Extension -> category mapping, plus the category home screen queries.

use std::cmp::Reverse;
use crate::cancel::CancelToken;
use crate::errors::{FileError, Result};
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, Ordering};
use crate::types::{is_hidden_dir, FileCategory, FileEntry};
use std::sync::Arc;
use walkdir::WalkDir;

/// Extensions per category. Kept as sorted `&str` slices rather than a HashMap
/// because these are tiny and a linear scan over <30 short strings beats
/// hashing, and it keeps the table readable.
const IMAGE: &[&str] = &[
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif", "svg", "avif", "dng",
];
const VIDEO: &[&str] = &[
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpg", "mpeg", "ts",
];
const AUDIO: &[&str] = &[
    "mp3", "wav", "flac", "aac", "ogg", "oga", "m4a", "wma", "opus", "amr", "mid", "midi",
];
const DOCUMENT: &[&str] = &[
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "md",
    "csv", "epub", "html", "htm", "json", "xml",
];
const ARCHIVE: &[&str] = &[
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tgz", "iso",
];
const APK: &[&str] = &["apk", "apex", "aab", "xapk"];

/// Longest extension in the tables above, used to reject early.
const LONGEST_EXTENSION: usize = 5;

/// Whether `ext` is in `list`, ignoring case, without allocating.
///
/// `list.contains(&ext)` needs an owned lowercase copy of the extension to
/// compare against, and this runs once per file in a scan of tens of thousands,
/// so the allocation rather than the comparison was the cost. The lists are
/// short enough that a linear walk of them is still the right shape.
fn contains_ignoring_case(list: &[&str], ext: &str) -> bool {
    list.iter().any(|candidate| candidate.eq_ignore_ascii_case(ext))
}

/// Classify a file by its name. Extension-only: reading magic bytes would mean
/// opening every file during a scan, which is far too slow for a listing.
pub fn categorize(name: &str) -> FileCategory {
    let ext = match name.rsplit_once('.') {
        Some((_, ext)) if !ext.is_empty() => ext,
        _ => return FileCategory::Other,
    };

    // An extension longer than any in the tables cannot be in them, and this
    // skips the whole comparison for names that merely contain a dot - which
    // on a phone is most of Android/data.
    if ext.len() > LONGEST_EXTENSION {
        return FileCategory::Other;
    }

    if contains_ignoring_case(IMAGE, ext) {
        FileCategory::Image
    } else if contains_ignoring_case(VIDEO, ext) {
        FileCategory::Video
    } else if contains_ignoring_case(AUDIO, ext) {
        FileCategory::Audio
    } else if contains_ignoring_case(DOCUMENT, ext) {
        FileCategory::Document
    } else if contains_ignoring_case(ARCHIVE, ext) {
        FileCategory::Archive
    } else if contains_ignoring_case(APK, ext) {
        FileCategory::Apk
    } else {
        FileCategory::Other
    }
}

/// Every file under `root` in the given category, newest first.
///
/// Backs the Images / Videos / Audio / Documents tiles on the home screen.
/// `limit` of 0 means unlimited.
#[uniffi::export]
pub fn files_in_category(
    root: String,
    category: FileCategory,
    limit: u32,
    cancel: Option<Arc<CancelToken>>,
) -> Result<Vec<FileEntry>> {
    let cancelled = AtomicBool::new(false);

    let mut out: Vec<FileEntry> = crate::walk::units(&[root], false)
        .par_iter()
        .flat_map_iter(|unit| {
            let mut found = Vec::new();
            let mut walk = WalkDir::new(&unit.path);
            if unit.shallow {
                walk = walk.max_depth(1);
            }
            for entry in walk
                .into_iter()
                .filter_entry(|e| !is_hidden_dir(e))
                .filter_map(|e| e.ok())
            {
                if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
                    cancelled.store(true, Ordering::Relaxed);
                    break;
                }
                // file_type is free; metadata is a syscall. Only files that
                // are going to be kept are worth one.
                if entry.file_type().is_dir() {
                    continue;
                }
                if categorize(entry.file_name().to_string_lossy().as_ref()) != category {
                    continue;
                }
                let Ok(meta) = entry.metadata() else { continue };
                found.push(FileEntry::from_metadata(entry.path(), &meta));
            }
            found
        })
        .collect();

    if cancelled.load(Ordering::Relaxed) {
        return Err(FileError::Cancelled);
    }

    // Newest first -- matches how Samsung orders each category page.
    out.sort_by_key(|e| Reverse(e.modified_ms));
    if limit > 0 {
        out.truncate(limit as usize);
    }
    Ok(out)
}

/// Files modified within the last `days`, newest first. Backs "Recent files".
#[uniffi::export]
pub fn recent_files(
    root: String,
    days: u32,
    limit: u32,
    cancel: Option<Arc<CancelToken>>,
) -> Result<Vec<FileEntry>> {
    let cutoff = crate::types::now_millis()
        .saturating_sub(days as u64 * 24 * 60 * 60 * 1000);
    let cancelled = AtomicBool::new(false);

    // Hidden directories are pruned, so the trash and thumbnail caches do not
    // turn up among a user's recent files.
    let mut out: Vec<FileEntry> = crate::walk::units(&[root], false)
        .par_iter()
        .flat_map_iter(|unit| {
            let mut found = Vec::new();
            let mut walk = WalkDir::new(&unit.path);
            if unit.shallow {
                walk = walk.max_depth(1);
            }
            for entry in walk
                .into_iter()
                .filter_entry(|e| !is_hidden_dir(e))
                .filter_map(|e| e.ok())
            {
                if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
                    cancelled.store(true, Ordering::Relaxed);
                    break;
                }
                if entry.file_type().is_dir() {
                    continue;
                }
                // Hidden and old files are the overwhelming majority, and both
                // can be rejected before the entry is built: the name says
                // hidden, and the metadata says when - where building the
                // entry allocates a name and a path for every file on the
                // device just to throw almost all of them away.
                if entry.file_name().to_string_lossy().starts_with('.') {
                    continue;
                }
                let Ok(meta) = entry.metadata() else { continue };
                if crate::types::modified_millis(&meta) < cutoff {
                    continue;
                }
                found.push(FileEntry::from_metadata(entry.path(), &meta));
            }
            found
        })
        .collect();

    if cancelled.load(Ordering::Relaxed) {
        return Err(FileError::Cancelled);
    }

    out.sort_by_key(|e| Reverse(e.modified_ms));
    if limit > 0 {
        out.truncate(limit as usize);
    }
    Ok(out)
}
