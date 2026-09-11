//! Cancellation for long-running scans.
//!
//! A `uniffi::Object` becomes a real Kotlin class with a lifetime -- unlike a
//! Record, it is passed by reference, so Kotlin can hold one, hand it to a
//! scan, and call `cancel()` from another thread when the user leaves the
//! screen. Without this a search over a 128 GB card keeps burning CPU after
//! the UI is gone.

use crate::errors::{FileError, Result};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

#[derive(Debug, Default, uniffi::Object)]
pub struct CancelToken {
    cancelled: AtomicBool,
}

#[uniffi::export]
impl CancelToken {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(CancelToken::default())
    }

    /// Safe to call from any thread, including while a scan is running.
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
}

impl CancelToken {
    /// Internal helper: bail out of a walk with `FileError::Cancelled`.
    pub(crate) fn check(&self) -> Result<()> {
        if self.is_cancelled() {
            Err(FileError::Cancelled)
        } else {
            Ok(())
        }
    }
}

/// Progress reporting for operations that take long enough to need a bar.
///
/// `with_foreign` means Kotlin can implement this trait and pass an instance
/// down into Rust -- the calls go back up into the JVM.
#[uniffi::export(with_foreign)]
pub trait ProgressListener: Send + Sync {
    /// `done`/`total` are in the operation's natural unit (files, or bytes for
    /// archives). `total` is 0 when it isn't known up front.
    fn on_progress(&self, done: u64, total: u64, current_path: String);
}
