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
use filemanager_core::types::{FileCategory, FileEntry, SortKey, SortOptions};
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
    // Split-APK bundles are installation files too - the app installs them.
    assert_eq!(categorize("game.XAPK"), FileCategory::Apk);
    assert_eq!(categorize("app.apks"), FileCategory::Apk);
    assert_eq!(categorize("app.apkm"), FileCategory::Apk);
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

    // And it can still be listed. A zip does not encrypt its index, so showing
    // what is inside must not require the password.
    let listed = archive_list(locked.to_string_lossy().into_owned()).unwrap();
    assert!(
        listed.iter().any(|e| e.name == "private/notes.txt"),
        "listed {listed:?}",
    );

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

/// The walk is split into one task per top-level directory. That split must be
/// invisible: the same files, once each, however the tree is shaped.
#[test]
fn splitting_the_walk_finds_every_file_exactly_once() {
    let tree = TempTree::new("walk-split");
    // Files directly in the root, which belong to the shallow unit.
    tree.file("top-level.txt", b"a");
    tree.file("another.txt", b"b");
    // And files under subdirectories, which are units of their own.
    tree.file("one/deep/first.txt", b"c");
    tree.file("one/second.txt", b"d");
    tree.file("two/third.txt", b"e");
    tree.file("two/nested/more/fourth.txt", b"f");

    let found = collect(&tree, "", false);
    let mut names: Vec<String> = found.iter().map(|e| e.name.clone()).collect();
    names.sort();
    assert_eq!(
        names,
        vec![
            "another.txt",
            "first.txt",
            "fourth.txt",
            "second.txt",
            "third.txt",
            "top-level.txt",
        ],
    );

    // Once each: a root walked both shallowly and as part of a subtree would
    // report its files twice.
    let mut paths: Vec<String> = found.iter().map(|e| e.path.clone()).collect();
    paths.sort();
    let unique = paths.len();
    paths.dedup();
    assert_eq!(unique, paths.len(), "a file was reported more than once");
}

#[test]
fn splitting_the_walk_keeps_hidden_files_hidden() {
    let tree = TempTree::new("walk-split-hidden");
    tree.file("visible.txt", b"a");
    tree.file(".hidden-file.txt", b"b");
    tree.file(".hidden-dir/inside.txt", b"c");
    tree.file("normal/.also-hidden.txt", b"d");

    let shown: Vec<String> = collect(&tree, "", false).iter().map(|e| e.name.clone()).collect();
    assert_eq!(shown, vec!["visible.txt"], "got {shown:?}");

    let all = collect(&tree, "", true);
    let mut names: Vec<String> = all.iter().map(|e| e.name.clone()).collect();
    names.sort();
    assert_eq!(
        names,
        vec![".also-hidden.txt", ".hidden-file.txt", "inside.txt", "visible.txt"],
    );
}

#[test]
fn a_root_with_no_subdirectories_is_still_walked() {
    let tree = TempTree::new("walk-split-flat");
    tree.file("only.txt", b"a");
    let found = collect(&tree, "", false);
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].name, "only.txt");
}

#[test]
fn a_root_that_is_not_there_yields_nothing_rather_than_failing() {
    let tree = TempTree::new("walk-split-missing");
    let missing = tree.path().join("no-such-folder");
    let sink = Collector::new();
    search_streaming(
        vec![missing.to_string_lossy().into_owned()],
        SearchFilter {
            query: String::new(),
            categories: vec![],
            min_size: None,
            max_size: None,
            modified_after: None,
            include_hidden: false,
            limit: 0,
        },
        sink.clone(),
        None,
    )
    .unwrap();
    assert!(sink.taken().is_empty());
}

/// Shared harness for the walk-splitting tests.
struct Collector {
    entries: std::sync::Mutex<Vec<FileEntry>>,
}

impl Collector {
    fn new() -> std::sync::Arc<Collector> {
        std::sync::Arc::new(Collector { entries: std::sync::Mutex::new(Vec::new()) })
    }
    fn taken(&self) -> Vec<FileEntry> {
        self.entries.lock().unwrap().clone()
    }
}

impl SearchSink for Collector {
    fn on_batch(&self, entries: Vec<FileEntry>) {
        self.entries.lock().unwrap().extend(entries);
    }
    fn on_scanned(&self, _count: u64) {}
    fn on_finished(&self, _matched: u64, _cancelled: bool) {}
}

fn collect(tree: &TempTree, query: &str, include_hidden: bool) -> Vec<FileEntry> {
    let sink = Collector::new();
    search_streaming(
        vec![tree.path().to_string_lossy().into_owned()],
        SearchFilter {
            query: query.to_string(),
            categories: vec![],
            min_size: None,
            max_size: None,
            modified_after: None,
            include_hidden,
            limit: 0,
        },
        sink.clone(),
        None,
    )
    .unwrap();
    sink.taken()
}

