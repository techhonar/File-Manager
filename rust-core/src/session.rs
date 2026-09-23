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
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicU64;
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
    ///
    /// On a remembered page, true when the walk it came from ran to the end.
    /// A walk stopped partway - the user left before it was done - leaves
    /// its page remembered with this false: something to show, but not the
    /// whole list, so the caller knows to let a fresh walk replace it.
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
    /// Which run the results belong to. A run that is still stopping when a
    /// newer one starts - its walk held up in one slow directory, say - must
    /// neither add to the newer run's results nor file them under its own key.
    run: u64,
    /// Whether a run is walking right now.
    walking: bool,
    /// Set by [`SearchSession::clear`] during a walk; see there.
    clear_when_done: bool,
}

impl Accumulated {
    fn empty(&mut self) {
        self.found = Vec::new();
        self.total = 0;
        self.trimmed = false;
        self.last_page = None;
    }
}

/// How many entries a session keeps.
///
/// Held results are what makes narrowing free, but they are also the largest
/// thing this app keeps in memory: about a third of a kilobyte each once the
/// path and name are counted. Unbounded, a query matching every photo on a
/// full phone would hold tens of megabytes for as long as the screen is open.
/// Twenty thousand is far more than is ever displayed and costs a few.
const RETAIN_LIMIT: usize = 20_000;

/// How many keys the cache holds.
///
/// One per category and nothing else - free-text searches are not cached,
/// because there is no bound on how many different ones someone types. Eight
/// leaves room for the categories that exist with a little spare.
const CACHE_KEYS: usize = 8;

/// How many entries are remembered per key.
///
/// Smaller than a page on purpose. This one is read on the way into a screen,
/// and every entry costs about seven microseconds to hand over - a full page
/// of a thousand is a dropped frame on the tap. Two hundred fills any screen
/// with room to scroll, and the fresh walk replaces it within the second.
const CACHE_PAGE: usize = 200;

/// One search screen's worth of state.
#[derive(uniffi::Object, Default)]
pub struct SearchSession {
    inner: Mutex<Accumulated>,
    /// The last page shown for a category, kept so that opening it again has
    /// something to show at once. Outlives the screen deliberately: it is the
    /// screen closing and opening that this exists for.
    cache: Mutex<HashMap<String, SearchPage>>,
    /// Where remembered pages are also written, so they outlive the process.
    /// None keeps them in memory only.
    cache_dir: Option<PathBuf>,
}

/// A remembered page as written to disk: which files, not what they were.
///
/// Paths only. What else an entry holds is read again from the file when the
/// page is loaded, which is also how a file deleted in the meantime is noticed
/// rather than shown.
#[derive(Serialize, Deserialize)]
struct SavedPage {
    total: u64,
    finished: bool,
    complete: bool,
    paths: Vec<String>,
}

