//! Directory listing and recursive size computation.

use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::{FileError, Result};
use crate::types::{sort_entries, FileEntry, SortOptions};
use rayon::prelude::*;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
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
    if !dir.exists() {
        return Err(FileError::NotFound { path });
    }
    if !dir.is_dir() {
        return Err(FileError::NotADirectory { path });
    }

    let read = std::fs::read_dir(dir).map_err(|e| FileError::from_io(e, dir))?;
    let mut entries = Vec::new();

    for item in read.flatten() {
        let Ok(meta) = item.metadata() else { continue };
        let entry = FileEntry::from_metadata(&item.path(), &meta);
        if entry.is_hidden && !show_hidden {
            continue;
        }
        entries.push(entry);
    }

    sort_entries(&mut entries, &sort);
    Ok(entries)
}

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
