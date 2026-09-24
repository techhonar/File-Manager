//! A recycle bin: move to trash, restore, purge.
//!
//! Layout inside the trash directory, which Kotlin supplies (it is the app's
//! own external files dir, so nothing here needs extra permissions):
//!
//!   <trash>/files/<id>          the moved file or directory
//!   <trash>/meta/<id>.json      where it came from and when
//!
//! The original path lives in the metadata rather than in the file name
//! because a path can contain any byte a file name can, including `/`.

use std::cmp::Reverse;
use crate::errors::{FileError, Result};
use crate::types::{now_millis, FileEntry};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
struct TrashMeta {
    id: String,
    original_path: String,
    name: String,
    deleted_at_ms: u64,
    size: u64,
    is_dir: bool,
}

/// A trashed item as the Trash screen shows it.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TrashItem {
    pub id: String,
    pub name: String,
    pub original_path: String,
    pub deleted_at_ms: u64,
    pub size: u64,
    pub is_dir: bool,
    /// Days left before auto-purge removes it, given the caller's retention.
    pub days_remaining: i32,
}

/// Move a path into the trash. Returns the new trash id.
#[uniffi::export]
pub fn trash_move(trash_dir: String, path: String) -> Result<String> {
    let src = Path::new(&path);
    let meta = std::fs::symlink_metadata(src).map_err(|e| FileError::from_io(e, src))?;

    let (files_dir, meta_dir) = trash_layout(&trash_dir)?;

    // An id nothing is already using. The id names the stored file, and a
    // rename onto an existing file replaces it without a word - so a repeated
    // id would silently destroy whatever was trashed under it first.
    let (id, dest) = loop {
        let id = new_id();
        let dest = files_dir.join(&id);
        if !dest.exists() && !meta_dir.join(format!("{id}.json")).exists() {
            break (id, dest);
        }
    };

    let record = TrashMeta {
        id: id.clone(),
        original_path: path.clone(),
        name: src
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| id.clone()),
        deleted_at_ms: now_millis(),
        // For a folder, what is inside it. `len()` on a directory is the size
        // of the directory entry itself, which is how a trashed album of
        // photos came to be listed as 4 KB.
        size: if meta.is_dir() {
            crate::scanner::dir_size(path.clone(), None).unwrap_or(0)
        } else {
            meta.len()
        },
        is_dir: meta.is_dir(),
    };
    let json = serde_json::to_string_pretty(&record)
        .map_err(|e| FileError::Io { detail: e.to_string() })?;
    let meta_path = meta_dir.join(format!("{id}.json"));

    // The record first, then the move. The other way round, a record that
    // failed to write - storage full is the likely reason, and full storage
    // is when people empty things into the trash - left the file stored with
    // nothing pointing at it: never listed, never restorable, never purged.
    // This way a failure leaves at worst a record for a file still where it
    // was, which is refused on restore rather than lost.
    std::fs::write(&meta_path, json).map_err(|e| FileError::from_io(e, &meta_path))?;
    if let Err(error) = move_path(src, &dest) {
        // Something at `dest` after a failure is a whole copy whose original
        // could not all be removed - a failed copy takes its part back out.
        // Some of the original may already be gone, so that copy is the only
        // place those files are, and it keeps its record to stay listed.
        if !dest.exists() {
            let _ = std::fs::remove_file(&meta_path);
        }
        return Err(error);
    }

    Ok(id)
}

/// Everything currently in the trash, most recently deleted first.
#[uniffi::export]
pub fn trash_list(trash_dir: String, retention_days: u32) -> Result<Vec<TrashItem>> {
    let (_, meta_dir) = trash_layout(&trash_dir)?;
    let mut items = Vec::new();
    let now = now_millis();

    let read = std::fs::read_dir(&meta_dir).map_err(|e| FileError::from_io(e, &meta_dir))?;
    for entry in read.flatten() {
        let Ok(text) = std::fs::read_to_string(entry.path()) else { continue };
        let Ok(meta) = serde_json::from_str::<TrashMeta>(&text) else { continue };

        let age_days = (now.saturating_sub(meta.deleted_at_ms) / 86_400_000) as i32;
        items.push(TrashItem {
            id: meta.id,
            name: meta.name,
            original_path: meta.original_path,
            deleted_at_ms: meta.deleted_at_ms,
            size: meta.size,
            is_dir: meta.is_dir,
            days_remaining: retention_days as i32 - age_days,
        });
    }

    items.sort_by_key(|i| Reverse(i.deleted_at_ms));
    Ok(items)
}

