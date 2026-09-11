//! Duplicate detection for the storage-cleanup screen.
//!
//! Three passes, cheapest first, because hashing every file on a phone would
//! take minutes:
//!   1. group by size          -- a unique size cannot have a duplicate
//!   2. hash the first 16 KB   -- kills most same-size-different-content pairs
//!   3. hash the whole file    -- only for what survives

use std::cmp::Reverse;
use crate::cancel::{CancelToken, ProgressListener};
use crate::errors::Result;
use crate::types::FileEntry;
use rayon::prelude::*;
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;
use std::sync::Arc;
use walkdir::WalkDir;

const HEAD_BYTES: usize = 16 * 1024;

/// A set of files with identical content.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DuplicateGroup {
    /// Hex blake3 of the shared content.
    pub hash: String,
    /// Size of one copy, in bytes.
    pub size: u64,
    pub files: Vec<FileEntry>,
    /// Bytes reclaimable by keeping one copy: size * (count - 1).
    pub wasted_bytes: u64,
}

/// Find duplicate files under `root`, biggest waste first.
///
/// Files under `min_size` are ignored -- thousands of identical tiny files
/// are usually app caches and not worth showing.
#[uniffi::export]
pub fn find_duplicates(
    root: String,
    min_size: u64,
    listener: Option<Arc<dyn ProgressListener>>,
    cancel: Option<Arc<CancelToken>>,
) -> Result<Vec<DuplicateGroup>> {
    // Pass 1: bucket by size.
    let mut by_size: HashMap<u64, Vec<FileEntry>> = HashMap::new();

    for entry in WalkDir::new(&root).follow_links(false).into_iter().filter_map(|e| e.ok()) {
        if let Some(ref token) = cancel {
            token.check()?;
        }
        let Ok(meta) = entry.metadata() else { continue };
        if !meta.is_file() || meta.len() < min_size {
            continue;
        }
        by_size
            .entry(meta.len())
            .or_default()
            .push(FileEntry::from_metadata(entry.path(), &meta));
    }
    by_size.retain(|_, files| files.len() > 1);

    // Pass 2 and 3, in parallel across size buckets.
    let candidates: Vec<_> = by_size.into_iter().collect();
    let total = candidates.len() as u64;

    let groups: Vec<DuplicateGroup> = candidates
        .par_iter()
        .enumerate()
        .flat_map(|(i, (size, files))| {
            if cancel.as_ref().is_some_and(|t| t.is_cancelled()) {
                return Vec::new();
            }
            if let Some(ref l) = listener {
                l.on_progress(i as u64, total, format!("{size} bytes"));
            }
            group_by_content(*size, files)
        })
        .collect();

    let mut groups = groups;
    groups.sort_by_key(|g| Reverse(g.wasted_bytes));
    Ok(groups)
}

/// Within one size bucket, split by head hash then confirm by full hash.
fn group_by_content(size: u64, files: &[FileEntry]) -> Vec<DuplicateGroup> {
    let mut by_head: HashMap<String, Vec<FileEntry>> = HashMap::new();
    for file in files {
        if let Some(head) = hash_head(Path::new(&file.path)) {
            by_head.entry(head).or_default().push(file.clone());
        }
    }

    let mut out = Vec::new();
    for (_, same_head) in by_head {
        if same_head.len() < 2 {
            continue;
        }

        // A file smaller than the head sample was fully read already, so the
        // head hash is the full hash -- no second pass needed.
        let mut by_full: HashMap<String, Vec<FileEntry>> = HashMap::new();
        if size <= HEAD_BYTES as u64 {
            if let Some(hash) = hash_head(Path::new(&same_head[0].path)) {
                by_full.insert(hash, same_head);
            }
        } else {
            for file in same_head {
                if let Some(hash) = hash_full(Path::new(&file.path)) {
                    by_full.entry(hash).or_default().push(file);
                }
            }
        }

        for (hash, group) in by_full {
            if group.len() < 2 {
                continue;
            }
            out.push(DuplicateGroup {
                hash,
                size,
                wasted_bytes: size * (group.len() as u64 - 1),
                files: group,
            });
        }
    }
    out
}

fn hash_head(path: &Path) -> Option<String> {
    let mut file = File::open(path).ok()?;
    let mut buffer = vec![0u8; HEAD_BYTES];
    let read = file.read(&mut buffer).ok()?;
    buffer.truncate(read);
    Some(blake3::hash(&buffer).to_hex().to_string())
}

fn hash_full(path: &Path) -> Option<String> {
    let file = File::open(path).ok()?;
    let mut reader = BufReader::with_capacity(64 * 1024, file);
    let mut hasher = blake3::Hasher::new();
    let mut buffer = vec![0u8; 64 * 1024];

    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buffer[..n]),
            Err(_) => return None,
        };
    }
    Some(hasher.finalize().to_hex().to_string())
}

/// blake3 of a single file, for the file-details sheet.
#[uniffi::export]
pub fn file_hash(path: String) -> Result<String> {
    hash_full(Path::new(&path)).ok_or(crate::errors::FileError::NotFound { path })
}
