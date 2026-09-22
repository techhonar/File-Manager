//! Storage analysis: the usage breakdown and largest-files screens.

use std::cmp::Reverse;
use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::{FileError, Result};
use crate::types::{FileCategory, FileEntry};
use rayon::prelude::*;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::path::PathBuf;
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

/// Everything the storage screen needs, from one walk.
#[derive(Debug, Clone, uniffi::Record)]
pub struct StorageAnalysis {
    pub total_bytes: u64,
    pub free_bytes: u64,
    pub scanned_bytes: u64,
    pub file_count: u64,
    pub by_category: Vec<CategoryUsage>,
    pub largest: Vec<FileEntry>,
}

/// The per-category totals plus the biggest files, gathered in a single
/// parallel pass.
///
/// This replaces calling `storage_summary` and `largest_files` one after the
/// other. Those walk the entire device once each, single threaded, so the
/// screen paid for two full traversals back to back - on a full 128 GB phone
/// that is the difference between a wait and a very long wait. Here every
/// top-level directory is walked on its own rayon thread, each keeping its own
/// counters and its own bounded top-N list, and the partials are merged at the
/// end.
#[uniffi::export]
pub fn analyze_storage(
    root: String,
    largest_limit: u32,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<StorageAnalysis> {
    let fs_stats = filesystem_stats(root.clone())?;
    let limit = largest_limit.max(1) as usize;

    let children: Vec<PathBuf> = match std::fs::read_dir(&root) {
        Ok(read) => read.flatten().map(|e| e.path()).collect(),
        Err(e) => return Err(FileError::from_io(e, std::path::Path::new(&root))),
    };

    let scanned = AtomicU64::new(0);

    // One partial per top-level directory, merged below.
    let partials: Vec<Partial> = children
        .par_iter()
        .map(|child| {
            let mut partial = Partial::new(limit);
            if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
                return partial;
            }

            // WalkDir always follows its ROOT entry, even with
            // follow_links(false) - that flag only governs links found during
            // the walk. Handing it a symlinked directory would therefore
            // descend into the target and count it twice: on this machine
            // /usr/lib64 -> lib alone added 136,270 duplicate entries. The
            // sequential walk never does that, because there the symlink is an
            // entry inside the walk rather than its root, so match it by
            // counting the link itself and not descending.
            let Ok(link_meta) = std::fs::symlink_metadata(child) else {
                return partial;
            };
            // Whether anything under this child may be offered in the largest
            // list. Worked out once per child for the top level, then per entry
            // below it.
            let child_hidden = child
                .file_name()
                .is_some_and(|n| n.to_string_lossy().starts_with('.'));

            if link_meta.is_symlink() {
                partial.add(child, &link_meta, limit, !child_hidden);
                return partial;
            }

            for entry in WalkDir::new(child).follow_links(false).into_iter().filter_map(|e| e.ok()) {
                if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
                    break;
                }
                let Ok(meta) = entry.metadata() else { continue };
                if meta.is_dir() {
                    continue;
                }

                // Report every 4096 files. Often enough that the screen looks
                // alive, rare enough that the hop into the JVM is not itself
                // a cost.
                let n = scanned.fetch_add(1, Ordering::Relaxed);
                if n % 4096 == 0 {
                    if let Some(ref l) = listener {
                        l.on_progress(n, 0, entry.path().to_string_lossy().into_owned());
                    }
                }

                // Hidden if the top-level folder is, or anything between it and
                // the file is, or the file itself. Counted either way - the
                // space is used - but only offered if it is something the user
                // would recognise and could sensibly delete.
                let hidden = child_hidden
                    || entry
                        .path()
                        .strip_prefix(child)
                        .map(|rel| {
                            rel.components()
                                .any(|c| c.as_os_str().to_string_lossy().starts_with('.'))
                        })
                        .unwrap_or(false);
                partial.add(entry.path(), &meta, limit, !hidden);
            }
            partial
        })
        .collect();

    if let Some(token) = cancel {
        token.check()?;
    }

    let mut merged = Partial::new(limit);
    for partial in partials {
        merged.merge(partial, limit);
    }

    let mut by_category: Vec<CategoryUsage> = CATEGORY_ORDER
        .iter()
        .enumerate()
        .map(|(i, category)| CategoryUsage {
            category: *category,
            bytes: merged.bytes[i],
            file_count: merged.counts[i],
        })
        .collect();
    by_category.sort_by_key(|c| Reverse(c.bytes));

    Ok(StorageAnalysis {
        total_bytes: fs_stats.total_bytes,
        free_bytes: fs_stats.free_bytes,
        scanned_bytes: merged.scanned_bytes,
        file_count: merged.counts.iter().sum(),
        by_category,
        largest: merged.largest,
    })
}

/// Categories in a fixed order, so the walk can index arrays instead of
/// allocating or hashing per file.
const CATEGORY_ORDER: [FileCategory; 7] = [
    FileCategory::Image,
    FileCategory::Video,
    FileCategory::Audio,
    FileCategory::Document,
    FileCategory::Archive,
    FileCategory::Apk,
    FileCategory::Other,
];

/// One thread's share of the results.
struct Partial {
    bytes: [u64; 7],
    counts: [u64; 7],
    scanned_bytes: u64,
    /// Biggest first, never longer than the caller's limit.
    largest: Vec<FileEntry>,
}

impl Partial {
    fn new(limit: usize) -> Self {
        Partial {
            bytes: [0; 7],
            counts: [0; 7],
            scanned_bytes: 0,
            largest: Vec::with_capacity(limit + 1),
        }
    }

    /// Count a file, and consider it for the largest list if `offer` is set.
    ///
    /// Hidden files are counted but not offered. The largest list is where
    /// people pick things to delete, and a trashed file turned up there under
    /// its trash id - unrecognisable, and deleting it moved a file already in
    /// the trash into the trash again, leaving its original record pointing
    /// at nothing.
    fn add(&mut self, path: &std::path::Path, meta: &std::fs::Metadata, limit: usize, offer: bool) {
        let name = path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default();
        let category = crate::categories::categorize(name.as_ref());
        if let Some(i) = CATEGORY_ORDER.iter().position(|c| *c == category) {
            self.bytes[i] += meta.len();
            self.counts[i] += 1;
        }
        self.scanned_bytes += meta.len();

        if !offer {
            return;
        }
        // Skip anything that cannot beat the current cut-off, so the common
        // case costs one comparison rather than building a FileEntry.
        if self.largest.len() == limit && meta.len() <= self.largest[limit - 1].size {
            return;
        }
        let item = FileEntry::from_metadata(path, meta);
        let pos = self.largest.partition_point(|e| e.size > item.size);
        self.largest.insert(pos, item);
        self.largest.truncate(limit);
    }

    fn merge(&mut self, other: Partial, limit: usize) {
        for i in 0..7 {
            self.bytes[i] += other.bytes[i];
            self.counts[i] += other.counts[i];
        }
        self.scanned_bytes += other.scanned_bytes;
        self.largest.extend(other.largest);
        self.largest.sort_by_key(|e| Reverse(e.size));
        self.largest.truncate(limit);
    }
}
