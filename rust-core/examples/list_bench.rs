//! Times one folder's listing - what opening a folder in the browser costs -
//! under each sort order. Give it a folder; each case runs ten times and the
//! best is reported.
//!
//!     cargo run --release --example list_bench -- /some/big/folder
//!
//! On a phone, shared storage is served through FUSE, where every stat is a
//! round trip to the media provider. A FUSE passthrough mount of the folder
//! (rofiles-fuse, bindfs) is the nearer comparison to that than the folder
//! itself.

use filemanager_core::scanner::list_dir;
use filemanager_core::types::{SortKey, SortOptions};
use std::time::Instant;

fn run(label: &str, dir: &str, show_hidden: bool, key: SortKey) {
    let sort = SortOptions { key, descending: false, dirs_first: true };
    let mut best = f64::MAX;
    let mut count = 0;
    for _ in 0..10 {
        let start = Instant::now();
        count = list_dir(dir.to_string(), show_hidden, sort.clone()).unwrap().len();
        best = best.min(start.elapsed().as_secs_f64() * 1000.0);
    }
    println!("  {label:<30} {best:8.2} ms   {count} entries");
}

fn main() {
    let dir = std::env::args().nth(1).expect("give me a folder");
    run("by name", &dir, false, SortKey::Name);
    run("by name, hidden shown", &dir, true, SortKey::Name);
    run("by type", &dir, false, SortKey::Type);
    run("by date", &dir, false, SortKey::Modified);
    run("by size", &dir, false, SortKey::Size);
}