/// Put a trashed item back where it came from.
///
/// Fails rather than overwriting if something new occupies the original path.
#[uniffi::export]
pub fn trash_restore(trash_dir: String, id: String) -> Result<String> {
    let (files_dir, meta_dir) = trash_layout(&trash_dir)?;
    let meta_path = meta_dir.join(format!("{id}.json"));

    let text = std::fs::read_to_string(&meta_path)
        .map_err(|e| FileError::from_io(e, &meta_path))?;
    let meta: TrashMeta = serde_json::from_str(&text)
        .map_err(|e| FileError::Io { detail: e.to_string() })?;

    let dest = PathBuf::from(&meta.original_path);
    if dest.exists() {
        return Err(FileError::AlreadyExists { path: meta.original_path });
    }
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| FileError::from_io(e, parent))?;
    }

    let stored = files_dir.join(&id);
    move_path(&stored, &dest)?;
    std::fs::remove_file(&meta_path).ok();

    Ok(meta.original_path)
}

/// Permanently delete one trashed item.
#[uniffi::export]
pub fn trash_delete(trash_dir: String, id: String) -> Result<()> {
    let (files_dir, meta_dir) = trash_layout(&trash_dir)?;
    let stored = files_dir.join(&id);

    if stored.exists() {
        crate::scanner::delete_paths(vec![stored.to_string_lossy().into_owned()], None, None)?;
    }
    std::fs::remove_file(meta_dir.join(format!("{id}.json"))).ok();
    Ok(())
}

/// Delete everything older than `retention_days`. Returns how many went.
///
/// Call this on app start; Samsung purges at 30 days.
#[uniffi::export]
pub fn trash_purge_expired(trash_dir: String, retention_days: u32) -> Result<u32> {
    let mut purged = 0;
    for item in trash_list(trash_dir.clone(), retention_days)? {
        // One item that cannot be removed must not keep the rest past their
        // time. This runs at startup, unattended; stopping at the first
        // failure meant everything after it stayed in the trash indefinitely.
        if item.days_remaining <= 0 && trash_delete(trash_dir.clone(), item.id).is_ok() {
            purged += 1;
        }
    }
    Ok(purged)
}

/// Empty the trash completely.
#[uniffi::export]
pub fn trash_empty(trash_dir: String) -> Result<u32> {
    let mut count = 0;
    let mut first_failure = None;
    for item in trash_list(trash_dir.clone(), u32::MAX)? {
        // Everything that can go, goes. Stopping at the first failure left the
        // rest behind and reported nothing about what had been deleted.
        match trash_delete(trash_dir.clone(), item.id) {
            Ok(()) => count += 1,
            Err(error) => {
                first_failure.get_or_insert(error);
            }
        }
    }
    // Reported only after trying all of them, so the caller learns something
    // was left rather than the trash quietly not being empty.
    match first_failure {
        Some(error) if count == 0 => Err(error),
        _ => Ok(count),
    }
}

/// Total bytes the trash is holding, for the "Trash (1.2 GB)" label.
#[uniffi::export]
pub fn trash_size(trash_dir: String) -> Result<u64> {
    let (files_dir, _) = trash_layout(&trash_dir)?;
    crate::scanner::dir_size(files_dir.to_string_lossy().into_owned(), None)
}

/// Move `src` so that it ends up at exactly `dest`.
///
/// A rename is instant, but only within one filesystem. That fallback matters
/// far more than it looks: the trash used to live under `Android/data`, which
/// Android serves through a separate mount, so renaming a file out of DCIM
/// into it returned EXDEV and every delete took this path.
///
/// The previous fallback was wrong in three ways. For a file it called
/// `create_dir_all(dest)`, creating a directory where the file was supposed to
/// go, and then copied onto it - which cannot succeed. For a directory it
/// copied *into* `dest` rather than *as* `dest`, so a restore later looked in
/// the wrong place. And it delegated to `copy_paths`, which requires its
/// destination to already be a directory, so it failed before copying
/// anything.
fn move_path(src: &Path, dest: &Path) -> Result<()> {
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| FileError::from_io(e, parent))?;
    }
    if std::fs::rename(src, dest).is_ok() {
        return Ok(());
    }
    if let Err(error) = copy_exact(src, dest) {
        // Nothing half-copied is left behind. `dest` was free when this began
        // - both callers make sure of it - so all that is there is this copy.
        let _ = remove_exact(dest);
        return Err(error);
    }
    remove_exact(src)
}

