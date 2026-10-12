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
