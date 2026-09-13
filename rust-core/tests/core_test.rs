//! Tests run natively on Linux -- no Android, no emulator. The filesystem
//! logic is the same code that ships in the .so, so a bug caught here is a
//! bug that never reaches the phone.

use filemanager_core::archive::{archive_create, archive_extract, archive_list};
use filemanager_core::categories::{categorize, files_in_category};
use filemanager_core::dedup::find_duplicates;
use filemanager_core::scanner::{copy_paths, delete_paths, dir_size, list_dir, tree_stats};
use filemanager_core::search::{search, SearchFilter};
use filemanager_core::storage::{analyze_storage, largest_files, storage_summary};
use filemanager_core::trash::{trash_list, trash_move, trash_restore, trash_purge_expired};
use filemanager_core::types::{FileCategory, SortKey, SortOptions};
use filemanager_core::format_size;
use std::fs;
use std::path::{Path, PathBuf};

/// A throwaway directory tree, removed when the test ends.
struct TempTree(PathBuf);

impl TempTree {
    fn new(name: &str) -> Self {
        let dir = std::env::temp_dir().join(format!("fm-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        TempTree(dir)
    }

    fn path(&self) -> &Path {
        &self.0
    }

    fn str(&self) -> String {
        self.0.to_string_lossy().into_owned()
    }

    fn file(&self, rel: &str, contents: &[u8]) -> PathBuf {
        let path = self.0.join(rel);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, contents).unwrap();
        path
    }

    fn dir(&self, rel: &str) -> PathBuf {
        let path = self.0.join(rel);
        fs::create_dir_all(&path).unwrap();
        path
    }
}

impl Drop for TempTree {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn sort_by_name() -> SortOptions {
    SortOptions { key: SortKey::Name, descending: false, dirs_first: true }
}

#[test]
fn categorizes_by_extension() {
    assert_eq!(categorize("holiday.JPG"), FileCategory::Image);
    assert_eq!(categorize("clip.mp4"), FileCategory::Video);
    assert_eq!(categorize("song.flac"), FileCategory::Audio);
    assert_eq!(categorize("report.pdf"), FileCategory::Document);
    assert_eq!(categorize("backup.zip"), FileCategory::Archive);
    assert_eq!(categorize("app.apk"), FileCategory::Apk);
    assert_eq!(categorize("no-extension"), FileCategory::Other);
    assert_eq!(categorize(".bashrc"), FileCategory::Other);
}

#[test]
fn lists_directory_with_dirs_first_and_hidden_filtered() {
    let tree = TempTree::new("list");
    tree.file("zebra.txt", b"z");
    tree.file("apple.txt", b"a");
    tree.file(".hidden", b"h");
    tree.dir("subdir");

    let visible = list_dir(tree.str(), false, sort_by_name()).unwrap();
    let names: Vec<_> = visible.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["subdir", "apple.txt", "zebra.txt"]);

    let all = list_dir(tree.str(), true, sort_by_name()).unwrap();
    assert_eq!(all.len(), 4, "hidden file should appear when asked for");
    assert!(all.iter().any(|e| e.name == ".hidden" && e.is_hidden));
}

#[test]
fn sorts_by_size_descending() {
    let tree = TempTree::new("sort");
    tree.file("small.bin", &[0u8; 10]);
    tree.file("big.bin", &[0u8; 5000]);
    tree.file("medium.bin", &[0u8; 500]);

    let sort = SortOptions { key: SortKey::Size, descending: true, dirs_first: false };
    let entries = list_dir(tree.str(), false, sort).unwrap();
    let names: Vec<_> = entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["big.bin", "medium.bin", "small.bin"]);
}

