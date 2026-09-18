//! Splitting a tree into pieces several threads can walk at once.
//!
//! A walk of shared storage is dominated by waiting on the filesystem -
//! `getdents` to read a directory, `stat` to learn about a file - and waiting
//! is what spreads well across threads. What does not spread is several threads
//! contending over one directory, so the split is by subtree: each top-level
//! directory is walked whole, by one thread.
//!
//! Every scan here used to run on a single thread, because the only
//! parallelism was across roots and a phone has one.

use std::path::PathBuf;

/// One piece of the tree for a thread to walk.
pub struct WalkUnit {
    pub path: PathBuf,
    /// True for a root being walked for its own files only, because its
    /// subdirectories are units in their own right.
    pub shallow: bool,
}

/// Split `roots` into pieces that can be walked independently.
///
/// Each root contributes its immediate subdirectories as deep units, plus
/// itself as a shallow one for the files sitting directly in it. A root whose
/// children cannot be read - a permission wall, or it is a file, or it is not
/// there - becomes a single deep unit, which is how the whole walk behaved
/// before it was split.
pub fn units(roots: &[String], include_hidden: bool) -> Vec<WalkUnit> {
    let mut out = Vec::new();

    for root in roots {
        let path = PathBuf::from(root);
        let Ok(children) = std::fs::read_dir(&path) else {
            out.push(WalkUnit { path, shallow: false });
            continue;
        };

        let mut split = false;
        for child in children.flatten() {
            let Ok(kind) = child.file_type() else { continue };
            if !kind.is_dir() {
                continue;
            }
            if !include_hidden && child.file_name().to_string_lossy().starts_with('.') {
                continue;
            }
            out.push(WalkUnit { path: child.path(), shallow: false });
            split = true;
        }

        // Always pushed, so files directly under the root are not lost - and
        // as the only unit when the root has no subdirectories at all.
        out.push(WalkUnit { path, shallow: split });
    }

    out
}