#[uniffi::export]
impl SearchSession {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(SearchSession::default())
    }

    /// A session whose remembered pages survive the process, kept in `dir`.
    ///
    /// Android ends a backgrounded app's process whenever it wants the memory,
    /// and a cache held only in memory went with it - so a category opened
    /// after coming back to the app started from a spinner, which is the one
    /// thing the cache is for.
    #[uniffi::constructor]
    pub fn with_cache_dir(dir: String) -> Arc<Self> {
        Arc::new(SearchSession { cache_dir: Some(PathBuf::from(dir)), ..Default::default() })
    }

    /// Walk, collecting matches and handing over pages as they accumulate.
    ///
    /// Replaces anything a previous run found. Returns when the walk is done;
    /// the last page arrives through `observer` with `finished` set, and is
    /// filed under `cache_key` unless that is empty.
    pub fn run(
        &self,
        roots: Vec<String>,
        filter: SearchFilter,
        page_size: u32,
        // cache_key: where to file the finished page so the next visit has
        // something to show at once. Empty means do not cache this one.
        cache_key: String,
        observer: Arc<dyn SearchObserver>,
        cancel: Option<Arc<CancelToken>>,
    ) -> Result<()> {
        let run = self.start_run();
        let deliver = |page: SearchPage| observer.on_page(page);

        let walked = crate::search::walk_matching(&roots, &filter, cancel.clone(), |batch| {
            let page = {
                let mut held = self.inner.lock().unwrap();
                // Overtaken by a newer run while stopping; see Accumulated::run.
                if held.run != run {
                    return;
                }
                held.total += batch.len() as u64;
                held.found.extend(batch);

                // Trimmed in bulk rather than on every batch: partitioning is
                // linear, so doing it once per doubling keeps the amortised
                // cost per entry constant.
                if held.found.len() > RETAIN_LIMIT * 2 {
                    trim_to(&mut held.found, RETAIN_LIMIT);
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
        });

        let cancelled = cancel.as_ref().is_some_and(|t| t.is_cancelled());
        let last = self.finish_run(run, &cache_key, page_size, cancelled);
        walked?;
        if let Some(page) = last {
            deliver(page);
        }
        Ok(())
    }

    /// What was last shown for this key, if anything.
    ///
    /// Stale by definition - it is whatever the last walk found, which may
    /// have been a while ago. The caller shows it while a fresh walk runs, so
    /// that opening a category does not start with an empty screen every time.
    pub fn cached(&self, cache_key: String) -> Option<SearchPage> {
        if let Some(page) = self.cache.lock().unwrap().get(&cache_key) {
            return Some(page.clone());
        }
        let page = self.load(&cache_key)?;
        // Held once read, so later visits in this run of the app cost what
        // they always did.
        self.cache.lock().unwrap().entry(cache_key).or_insert_with(|| page.clone());
        Some(page)
    }

    /// Drop everything remembered for every key, on disk as well.
    pub fn forget_cached(&self) {
        self.cache.lock().unwrap().clear();
        if let Some(dir) = &self.cache_dir {
            let _ = std::fs::remove_dir_all(dir);
        }
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
        self.inner.lock().unwrap().found.retain(|entry| !gone.contains(entry.path.as_str()));

        // And from what is remembered, which is what the next visit opens
        // with: a file deleted from a category came back the next time the
        // category was opened, until the walk caught up with it.
        for page in self.cache.lock().unwrap().values_mut() {
            let before = page.entries.len();
            page.entries.retain(|entry| !gone.contains(entry.path.as_str()));
            page.total = page.total.saturating_sub((before - page.entries.len()) as u64);
        }
    }

    /// Forget the results. Called when the screen is closed, so a scan of the
    /// whole device is not held for the life of the process.
    ///
    /// Asked during a walk - the screen closing on a walk it has just
    /// cancelled - the walk empties them itself once it has filed what it
    /// found. Emptied here and then, it had nothing left to file, and leaving
    /// a category before its walk finished left nothing for next time.
    pub fn clear(&self) {
        let mut held = self.inner.lock().unwrap();
        if held.walking {
            held.clear_when_done = true;
        } else {
            held.empty();
        }
    }
}

impl SearchSession {
    /// Take the session over for a new run, and say which run it is.
    fn start_run(&self) -> u64 {
        let mut held = self.inner.lock().unwrap();
        held.empty();
        held.last_page = Some(Instant::now());
        held.run += 1;
        held.walking = true;
        held.clear_when_done = false;
        held.run
    }

    /// End run `run`: its last page, which is also filed under `cache_key`.
    ///
    /// None when a newer run has taken the session over in the meantime. What
    /// is held is then that run's, and reporting or filing it as this one's
    /// would put one category's files under another's name.
    fn finish_run(
        &self,
        run: u64,
        cache_key: &str,
        page_size: u32,
        cancelled: bool,
    ) -> Option<SearchPage> {
        let page = {
            let mut held = self.inner.lock().unwrap();
            if held.run != run {
                return None;
            }
            held.walking = false;
            // One last trim, so what is held afterwards is bounded whatever
            // the walk found.
            if held.found.len() > RETAIN_LIMIT {
                trim_to(&mut held.found, RETAIN_LIMIT);
                held.trimmed = true;
            }
            let (total, complete) = (held.total, !held.trimmed);
            let page = take_page(&mut held.found, page_size, true, total, complete);
            if std::mem::take(&mut held.clear_when_done) {
                held.empty();
            }
            page
        };

        if !cache_key.is_empty() {
            let mut remembered = page.clone();
            remembered.entries.truncate(CACHE_PAGE);
            // A walk stopped partway is remembered as such. Only a walk that
            // ran to the end used to be, so leaving a category before its walk
            // finished - on a full phone, most visits - left nothing, and every
            // visit after that started from nothing too.
            remembered.finished = !cancelled;
            self.remember(cache_key.to_string(), remembered);
        }
        Some(page)
    }

    /// File `page` under `key`, unless what is there already is better.
    fn remember(&self, key: String, page: SearchPage) {
        // Only part of a list has to be weighed against what is there, which
        // may be on disk from an earlier run of the app rather than in memory.
        let held = if page.finished { None } else { self.cached(key.clone()) };
        if !worth_remembering(held.as_ref(), &page) {
            return;
        }
        {
            let mut cache = self.cache.lock().unwrap();
            if cache.len() >= CACHE_KEYS && !cache.contains_key(&key) {
                // Nothing clever: the keys are categories, so this only ever
                // fires if that set grows, and any of them is as good to drop.
                if let Some(victim) = cache.keys().next().cloned() {
                    cache.remove(&victim);
                }
            }
            cache.insert(key.clone(), page.clone());
        }
        self.save(&key, &page);
    }

    /// Write a remembered page to disk. Best effort: a cache that cannot be
    /// written is only a slower next visit, not a failure worth reporting.
    fn save(&self, key: &str, page: &SearchPage) {
        let Some(dir) = &self.cache_dir else { return };
        let saved = SavedPage {
            total: page.total,
            finished: page.finished,
            complete: page.complete,
            paths: page.entries.iter().map(|entry| entry.path.clone()).collect(),
        };
        let Ok(json) = serde_json::to_vec(&saved) else { return };
        if std::fs::create_dir_all(dir).is_err() {
            return;
        }

        // Written aside and renamed into place, so a process killed mid-write
        // leaves the old page or the new one, never half of one. Named per
        // write because a stopped walk and the one replacing it can both be
        // filing the same key at once.
        static WRITES: AtomicU64 = AtomicU64::new(0);
        let n = WRITES.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let target = dir.join(file_name_for(key));
        let partial = dir.join(format!("{}.{}-{n}.part", file_name_for(key), std::process::id()));
        if std::fs::write(&partial, json).is_err() || std::fs::rename(&partial, &target).is_err() {
            let _ = std::fs::remove_file(&partial);
        }
    }

    /// A page saved by an earlier run of the app, if there is one.
    fn load(&self, key: &str) -> Option<SearchPage> {
        let dir = self.cache_dir.as_ref()?;
        let bytes = std::fs::read(dir.join(file_name_for(key))).ok()?;
        let saved: SavedPage = serde_json::from_slice(&bytes).ok()?;

        // Each file looked at again rather than trusted: this was saved some
        // time ago, and a file deleted since - by this app or any other - would
        // otherwise be listed until the walk caught up, and fail when tapped.
        let mut entries: Vec<FileEntry> = saved
            .paths
            .iter()
            .filter_map(|path| {
                let path = Path::new(path);
                let meta = std::fs::metadata(path).ok()?;
                (!meta.is_dir()).then(|| FileEntry::from_metadata(path, &meta))
            })
            .collect();
        entries.sort_unstable_by(newest_first);
        let gone = (saved.paths.len() - entries.len()) as u64;

        Some(SearchPage {
            entries,
            total: saved.total.saturating_sub(gone),
            finished: saved.finished,
            complete: saved.complete,
        })
    }
}

/// Whether `page` should replace `held` as what a key remembers.
///
/// A walk that ran to the end always does: it is the newest whole answer. One
/// stopped partway only fills a gap - better than nothing to open with, but
/// not better than a whole list, however old - and only if it found anything,
/// since an empty page would open the category onto "No files match".
fn worth_remembering(held: Option<&SearchPage>, page: &SearchPage) -> bool {
    if page.finished {
        return true;
    }
    !page.entries.is_empty() && held.map_or(true, |held| !held.finished)
}

/// The file a key is saved in. Keys are this app's own ("category:IMAGE"), but
/// anything outside a plain name is replaced so none can reach another folder.
fn file_name_for(key: &str) -> String {
    let safe: String = key
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect();
    format!("{safe}.json")
}

/// Newest first, and by path where that ties.
///
/// The tie-break is not cosmetic: bulk-copied files share a timestamp to the
/// millisecond, and without it two runs over the same unchanged folder pick
/// different members of the tie and the list visibly reshuffles on every
/// refresh.
fn newest_first(a: &FileEntry, b: &FileEntry) -> Ordering {
    b.modified_ms.cmp(&a.modified_ms).then_with(|| a.path.cmp(&b.path))
}

/// Keep the newest `keep` entries, dropping the rest.
fn trim_to(all: &mut Vec<FileEntry>, keep: usize) {
    all.select_nth_unstable_by(keep, newest_first);
    all.truncate(keep);
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
        all.select_nth_unstable_by(wanted, newest_first);
    }
    let page = &mut all[..wanted];
    page.sort_unstable_by(newest_first);

    SearchPage {
        entries: page.to_vec(),
        total,
        finished,
        complete,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn page(files: usize, finished: bool) -> SearchPage {
        let entries = (0..files)
            .map(|i| FileEntry {
                name: format!("f{i}.jpg"),
                path: format!("/f{i}.jpg"),
                size: 1,
                is_dir: false,
                is_hidden: false,
                modified_ms: i as u64,
                category: crate::types::FileCategory::Image,
            })
            .collect();
        SearchPage { entries, total: files as u64, finished, complete: true }
    }

    #[test]
    fn a_whole_list_always_replaces_what_was_remembered() {
        assert!(worth_remembering(None, &page(3, true)));
        assert!(worth_remembering(Some(&page(9, true)), &page(3, true)));
        assert!(worth_remembering(Some(&page(9, false)), &page(0, true)));
    }

    #[test]
    fn part_of_a_list_fills_a_gap_but_never_replaces_a_whole_one() {
        assert!(worth_remembering(None, &page(3, false)), "better than nothing");
        assert!(worth_remembering(Some(&page(1, false)), &page(3, false)), "and than a part");
        assert!(!worth_remembering(Some(&page(9, true)), &page(3, false)), "not than a whole");
    }

    #[test]
    fn a_walk_stopped_before_it_found_anything_is_not_remembered() {
        // It would open the category onto "No files match".
        assert!(!worth_remembering(None, &page(0, false)));
    }

    /// What a walk would have found by the time it is stopped.
    fn found_so_far(session: &SearchSession, files: usize) {
        let mut held = session.inner.lock().unwrap();
        held.found.extend(page(files, true).entries);
        held.total = files as u64;
    }

    #[test]
    fn clearing_during_a_walk_leaves_it_something_to_remember() {
        // The screen closing: its walk is cancelled and the results cleared at
        // once, while the walk is still stopping.
        let session = SearchSession::default();
        let run = session.start_run();
        found_so_far(&session, 3);
        session.clear();
        assert_eq!(session.inner.lock().unwrap().found.len(), 3, "emptied under the walk");

        let last = session.finish_run(run, "category:IMAGE", 10, true).unwrap();
        assert_eq!(last.entries.len(), 3);
        let remembered = session.cached("category:IMAGE".into()).expect("filed for next time");
        assert_eq!(remembered.entries.len(), 3);
        assert!(!remembered.finished, "as part of a list, since the walk was stopped");
        assert!(session.inner.lock().unwrap().found.is_empty(), "and then emptied after all");
    }

    #[test]
    fn a_run_overtaken_by_a_newer_one_neither_reports_nor_files() {
        let session = SearchSession::default();
        let old = session.start_run();
        let _new = session.start_run();
        found_so_far(&session, 2); // the newer run's files

        assert!(session.finish_run(old, "category:IMAGE", 10, true).is_none());
        assert!(
            session.cached("category:IMAGE".into()).is_none(),
            "the newer run's files were filed under the old run's category",
        );
        assert_eq!(session.inner.lock().unwrap().found.len(), 2, "and are still the newer run's");
    }

    #[test]
    fn clearing_between_walks_empties_at_once() {
        let session = SearchSession::default();
        let run = session.start_run();
        found_so_far(&session, 2);
        session.finish_run(run, "", 10, false);
        session.clear();
        assert!(session.inner.lock().unwrap().found.is_empty());
    }

    #[test]
    fn no_key_can_name_a_file_outside_the_cache() {
        assert_eq!(file_name_for("category:IMAGE"), "category_IMAGE.json");
        assert_eq!(file_name_for("../../x"), "______x.json");
    }
}
