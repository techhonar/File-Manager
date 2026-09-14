//! Tests run natively on Linux -- no Android, no emulator. The filesystem
//! logic is the same code that ships in the .so, so a bug caught here is a
//! bug that never reaches the phone.

use filemanager_core::archive::{archive_create, archive_extract, archive_is_encrypted, archive_list};
use filemanager_core::categories::{categorize, files_in_category, recent_files};
use filemanager_core::dedup::find_duplicates;
use filemanager_core::scanner::{copy_paths, delete_paths, dir_size, list_dir, tree_stats};
use filemanager_core::search::{search, search_streaming, SearchFilter, SearchSink};
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

/// Records everything a streaming search hands back, so a test can assert on
/// both the results and how they arrived.
#[derive(Default)]
struct RecordingSink {
    batches: std::sync::Mutex<Vec<Vec<filemanager_core::types::FileEntry>>>,
    scanned_calls: std::sync::atomic::AtomicU64,
    finished: std::sync::Mutex<Option<(u64, bool)>>,
}

impl SearchSink for RecordingSink {
    fn on_batch(&self, entries: Vec<filemanager_core::types::FileEntry>) {
        self.batches.lock().unwrap().push(entries);
    }
    fn on_scanned(&self, _count: u64) {
        self.scanned_calls.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    fn on_finished(&self, matched: u64, cancelled: bool) {
        *self.finished.lock().unwrap() = Some((matched, cancelled));
    }
}

impl RecordingSink {
    fn names(&self) -> Vec<String> {
        let mut v: Vec<String> = self.batches.lock().unwrap().iter()
            .flatten().map(|e| e.name.clone()).collect();
        v.sort();
        v
    }
}

#[test]
fn streaming_search_finds_exactly_what_the_batch_search_finds() {
    let tree = TempTree::new("stream");
    tree.file("holiday.jpg", &[0u8; 100]);
    tree.file("nested/holiday-2.png", &[0u8; 100]);
    tree.file("nested/deep/holiday notes.txt", b"x");
    tree.file("unrelated.pdf", b"y");

    let filter = SearchFilter { query: "holiday".into(), ..Default::default() };
    let batch = search(vec![tree.str()], filter.clone(), sort_by_name(), None, None).unwrap();

    let sink = std::sync::Arc::new(RecordingSink::default());
    search_streaming(vec![tree.str()], filter, sink.clone(), None).unwrap();

    let mut expected: Vec<String> = batch.iter().map(|e| e.name.clone()).collect();
    expected.sort();
    assert_eq!(sink.names(), expected, "streaming must find the same files");

    let (matched, cancelled) = sink.finished.lock().unwrap().expect("on_finished must be called");
    assert_eq!(matched, 3);
    assert!(!cancelled);
}

#[test]
fn streaming_search_delivers_in_several_batches_rather_than_one() {
    // The whole point is that results appear during the walk. With more
    // matches than fit in one batch, the sink must be called more than once -
    // otherwise this is just the batch search with extra steps.
    let tree = TempTree::new("stream-batches");
    for i in 0..200 {
        tree.file(&format!("dir{}/match{i}.log", i % 8), b"x");
    }

    let filter = SearchFilter { query: "match".into(), limit: 0, ..Default::default() };
    let sink = std::sync::Arc::new(RecordingSink::default());
    search_streaming(vec![tree.str()], filter, sink.clone(), None).unwrap();

    let batch_count = sink.batches.lock().unwrap().len();
    assert!(batch_count > 1, "expected several batches, got {batch_count}");
    assert_eq!(sink.names().len(), 200);
    assert!(
        sink.scanned_calls.load(std::sync::atomic::Ordering::Relaxed) > 0,
        "progress must be reported while scanning",
    );
}

#[test]
fn streaming_search_stops_at_the_limit() {
    let tree = TempTree::new("stream-limit");
    for i in 0..500 {
        tree.file(&format!("f{i}.log"), b"x");
    }

    let filter = SearchFilter { query: "f".into(), limit: 64, ..Default::default() };
    let sink = std::sync::Arc::new(RecordingSink::default());
    search_streaming(vec![tree.str()], filter, sink.clone(), None).unwrap();

    let found = sink.names().len();
    assert!(found >= 64, "must deliver at least the limit, got {found}");
    assert!(found < 500, "must stop early rather than walking everything, got {found}");
}

#[test]
fn streaming_search_reports_cancellation() {
    let tree = TempTree::new("stream-cancel");
    for i in 0..100 {
        tree.file(&format!("f{i}.log"), b"x");
    }

    let token = filemanager_core::cancel::CancelToken::new();
    token.cancel();

    let filter = SearchFilter { query: "f".into(), limit: 0, ..Default::default() };
    let sink = std::sync::Arc::new(RecordingSink::default());
    search_streaming(vec![tree.str()], filter, sink.clone(), Some(token)).unwrap();

    let (_, cancelled) = sink.finished.lock().unwrap().expect("on_finished must still be called");
    assert!(cancelled, "a cancelled walk must say so, not look like a normal end");
}

#[test]
fn rare_matches_arrive_during_the_walk_not_at_the_end() {
    // The reported bug: searching for a name only a few files carry showed
    // nothing until the whole device had been scanned. Matches never filled a
    // 64-item batch, so they sat in the buffer until the walk finished.
    //
    // Build a tree where the only matches are found early, followed by a lot
    // of non-matching files, and assert the matches were handed over before
    // the walk ended.
    let tree = TempTree::new("stream-rare");
    tree.file("aaa-madison.txt", b"x");
    tree.file("aab-madison.txt", b"x");
    for i in 0..20_000 {
        tree.file(&format!("zzz/filler{i}.bin"), b"x");
    }

    struct TimingSink {
        first_batch_at: std::sync::Mutex<Option<std::time::Instant>>,
        finished_at: std::sync::Mutex<Option<std::time::Instant>>,
        names: std::sync::Mutex<Vec<String>>,
    }
    impl SearchSink for TimingSink {
        fn on_batch(&self, entries: Vec<filemanager_core::types::FileEntry>) {
            let mut first = self.first_batch_at.lock().unwrap();
            if first.is_none() {
                *first = Some(std::time::Instant::now());
            }
            self.names.lock().unwrap().extend(entries.into_iter().map(|e| e.name));
        }
        fn on_scanned(&self, _c: u64) {}
        fn on_finished(&self, _m: u64, _c: bool) {
            *self.finished_at.lock().unwrap() = Some(std::time::Instant::now());
        }
    }

    let sink = std::sync::Arc::new(TimingSink {
        first_batch_at: std::sync::Mutex::new(None),
        finished_at: std::sync::Mutex::new(None),
        names: std::sync::Mutex::new(Vec::new()),
    });
    let filter = SearchFilter { query: "madison".into(), limit: 0, ..Default::default() };
    search_streaming(vec![tree.str()], filter, sink.clone(), None).unwrap();

    let mut names = sink.names.lock().unwrap().clone();
    names.sort();
    assert_eq!(names, vec!["aaa-madison.txt", "aab-madison.txt"]);

    let first = sink.first_batch_at.lock().unwrap().expect("a batch must be delivered");
    let done = sink.finished_at.lock().unwrap().expect("on_finished must be called");
    assert!(
        first < done,
        "matches must be handed over while the walk is still running, not with on_finished",
    );
}

#[test]
fn copy_exact_places_a_file_at_the_destination_not_inside_it() {
    // The delete bug: the fallback called create_dir_all on the destination,
    // making a directory where the file belonged, then copied onto it. Any
    // delete that could not be done with a rename failed outright.
    let tree = TempTree::new("copy-exact-file");
    let src = tree.file("DCIM/photo.jpg", &[7u8; 500]);
    let dest = tree.path().join("trash/files/abc123");

    filemanager_core::trash::copy_exact(&src, &dest).unwrap();

    assert!(dest.is_file(), "destination must be the file itself, not a directory");
    assert_eq!(std::fs::read(&dest).unwrap(), vec![7u8; 500]);
}

#[test]
fn copy_exact_copies_a_directory_as_the_destination_not_into_it() {
    // The other half: a directory was copied *into* the destination, so a
    // later restore looked for it one level too deep and found nothing.
    let tree = TempTree::new("copy-exact-dir");
    tree.file("album/one.jpg", b"a");
    tree.file("album/nested/two.jpg", b"b");
    let src = tree.path().join("album");
    let dest = tree.path().join("trash/files/xyz789");

    filemanager_core::trash::copy_exact(&src, &dest).unwrap();

    assert!(dest.join("one.jpg").is_file(), "contents belong directly under dest");
    assert!(dest.join("nested/two.jpg").is_file());
    assert!(!dest.join("album").exists(), "must not nest the source name inside dest");
}

#[test]
fn trashing_and_restoring_a_file_from_a_media_folder_round_trips() {
    // Mirrors the report: a file in DCIM could not be deleted.
    let tree = TempTree::new("trash-dcim");
    let photo = tree.file("DCIM/Camera/IMG_0042.jpg", &[3u8; 2048]);
    let trash_dir = tree.dir("trash").to_string_lossy().into_owned();

    let id = trash_move(trash_dir.clone(), photo.to_string_lossy().into_owned()).unwrap();
    assert!(!photo.exists(), "the original must be gone");

    let listed = trash_list(trash_dir.clone(), 30).unwrap();
    assert_eq!(listed.len(), 1);
    assert_eq!(listed[0].name, "IMG_0042.jpg");

    let restored = trash_restore(trash_dir, id).unwrap();
    assert_eq!(restored, photo.to_string_lossy());
    assert!(photo.is_file(), "restore must put the file back, as a file");
    assert_eq!(std::fs::read(&photo).unwrap(), vec![3u8; 2048]);
}

#[test]
fn trashing_and_restoring_a_directory_round_trips() {
    let tree = TempTree::new("trash-dir");
    tree.file("album/a.jpg", b"one");
    tree.file("album/sub/b.jpg", b"two");
    let album = tree.path().join("album");
    let trash_dir = tree.dir("trash").to_string_lossy().into_owned();

    let id = trash_move(trash_dir.clone(), album.to_string_lossy().into_owned()).unwrap();
    assert!(!album.exists());

    trash_restore(trash_dir, id).unwrap();
    assert_eq!(std::fs::read_to_string(album.join("a.jpg")).unwrap(), "one");
    assert_eq!(std::fs::read_to_string(album.join("sub/b.jpg")).unwrap(), "two");
}

#[test]
fn search_does_not_surface_files_inside_hidden_directories() {
    // The trash lives in a dot-directory at the root of storage, and trashed
    // files are stored under opaque ids - so nothing about the file itself is
    // hidden. Checking only the file's own name for a leading dot would let
    // every deleted file reappear in search results.
    let tree = TempTree::new("hidden-dirs");
    tree.file("DCIM/madison.jpg", b"real");
    tree.file(".FileManagerTrash/files/18f2a-abc123", b"deleted madison");
    tree.file(".thumbnails/madison-thumb.jpg", b"cache");

    let filter = SearchFilter { query: "madison".into(), ..Default::default() };
    let hits = search(vec![tree.str()], filter.clone(), sort_by_name(), None, None).unwrap();
    let names: Vec<_> = hits.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["madison.jpg"], "only the real file may be returned");