// --- The search session, which now owns the results ------------------------

struct PageSink {
    pages: std::sync::Mutex<Vec<filemanager_core::session::SearchPage>>,
}

impl PageSink {
    fn new() -> std::sync::Arc<PageSink> {
        std::sync::Arc::new(PageSink { pages: std::sync::Mutex::new(Vec::new()) })
    }
    fn pages(&self) -> Vec<filemanager_core::session::SearchPage> {
        self.pages.lock().unwrap().clone()
    }
    fn last(&self) -> filemanager_core::session::SearchPage {
        self.pages().last().expect("no page was delivered").clone()
    }
}

impl filemanager_core::session::SearchObserver for PageSink {
    fn on_page(&self, page: filemanager_core::session::SearchPage) {
        self.pages.lock().unwrap().push(page);
    }
}

fn plain_filter(query: &str) -> SearchFilter {
    SearchFilter {
        query: query.to_string(),
        categories: vec![],
        min_size: None,
        max_size: None,
        modified_after: None,
        include_hidden: false,
        limit: 0,
    }
}

#[test]
fn a_session_returns_a_page_newest_first_and_the_true_total() {
    let tree = TempTree::new("session-order");
    // Written oldest to newest so the order on disk is not the answer.
    for i in 0..12 {
        let path = tree.file(&format!("file{i:02}.txt"), b"x");
        let when = std::time::SystemTime::UNIX_EPOCH
            + std::time::Duration::from_secs(1_700_000_000 + i as u64 * 60);
        // std rather than a crate for the sake of one line in one test.
        let handle = fs::File::options().write(true).open(&path).unwrap();
        handle
            .set_times(fs::FileTimes::new().set_modified(when))
            .unwrap();
    }

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter(""),
            5,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();

    let page = sink.last();
    assert!(page.finished, "the last page should say so");
    assert_eq!(page.total, 12, "the total counts everything, not the page");
    assert_eq!(page.entries.len(), 5, "the page is capped at what was asked for");

    // Newest first, and the newest five are the five it kept.
    let names: Vec<&str> = page.entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["file11.txt", "file10.txt", "file09.txt", "file08.txt", "file07.txt"]);
}

#[test]
fn a_session_narrows_without_walking_again() {
    let tree = TempTree::new("session-narrow");
    tree.file("madison-one.txt", b"x");
    tree.file("madison-two.txt", b"x");
    tree.file("something-else.txt", b"x");

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter("ma"),
            100,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();
    assert_eq!(sink.last().total, 2);

    // Narrowing happens against what is held, so deleting the files first
    // proves the disk is not touched.
    std::fs::remove_file(tree.path().join("madison-one.txt")).unwrap();
    std::fs::remove_file(tree.path().join("madison-two.txt")).unwrap();

    let narrowed = session.narrow("madison-t".to_string(), 100);
    assert_eq!(narrowed.total, 1);
    assert_eq!(narrowed.entries[0].name, "madison-two.txt");
    assert!(narrowed.finished);

    // Case-insensitively, the same as the walk matches.
    assert_eq!(session.narrow("MADISON".to_string(), 100).total, 2);

    session.clear();
    assert_eq!(session.narrow("madison".to_string(), 100).total, 0);
}

#[test]
fn a_session_delivers_something_before_the_walk_ends() {
    let tree = TempTree::new("session-streaming");
    for i in 0..300 {
        tree.file(&format!("dir{}/file{i:03}.txt", i % 8), b"x");
    }

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter(""),
            50,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();

    assert!(!sink.pages().is_empty(), "nothing was delivered at all");
    assert_eq!(sink.last().total, 300);
    // Only the last page is final; any earlier ones are progress.
    let finals = sink.pages().iter().filter(|p| p.finished).count();
    assert_eq!(finals, 1, "exactly one page should be marked finished");
}

#[test]
fn a_session_forgets_files_that_have_been_deleted() {
    let tree = TempTree::new("session-forget");
    tree.file("madison-one.txt", b"x");
    tree.file("madison-two.txt", b"x");

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter("madison"),
            100,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();
    assert_eq!(sink.last().total, 2);

    let gone = tree.path().join("madison-one.txt").to_string_lossy().into_owned();
    session.forget(vec![gone]);

    // Narrowing must not bring it back, which is the whole reason this exists.
    let narrowed = session.narrow("madison".to_string(), 100);
    assert_eq!(narrowed.total, 1);
    assert_eq!(narrowed.entries[0].name, "madison-two.txt");
}

