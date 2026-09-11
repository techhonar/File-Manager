//! The single error type returned across the boundary.
//!
//! UniFFI turns this into a Kotlin sealed class `FileError`, and any function
//! returning `Result<T, FileError>` becomes a Kotlin function that throws it.

use std::io;
use std::path::Path;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FileError {
    #[error("no such file or directory: {path}")]
    NotFound { path: String },

    #[error("permission denied: {path}")]
    PermissionDenied { path: String },

    #[error("not a directory: {path}")]
    NotADirectory { path: String },

    #[error("already exists: {path}")]
    AlreadyExists { path: String },

    #[error("archive error: {message}")]
    Archive { message: String },

    #[error("operation cancelled")]
    Cancelled,

    #[error("io error: {message}")]
    Io { message: String },
}

pub type Result<T> = std::result::Result<T, FileError>;

impl FileError {
    /// Convert an `io::Error` into the boundary error, keeping the path for
    /// the message -- an error that says only "permission denied" is useless
    /// in a file manager.
    pub fn from_io(err: io::Error, path: &Path) -> Self {
        let p = path.to_string_lossy().into_owned();
        match err.kind() {
            io::ErrorKind::NotFound => FileError::NotFound { path: p },
            io::ErrorKind::PermissionDenied => FileError::PermissionDenied { path: p },
            io::ErrorKind::AlreadyExists => FileError::AlreadyExists { path: p },
            _ => FileError::Io { message: format!("{p}: {err}") },
        }
    }
}

impl From<io::Error> for FileError {
    fn from(err: io::Error) -> Self {
        FileError::Io { message: err.to_string() }
    }
}

impl From<zip::result::ZipError> for FileError {
    fn from(err: zip::result::ZipError) -> Self {
        FileError::Archive { message: err.to_string() }
    }
}