    // Streaming must agree with the batch search here too.
    let sink = std::sync::Arc::new(RecordingSink::default());
    search_streaming(vec![tree.str()], filter, sink.clone(), None).unwrap();
    assert_eq!(sink.names(), vec!["madison.jpg"]);
}

#[test]
fn search_can_still_look_inside_hidden_directories_when_asked() {
    let tree = TempTree::new("hidden-dirs-opt-in");
    tree.file(".config/madison.conf", b"x");

    let filter = SearchFilter {
        query: "madison".into(),
        include_hidden: true,
        ..Default::default()
    };
    let hits = search(vec![tree.str()], filter, sort_by_name(), None, None).unwrap();
    assert_eq!(hits.len(), 1, "include_hidden must still reach into dot-directories");
}

#[test]
fn recent_files_excludes_the_trash() {
    let tree = TempTree::new("recent-trash");
    tree.file("DCIM/new.jpg", b"a");
    tree.file(".FileManagerTrash/files/deadbeef", b"b");

    let recent = recent_files(tree.str(), 7, 0, None).unwrap();
    let names: Vec<_> = recent.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["new.jpg"], "deleted files are not recent files");
}

#[test]
fn an_empty_query_lists_every_file_under_a_folder() {
    // Sharing a folder expands it by searching with an empty query, so that
    // has to mean "everything here", recursively, and must not quietly stop
    // at a default limit.
    let tree = TempTree::new("expand-folder");
    tree.file("album/a.jpg", b"1");
    tree.file("album/nested/b.jpg", b"2");
    tree.file("album/nested/deeper/c.png", b"3");
    tree.file("album/.thumbnails/cache.jpg", b"hidden");

    let filter = SearchFilter {
        query: String::new(),
        categories: Vec::new(),
        min_size: None,
        max_size: None,
        modified_after: None,
        include_hidden: false,
        limit: 0,
    };
    let found = search(
        vec![tree.path().join("album").to_string_lossy().into_owned()],
        filter,
        sort_by_name(),
        None,
        None,
    )
    .unwrap();

    let mut names: Vec<_> = found.iter().map(|e| e.name.as_str()).collect();
    names.sort();
    assert_eq!(
        names,
        vec!["a.jpg", "b.jpg", "c.png"],
        "every depth is included, and the thumbnail cache is not",
    );
}