#[test]
fn a_session_stops_growing_once_it_is_holding_enough() {
    // The cap is twenty thousand; going past it needs more files than a test
    // should create, so this checks the shape below it instead: everything
    // found is held, the total agrees, and the page says so.
    let tree = TempTree::new("session-retention");
    for i in 0..50 {
        tree.file(&format!("dir{}/file{i:03}.txt", i % 5), b"x");
    }

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter(""),
            10,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();

    let page = sink.last();
    assert_eq!(page.total, 50, "the total counts everything, not the page");
    assert_eq!(page.entries.len(), 10, "the page is what was asked for");
    assert!(page.complete, "nothing was dropped, so narrowing is sound");

    // And narrowing agrees about completeness.
    assert!(session.narrow("file".to_string(), 10).complete);
}

#[test]
fn a_cleared_session_holds_nothing() {
    let tree = TempTree::new("session-cleared");
    tree.file("one.txt", b"x");
    tree.file("two.txt", b"x");

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter(""),
            100,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();
    assert_eq!(sink.last().total, 2);

    session.clear();
    // Everything resets, not just the entries - a stale total would be
    // reported as though those results were still there.
    let after = session.narrow("".to_string(), 100);
    assert_eq!(after.total, 0);
    assert!(after.entries.is_empty());
    assert!(after.complete);
}

#[test]
fn a_reused_session_replaces_what_it_held() {
    // One session serves the whole process, so a second run must not add to
    // the first. This is the shape the app relies on: open a category, leave,
    // open another.
    let tree = TempTree::new("session-reuse");
    tree.file("alpha.txt", b"x");
    tree.file("beta.txt", b"x");
    tree.file("gamma.txt", b"x");

    let session = filemanager_core::session::SearchSession::new();
    let root = vec![tree.path().to_string_lossy().into_owned()];

    let first = PageSink::new();
    session.run(root.clone(), plain_filter("a"), 100, String::new(), first.clone(), None)
        .unwrap();
    assert_eq!(first.last().total, 3, "alpha, beta and gamma all contain an a");

    let second = PageSink::new();
    session.run(root, plain_filter("alpha"), 100, String::new(), second.clone(), None)
        .unwrap();
    assert_eq!(second.last().total, 1, "the second run replaces the first");
    assert_eq!(second.last().entries[0].name, "alpha.txt");
}

#[test]
fn a_finished_walk_is_remembered_for_next_time() {
    let tree = TempTree::new("session-cache");
    tree.file("one.jpg", b"x");
    tree.file("two.jpg", b"x");
    let root = vec![tree.path().to_string_lossy().into_owned()];

    let session = filemanager_core::session::SearchSession::new();
    assert!(session.cached("images".into()).is_none(), "nothing yet");

    let sink = PageSink::new();
    session
        .run(root.clone(), plain_filter(""), 100, "images".into(), sink.clone(), None)
        .unwrap();

    let remembered = session.cached("images".into()).expect("should be cached");
    assert_eq!(remembered.total, 2);
    assert_eq!(remembered.entries.len(), 2);
    assert!(remembered.finished);

    // It outlives the results themselves. Closing the screen empties those;
    // the point of the cache is that the next visit still has something.
    session.clear();
    assert_eq!(session.narrow("".into(), 100).total, 0, "results are gone");
    assert_eq!(
        session.cached("images".into()).map(|p| p.total),
        Some(2),
        "but what to show next time is not",
    );

    // A different key is a different list.
    assert!(session.cached("video".into()).is_none());

    session.forget_cached();
    assert!(session.cached("images".into()).is_none());
}

#[test]
fn an_empty_key_is_not_cached() {
    // Free-text searches are not remembered: there is no bound on how many
    // different ones get typed, and the cache would grow without one.
    let tree = TempTree::new("session-cache-unkeyed");
    tree.file("one.txt", b"x");

    let session = filemanager_core::session::SearchSession::new();
    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter(""),
            100,
            String::new(),
            sink.clone(),
            None,
        )
        .unwrap();

    assert_eq!(sink.last().total, 1, "the walk still ran");
    assert!(session.cached(String::new()).is_none(), "but nothing was filed");
}

#[test]
fn a_cancelled_walk_is_not_remembered() {
    let tree = TempTree::new("session-cache-cancelled");
    for i in 0..40 {
        tree.file(&format!("dir{}/f{i:02}.txt", i % 4), b"x");
    }
    let session = filemanager_core::session::SearchSession::new();
    let token = filemanager_core::cancel::CancelToken::new();
    token.cancel();

    let sink = PageSink::new();
    session
        .run(
            vec![tree.path().to_string_lossy().into_owned()],
            plain_filter(""),
            100,
            "images".into(),
            sink.clone(),
            Some(token),
        )
        .unwrap();

    // Stopped before it found anything, so there is nothing to show next
    // time - and an empty page would open the category on "No files match".
    assert!(session.cached("images".into()).is_none());
}