/// Copy `src` to exactly `dest`, recursing for directories.
///
/// Note "to", not "into": copying `a/b` to `x/y` produces `x/y`, not `x/y/b`.
///
/// Public so the tests can exercise it directly. It is the half of the move
/// that only runs when a rename is impossible, which is exactly the path that
/// was broken and the hardest to reach through the public API.
pub fn copy_exact(src: &Path, dest: &Path) -> Result<()> {
    let meta = std::fs::symlink_metadata(src).map_err(|e| FileError::from_io(e, src))?;

    if meta.is_dir() {
        std::fs::create_dir_all(dest).map_err(|e| FileError::from_io(e, dest))?;
        let read = std::fs::read_dir(src).map_err(|e| FileError::from_io(e, src))?;
        for child in read {
            // Every entry or none. The source is deleted once this returns,
            // so an entry skipped here is an entry lost.
            let child = child.map_err(|e| FileError::from_io(e, src))?;
            copy_exact(&child.path(), &dest.join(child.file_name()))?;
        }
        crate::scanner::keep_modified_time(src, dest);
        return Ok(());
    }

    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| FileError::from_io(e, parent))?;
    }
    std::fs::copy(src, dest).map_err(|e| FileError::from_io(e, src))?;
    // A file trashed from an SD card and restored came back dated today.
    crate::scanner::keep_modified_time(src, dest);
    Ok(())
}

fn remove_exact(path: &Path) -> Result<()> {
    let meta = std::fs::symlink_metadata(path).map_err(|e| FileError::from_io(e, path))?;
    if meta.is_dir() {
        std::fs::remove_dir_all(path).map_err(|e| FileError::from_io(e, path))
    } else {
        std::fs::remove_file(path).map_err(|e| FileError::from_io(e, path))
    }
}

/// Ensure `<trash>/files` and `<trash>/meta` exist, and hand both back.
fn trash_layout(trash_dir: &str) -> Result<(PathBuf, PathBuf)> {
    let root = PathBuf::from(trash_dir);
    let files = root.join("files");
    let meta = root.join("meta");

    std::fs::create_dir_all(&files).map_err(|e| FileError::from_io(e, &files))?;
    std::fs::create_dir_all(&meta).map_err(|e| FileError::from_io(e, &meta))?;
    Ok((files, meta))
}

/// A new trash id.
///
/// The time alone was the whole of it before, with a hash of that same time
/// appended - which adds no uniqueness, since it is a function of the value it
/// is meant to disambiguate. The wall clock can be stepped backwards by a
/// network time sync, and an id repeated after that lands on a file already in
/// the trash. A counter for the life of the process, and the process id,
/// settle it; `trash_move` still checks, because correctness should not rest
/// on an argument about clocks.
fn new_id() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static COUNTER: AtomicU64 = AtomicU64::new(0);

    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let count = COUNTER.fetch_add(1, Ordering::Relaxed);
    format!("{nanos:x}-{:x}-{count:x}", std::process::id())
}

/// Convenience for the browser: trash several paths at once, collecting the
/// ids so a single "Undo" snackbar can restore the whole selection.
#[uniffi::export]
pub fn trash_move_many(trash_dir: String, paths: Vec<String>) -> Result<Vec<String>> {
    paths
        .into_iter()
        .map(|p| trash_move(trash_dir.clone(), p))
        .collect()
}

/// Entries in the trash rendered as normal `FileEntry` values, so the Trash
/// screen can reuse the same list row as the browser.
#[uniffi::export]
pub fn trash_entries(trash_dir: String, retention_days: u32) -> Result<Vec<FileEntry>> {
    Ok(trash_list(trash_dir, retention_days)?
        .into_iter()
        .map(|item| FileEntry {
            category: if item.is_dir {
                crate::types::FileCategory::Directory
            } else {
                crate::categories::categorize(&item.name)
            },
            name: item.name,
            path: item.original_path,
            size: item.size,
            is_dir: item.is_dir,
            is_hidden: false,
            modified_ms: item.deleted_at_ms,
        })
        .collect())
}
