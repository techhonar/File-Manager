//! Global search: a parallel filesystem walk with filters.
//!
//! No index is maintained. A persistent index would be faster on repeat
//! searches but has to be invalidated as files change, and on a phone-sized
//! tree a parallel walk answers in well under a second. If search ever feels
//! slow, this is the module to revisit -- the API would not change.

use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::Result;
use crate::types::{is_hidden_dir, sort_entries, FileCategory, FileEntry, SortOptions};
use rayon::prelude::*;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
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

/// A [`SearchFilter`] with the per-walk work done once.
///
/// The filter arrives over FFI as plain data and was used directly, which meant
/// `query.to_lowercase()` ran once per file examined - tens of thousands of
/// identical allocations for a value that never changes. Compiling it once also
/// gives somewhere to answer the cheap questions from, so a file can be
/// rejected on its name before anything is allocated for it.
struct Compiled {
    query: String,
    categories: Vec<crate::types::FileCategory>,
    include_hidden: bool,
    min_size: Option<u64>,
    max_size: Option<u64>,
    modified_after: Option<u64>,
}

impl Compiled {
    fn new(filter: &SearchFilter) -> Self {
        Compiled {
            query: filter.query.to_lowercase(),
            categories: filter.categories.clone(),
            include_hidden: filter.include_hidden,
            min_size: filter.min_size,
            max_size: filter.max_size,
            modified_after: filter.modified_after,
        }
    }

    /// Everything decidable from the file name, which is borrowed from the
    /// directory entry rather than owned.
    ///
    /// This is the hot path: it runs for every file on the device, and all but
    /// a few of them fail it. Building the full entry first meant allocating a
    /// name and a path for each one before finding out it was not wanted.
    fn name_passes(&self, name: &str) -> bool {
        if !self.include_hidden && name.starts_with('.') {
            return false;
        }
        if !self.query.is_empty() && !contains_ignoring_case(name, &self.query) {
            return false;
        }
        if !self.categories.is_empty()
            && !self.categories.contains(&crate::categories::categorize(name))
        {
            return false;
        }
        true
    }

    /// The rest, which needs the metadata.
    fn metadata_passes(&self, size: u64, modified_ms: u64) -> bool {
        if self.min_size.is_some_and(|min| size < min) {
            return false;
        }
        if self.max_size.is_some_and(|max| size > max) {
            return false;
        }
        if self.modified_after.is_some_and(|after| modified_ms < after) {
            return false;
        }
        true
    }
}

/// Case-insensitive substring search that does not allocate for ASCII.
///
/// `needle` must already be lowercase. The ASCII path covers almost every file
/// name; anything else falls back to the allocating comparison, which is what
/// this replaced and is still correct for names in other scripts.
fn contains_ignoring_case(haystack: &str, needle: &str) -> bool {
    if needle.is_empty() {
        return true;
    }
    if !haystack.is_ascii() || !needle.is_ascii() {
        return haystack.to_lowercase().contains(needle);
    }

    let hay = haystack.as_bytes();
    let pin = needle.as_bytes();
    if pin.len() > hay.len() {
        return false;
    }
    hay.windows(pin.len())
        .any(|window| window.eq_ignore_ascii_case(pin))
}