#[test]
fn a_remembered_category_is_still_there_for_the_next_run_of_the_app() {
    // Android ends a backgrounded app's process freely, and a cache kept only
    // in memory went with it: the category opened on a spinner every time the
    // user came back to the app.
    let tree = TempTree::new("session-cache-disk");
    tree.file("photos/one.jpg", b"x");
    tree.file("photos/two.jpg", b"x");
    let root = vec![tree.path().join("photos").to_string_lossy().into_owned()];
    let cache_dir = tree.path().join("cache").to_string_lossy().into_owned();

    let first = filemanager_core::session::SearchSession::with_cache_dir(cache_dir.clone());
    first
        .run(root, plain_filter(""), 100, "category:IMAGE".into(), PageSink::new(), None)
        .unwrap();
    drop(first);

    let next = filemanager_core::session::SearchSession::with_cache_dir(cache_dir);
    let page = next.cached("category:IMAGE".into()).expect("remembered on disk");
    let mut names: Vec<&str> = page.entries.iter().map(|e| e.name.as_str()).collect();
    names.sort();
    assert_eq!(names, vec!["one.jpg", "two.jpg"]);
    assert_eq!(page.total, 2);
    assert!(page.finished, "it came from a walk that ran to the end");
    assert!(next.cached("category:VIDEO".into()).is_none(), "and only under its own key");
}

#[test]
fn a_file_deleted_since_is_not_brought_back_from_disk() {
    let tree = TempTree::new("session-cache-disk-stale");
    tree.file("photos/kept.jpg", b"x");
    let gone = tree.file("photos/gone.jpg", b"x");
    let root = vec![tree.path().join("photos").to_string_lossy().into_owned()];
    let cache_dir = tree.path().join("cache").to_string_lossy().into_owned();

    filemanager_core::session::SearchSession::with_cache_dir(cache_dir.clone())
        .run(root, plain_filter(""), 100, "category:IMAGE".into(), PageSink::new(), None)
        .unwrap();
    // Deleted by some other app while this one was not running.
    fs::remove_file(&gone).unwrap();

    let page = filemanager_core::session::SearchSession::with_cache_dir(cache_dir)
        .cached("category:IMAGE".into())
        .unwrap();
    let names: Vec<&str> = page.entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["kept.jpg"], "a file that has gone would fail when tapped");
    assert_eq!(page.total, 1);
}

#[test]
fn a_file_deleted_from_the_results_is_forgotten_by_the_cache_too() {
    // Otherwise it came back the next time the category was opened, until
    // the walk caught up with it.
    let tree = TempTree::new("session-cache-forget");
    tree.file("one.jpg", b"x");
    let two = tree.file("two.jpg", b"x");
    let root = vec![tree.path().to_string_lossy().into_owned()];

    let session = filemanager_core::session::SearchSession::new();
    session
        .run(root, plain_filter(""), 100, "category:IMAGE".into(), PageSink::new(), None)
        .unwrap();
    session.forget(vec![two.to_string_lossy().into_owned()]);

    let page = session.cached("category:IMAGE".into()).unwrap();
    let names: Vec<&str> = page.entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["one.jpg"]);
    assert_eq!(page.total, 1);
}

#[test]
fn forgetting_the_cache_forgets_it_on_disk_as_well() {
    let tree = TempTree::new("session-cache-disk-forget");
    tree.file("one.jpg", b"x");
    let root = vec![tree.path().to_string_lossy().into_owned()];
    let cache_dir = tree.path().join("cache").to_string_lossy().into_owned();

    let session = filemanager_core::session::SearchSession::with_cache_dir(cache_dir.clone());
    session
        .run(root, plain_filter(""), 100, "category:IMAGE".into(), PageSink::new(), None)
        .unwrap();
    session.forget_cached();

    assert!(session.cached("category:IMAGE".into()).is_none());
    assert!(
        filemanager_core::session::SearchSession::with_cache_dir(cache_dir)
            .cached("category:IMAGE".into())
            .is_none(),
        "a new session found it on disk",
    );
}

