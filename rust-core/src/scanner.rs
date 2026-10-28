//! Directory listing and recursive size computation.

use crate::cancel::{CancelToken, PlaceListener, ProgressListener};
use crate::errors::{FileError, Result};
use crate::types::{sort_entries, FileEntry, SortOptions};
use rayon::prelude::*;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};
use walkdir::WalkDir;

/// One level of a directory, sorted. This is the browser's main call.
///
/// Unreadable children are skipped rather than failing the whole listing --
/// Android has plenty of directories you can see but not stat.
#[uniffi::export]
pub fn list_dir(
    path: String,
    show_hidden: bool,
    sort: SortOptions,
) -> Result<Vec<FileEntry>> {
    let dir = Path::new(&path);
    // One stat answers both questions, where exists() and is_dir() made one
    // each. As with them, a stat that fails counts as nothing there.
    let Ok(meta) = std::fs::metadata(dir) else {
        return Err(FileError::NotFound { path });
    };
    if !meta.is_dir() {
        return Err(FileError::NotADirectory { path });
    }

    let read = std::fs::read_dir(dir).map_err(|e| FileError::from_io(e, dir))?;
    // Hidden names go before their stat rather than after it. The stats are
    // what a listing spends its time on, and those were thrown away.
    let items: Vec<_> = read
        .flatten()
        .filter(|item| show_hidden || !item.file_name().as_encoded_bytes().starts_with(b"."))
        .collect();

    // On a phone, shared storage is served through FUSE, so every stat is a
    // round trip to the media provider, and a big folder spent most of its
    // listing waiting on them one at a time. They do not depend on each
    // other, so a big folder's go out several at once.
    let mut entries: Vec<FileEntry> = match listing_pool() {
        Some(pool) if items.len() >= PARALLEL_FROM => {
            pool.install(|| items.par_iter().filter_map(entry_of).collect())
        }
        _ => items.iter().filter_map(entry_of).collect(),
    };

    sort_entries(&mut entries, &sort);
    Ok(entries)
}

/// A folder this size or more has its entries read several at once. Below
/// it, sharing out the work costs about what it saves.
const PARALLEL_FROM: usize = 256;

/// One entry of a listing, or none for a child that cannot be stat'ed.
fn entry_of(item: &std::fs::DirEntry) -> Option<FileEntry> {
    let meta = item.metadata().ok()?;
    Some(FileEntry::from_metadata(&item.path(), &meta))
}

/// Threads for listing alone, apart from rayon's global pool. A search, a
/// category or the storage analysis can keep that one busy for seconds, and
/// a folder's listing queued behind them would stay blank as long. None if
/// the threads could not be started, and then a listing reads one at a time.
fn listing_pool() -> Option<&'static rayon::ThreadPool> {
    static POOL: OnceLock<Option<rayon::ThreadPool>> = OnceLock::new();
    POOL.get_or_init(|| {
        rayon::ThreadPoolBuilder::new()
            .num_threads(LISTING_THREADS)
            .thread_name(|i| format!("list-{i}"))
            .build()
            .ok()
    })
    .as_ref()
}

/// How many stats a listing has waiting at once. They are spent waiting on
/// the media provider, not working, so this need not match the cores.
const LISTING_THREADS: usize = 8;

/// Total bytes under a directory, following no symlinks.
///
/// Parallel across the top-level children: on a phone this is the difference
/// between a snappy folder-properties sheet and a two-second freeze.
#[uniffi::export]
pub fn dir_size(path: String, cancel: Option<Arc<CancelToken>>) -> Result<u64> {
    let dir = Path::new(&path);
    if !dir.exists() {
        return Err(FileError::NotFound { path });
    }

    let children: Vec<_> = match std::fs::read_dir(dir) {
        Ok(read) => read.flatten().map(|e| e.path()).collect(),
        Err(e) => return Err(FileError::from_io(e, dir)),
    };

    let total = AtomicU64::new(0);
    children.par_iter().for_each(|child| {
        if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
            return;
        }
        total.fetch_add(walk_size(child), Ordering::Relaxed);
    });

    if let Some(token) = cancel {
        token.check()?;
    }
    Ok(total.load(Ordering::Relaxed))
}

