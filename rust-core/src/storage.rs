//! Storage analysis: the usage breakdown and largest-files screens.

use std::cmp::Reverse;
use crate::cancel::CancelToken;
use crate::errors::{FileError, Result};
use crate::types::{FileCategory, FileEntry};
use std::sync::Arc;
use walkdir::WalkDir;

#[derive(Debug, Clone, uniffi::Record)]
pub struct CategoryUsage {
    pub category: FileCategory,
    pub bytes: u64,
    pub file_count: u64,
}

/// Capacity and free space of a filesystem.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct FilesystemStats {
    pub total_bytes: u64,
    pub free_bytes: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct StorageSummary {
    /// Filesystem capacity and free space, from statvfs.
    pub total_bytes: u64,
    pub free_bytes: u64,
    /// Bytes we actually walked and attributed to a category. This is less
    /// than `total_bytes - free_bytes`, because the OS, other apps' private
    /// data and unreadable directories are not visible to us.
    pub scanned_bytes: u64,
    pub by_category: Vec<CategoryUsage>,
}

/// Capacity and free space for the filesystem holding `path`.
///
/// Kotlin's `StatFs` could do this too, but keeping it here means the storage
/// screen makes one call into Rust instead of stitching two sources together.
#[uniffi::export]
pub fn filesystem_stats(path: String) -> Result<FilesystemStats> {
    use std::ffi::CString;

    let c_path = CString::new(path.as_str())
        .map_err(|_| FileError::Io { detail: "path contains a null byte".into() })?;

    // SAFETY: c_path is a valid NUL-terminated string that outlives the call,
    // and statvfs only writes into the zeroed struct we hand it.
    let stats = unsafe {
        let mut stats: libc::statvfs = std::mem::zeroed();
        if libc::statvfs(c_path.as_ptr(), &mut stats) != 0 {
            return Err(FileError::from_io(
                std::io::Error::last_os_error(),
                std::path::Path::new(&path),
            ));
        }
        stats
    };

    // f_bavail, not f_bfree: blocks free for an unprivileged process, which
    // is what the user actually gets to fill.
    let block = stats.f_frsize as u64;
    Ok(FilesystemStats {
        total_bytes: stats.f_blocks as u64 * block,
        free_bytes: stats.f_bavail as u64 * block,
    })
}

/// Walk `root` once and attribute every file to a category.
///
/// One pass for all categories, rather than one pass per category -- on a
/// full 128 GB card that is the difference between seconds and a minute.
#[uniffi::export]
pub fn storage_summary(
    root: String,
    cancel: Option<Arc<CancelToken>>,
) -> Result<StorageSummary> {
    let fs_stats = filesystem_stats(root.clone())?;

    // Index into this by category so the walk does no allocation per file.
    let order = [
        FileCategory::Image,
        FileCategory::Video,
        FileCategory::Audio,
        FileCategory::Document,
        FileCategory::Archive,
        FileCategory::Apk,
        FileCategory::Other,
    ];
    let mut bytes = [0u64; 7];
    let mut counts = [0u64; 7];
    let mut scanned_bytes = 0u64;

    for entry in WalkDir::new(&root).follow_links(false).into_iter().filter_map(|e| e.ok()) {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            continue;
        }

        let category = crate::categories::categorize(entry.file_name().to_string_lossy().as_ref());
        if let Some(i) = order.iter().position(|c| *c == category) {
            bytes[i] += meta.len();
            counts[i] += 1;
        }
        scanned_bytes += meta.len();
    }

    let mut by_category: Vec<CategoryUsage> = order
        .iter()
        .enumerate()
        .map(|(i, category)| CategoryUsage {
            category: *category,
            bytes: bytes[i],
            file_count: counts[i],
        })
        .collect();

    // Biggest slice first, so the UI can render the bar in order.
    by_category.sort_by_key(|c| Reverse(c.bytes));

    Ok(StorageSummary {
        total_bytes: fs_stats.total_bytes,
        free_bytes: fs_stats.free_bytes,
        scanned_bytes,
        by_category,
    })
}

/// The `limit` biggest files under `root`, largest first.
///
/// Keeps only `limit` entries in memory via a bounded insert rather than
/// sorting every file on the device.
#[uniffi::export]
pub fn largest_files(
    root: String,
    limit: u32,
    cancel: Option<Arc<CancelToken>>,
) -> Result<Vec<FileEntry>> {
    let limit = limit.max(1) as usize;
    let mut top: Vec<FileEntry> = Vec::with_capacity(limit + 1);

    for entry in WalkDir::new(&root).follow_links(false).into_iter().filter_map(|e| e.ok()) {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            continue;
        }
        // Skip anything that cannot crack the current cut-off.
        if top.len() == limit && meta.len() <= top[limit - 1].size {
            continue;
        }

        let item = FileEntry::from_metadata(entry.path(), &meta);
        let pos = top.partition_point(|e| e.size > item.size);
        top.insert(pos, item);
        top.truncate(limit);
    }
    Ok(top)
}