impl SearchFilter {
    fn matches(&self, entry: &FileEntry) -> bool {
        let compiled = Compiled::new(self);
        compiled.name_passes(&entry.name)
            && compiled.metadata_passes(entry.size, entry.modified_ms)
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

        let walk = WalkDir::new(root)
            .follow_links(false)
            .into_iter()
            .filter_entry(|e| filter.include_hidden || !is_hidden_dir(e));

        for entry in walk {
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

/// How many matches to gather before handing them over.
///
/// Every call crosses into the JVM, so emitting one file at a time would make
/// the boundary the bottleneck on a folder of thousands. 64 is small enough
/// that results appear to arrive continuously and large enough that the hop
/// costs nothing measurable.
const BATCH_SIZE: usize = 64;

/// Hand over a partial batch after this long, however few matches it holds.
///
/// Size alone is not enough. A narrow query like "madison" might match three
/// files on the whole device, which never fills a batch - so those three would
/// sit in the buffer until the walk ended, and the screen would show nothing
/// until every file had been examined. That is precisely the behaviour
/// streaming exists to avoid.
const FLUSH_AFTER: Duration = Duration::from_millis(120);

/// How often to check the clock, in files examined. Reading it per file would
/// be wasted work on a tree of millions.
const CLOCK_CHECK_INTERVAL: u64 = 64;

/// How often to report files examined, in files.
const SCAN_REPORT_INTERVAL: u64 = 512;

/// Receives results while the walk is still running.
///
/// The plain [`search`] collects everything, sorts it and returns once, so the
/// caller sees nothing until the whole device has been walked. This lets the
/// UI fill in as matches are found, which is how a file manager is expected to
/// behave.
#[uniffi::export(with_foreign)]
pub trait SearchSink: Send + Sync {
    /// A batch of newly found matches, in discovery order.
    ///
    /// Called from several threads. Order between batches is not meaningful -
    /// the caller sorts what it has accumulated.
    fn on_batch(&self, entries: Vec<FileEntry>);

    /// Files examined so far, matched or not. Called periodically, so a search
    /// that is finding nothing still looks alive.
    fn on_scanned(&self, count: u64);

    /// The walk has stopped. `cancelled` separates a user stop and the limit
    /// being reached from a natural end.
    fn on_finished(&self, matched: u64, cancelled: bool);
}

/// Search, delivering matches as they are found rather than all at once.
///
/// Returns when the walk is done; every result arrives through `sink`. The
/// filter's `limit` still applies - the walk stops early once that many
/// matches have been emitted, since nothing beyond it can be displayed.
#[uniffi::export]
pub fn search_streaming(
    roots: Vec<String>,
    filter: SearchFilter,
    sink: Arc<dyn SearchSink>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<()> {
    let matched = AtomicU64::new(0);
    let scanned = AtomicU64::new(0);
    let hit_limit = AtomicBool::new(false);
    let limit = if filter.limit == 0 { u64::MAX } else { filter.limit as u64 };

    // Compiled once here, not once per file. See Compiled.
    let filter = Compiled::new(&filter);

    // One task per top-level directory rather than one per root.
    //
    // A phone has a single root, so par_iter over the roots put the whole
    // device on one thread while the rest of the pool sat idle. The work here
    // is dominated by waiting on the filesystem - getdents and stat - which is
    // exactly what spreads well across threads, as long as they are walking
    // different subtrees rather than contending over one directory.
    let units = crate::walk::units(&roots, filter.include_hidden);

    units.par_iter().for_each(|unit| {
        let mut batch: Vec<FileEntry> = Vec::with_capacity(BATCH_SIZE);
        let mut last_flush = Instant::now();
        let mut since_clock_check = 0u64;

        let mut walk = WalkDir::new(&unit.path).follow_links(false);
        if unit.shallow {
            // The root's own files only; its subdirectories are units of
            // their own and would otherwise be walked twice.
            walk = walk.max_depth(1);
        }
        let walk = walk
            .into_iter()
            .filter_entry(|e| filter.include_hidden || !is_hidden_dir(e));

        for entry in walk {
            if cancel.as_ref().is_some_and(|t| t.is_cancelled())
                || hit_limit.load(Ordering::Relaxed)
            {
                break;
            }
            let Ok(entry) = entry else { continue };
            // file_type() is free - the walk already knows it - where
            // metadata() is a stat syscall. Directories are the majority of
            // entries in a deep tree and none of them can match.
            if entry.file_type().is_dir() {
                continue;
            }

            let n = scanned.fetch_add(1, Ordering::Relaxed);
            if n % SCAN_REPORT_INTERVAL == 0 {
                sink.on_scanned(n);
            }

            // Time-based flush, checked while scanning rather than only on a
            // match: a rare query would otherwise leave its handful of results
            // sitting in the buffer for the rest of the walk.
            since_clock_check += 1;
            if since_clock_check >= CLOCK_CHECK_INTERVAL {
                since_clock_check = 0;
                if !batch.is_empty() && last_flush.elapsed() >= FLUSH_AFTER {
                    matched.fetch_add(batch.len() as u64, Ordering::Relaxed);
                    sink.on_batch(std::mem::take(&mut batch));
                    batch.reserve(BATCH_SIZE);
                    last_flush = Instant::now();
                }
            }

            // Name first, borrowed from the entry. Almost every file fails
            // here, and failing costs nothing: no stat, no allocation.
            let name = entry.file_name().to_string_lossy();
            if !filter.name_passes(&name) {
                continue;
            }

            // Only now is the metadata worth a syscall.
            let Ok(meta) = entry.metadata() else { continue };
            if !filter.metadata_passes(meta.len(), crate::types::modified_millis(&meta)) {
                continue;
            }

            batch.push(FileEntry::from_metadata(entry.path(), &meta));
            if batch.len() >= BATCH_SIZE {
                let count = matched.fetch_add(batch.len() as u64, Ordering::Relaxed)
                    + batch.len() as u64;
                sink.on_batch(std::mem::take(&mut batch));
                batch.reserve(BATCH_SIZE);
                last_flush = Instant::now();
                if count >= limit {
                    hit_limit.store(true, Ordering::Relaxed);
                    break;
                }
            }
        }

        // Whatever did not fill a batch still has to be delivered.
        if !batch.is_empty() {
            matched.fetch_add(batch.len() as u64, Ordering::Relaxed);
            sink.on_batch(batch);
        }
    });

    let was_cancelled = cancel.as_ref().is_some_and(|t| t.is_cancelled());
    sink.on_scanned(scanned.load(Ordering::Relaxed));
    sink.on_finished(matched.load(Ordering::Relaxed), was_cancelled);
    Ok(())
}
