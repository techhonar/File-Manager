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
    let id = new_id();
    let dest = files_dir.join(&id);

    // A rename is instant but only works within one filesystem. Moving from
    // the SD card to internal storage crosses filesystems, so fall back to
    // copy-then-delete.
    if std::fs::rename(src, &dest).is_err() {
        if meta.is_dir() {
            crate::scanner::copy_paths(vec![path.clone()], dest.to_string_lossy().into_owned(), true, None, None)?;
        } else {
            std::fs::create_dir_all(&dest).ok();
            std::fs::copy(src, &dest).map_err(|e| FileError::from_io(e, src))?;
        }
        crate::scanner::delete_paths(vec![path.clone()], None, None)?;
    }

    let record = TrashMeta {
        id: id.clone(),
        original_path: path.clone(),
        name: src
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| id.clone()),
        deleted_at_ms: now_millis(),
        size: meta.len(),
        is_dir: meta.is_dir(),
    };
    let json = serde_json::to_string_pretty(&record)
        .map_err(|e| FileError::Io { message: e.to_string() })?;
    let meta_path = meta_dir.join(format!("{id}.json"));
    std::fs::write(&meta_path, json).map_err(|e| FileError::from_io(e, &meta_path))?;

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
        .map_err(|e| FileError::Io { message: e.to_string() })?;

    let dest = PathBuf::from(&meta.original_path);
    if dest.exists() {
        return Err(FileError::AlreadyExists { path: meta.original_path });
    }
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| FileError::from_io(e, parent))?;
    }

    let stored = files_dir.join(&id);
    if std::fs::rename(&stored, &dest).is_err() {
        crate::scanner::copy_paths(
            vec![stored.to_string_lossy().into_owned()],
            dest.parent().unwrap_or(Path::new("/")).to_string_lossy().into_owned(),
            false,
            None,
            None,
        )?;
        crate::scanner::delete_paths(vec![stored.to_string_lossy().into_owned()], None, None)?;
    }
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
        if item.days_remaining <= 0 {
            trash_delete(trash_dir.clone(), item.id)?;
            purged += 1;
        }
    }
    Ok(purged)
}

/// Empty the trash completely.
#[uniffi::export]
pub fn trash_empty(trash_dir: String) -> Result<u32> {
    let mut count = 0;
    for item in trash_list(trash_dir.clone(), u32::MAX)? {
        trash_delete(trash_dir.clone(), item.id)?;
        count += 1;
    }
    Ok(count)
}

/// Total bytes the trash is holding, for the "Trash (1.2 GB)" label.
#[uniffi::export]
pub fn trash_size(trash_dir: String) -> Result<u64> {
    let (files_dir, _) = trash_layout(&trash_dir)?;
    crate::scanner::dir_size(files_dir.to_string_lossy().into_owned(), None)
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

/// Collision-resistant id without pulling in a uuid dependency: the current
/// time in nanos, plus a hash of it, is unique enough for one device's trash.
fn new_id() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let hash = blake3::hash(&nanos.to_le_bytes());
    format!("{nanos:x}-{}", &hash.to_hex()[..8])
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
