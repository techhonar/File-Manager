//! Times the scans over a real directory tree, to see where they spend their
//! time. Give it a root; each case runs three times and the best is reported.
//!
//!     cargo run --release --example search_bench -- /some/large/tree
//!
//! An example rather than a bin: `cargo build` does not build examples, and
//! the Android build cross-compiles every bin in this crate for four ABIs.

use filemanager_core::search::{search_streaming, SearchFilter, SearchSink};
use filemanager_core::types::FileCategory;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

struct Counting {
    matched: AtomicU64,
    scanned: AtomicU64,
}

impl SearchSink for Counting {
    fn on_batch(&self, entries: Vec<filemanager_core::types::FileEntry>) {
        self.matched.fetch_add(entries.len() as u64, Ordering::Relaxed);
    }
    fn on_scanned(&self, count: u64) {
        self.scanned.store(count, Ordering::Relaxed);
    }
    fn on_finished(&self, _matched: u64, _cancelled: bool) {}
}

fn run(label: &str, root: &str, filter: SearchFilter) {
    let mut best = f64::MAX;
    let mut found = 0;
    for _ in 0..3 {
        let sink = Arc::new(Counting {
            matched: AtomicU64::new(0),
            scanned: AtomicU64::new(0),
        });
        let start = Instant::now();
        search_streaming(vec![root.to_string()], filter.clone(), sink.clone(), None).unwrap();
        let elapsed = start.elapsed().as_secs_f64() * 1000.0;
        if elapsed < best {
            best = elapsed;
        }
        found = sink.matched.load(Ordering::Relaxed);
    }
    println!("  {label:<34} {best:8.1} ms   {found} matches");
}

fn main() {
    let root = std::env::args().nth(1).expect("give me a root");

    let base = SearchFilter {
        query: String::new(),
        categories: vec![],
        min_size: None,
        max_size: None,
        modified_after: None,
        include_hidden: false,
        limit: 0,
    };

    run("walk only, no filter", &root, base.clone());
    run(
        "name search \"madison\"",
        &root,
        SearchFilter { query: "madison".into(), ..base.clone() },
    );
    run(
        "category: video",
        &root,
        SearchFilter { categories: vec![FileCategory::Video], ..base.clone() },
    );
    run(
        "category: image, limit 20000",
        &root,
        SearchFilter { categories: vec![FileCategory::Image], limit: 20000, ..base.clone() },
    );

    // The home screen's own two, which run when the app is opened.
    let mut best = f64::MAX;
    for _ in 0..3 {
        let start = Instant::now();
        let n = filemanager_core::categories::recent_files(root.clone(), 7, 200, None)
            .unwrap()
            .len();
        let ms = start.elapsed().as_secs_f64() * 1000.0;
        if ms < best { best = ms; }
        if best < f64::MAX { let _ = n; }
    }
    println!("  {:<34} {:8.1} ms", "recent_files (home screen)", best);

    let mut best = f64::MAX;
    for _ in 0..3 {
        let start = Instant::now();
        filemanager_core::categories::files_in_category(
            root.clone(), FileCategory::Image, 0, None,
        ).unwrap();
        let ms = start.elapsed().as_secs_f64() * 1000.0;
        if ms < best { best = ms; }
    }
    println!("  {:<34} {:8.1} ms", "files_in_category (images)", best);
}