#[test]
fn files_sharing_a_timestamp_come_back_in_the_same_order_every_time() {
    // Bulk-copied files share a timestamp to the millisecond. Picking the
    // newest few of those is a tie, and a tie broken differently each time
    // makes the list reshuffle on every refresh - visibly, because the cached
    // rows are replaced by fresh ones that disagree about the order.
    let tree = TempTree::new("session-stable-order");
    let when = std::time::SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(1_700_000_000);
    // Enough files across enough directories that the parallel walk delivers
    // them in a different order each run, which is what exposes an unstable
    // tie-break. A handful in one directory arrives the same way every time
    // and proves nothing.
    for i in 0..600 {
        let path = tree.file(&format!("dir{}/file{i:03}.txt", i % 16), b"x");
        let handle = fs::File::options().write(true).open(&path).unwrap();
        handle.set_times(fs::FileTimes::new().set_modified(when)).unwrap();
    }

    let session = filemanager_core::session::SearchSession::new();
    let root = vec![tree.path().to_string_lossy().into_owned()];

    let orders: Vec<Vec<String>> = (0..6)
        .map(|_| {
            let sink = PageSink::new();
            session
                .run(root.clone(), plain_filter(""), 10, String::new(), sink.clone(), None)
                .unwrap();
            sink.last().entries.iter().map(|e| e.path.clone()).collect()
        })
        .collect();

    for (index, order) in orders.iter().enumerate().skip(1) {
        assert_eq!(&orders[0], order, "walk {index} disagreed with the first");
    }
    assert_eq!(orders[0].len(), 10);
}

// --- Audit: things that could lose or mangle files ------------------------

#[test]
fn copying_a_folder_into_itself_is_refused_rather_than_recursing() {
    // Pasting a folder into one of its own subfolders used to recurse forever:
    // each level of the copy was itself inside the source, so it was copied
    // again, nesting deeper until the path grew too long - filling storage on
    // the way.
    let tree = TempTree::new("copy-into-self");
    tree.file("album/photo.jpg", b"a photo");
    tree.dir("album/sub");
    let source = tree.path().join("album").to_string_lossy().into_owned();
    let inside = tree.path().join("album/sub").to_string_lossy().into_owned();

    let result = copy_paths(vec![source.clone()], inside.clone(), false, None, None);
    assert!(result.is_err(), "copying a folder into itself must fail, got {result:?}");

    // And nothing was created on the way to failing.
    assert!(
        !tree.path().join("album/sub/album").exists(),
        "a partial nested copy was left behind",
    );
    // A folder into itself directly is the same mistake.
    assert!(copy_paths(vec![source.clone()], source, false, None, None).is_err());
}

#[test]
fn a_copy_keeps_the_original_modified_time() {
    // Copied photos used to take the time of the copy, so a whole album pasted
    // somewhere jumped to the top of every date-sorted list as brand new.
    let tree = TempTree::new("copy-keeps-mtime");
    let original = tree.file("from/old.jpg", b"taken years ago");
    let when = std::time::SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(1_500_000_000);
    fs::File::options()
        .write(true)
        .open(&original)
        .unwrap()
        .set_times(fs::FileTimes::new().set_modified(when))
        .unwrap();
    let dest = tree.dir("to");

    copy_paths(
        vec![original.to_string_lossy().into_owned()],
        dest.to_string_lossy().into_owned(),
        false,
        None,
        None,
    )
    .unwrap();

    let copied = fs::metadata(dest.join("old.jpg")).unwrap().modified().unwrap();
    assert_eq!(copied, when, "the copy should carry the original's date");
}

#[test]
fn a_trashed_folder_reports_what_it_holds_not_its_inode() {
    // The trash showed a folder of photos as 4 KB - the size of the directory
    // entry itself - which made "empty the trash to free space" look pointless.
    let tree = TempTree::new("trash-folder-size");
    tree.file("album/one.jpg", &vec![0u8; 50_000]);
    tree.file("album/two.jpg", &vec![0u8; 30_000]);
    let trash = tree.dir("trash").to_string_lossy().into_owned();

    trash_move(trash.clone(), tree.path().join("album").to_string_lossy().into_owned()).unwrap();

    let items = trash_list(trash, 30).unwrap();
    assert_eq!(items.len(), 1);
    assert!(items[0].is_dir);
    assert_eq!(items[0].size, 80_000, "a folder's size is what is inside it");
}

#[test]
fn duplicates_ignore_hidden_folders_including_the_trash() {
    // The scan walked into the app's own trash, so a copy already deleted was
    // offered as a duplicate of the one still in use - and which of them got
    // kept depended on hash-map order.
    let tree = TempTree::new("dedup-hidden");
    let body = vec![7u8; 200_000];
    tree.file("Pictures/photo.jpg", &body);
    tree.file(".FileManagerTrash/files/abc123", &body);
    tree.file(".thumbnails/cache.bin", &body);

    let groups = find_duplicates(tree.str(), 100_000, None, None).unwrap();
    assert!(groups.is_empty(), "nothing hidden should count, got {groups:?}");
}