/// Build a zip whose single entry is AES-encrypted with `password`.
fn encrypted_zip(path: &std::path::Path, password: &str, contents: &[u8]) {
    use std::io::Write;
    let file = fs::File::create(path).unwrap();
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .with_aes_encryption(zip::AesMode::Aes256, password);
    writer.start_file("secret.txt", options).unwrap();
    writer.write_all(contents).unwrap();
    writer.finish().unwrap();
}

#[test]
fn an_encrypted_archive_is_reported_as_such() {
    let tree = TempTree::new("zip-encrypted-detect");
    let locked = tree.path().join("locked.zip");
    encrypted_zip(&locked, "hunter2", b"classified");

    assert!(archive_is_encrypted(locked.to_string_lossy().into_owned()).unwrap());

    // And a plain one is not, so the app does not prompt for nothing.
    tree.file("plain/a.txt", b"open");
    let plain = tree.path().join("plain.zip");
    archive_create(
        vec![tree.path().join("plain").to_string_lossy().into_owned()],
        plain.to_string_lossy().into_owned(),
        None,
        None,
        None,
    )
    .unwrap();
    assert!(!archive_is_encrypted(plain.to_string_lossy().into_owned()).unwrap());
}

#[test]
fn extracting_an_encrypted_archive_needs_the_right_password() {
    let tree = TempTree::new("zip-encrypted-extract");
    let locked = tree.path().join("locked.zip");
    encrypted_zip(&locked, "hunter2", b"classified");
    let out = tree.dir("out");

    // No password at all must say so, not fail as a corrupt archive.
    let err = archive_extract(
        locked.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        None,
        None,
        None,
    )
    .unwrap_err();
    assert!(
        matches!(err, filemanager_core::errors::FileError::PasswordRequired),
        "expected PasswordRequired, got {err:?}",
    );

    // A wrong one must be distinguishable, so the user can be asked again.
    let err = archive_extract(
        locked.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        Some("wrong".into()),
        None,
        None,
    )
    .unwrap_err();
    assert!(
        matches!(err, filemanager_core::errors::FileError::WrongPassword),
        "expected WrongPassword, got {err:?}",
    );

    // And the right one works.
    archive_extract(
        locked.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        Some("hunter2".into()),
        None,
        None,
    )
    .unwrap();
    assert_eq!(fs::read_to_string(out.join("secret.txt")).unwrap(), "classified");
}

