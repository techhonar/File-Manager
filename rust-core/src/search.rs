//! Global search: a parallel filesystem walk with filters.
//!
//! No index is maintained. A persistent index would be faster on repeat
//! searches but has to be invalidated as files change, and on a phone-sized
//! tree a parallel walk answers in well under a second. If search ever feels
//! slow, this is the module to revisit -- the API would not change.

use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::Result;
use crate::types::{sort_entries, FileCategory, FileEntry, SortOptions};
use rayon::prelude::*;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use walkdir::WalkDir;

/// Everything the search screen's filter sheet can set.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchFilter {
    /// Matched case-insensitively against the file name. Empty matches all,
    /// which is how "show me every PDF" works with no typed query.
    pub query: String,
    /// Empty means every category.
    pub categories: Vec<FileCategory>,
    pub min_size: Option<u64>,
    pub max_size: Option<u64>,
    /// Epoch millis; only files modified at or after this.
    pub modified_after: Option<u64>,
    pub include_hidden: bool,
    /// 0 means unlimited.
    pub limit: u32,
}

impl Default for SearchFilter {
    fn default() -> Self {
        SearchFilter {
            query: String::new(),
            categories: Vec::new(),
            min_size: None,
            max_size: None,
            modified_after: None,
            include_hidden: false,
            limit: 500,
        }
    }
}

impl SearchFilter {
    fn matches(&self, entry: &FileEntry) -> bool {
        if !self.include_hidden && entry.is_hidden {
            return false;
        }
        if !self.query.is_empty()
            && !entry.name.to_lowercase().contains(&self.query.to_lowercase())
        {
            return false;
        }
        if !self.categories.is_empty() && !self.categories.contains(&entry.category) {
            return false;
        }
        if self.min_size.is_some_and(|min| entry.size < min) {
            return false;
        }
        if self.max_size.is_some_and(|max| entry.size > max) {
            return false;
        }
        if self.modified_after.is_some_and(|after| entry.modified_ms < after) {
            return false;
        }
        true
    }
}

/// Search one or more roots in parallel.
///
/// Each root gets its own rayon task; within a root the walk is sequential,
/// which is the right shape for flash storage -- more threads per directory
/// mostly adds contention rather than throughput.
#[uniffi::export]
pub fn search(
    roots: Vec<String>,
    filter: SearchFilter,
    sort: SortOptions,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<Vec<FileEntry>> {
    let results = Mutex::new(Vec::new());
    let scanned = AtomicU64::new(0);

    roots.par_iter().for_each(|root| {
        let mut local = Vec::new();

        for entry in WalkDir::new(root).follow_links(false).into_iter() {
            if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
                return;
            }
            let Ok(entry) = entry else { continue };
            let Ok(meta) = entry.metadata() else { continue };
            if meta.is_dir() {
                continue;
            }

            // Report every 512 files -- often enough to feel live, rare
            // enough that the JNI hop is not the bottleneck.
            let n = scanned.fetch_add(1, Ordering::Relaxed);
            if n % 512 == 0 {
                if let Some(ref l) = listener {
                    l.on_progress(n, 0, entry.path().to_string_lossy().into_owned());
                }
            }

            let item = FileEntry::from_metadata(entry.path(), &meta);
            if filter.matches(&item) {
                local.push(item);
            }
        }

        // One lock per root rather than per match.
        results.lock().unwrap().extend(local);
    });

    if let Some(token) = cancel {
        token.check()?;
    }

    let mut out = results.into_inner().unwrap();
    sort_entries(&mut out, &sort);
    if filter.limit > 0 {
        out.truncate(filter.limit as usize);
    }
    Ok(out)
}