/// Size of every file in a subtree, ignoring anything we cannot read.
fn walk_size(root: &Path) -> u64 {
    WalkDir::new(root)
        .follow_links(false)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter_map(|e| e.metadata().ok())
        .filter(|m| m.is_file())
        .map(|m| m.len())
        .sum()
}

/// How many files and directories a subtree holds, and their total size.
///
/// Used by copy/move so the UI can show "342 of 1,208 files" instead of a
/// spinner with no end in sight.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TreeStats {
    pub file_count: u64,
    pub dir_count: u64,
    pub total_bytes: u64,
}

#[uniffi::export]
pub fn tree_stats(
    paths: Vec<String>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<TreeStats> {
    let mut stats = TreeStats { file_count: 0, dir_count: 0, total_bytes: 0 };

    for path in &paths {
        for entry in WalkDir::new(path).follow_links(false).into_iter().filter_map(|e| e.ok()) {
            if let Some(ref token) = cancel {
                token.check()?;
            }
            let Ok(meta) = entry.metadata() else { continue };
            if meta.is_dir() {
                stats.dir_count += 1;
            } else {
                stats.file_count += 1;
                stats.total_bytes += meta.len();
            }
        }
    }
    Ok(stats)
}

/// Recursively copy `sources` into `dest_dir`, reporting progress.
///
/// Lives in Rust rather than Kotlin because a 4 GB video copied through the
/// JVM means the bytes cross the JNI boundary; here it is a plain syscall
/// loop, and the progress callback fires per file, not per buffer.
#[uniffi::export]
pub fn copy_paths(
    sources: Vec<String>,
    dest_dir: String,
    overwrite: bool,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let dest = Path::new(&dest_dir);
    if !dest.is_dir() {
        return Err(FileError::NotADirectory { path: dest_dir });
    }

    // Refused before anything is written. Copying a folder into one of its
    // own subfolders recursed without end: each level of the copy was itself
    // inside the source, so it was copied again, until the path grew too long
    // - measured at 797 nested folders before the filesystem gave up, and a
    // copy of every file at each level if the directory happened to list them
    // before the subfolder.
    for source in &sources {
        if lands_inside(Path::new(source), dest) {
            return Err(FileError::IntoItself { path: source.clone() });
        }
    }

    let total = tree_stats(sources.clone(), cancel.clone())?.file_count;
    let mut done = 0u64;

    for source in &sources {
        let src = Path::new(source);
        let Some(name) = src.file_name() else { continue };
        copy_into(src, &dest.join(name), overwrite, &mut done, total, &listener, &cancel)?;
    }
    Ok(done)
}

fn copy_into(
    src: &Path,
    dst: &Path,
    overwrite: bool,
    done: &mut u64,
    total: u64,
    listener: &Option<Arc<dyn ProgressListener>>,
    cancel: &Option<Arc<CancelToken>>,
) -> Result<()> {
    if let Some(token) = cancel {
        token.check()?;
    }

    if src.is_dir() {
        std::fs::create_dir_all(dst).map_err(|e| FileError::from_io(e, dst))?;
        let read = std::fs::read_dir(src).map_err(|e| FileError::from_io(e, src))?;
        for child in read {
            // Not flatten(): an entry that cannot be read has to stop the copy.
            // Skipping it quietly is how a move - which deletes the source once
            // this returns - loses the one file it could not see.
            let child = child.map_err(|e| FileError::from_io(e, src))?;
            copy_into(
                &child.path(),
                &dst.join(child.file_name()),
                overwrite,
                done,
                total,
                listener,
                cancel,
            )?;
        }
        // After the children, because adding each of them moved it on.
        keep_modified_time(src, dst);
        return Ok(());
    }

    if dst.exists() && !overwrite {
        return Err(FileError::AlreadyExists { path: dst.to_string_lossy().into_owned() });
    }
    std::fs::copy(src, dst).map_err(|e| FileError::from_io(e, src))?;
    keep_modified_time(src, dst);

    *done += 1;
    if let Some(l) = listener {
        l.on_progress(*done, total, src.to_string_lossy().into_owned());
    }
    Ok(())
}

/// Copy or move `sources` into `dest_dir`: the Select folder sheet's Copy
/// here and Move here.
///
/// Each item lands whole or not at all. A file is written under its final
/// name, claimed before the first byte so nothing already there is ever
/// written over - a name in use gets a numbered one, "photo (1).jpg", as an
/// extraction or a download does - and is removed again if the copy is
/// cancelled or fails partway, so a cancelled 17 GB copy leaves nothing
/// behind. A folder is rolled back the same way. Items finished before that
/// stay where they are, and `listener` has been told of each as it landed.
///
/// A move within one volume is a rename, instant whatever the size; only one
/// across volumes - to or from an SD card - copies, and removes the original
/// once its copy is complete. An item already in `dest_dir` stays as it is.
///
/// Progress counts bytes as they are written and is checked for cancelling
/// between buffers, where the copy it replaced counted and checked only
/// between files: a single large video showed nothing for minutes and could
/// not be stopped.
#[uniffi::export]
pub fn place_paths(
    sources: Vec<String>,
    dest_dir: String,
    move_them: bool,
    listener: Option<Arc<dyn PlaceListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<()> {
    place_paths_paced(sources, dest_dir, move_them, listener, cancel, REPORT_EVERY)
}

/// place_paths, reporting at most every `every`: zero in the tests, which
/// want every step.
fn place_paths_paced(
    sources: Vec<String>,
    dest_dir: String,
    move_them: bool,
    listener: Option<Arc<dyn PlaceListener>>,
    cancel: Option<Arc<CancelToken>>,
    every: Duration,
) -> Result<()> {
    let dest = Path::new(&dest_dir);
    if !dest.is_dir() {
        return Err(FileError::NotADirectory { path: dest_dir });
    }
    for source in &sources {
        if lands_inside(Path::new(source), dest) {
            return Err(FileError::IntoItself { path: source.clone() });
        }
    }

    // Each item's size, for the totals now and for a rename's share of the
    // progress later.
    let sizes = sources
        .iter()
        .map(|s| tree_stats(vec![s.clone()], cancel.clone()))
        .collect::<Result<Vec<_>>>()?;
    let mut tally = Tally {
        files_done: 0,
        files_total: sizes.iter().map(|s| s.file_count).sum(),
        bytes_done: 0,
        bytes_total: sizes.iter().map(|s| s.total_bytes).sum(),
        last: Instant::now(),
        every,
        listener: &listener,
    };
    tally.report(true);
    let mut buffer = vec![0u8; COPY_BUFFER];

    for (source, size) in sources.iter().zip(&sizes) {
        if let Some(token) = &cancel {
            token.check()?;
        }
        let src = Path::new(source);
        let Some(name) = src.file_name().map(|n| n.to_string_lossy().into_owned()) else { continue };
        let is_dir = src.is_dir();

        if move_them && already_in(src, dest) {
            tally.item(size.file_count, size.total_bytes);
            if let Some(l) = &listener {
                l.on_placed(source.clone(), source.clone());
            }
            continue;
        }

        if move_them {
            let target = free_target(dest, &name, is_dir);
            match std::fs::rename(src, &target) {
                Ok(()) => {
                    tally.item(size.file_count, size.total_bytes);
                    if let Some(l) = &listener {
                        l.on_placed(source.clone(), target.to_string_lossy().into_owned());
                    }
                    continue;
                }
                // Another volume: copied below, then the original removed.
                Err(e) if e.raw_os_error() == Some(libc::EXDEV) => {}
                Err(e) => return Err(FileError::from_io(e, src)),
            }
        }

        let target = copy_whole(src, dest, &name, is_dir, &mut tally, &mut buffer, &cancel)?;
        if move_them {
            let removed = if is_dir { std::fs::remove_dir_all(src) } else { std::fs::remove_file(src) };
            removed.map_err(|e| FileError::from_io(e, src))?;
        }
        if let Some(l) = &listener {
            l.on_placed(source.clone(), target.to_string_lossy().into_owned());
        }
    }
    tally.report(true);
    Ok(())
}

/// Read and written this much at a time, with a check for cancelling between.
const COPY_BUFFER: usize = 1 << 20;

/// How often progress is passed on: each report is a call into the JVM.
const REPORT_EVERY: Duration = Duration::from_millis(100);

/// How far a copy or move has got, passed on to its listener.
struct Tally<'a> {
    files_done: u64,
    files_total: u64,
    bytes_done: u64,
    bytes_total: u64,
    last: Instant,
    every: Duration,
    listener: &'a Option<Arc<dyn PlaceListener>>,
}

impl Tally<'_> {
    fn bytes(&mut self, n: u64) {
        self.bytes_done += n;
        self.report(false);
    }

    fn file(&mut self) {
        self.files_done += 1;
        self.report(false);
    }

    /// A whole item at once: a rename, or one already where it is going.
    fn item(&mut self, files: u64, bytes: u64) {
        self.files_done += files;
        self.bytes_done += bytes;
        self.report(true);
    }

    fn report(&mut self, now: bool) {
        let Some(listener) = self.listener else { return };
        if !now && self.last.elapsed() < self.every {
            return;
        }
        self.last = Instant::now();
        listener.on_progress(self.files_done, self.files_total, self.bytes_done, self.bytes_total);
    }
}