#[test]
fn computes_recursive_size_and_counts() {
    let tree = TempTree::new("size");
    tree.file("a.bin", &[0u8; 100]);
    tree.file("nested/b.bin", &[0u8; 250]);
    tree.file("nested/deep/c.bin", &[0u8; 150]);

    assert_eq!(dir_size(tree.str(), None).unwrap(), 500);

    let stats = tree_stats(vec![tree.str()], None).unwrap();
    assert_eq!(stats.file_count, 3);
    assert_eq!(stats.total_bytes, 500);
    // root + nested + deep
    assert_eq!(stats.dir_count, 3);
}

#[test]
fn searches_by_name_and_category() {
    let tree = TempTree::new("search");
    tree.file("vacation-photo.jpg", &[0u8; 4000]);
    tree.file("vacation-notes.txt", b"notes");
    tree.file("nested/vacation-clip.mp4", &[0u8; 9000]);
    tree.file("unrelated.pdf", b"pdf");

    let by_name = SearchFilter { query: "vacation".into(), ..Default::default() };
    let hits = search(vec![tree.str()], by_name, sort_by_name(), None, None).unwrap();
    assert_eq!(hits.len(), 3, "should find all three vacation files, recursively");

    let images_only = SearchFilter {
        query: String::new(),
        categories: vec![FileCategory::Image],
        ..Default::default()
    };
    let images = search(vec![tree.str()], images_only, sort_by_name(), None, None).unwrap();
    assert_eq!(images.len(), 1);
    assert_eq!(images[0].name, "vacation-photo.jpg");

    let big = SearchFilter { min_size: Some(5000), ..Default::default() };
    let big_hits = search(vec![tree.str()], big, sort_by_name(), None, None).unwrap();
    assert_eq!(big_hits.len(), 1);
    assert_eq!(big_hits[0].name, "vacation-clip.mp4");
}

#[test]
fn groups_files_by_category_for_home_screen() {
    let tree = TempTree::new("cats");
    tree.file("a.png", b"1");
    tree.file("b.jpeg", b"2");
    tree.file("docs/c.pdf", b"3");

    let images = files_in_category(tree.str(), FileCategory::Image, 0, None).unwrap();
    assert_eq!(images.len(), 2);

    let docs = files_in_category(tree.str(), FileCategory::Document, 0, None).unwrap();
    assert_eq!(docs.len(), 1);
    assert_eq!(docs[0].name, "c.pdf");
}

#[test]
fn summarizes_storage_by_category() {
    let tree = TempTree::new("storage");
    tree.file("pic.jpg", &[0u8; 3000]);
    tree.file("movie.mp4", &[0u8; 9000]);
    tree.file("doc.pdf", &[0u8; 1000]);

    let summary = storage_summary(tree.str(), None).unwrap();
    assert_eq!(summary.scanned_bytes, 13_000);
    assert!(summary.total_bytes > 0, "statvfs should report a real capacity");

    // Sorted biggest first, so video leads.
    assert_eq!(summary.by_category[0].category, FileCategory::Video);
    assert_eq!(summary.by_category[0].bytes, 9000);

    let biggest = largest_files(tree.str(), 2, None).unwrap();
    assert_eq!(biggest.len(), 2);
    assert_eq!(biggest[0].name, "movie.mp4");
    assert_eq!(biggest[1].name, "pic.jpg");
}

#[test]
fn copies_and_deletes_trees() {
    let tree = TempTree::new("copy");
    tree.file("src/a.txt", b"hello");
    tree.file("src/nested/b.txt", b"world");
    let dest = tree.dir("dest");

    let copied = copy_paths(
        vec![tree.path().join("src").to_string_lossy().into_owned()],
        dest.to_string_lossy().into_owned(),
        false,
        None,
        None,
    )
    .unwrap();
    assert_eq!(copied, 2);
    assert!(dest.join("src/nested/b.txt").exists());
    assert_eq!(fs::read_to_string(dest.join("src/a.txt")).unwrap(), "hello");

    let removed = delete_paths(
        vec![dest.join("src").to_string_lossy().into_owned()],
        None,
        None,
    )
    .unwrap();
    assert_eq!(removed, 2);
    assert!(!dest.join("src").exists());
}