#[test]
fn the_copy_to_keep_is_chosen_the_same_way_every_time() {
    // Keep-the-first only means something if "first" is decided on purpose.
    // The oldest is the one most likely to be the original.
    let tree = TempTree::new("dedup-order");
    let body = vec![9u8; 200_000];
    let names = ["b/copy.jpg", "a/original.jpg", "c/another.jpg"];
    let ages = [1_700_000_300u64, 1_700_000_000, 1_700_000_600];
    for (name, age) in names.iter().zip(ages) {
        let path = tree.file(name, &body);
        let when = std::time::SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(age);
        fs::File::options()
            .write(true)
            .open(&path)
            .unwrap()
            .set_times(fs::FileTimes::new().set_modified(when))
            .unwrap();
    }

    for _ in 0..5 {
        let groups = find_duplicates(tree.str(), 100_000, None, None).unwrap();
        assert_eq!(groups.len(), 1);
        assert!(
            groups[0].files[0].path.ends_with("a/original.jpg"),
            "the oldest copy should come first, got {:?}",
            groups[0].files.iter().map(|f| &f.path).collect::<Vec<_>>(),
        );
    }
}

#[test]
fn a_trash_move_that_fails_leaves_no_record_behind() {
    // The record is written first now, so a move that then fails must take
    // its record back out - or the trash lists a file that was never moved.
    use std::os::unix::fs::PermissionsExt;
    let tree = TempTree::new("trash-move-fails");
    let target = tree.file("keep.txt", b"still here");
    let trash = tree.dir("trash");
    let trash_str = trash.to_string_lossy().into_owned();

    // Make the stored-files folder impossible to write into.
    trash_list(trash_str.clone(), 30).unwrap(); // creates the layout
    let files = trash.join("files");
    fs::set_permissions(&files, fs::Permissions::from_mode(0o555)).unwrap();

    let result = trash_move(trash_str.clone(), target.to_string_lossy().into_owned());
    fs::set_permissions(&files, fs::Permissions::from_mode(0o755)).unwrap();

    assert!(result.is_err(), "the move should have failed");
    assert_eq!(fs::read_to_string(&target).unwrap(), "still here", "the file must be untouched");
    assert!(trash_list(trash_str, 30).unwrap().is_empty(), "no record for a file never moved");
}

#[test]
fn trashing_many_files_at_once_keeps_every_one() {
    // Ids used to be the wall-clock time alone. Each file here must get its
    // own, or the later ones would be renamed over the earlier ones.
    let tree = TempTree::new("trash-many");
    let trash = tree.dir("trash").to_string_lossy().into_owned();
    let paths: Vec<String> = (0..200)
        .map(|i| tree.file(&format!("f{i:03}.txt"), format!("file {i}").as_bytes()))
        .map(|p| p.to_string_lossy().into_owned())
        .collect();

    let ids = filemanager_core::trash::trash_move_many(trash.clone(), paths.clone()).unwrap();
    let unique: std::collections::HashSet<_> = ids.iter().collect();
    assert_eq!(unique.len(), 200, "every file needs its own id");
    assert_eq!(trash_list(trash.clone(), 30).unwrap().len(), 200);

    // And every one comes back with its own contents.
    for (i, id) in ids.iter().enumerate() {
        let restored = trash_restore(trash.clone(), id.clone()).unwrap();
        assert_eq!(fs::read_to_string(&restored).unwrap(), format!("file {i}"));
    }
}

#[test]
fn a_folder_into_itself_says_so() {
    // Its own error, so the app can tell the user what they did rather than
    // show a four-thousand-character path.
    let tree = TempTree::new("into-itself-error");
    tree.dir("album/sub");
    let err = copy_paths(
        vec![tree.path().join("album").to_string_lossy().into_owned()],
        tree.path().join("album/sub").to_string_lossy().into_owned(),
        false,
        None,
        None,
    )
    .unwrap_err();
    assert!(
        matches!(err, filemanager_core::errors::FileError::IntoItself { .. }),
        "got {err:?}",
    );

    // A sibling whose name merely starts the same way is not inside it.
    tree.dir("album-2");
    tree.file("album/x.txt", b"x");
    copy_paths(
        vec![tree.path().join("album").to_string_lossy().into_owned()],
        tree.path().join("album-2").to_string_lossy().into_owned(),
        false,
        None,
        None,
    )
    .expect("album-2 is next to album, not inside it");
    assert!(tree.path().join("album-2/album/x.txt").exists());
}