/// Whether `src` sits directly in `dest`, so moving it there changes nothing.
fn already_in(src: &Path, dest: &Path) -> bool {
    match (src.parent().and_then(|p| p.canonicalize().ok()), dest.canonicalize().ok()) {
        (Some(parent), Some(dest)) => parent == dest,
        _ => false,
    }
}

/// `name`, then "name (1)", "name (2)"... - the numbering the Kotlin side's
/// freeName uses, so a copy, an extraction and a download count alike. A
/// file's number goes before its extension; a name that is all extension,
/// such as ".nomedia", and a folder's, take it at the end.
fn numbered(name: &str, n: u32, is_dir: bool) -> String {
    if n == 0 {
        return name.to_string();
    }
    if !is_dir {
        if let Some((base, extension)) = name.rsplit_once('.') {
            if !base.is_empty() {
                return format!("{base} ({n}).{extension}");
            }
        }
    }
    format!("{name} ({n})")
}

/// The first of `name`, "name (1)"... with nothing in `dest` under it.
fn free_target(dest: &Path, name: &str, is_dir: bool) -> PathBuf {
    (0..)
        .map(|n| dest.join(numbered(name, n, is_dir)))
        .find(|candidate| std::fs::symlink_metadata(candidate).is_err())
        .expect("an unbounded range always has a free name")
}