#[test]
fn round_trips_a_zip_archive() {
    let tree = TempTree::new("zip");
    tree.file("data/one.txt", b"first");
    tree.file("data/sub/two.txt", b"second");
    let zip_path = tree.path().join("out.zip");

    let count = archive_create(
        vec![tree.path().join("data").to_string_lossy().into_owned()],
        zip_path.to_string_lossy().into_owned(),
        None,
        None,
    )
    .unwrap();
    assert_eq!(count, 2);
    assert!(zip_path.exists());

    let listed = archive_list(zip_path.to_string_lossy().into_owned()).unwrap();
    assert!(listed.iter().any(|e| e.name == "data/one.txt"));

    let out = tree.dir("extracted");
    archive_extract(
        zip_path.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        None,
        None,
    )
    .unwrap();
    assert_eq!(fs::read_to_string(out.join("data/sub/two.txt")).unwrap(), "second");
}

#[test]
fn trash_moves_restores_and_purges() {
    let tree = TempTree::new("trash");
    let target = tree.file("important.txt", b"keep me");
    let trash_dir = tree.dir("trash").to_string_lossy().into_owned();

    let id = trash_move(trash_dir.clone(), target.to_string_lossy().into_owned()).unwrap();
    assert!(!target.exists(), "file should be gone from its original location");

    let listed = trash_list(trash_dir.clone(), 30).unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].name, "important.txt");
    assert_eq!(listed[0].days_remaining, 30, "nothing expires on day zero");

    // Nothing is old enough to purge yet.
    assert_eq!(trash_purge_expired(trash_dir.clone(), 30).unwrap(), 0);

    let restored = trash_restore(trash_dir.clone(), id).unwrap();
    assert_eq!(restored, target.to_string_lossy());
    assert_eq!(fs::read_to_string(&target).unwrap(), "keep me");
    assert!(trash_list(trash_dir, 30).unwrap().is_empty());
}

#[test]
fn finds_duplicate_files() {
    let tree = TempTree::new("dedup");
    let payload = vec![7u8; 40_000];
    tree.file("photos/one.jpg", &payload);
    tree.file("backup/one-copy.jpg", &payload);
    tree.file("photos/different.jpg", &vec![9u8; 40_000]);

    let groups = find_duplicates(tree.str(), 1024, None, None).unwrap();
    assert_eq!(groups.len(), 1, "only the identical pair should group");
    assert_eq!(groups[0].files.len(), 2);
    assert_eq!(groups[0].wasted_bytes, 40_000);
}

#[test]
fn rejects_zip_slip_paths() {
    // An archive entry named ../escaped.txt must not write outside the
    // destination directory.
    let tree = TempTree::new("zipslip");
    let zip_path = tree.path().join("evil.zip");
    {
        use std::io::Write;
        let file = fs::File::create(&zip_path).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        writer
            .start_file("../escaped.txt", zip::write::SimpleFileOptions::default())
            .unwrap();
        writer.write_all(b"pwned").unwrap();
        writer.finish().unwrap();
    }

    let out = tree.dir("safe-out");
    archive_extract(
        zip_path.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        None,
        None,
    )
    .unwrap();

    assert!(!tree.path().join("escaped.txt").exists(), "must not escape the destination");
    assert!(!out.join("escaped.txt").exists(), "malicious entry should be skipped");
}

#[test]
fn formats_sizes_for_display() {
    assert_eq!(format_size(0), "0 B");
    assert_eq!(format_size(512), "512 B");
    assert_eq!(format_size(1024), "1.0 KB");
    assert_eq!(format_size(1536), "1.5 KB");
    assert_eq!(format_size(15 * 1024 * 1024), "15 MB");
    assert_eq!(format_size(3 * 1024 * 1024 * 1024), "3.0 GB");
}

