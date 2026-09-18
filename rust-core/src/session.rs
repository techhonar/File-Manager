//! A search that keeps its results on this side of the boundary.
//!
//! The Kotlin side used to hold everything: batches arrived in walk order, it
//! merged each one into a list kept newest-first, and it kept a second copy so
//! that typing another letter could narrow without walking the disk again.
//!
//! Two things were wrong with that. Every entry found had to cross the FFI -
//! measured at about seven microseconds each, so twenty thousand of them cost
//! more than the walk that found them - and the merge rebuilt a twenty-thousand
//! element list for every batch of sixty-four, which is three million moves per
//! scan and a new array each time for the garbage collector to deal with.
//!
//! Here the results stay in Rust. What crosses is a page: the newest few
//! hundred, which is more than any screen shows, plus a count of how many there
//! really are. Pages go over on a timer rather than per batch, so a scan that
//! finds fifty thousand files sends a handful of pages instead of eight hundred.

use crate::cancel::CancelToken;
use crate::errors::Result;
use crate::search::SearchFilter;
use crate::types::FileEntry;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// How often results are handed over while a walk is running.
///
/// Fast enough to look live, slow enough that the list is not rebuilt on every
/// batch. The old code sent one per sixty-four matches, which on a full device
/// is several hundred rebuilds of the whole list.
const PAGE_INTERVAL: Duration = Duration::from_millis(200);

/// What the UI receives: the newest slice, and the truth about the rest.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchPage {
    /// Newest first, at most the page size asked for.
    pub entries: Vec<FileEntry>,
    /// Everything that matched, which may be far more than `entries` holds.
    pub total: u64,
    /// True for the last page of a walk, so the caller can stop showing
    /// progress without a second callback for it.
    pub finished: bool,
    /// Whether everything that matched is still held.
    ///
    /// False once the retention cap is reached and the oldest matches start
    /// being dropped. Narrowing is only sound while this is true: after that,
    /// filtering what is held would quietly answer from a subset.
    pub complete: bool,
}

/// Receives pages while the walk runs.
#[uniffi::export(with_foreign)]
pub trait SearchObserver: Send + Sync {
    /// A page that replaces whatever came before it. Already ordered, so the
    /// caller has nothing to sort or merge.
    fn on_page(&self, page: SearchPage);
}

/// Results held between calls, so narrowing costs no disk.
#[derive(Default)]
struct Accumulated {
    /// Unordered. Ordering happens when a page is taken, which is a handful of
    /// times per walk rather than once per batch.
    found: Vec<FileEntry>,
    /// Everything that matched, including what has since been dropped.
    total: u64,
    /// Set once the cap has forced anything out.
    trimmed: bool,
    last_page: Option<Instant>,
}

/// How many entries a session keeps.
///
/// Held results are what makes narrowing free, but they are also the largest
/// thing this app keeps in memory: about a third of a kilobyte each once the
/// path and name are counted. Unbounded, a query matching every photo on a
/// full phone would hold tens of megabytes for as long as the screen is open.
/// Twenty thousand is far more than is ever displayed and costs a few.
const RETAIN_LIMIT: usize = 20_000;

/// One search screen's worth of state.
#[derive(uniffi::Object, Default)]
pub struct SearchSession {
    inner: Mutex<Accumulated>,
}