/// Copy `src` into `dest` as one item, under a name nothing there has, and
/// remove what was made of it if the copy does not finish. Returns where it
/// went.
fn copy_whole(
    src: &Path,
    dest: &Path,
    name: &str,
    is_dir: bool,
    tally: &mut Tally,
    buffer: &mut [u8],
    cancel: &Option<Arc<CancelToken>>,
) -> Result<PathBuf> {
    if is_dir {
        let target = claim(dest, name, true, |path| std::fs::create_dir(path).map(|_| ()))?;
        return match copy_tree(src, &target, tally, buffer, cancel) {
            Ok(()) => {
                keep_modified_time(src, &target);
                Ok(target)
            }
            Err(e) => {
                // Made by this copy, under a name that was free: nothing in
                // it was there before.
                let _ = std::fs::remove_dir_all(&target);
                Err(e)
            }
        };
    }

    let mut out = None;
    let target = claim(dest, name, false, |path| {
        out = Some(std::fs::OpenOptions::new().write(true).create_new(true).open(path)?);
        Ok(())
    })?;
    let file = out.expect("claim succeeded, so the file was opened");
    match copy_bytes(src, file, tally, buffer, cancel) {
        Ok(()) => {
            keep_attributes(src, &target);
            Ok(target)
        }
        Err(e) => {
            let _ = std::fs::remove_file(&target);
            Err(e)
        }
    }
}