#[test]
fn a_record_that_cannot_be_written_leaves_the_file_where_it_was() {
    // The original bug. The file was moved first and the record written
    // after, so a failed write - full storage, the usual reason to be emptying
    // things into the trash - left the file in the trash with nothing pointing
    // at it: gone from where it was, and never listed, restored or purged.
    use std::os::unix::fs::PermissionsExt;
    let tree = TempTree::new("trash-record-fails");
    let target = tree.file("precious.txt", b"do not lose me");
    let trash = tree.dir("trash");
    let trash_str = trash.to_string_lossy().into_owned();

    trash_list(trash_str.clone(), 30).unwrap(); // creates the layout
    let meta = trash.join("meta");
    fs::set_permissions(&meta, fs::Permissions::from_mode(0o555)).unwrap();

    let result = trash_move(trash_str.clone(), target.to_string_lossy().into_owned());
    fs::set_permissions(&meta, fs::Permissions::from_mode(0o755)).unwrap();

    assert!(result.is_err(), "the record could not be written, so this should fail");
    assert_eq!(
        fs::read_to_string(&target).unwrap(),
        "do not lose me",
        "the file must still be where the user left it",
    );
    let orphans = fs::read_dir(trash.join("files")).unwrap().count();
    assert_eq!(orphans, 0, "nothing may be stored without a record");
}

#[test]
fn the_largest_files_leave_out_hidden_ones_but_the_totals_do_not() {
    // "Largest files" is a list to pick deletions from. A trashed file turned
    // up in it under its trash id, unrecognisable - and deleting it from there
    // moved a file already in the trash into the trash again, leaving its
    // original record pointing at nothing. The space it takes is still used,
    // though, so the totals keep counting it.
    let tree = TempTree::new("largest-hidden");
    tree.file(".FileManagerTrash/files/18a2b3c-1f-0", &vec![0u8; 400_000]);
    tree.file(".thumbnails/big.cache", &vec![0u8; 300_000]);
    tree.file("Movies/.secret.mp4", &vec![0u8; 250_000]);
    tree.file("Movies/holiday.mp4", &vec![0u8; 100_000]);

    let analysis = analyze_storage(tree.str(), 10, None, None).unwrap();
    let names: Vec<&str> = analysis.largest.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, vec!["holiday.mp4"], "only the visible file should be offered");
    assert_eq!(
        analysis.scanned_bytes, 1_050_000,
        "every byte on the disk still counts towards what is used",
    );
}

#[test]
fn an_archive_that_cannot_read_a_folder_fails_rather_than_leaving_it_out() {
    // Compress-then-delete is how people free space. An archive that quietly
    // skipped the one folder it could not read reported success, and the
    // originals went with nothing to restore them from.
    use std::os::unix::fs::PermissionsExt;
    let tree = TempTree::new("zip-unreadable-folder");
    tree.file("album/one.jpg", b"one");
    tree.file("album/locked/two.jpg", b"two");
    let locked = tree.path().join("album/locked");
    fs::set_permissions(&locked, fs::Permissions::from_mode(0o000)).unwrap();
    let dest = tree.path().join("album.zip");

    let result = archive_create(
        vec![tree.path().join("album").to_string_lossy().into_owned()],
        dest.to_string_lossy().into_owned(),
        None,
        None,
        None,
    );
    fs::set_permissions(&locked, fs::Permissions::from_mode(0o755)).unwrap();

    assert!(result.is_err(), "reported success with a folder missing: {result:?}");
    assert!(!dest.exists(), "left a partial archive behind");
}

#[test]
fn an_archive_that_fails_partway_leaves_nothing_behind() {
    // A half-written zip is not an archive of anything, and leaving it meant
    // the next attempt was named "album (1).zip" beside a broken one.
    use std::os::unix::fs::PermissionsExt;
    let tree = TempTree::new("zip-partial");
    tree.file("album/one.jpg", b"one");
    let unreadable = tree.file("album/two.jpg", b"two");
    fs::set_permissions(&unreadable, fs::Permissions::from_mode(0o000)).unwrap();
    let dest = tree.path().join("album.zip");

    let result = archive_create(
        vec![tree.path().join("album").to_string_lossy().into_owned()],
        dest.to_string_lossy().into_owned(),
        None,
        None,
        None,
    );
    fs::set_permissions(&unreadable, fs::Permissions::from_mode(0o644)).unwrap();

    assert!(result.is_err());
    assert!(!dest.exists(), "left a partial archive behind");
}

#[test]
fn a_cancelled_archive_leaves_nothing_behind() {
    use filemanager_core::cancel::{CancelToken, ProgressListener};
    use std::sync::Arc;

    struct CancelAfterFirst(Arc<CancelToken>);
    impl ProgressListener for CancelAfterFirst {
        fn on_progress(&self, _done: u64, _total: u64, _current: String) {
            self.0.cancel();
        }
    }

    let tree = TempTree::new("zip-cancelled");
    for i in 0..5 {
        tree.file(&format!("album/{i}.jpg"), b"picture");
    }
    let dest = tree.path().join("album.zip");
    let token = CancelToken::new();

    let result = archive_create(
        vec![tree.path().join("album").to_string_lossy().into_owned()],
        dest.to_string_lossy().into_owned(),
        None,
        Some(Arc::new(CancelAfterFirst(token.clone()))),
        Some(token),
    );

    assert!(result.is_err());
    assert!(!dest.exists(), "left a partial archive behind");
}