#[test]
fn missing_directory_is_a_typed_error() {
    let err = list_dir("/definitely/not/here".into(), false, sort_by_name()).unwrap_err();
    assert!(matches!(err, filemanager_core::errors::FileError::NotFound { .. }));
}

#[test]
fn single_pass_analysis_agrees_with_the_two_separate_walks() {
    // analyze_storage exists to replace storage_summary + largest_files with
    // one parallel walk. It is only worth having if it returns the same
    // answers, so check it against both.
    let tree = TempTree::new("analyze");
    tree.file("pic.jpg", &[0u8; 3000]);
    tree.file("nested/movie.mp4", &[0u8; 9000]);
    tree.file("nested/deep/doc.pdf", &[0u8; 1000]);
    tree.file("song.mp3", &[0u8; 5000]);

    let summary = storage_summary(tree.str(), None).unwrap();
    let biggest = largest_files(tree.str(), 3, None).unwrap();
    let analysis = analyze_storage(tree.str(), 3, None, None).unwrap();

    assert_eq!(analysis.scanned_bytes, summary.scanned_bytes);
    assert_eq!(analysis.total_bytes, summary.total_bytes);
    assert_eq!(analysis.file_count, 4);
    // Not compared against the summary's figure: free space is live, and the
    // two calls run statvfs at different moments, so anything else writing to
    // the disk makes them disagree. Capacity is stable, so that one is checked
    // exactly above.
    assert!(analysis.free_bytes > 0);
    assert!(analysis.free_bytes <= analysis.total_bytes);

    let one: Vec<_> = analysis.by_category.iter()
        .map(|c| (c.category, c.bytes, c.file_count)).collect();
    let two: Vec<_> = summary.by_category.iter()
        .map(|c| (c.category, c.bytes, c.file_count)).collect();
    assert_eq!(one, two, "category breakdown must match the sequential walk");

    let names: Vec<_> = analysis.largest.iter().map(|e| e.name.as_str()).collect();
    let expected: Vec<_> = biggest.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, expected, "largest files must match, and stay biggest first");
    assert_eq!(names, vec!["movie.mp4", "song.mp3", "pic.jpg"]);
}

#[test]
fn analysis_walks_every_top_level_directory() {
    // The walk is parallel across the root's children, so a file under one
    // child must not be missed because another child finished first.
    let tree = TempTree::new("analyze-parallel");
    for i in 1..=12u64 {
        tree.file(&format!("dir{i}/file.bin"), &vec![1u8; (100 * i) as usize]);
    }

    let analysis = analyze_storage(tree.str(), 50, None, None).unwrap();
    assert_eq!(analysis.file_count, 12, "every child directory must be visited");
    let expected: u64 = (1..=12u64).map(|i| 100 * i).sum();
    assert_eq!(analysis.scanned_bytes, expected);
    assert_eq!(analysis.largest.len(), 12);
    // Biggest first across all threads, not merely within one.
    assert_eq!(analysis.largest[0].size, 1200);
}

#[test]
fn analysis_does_not_descend_into_symlinked_top_level_directories() {
    // WalkDir always follows its root entry, even with follow_links(false),
    // so walking each child of the root separately would descend into a
    // symlinked directory and count its target twice. Against /usr, where
    // lib64 -> lib, that inflated the total by 136,270 entries.
    let tree = TempTree::new("analyze-symlink");
    tree.file("real/a.bin", &[0u8; 4000]);
    tree.file("real/b.bin", &[0u8; 6000]);
    std::os::unix::fs::symlink(tree.path().join("real"), tree.path().join("alias")).unwrap();

    let analysis = analyze_storage(tree.str(), 10, None, None).unwrap();
    let summary = storage_summary(tree.str(), None).unwrap();

    // Two real files plus the symlink itself, counted as one entry.
    assert_eq!(analysis.file_count, 3, "the link target must not be walked again");
    assert_eq!(
        analysis.scanned_bytes, summary.scanned_bytes,
        "must agree with the sequential walk, which never follows the link",
    );
}