/// Take the first free name for `name` in `dest` by making it with `make`,
/// which fails where something already is: checking and then making would
/// let two copies at once take the same name.
fn claim(
    dest: &Path,
    name: &str,
    is_dir: bool,
    mut make: impl FnMut(&Path) -> std::io::Result<()>,
) -> Result<PathBuf> {
    for n in 0.. {
        let candidate = dest.join(numbered(name, n, is_dir));
        match make(&candidate) {
            Ok(()) => return Ok(candidate),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(FileError::from_io(e, &candidate)),
        }
    }
    unreachable!("an unbounded range always has a free name")
}

/// Copy what is in folder `src` into `dst`, a folder this copy just made, so
/// nothing in it is in the way.
fn copy_tree(
    src: &Path,
    dst: &Path,
    tally: &mut Tally,
    buffer: &mut [u8],
    cancel: &Option<Arc<CancelToken>>,
) -> Result<()> {
    let read = std::fs::read_dir(src).map_err(|e| FileError::from_io(e, src))?;
    for child in read {
        if let Some(token) = cancel {
            token.check()?;
        }
        // Not flatten(): an entry that cannot be read has to stop the copy,
        // or a move would remove the folder without the file it missed.
        let child = child.map_err(|e| FileError::from_io(e, src))?;
        let from = child.path();
        let to = dst.join(child.file_name());
        if from.is_dir() {
            std::fs::create_dir(&to).map_err(|e| FileError::from_io(e, &to))?;
            copy_tree(&from, &to, tally, buffer, cancel)?;
            keep_modified_time(&from, &to);
        } else {
            let out = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&to)
                .map_err(|e| FileError::from_io(e, &to))?;
            copy_bytes(&from, out, tally, buffer, cancel)?;
            keep_attributes(&from, &to);
        }
    }
    Ok(())
}

/// The bytes of file `src` into `out`, a buffer at a time.
fn copy_bytes(
    src: &Path,
    mut out: std::fs::File,
    tally: &mut Tally,
    buffer: &mut [u8],
    cancel: &Option<Arc<CancelToken>>,
) -> Result<()> {
    let mut input = std::fs::File::open(src).map_err(|e| FileError::from_io(e, src))?;
    loop {
        if let Some(token) = cancel {
            token.check()?;
        }
        let read = match input.read(buffer) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(FileError::from_io(e, src)),
        };
        out.write_all(&buffer[..read]).map_err(|e| FileError::from_io(e, src))?;
        tally.bytes(read as u64);
    }
    tally.file();
    Ok(())
}

/// What std::fs::copy carried, and the time it did not: permissions and the
/// modification time. Best effort, as on shared storage neither may stick.
fn keep_attributes(src: &Path, dst: &Path) {
    if let Ok(meta) = std::fs::metadata(src) {
        let _ = std::fs::set_permissions(dst, meta.permissions());
    }
    keep_modified_time(src, dst);
}

/// Whether copying `source` into `dest` would put it inside itself.
///
/// Canonicalised so that a trailing slash, a `..`, or a symlinked route to the
/// same place is not a way round it. A path that cannot be resolved is not
/// inside anything; the copy then fails on its own terms.
pub(crate) fn lands_inside(source: &Path, dest: &Path) -> bool {
    let (Ok(source), Ok(dest)) = (source.canonicalize(), dest.canonicalize()) else {
        return false;
    };
    source.is_dir() && dest.starts_with(&source)
}

