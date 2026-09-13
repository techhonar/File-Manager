//! Extension -> category mapping, plus the category home screen queries.

use std::cmp::Reverse;
use crate::cancel::CancelToken;
use crate::errors::Result;
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

/// Classify a file by its name. Extension-only: reading magic bytes would mean
/// opening every file during a scan, which is far too slow for a listing.
pub fn categorize(name: &str) -> FileCategory {
    let ext = match name.rsplit_once('.') {
        Some((_, ext)) if !ext.is_empty() => ext.to_lowercase(),
        _ => return FileCategory::Other,
    };
    let ext = ext.as_str();

    if IMAGE.contains(&ext) {
        FileCategory::Image
    } else if VIDEO.contains(&ext) {
        FileCategory::Video
    } else if AUDIO.contains(&ext) {
        FileCategory::Audio
    } else if DOCUMENT.contains(&ext) {
        FileCategory::Document
    } else if ARCHIVE.contains(&ext) {
        FileCategory::Archive
    } else if APK.contains(&ext) {
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
    let mut out = Vec::new();

    let walk = WalkDir::new(&root)
        .into_iter()
        .filter_entry(|e| !is_hidden_dir(e))
        .filter_map(|e| e.ok());

    for entry in walk {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            continue;
        }
        if categorize(entry.file_name().to_string_lossy().as_ref()) == category {
            out.push(FileEntry::from_metadata(entry.path(), &meta));
        }
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
    let mut out = Vec::new();

    // Hidden directories are pruned, so the trash and thumbnail caches do not
    // turn up among a user's recent files.
    let walk = WalkDir::new(&root)
        .into_iter()
        .filter_entry(|e| !is_hidden_dir(e))
        .filter_map(|e| e.ok());

    for entry in walk {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            continue;
        }
        let item = FileEntry::from_metadata(entry.path(), &meta);
        if item.modified_ms >= cutoff && !item.is_hidden {
            out.push(item);
        }
    }

    out.sort_by_key(|e| Reverse(e.modified_ms));
    if limit > 0 {
        out.truncate(limit as usize);
    }
    Ok(out)
}