#[test]
fn listing_an_archive_gives_each_entry_its_own_time() {
    let tree = TempTree::new("zip-list-times");
    let zip_path = tree.path().join("old.zip");
    {
        let file = fs::File::create(&zip_path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let stamp = zip::DateTime::from_date_and_time(2020, 1, 2, 3, 4, 6).unwrap();
        let options = zip::write::SimpleFileOptions::default().last_modified_time(stamp);
        zip.start_file("old.txt", options).unwrap();
        std::io::Write::write_all(&mut zip, b"from before").unwrap();
        zip.finish().unwrap();
    }

    let listed = archive_list(zip_path.to_string_lossy().into_owned()).unwrap();

    // 2020-01-02 03:04:06, the wall-clock time the entry carries.
    assert_eq!(listed[0].modified_ms, 1_577_934_246_000);
}

/// A folder on a different filesystem from the test trees, so a rename between
/// them fails with EXDEV and the copy fallback runs - the path a phone takes
/// whenever the trash and the file are on different mounts. None where there
/// is no such filesystem to use.
fn other_filesystem(name: &str) -> Option<TempTree> {
    use std::os::unix::fs::MetadataExt;
    let shm = Path::new("/dev/shm");
    let here = fs::metadata(std::env::temp_dir()).ok()?.dev();
    if fs::metadata(shm).ok()?.dev() == here {
        return None;
    }
    let dir = shm.join(format!("fm-test-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).ok()?;
    Some(TempTree(dir))
}

#[test]
fn a_trash_move_that_fails_partway_leaves_no_partial_copy_behind() {
    // The copy fallback stopped where it failed and left what it had copied
    // in the trash. Its record was taken back out, so that half a folder was
    // never listed, never purged, and counted against the trash's size.
    let Some(trash) = other_filesystem("trash-partial") else { return };
    let tree = TempTree::new("trash-partial-src");
    tree.file("album/a.jpg", b"one");
    // Something the copy cannot read: a link to a file that is not there.
    std::os::unix::fs::symlink(tree.path().join("gone"), tree.path().join("album/broken"))
        .unwrap();
    let album = tree.path().join("album");

    let result = trash_move(trash.str(), album.to_string_lossy().into_owned());

    assert!(result.is_err(), "the copy should have failed");
    assert_eq!(fs::read_to_string(album.join("a.jpg")).unwrap(), "one", "the original is untouched");
    assert!(trash_list(trash.str(), 30).unwrap().is_empty(), "no record for a move that failed");
    let stored = fs::read_dir(trash.path().join("files")).unwrap().count();
    assert_eq!(stored, 0, "half a folder was left in the trash with nothing listing it");
}

#[test]
fn a_trashed_folder_whose_original_cannot_all_be_removed_stays_listed() {
    // The copy reached the trash whole, then removing the original stopped
    // partway - after some of its files were already gone. The record was
    // dropped as though nothing had moved, so those files were left only in
    // a copy the trash did not list: not restorable, not even visible.
    use std::os::unix::fs::PermissionsExt;
    let Some(trash) = other_filesystem("trash-remove-fails") else { return };
    let tree = TempTree::new("trash-remove-fails-src");
    tree.file("album/a.jpg", b"one");
    tree.file("album/locked/b.jpg", b"two");
    let locked = tree.path().join("album/locked");
    fs::set_permissions(&locked, fs::Permissions::from_mode(0o555)).unwrap();
    // Root ignores the permission, so there is no failure to test there.
    if fs::write(locked.join("probe"), b"").is_ok() {
        let _ = fs::remove_file(locked.join("probe"));
        fs::set_permissions(&locked, fs::Permissions::from_mode(0o755)).unwrap();
        return;
    }

    let album = tree.path().join("album");
    let result = trash_move(trash.str(), album.to_string_lossy().into_owned());
    fs::set_permissions(&locked, fs::Permissions::from_mode(0o755)).unwrap();

    assert!(result.is_err(), "the original is not all gone, so this was not a clean move");
    let listed = trash_list(trash.str(), 30).unwrap();
    assert_eq!(listed.len(), 1, "the whole copy in the trash has to stay listed");
    let stored = trash.path().join("files").join(&listed[0].id);
    assert_eq!(fs::read_to_string(stored.join("a.jpg")).unwrap(), "one");
    assert_eq!(fs::read_to_string(stored.join("locked/b.jpg")).unwrap(), "two");
}