/// Give `dst` the modification time `src` has.
///
/// `std::fs::copy` carries permissions but not times, so a copied album took
/// the time of the copy and jumped to the top of every list sorted by date as
/// though it had just been taken. Best effort: a filesystem that will not set
/// times has still been copied to, which is the part that matters.
pub(crate) fn keep_modified_time(src: &Path, dst: &Path) {
    let Ok(when) = std::fs::metadata(src).and_then(|m| m.modified()) else { return };
    let target = if dst.is_dir() {
        std::fs::File::open(dst)
    } else {
        std::fs::File::options().write(true).open(dst)
    };
    if let Ok(file) = target {
        let _ = file.set_times(std::fs::FileTimes::new().set_modified(when));
    }
}

/// Recursively delete paths. Returns how many files were removed.
///
/// This is the permanent delete -- the trash module handles the reversible one.
#[uniffi::export]
pub fn delete_paths(
    paths: Vec<String>,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<u64> {
    let total = tree_stats(paths.clone(), cancel.clone())?.file_count;
    let mut done = 0u64;

    for path in &paths {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let p = Path::new(path);
        let meta = std::fs::symlink_metadata(p).map_err(|e| FileError::from_io(e, p))?;

        if meta.is_dir() {
            done += count_files(p);
            std::fs::remove_dir_all(p).map_err(|e| FileError::from_io(e, p))?;
        } else {
            std::fs::remove_file(p).map_err(|e| FileError::from_io(e, p))?;
            done += 1;
        }
        if let Some(ref l) = listener {
            l.on_progress(done, total, path.clone());
        }
    }
    Ok(done)
}

fn count_files(root: &Path) -> u64 {
    WalkDir::new(root)
        .into_iter()
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().is_file())
        .count() as u64
}

/// Resolve paths back into entries, skipping any that no longer exist.
///
/// Favourites and pinned items are stored as paths, so they have to be turned
/// back into entries to be listed - and a remembered path is exactly the kind
/// that goes stale, because the file behind it can be deleted or renamed at
/// any time by anything. Missing ones are dropped rather than reported, since
/// a favourite that no longer exists is not an error to show the user.
#[uniffi::export]
pub fn entries_for(paths: Vec<String>) -> Vec<FileEntry> {
    paths
        .iter()
        .filter_map(|path| {
            let p = Path::new(path);
            let meta = std::fs::symlink_metadata(p).ok()?;
            Some(FileEntry::from_metadata(p, &meta))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// Cancels its token at the first report with some bytes written.
    struct CancelPartway {
        token: Arc<CancelToken>,
        seen: Mutex<Vec<(u64, u64)>>,
    }

    impl PlaceListener for CancelPartway {
        fn on_progress(&self, _files_done: u64, _files_total: u64, bytes_done: u64, bytes_total: u64) {
            self.seen.lock().unwrap().push((bytes_done, bytes_total));
            if bytes_done > 0 && bytes_done < bytes_total {
                self.token.cancel();
            }
        }
        fn on_placed(&self, _source: String, _target: String) {}
    }

    #[test]
    fn a_copy_stopped_partway_through_a_file_leaves_nothing_of_it() {
        let root = std::env::temp_dir().join(format!("fm-place-partway-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let (from, to) = (root.join("from"), root.join("to"));
        std::fs::create_dir_all(&from).unwrap();
        std::fs::create_dir_all(&to).unwrap();
        let big = from.join("video.mkv");
        std::fs::write(&big, vec![7u8; 16 * COPY_BUFFER]).unwrap();

        let token = CancelToken::new();
        let listener = Arc::new(CancelPartway { token: token.clone(), seen: Mutex::new(vec![]) });
        let result = place_paths_paced(
            vec![big.to_string_lossy().into_owned()],
            to.to_string_lossy().into_owned(),
            false,
            Some(listener.clone()),
            Some(token),
            Duration::ZERO,
        );

        assert!(matches!(result, Err(FileError::Cancelled)), "{result:?}");
        assert_eq!(std::fs::read_dir(&to).unwrap().count(), 0, "the part written was removed");
        assert!(big.is_file(), "the original is untouched");
        let seen = listener.seen.lock().unwrap();
        assert!(seen.iter().any(|&(done, total)| done > 0 && done < total), "it stopped partway: {seen:?}");
        let _ = std::fs::remove_dir_all(&root);
    }
}
