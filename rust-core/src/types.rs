//! Types that cross the Rust <-> Kotlin boundary.
//!
//! Every `uniffi::Record` here becomes a Kotlin `data class`, and every
//! `uniffi::Enum` becomes a Kotlin `enum class`. You never write that Kotlin
//! by hand -- it is generated from these definitions at build time.

use std::cmp::Reverse;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

/// How a file is bucketed on the category home screen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum FileCategory {
    Directory,
    Image,
    Video,
    Audio,
    Document,
    Archive,
    /// .apk / .apex -- Samsung shows these as "Installation files".
    Apk,
    Other,
}

/// A single entry in a directory listing or search result.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    /// Bytes. For directories this is 0 -- computing it is a recursive walk,
    /// so ask for it explicitly with `dir_size` only where the UI shows it.
    pub size: u64,
    pub is_dir: bool,
    pub is_hidden: bool,
    /// Last-modified time, milliseconds since the Unix epoch.
    pub modified_ms: u64,
    pub category: FileCategory,
}

impl FileEntry {
    /// Build an entry from a path plus already-fetched metadata.
    ///
    /// Takes the metadata as an argument because callers have usually just
    /// read it from a `DirEntry` -- re-`stat`ing here would double the
    /// syscalls on a directory with thousands of files.
    pub fn from_metadata(path: &Path, meta: &std::fs::Metadata) -> Self {
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        let is_dir = meta.is_dir();

        FileEntry {
            is_hidden: name.starts_with('.'),
            category: if is_dir {
                FileCategory::Directory
            } else {
                crate::categories::categorize(&name)
            },
            size: if is_dir { 0 } else { meta.len() },
            modified_ms: modified_millis(meta),
            path: path.to_string_lossy().into_owned(),
            name,
            is_dir,
        }
    }
}

/// Sort orders offered in the browser's sort menu.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SortKey {
    Name,
    Size,
    Modified,
    Type,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SortOptions {
    pub key: SortKey,
    pub descending: bool,
    /// Keep directories above files regardless of the sort key, the way
    /// every file manager does.
    pub dirs_first: bool,
}

impl Default for SortOptions {
    fn default() -> Self {
        SortOptions { key: SortKey::Name, descending: false, dirs_first: true }
    }
}

pub(crate) fn modified_millis(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Current wall-clock time in epoch millis, used for trash timestamps.
pub(crate) fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Apply a `SortOptions` to a listing in place.
pub(crate) fn sort_entries(entries: &mut [FileEntry], opts: &SortOptions) {
    // Each entry's key is worked out once, up front. Name and type order on
    // lowercased names, and lowercasing allocates: done inside the
    // comparison, as it was, a sort lowercased every name about 2 * log2(n)
    // times - most of the time a large folder took to list.
    match opts.key {
        SortKey::Name => sort_keyed(entries, opts, |e| e.name.to_lowercase()),
        SortKey::Size => sort_keyed(entries, opts, |e| e.size),
        SortKey::Modified => sort_keyed(entries, opts, |e| e.modified_ms),
        SortKey::Type => sort_keyed(entries, opts, |e| (extension_of(&e.name), e.name.to_lowercase())),
    }
}

/// Stable, like the comparison sort it replaced: equal keys keep their order.
fn sort_keyed<K: Ord>(entries: &mut [FileEntry], opts: &SortOptions, key: impl Fn(&FileEntry) -> K) {
    // Directories first, and this beats the descending flag.
    let files_after = |e: &FileEntry| opts.dirs_first && !e.is_dir;
    if opts.descending {
        entries.sort_by_cached_key(|e| (files_after(e), Reverse(key(e))));
    } else {
        entries.sort_by_cached_key(|e| (files_after(e), key(e)));
    }
}

fn extension_of(name: &str) -> String {
    name.rsplit_once('.')
        .map(|(_, ext)| ext.to_lowercase())
        .unwrap_or_default()
}

/// True for a hidden directory below the walk's root.
///
/// Used to prune whole subtrees from searches. Without it, anything inside a
/// dot-directory still shows up, because only the file's own name is checked
/// for the leading dot - so trashed files, thumbnail caches and .git objects
/// all appear as ordinary results. The root itself is never pruned, or
/// searching a hidden folder deliberately would return nothing.
pub(crate) fn is_hidden_dir(entry: &walkdir::DirEntry) -> bool {
    entry.depth() > 0
        && entry.file_type().is_dir()
        && entry.file_name().to_string_lossy().starts_with('.')
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The order, put as plainly as it can be: one comparison per pair, keys
    /// worked out as it goes. sort_entries must agree with it exactly.
    fn plainly_sorted(entries: &[FileEntry], opts: &SortOptions) -> Vec<FileEntry> {
        let mut out = entries.to_vec();
        out.sort_by(|a, b| {
            if opts.dirs_first && a.is_dir != b.is_dir {
                return b.is_dir.cmp(&a.is_dir);
            }
            let ord = match opts.key {
                SortKey::Name => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
                SortKey::Size => a.size.cmp(&b.size),
                SortKey::Modified => a.modified_ms.cmp(&b.modified_ms),
                SortKey::Type => extension_of(&a.name)
                    .cmp(&extension_of(&b.name))
                    .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase())),
            };
            if opts.descending { ord.reverse() } else { ord }
        });
        out
    }

    /// Few distinct names, sizes and dates, so that most keys tie: a sort
    /// that drops stability, or orders one tie differently, shows up.
    fn entries() -> Vec<FileEntry> {
        let names = ["photo.JPG", "Photo.jpg", "notes", "Notes", "ärger.pdf", "Zebra.pdf", "a.b.zip", ".hidden", "Ä.txt"];
        let mut seed = 0x9e37_79b9_u32;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 17;
            seed ^= seed << 5;
            seed
        };
        (0..400)
            .map(|i| {
                let name = names[next() as usize % names.len()];
                FileEntry {
                    name: name.to_string(),
                    // Distinct, so that the order of ties can be told apart.
                    path: format!("/x/{i}/{name}"),
                    size: (next() % 4) as u64 * 100,
                    is_dir: next() % 3 == 0,
                    is_hidden: name.starts_with('.'),
                    modified_ms: (next() % 5) as u64 * 1000,
                    category: FileCategory::Other,
                }
            })
            .collect()
    }

    #[test]
    fn sort_agrees_with_the_plain_comparison_for_every_option() {
        let entries = entries();
        for key in [SortKey::Name, SortKey::Size, SortKey::Modified, SortKey::Type] {
            for descending in [false, true] {
                for dirs_first in [false, true] {
                    let opts = SortOptions { key, descending, dirs_first };
                    let mut sorted = entries.clone();
                    sort_entries(&mut sorted, &opts);
                    let paths = |list: &[FileEntry]| list.iter().map(|e| e.path.clone()).collect::<Vec<_>>();
                    assert_eq!(paths(&sorted), paths(&plainly_sorted(&entries, &opts)), "{opts:?}");
                }
            }
        }
    }
}