#[test]
fn entries_for_skips_paths_that_have_gone() {
    // Favourites and pinned items are remembered as paths, and the file behind
    // one can be deleted by anything at any time. A stale entry should quietly
    // disappear from the list rather than break it.
    let tree = TempTree::new("entries-for");
    let kept = tree.file("keep.txt", b"here");
    let removed = tree.path().join("gone.txt");

    let entries = filemanager_core::scanner::entries_for(vec![
        kept.to_string_lossy().into_owned(),
        removed.to_string_lossy().into_owned(),
    ]);

    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].name, "keep.txt");
}

#[test]
fn creates_an_archive_the_password_is_needed_to_read() {
    let tree = TempTree::new("zip-create-encrypted");
    tree.file("private/notes.txt", b"for my eyes only");
    let locked = tree.path().join("locked.zip");

    let count = archive_create(
        vec![tree.path().join("private").to_string_lossy().into_owned()],
        locked.to_string_lossy().into_owned(),
        Some("hunter2".to_string()),
        None,
        None,
    )
    .unwrap();
    assert_eq!(count, 1);

    // The app checks this before extracting to decide whether to prompt, so an
    // archive we wrote has to answer the same way as one written elsewhere.
    assert!(archive_is_encrypted(locked.to_string_lossy().into_owned()).unwrap());

    // Without the password there is nothing to read.
    let err = archive_extract(
        locked.to_string_lossy().into_owned(),
        tree.dir("no-password").to_string_lossy().into_owned(),
        None,
        None,
        None,
    )
    .unwrap_err();
    assert!(
        matches!(err, filemanager_core::errors::FileError::PasswordRequired),
        "got {err:?}",
    );

    // The wrong one is refused rather than quietly writing rubbish.
    let err = archive_extract(
        locked.to_string_lossy().into_owned(),
        tree.dir("wrong-password").to_string_lossy().into_owned(),
        Some("hunter3".to_string()),
        None,
        None,
    )
    .unwrap_err();
    assert!(
        matches!(err, filemanager_core::errors::FileError::WrongPassword),
        "got {err:?}",
    );

    // And the right one gives the bytes back unchanged.
    let out = tree.dir("out");
    archive_extract(
        locked.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        Some("hunter2".to_string()),
        None,
        None,
    )
    .unwrap();
    assert_eq!(
        fs::read_to_string(out.join("private/notes.txt")).unwrap(),
        "for my eyes only",
    );
}

#[test]
fn an_empty_password_leaves_the_archive_unencrypted() {
    // The dialog hands back "" when the field is left alone. Treating that as
    // a password would write an archive that asks for one nobody typed.
    let tree = TempTree::new("zip-empty-password");
    tree.file("open/a.txt", b"nothing secret");
    let zip_path = tree.path().join("open.zip");

    archive_create(
        vec![tree.path().join("open").to_string_lossy().into_owned()],
        zip_path.to_string_lossy().into_owned(),
        Some(String::new()),
        None,
        None,
    )
    .unwrap();

    assert!(!archive_is_encrypted(zip_path.to_string_lossy().into_owned()).unwrap());

    let out = tree.dir("out");
    archive_extract(
        zip_path.to_string_lossy().into_owned(),
        out.to_string_lossy().into_owned(),
        None,
        None,
        None,
    )
    .unwrap();
    assert_eq!(fs::read_to_string(out.join("open/a.txt")).unwrap(), "nothing secret");
}
