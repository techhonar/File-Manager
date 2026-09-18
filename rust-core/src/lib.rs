//! filemanager-core -- the native half of the File Manager app.
//!
//! Rust owns the work that touches many files at once: recursive scans,
//! search, storage analysis, archives, duplicate detection and the trash.
//! Kotlin owns the UI, permissions, MediaStore and single-file operations.
//!
//! Everything marked `#[uniffi::export]` shows up as a Kotlin function in
//! `uniffi.filemanager_core`. Nothing below is called by hand from JNI --
//! the bindings are generated at build time by the `uniffi-bindgen` binary.

pub mod archive;
pub mod cancel;
pub mod categories;
pub mod dedup;
pub mod errors;
pub mod scanner;
pub mod search;
pub mod session;
pub mod storage;
pub mod trash;
pub mod types;
pub mod walk;

// Generates the FFI scaffolding for every exported item in the crate.
uniffi::setup_scaffolding!();

/// Version of the native library, so the app's About screen can prove which
/// .so it actually loaded -- useful when a stale build is cached.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Format bytes the way the UI wants them ("1.4 GB").
///
/// In Rust rather than Kotlin only so the sizes in search results, storage
/// analysis and file details cannot drift apart.
#[uniffi::export]
pub fn format_size(bytes: u64) -> String {
    const UNITS: [&str; 6] = ["B", "KB", "MB", "GB", "TB", "PB"];
    if bytes < 1024 {
        return format!("{bytes} B");
    }

    let mut size = bytes as f64;
    let mut unit = 0;
    while size >= 1024.0 && unit < UNITS.len() - 1 {
        size /= 1024.0;
        unit += 1;
    }
    // One decimal below 10 ("9.4 MB"), none above ("512 MB").
    if size < 10.0 {
        format!("{size:.1} {}", UNITS[unit])
    } else {
        format!("{size:.0} {}", UNITS[unit])
    }
}