#[uniffi::export]
impl SearchSession {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(SearchSession::default())
    }

    /// Walk, collecting matches and handing over pages as they accumulate.
    ///
    /// Replaces anything a previous run found. Returns when the walk is done;
    /// the last page arrives through `observer` with `finished` set.
    pub fn run(
        &self,
        roots: Vec<String>,
        filter: SearchFilter,
        page_size: u32,
        observer: Arc<dyn SearchObserver>,
        cancel: Option<Arc<CancelToken>>,
    ) -> Result<()> {
        {
            let mut held = self.inner.lock().unwrap();
            held.found = Vec::new();
            held.total = 0;
            held.trimmed = false;
            held.last_page = Some(Instant::now());
        }

        let deliver = |page: SearchPage| observer.on_page(page);

        crate::search::walk_matching(&roots, &filter, cancel.clone(), |batch| {
            let page = {
                let mut held = self.inner.lock().unwrap();
                held.total += batch.len() as u64;
                held.found.extend(batch);

                // Trimmed in bulk rather than on every batch: partitioning is
                // linear, so doing it once per doubling keeps the amortised
                // cost per entry constant.
                if held.found.len() > RETAIN_LIMIT * 2 {
                    let keep = RETAIN_LIMIT;
                    held.found
                        .select_nth_unstable_by(keep, |a, b| b.modified_ms.cmp(&a.modified_ms));
                    held.found.truncate(keep);
                    held.trimmed = true;
                }

                let due = held
                    .last_page
                    .map_or(true, |at| at.elapsed() >= PAGE_INTERVAL);
                if !due {
                    return;
                }
                held.last_page = Some(Instant::now());
                let (total, complete) = (held.total, !held.trimmed);
                take_page(&mut held.found, page_size, false, total, complete)
            };
            deliver(page);
        })?;

        let final_page = {
            let mut held = self.inner.lock().unwrap();
            // One last trim, so what is held afterwards is bounded whatever
            // the walk found.
            if held.found.len() > RETAIN_LIMIT {
                held.found
                    .select_nth_unstable_by(RETAIN_LIMIT, |a, b| b.modified_ms.cmp(&a.modified_ms));
                held.found.truncate(RETAIN_LIMIT);
                held.trimmed = true;
            }
            let (total, complete) = (held.total, !held.trimmed);
            take_page(&mut held.found, page_size, true, total, complete)
        };
        deliver(final_page);
        Ok(())
    }

    /// Narrow what the last run found, without touching the disk.
    ///
    /// Only ever called with a query that extends the one the results were
    /// collected for - a name containing "mad" also contains "ma" - so what is
    /// held is always a superset of the answer.
    pub fn narrow(&self, query: String, page_size: u32) -> SearchPage {
        let held = self.inner.lock().unwrap();
        let needle = query.to_lowercase();

        let mut matches: Vec<FileEntry> = held
            .found
            .iter()
            .filter(|entry| crate::search::contains_ignoring_case(&entry.name, &needle))
            .cloned()
            .collect();

        let total = matches.len() as u64;
        let complete = !held.trimmed;
        take_page(&mut matches, page_size, true, total, complete)
    }

    /// Drop entries for files that have gone.
    ///
    /// Deleting from the results has to reach in here too. Without it the
    /// entries stay held, and the next keystroke narrows over them and brings
    /// the deleted files back onto the screen.
    pub fn forget(&self, paths: Vec<String>) {
        if paths.is_empty() {
            return;
        }
        let gone: std::collections::HashSet<&str> =
            paths.iter().map(String::as_str).collect();
        let mut held = self.inner.lock().unwrap();
        held.found.retain(|entry| !gone.contains(entry.path.as_str()));
    }

    /// Forget the results. Called when the screen is closed, so a scan of the
    /// whole device is not held for the life of the process.
    pub fn clear(&self) {
        let mut held = self.inner.lock().unwrap();
        held.found = Vec::new();
        held.total = 0;
        held.trimmed = false;
        held.last_page = None;
    }
}

/// The newest `page_size` of `all`, ordered, without sorting the rest.
///
/// `select_nth_unstable_by` partitions in linear time so only the page itself
/// has to be sorted. Sorting all fifty thousand to show the first few hundred
/// is most of the work for none of the benefit.
fn take_page(
    all: &mut [FileEntry],
    page_size: u32,
    finished: bool,
    total: u64,
    complete: bool,
) -> SearchPage {
    let wanted = (page_size as usize).min(all.len());

    if wanted < all.len() {
        all.select_nth_unstable_by(wanted, |a, b| b.modified_ms.cmp(&a.modified_ms));
    }
    let page = &mut all[..wanted];
    page.sort_unstable_by_key(|entry| std::cmp::Reverse(entry.modified_ms));

    SearchPage {
        entries: page.to_vec(),
        total,
        finished,
        complete,
    }
}
