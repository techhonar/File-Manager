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

    // NB: the field is `detail`, not `message`. UniFFI maps each variant to a
    // Kotlin class extending Exception and emits its own `override val
    // message`, so a variant field called `message` collides with it and the
    // generated Kotlin does not compile.
    #[error("archive error: {detail}")]
    Archive { detail: String },

    #[error("this archive is protected")]
    PasswordRequired,

    #[error("wrong password")]
    WrongPassword,

    #[error("operation cancelled")]
    Cancelled,

    /// A folder copied or moved into itself or one of its own subfolders.
    ///
    /// Its own variant rather than an Io string, because it is the user's
    /// mistake to correct rather than the filesystem's failure to report, and
    /// the message for it should say which.
    #[error("cannot copy a folder into itself: {path}")]
    IntoItself { path: String },

    /// Storage mounted read-only: a USB drive Android could only mount that
    /// way, a write-protected card, a file system it can read but not write.
    #[error("read-only file system: {path}")]
    ReadOnly { path: String },

    /// No room left on the storage being written to.
    #[error("no space left: {path}")]
    NoSpace { path: String },

    /// A file too big for the storage it is going to, as anything of 4 GB or
    /// more is for a FAT32 drive.
    #[error("file too large: {path}")]
    TooLarge { path: String },

    #[error("io error: {detail}")]
    Io { detail: String },
}

pub type Result<T> = std::result::Result<T, FileError>;

impl FileError {
    /// Convert an `io::Error` into the boundary error, keeping the path for
    /// the message -- an error that says only "permission denied" is useless
    /// in a file manager.
    pub fn from_io(err: io::Error, path: &Path) -> Self {
        let p = path.to_string_lossy().into_owned();
        // By number: these are what a USB drive or card says when it is full,
        // read-only or FAT32, and each needs its own words, not "io error".
        match err.raw_os_error() {
            Some(libc::EROFS) => return FileError::ReadOnly { path: p },
            Some(libc::ENOSPC) | Some(libc::EDQUOT) => return FileError::NoSpace { path: p },
            Some(libc::EFBIG) => return FileError::TooLarge { path: p },
            _ => {}
        }
        match err.kind() {
            io::ErrorKind::NotFound => FileError::NotFound { path: p },
            io::ErrorKind::PermissionDenied => FileError::PermissionDenied { path: p },
            io::ErrorKind::AlreadyExists => FileError::AlreadyExists { path: p },
            _ => FileError::Io { detail: format!("{p}: {err}") },
        }
    }
}

impl From<io::Error> for FileError {
    fn from(err: io::Error) -> Self {
        FileError::Io { detail: err.to_string() }
    }
}

impl From<zip::result::ZipError> for FileError {
    fn from(err: zip::result::ZipError) -> Self {
        // A bad password has to be distinguishable from a corrupt archive, or
        // the UI cannot tell the user to try again rather than give up.
        match err {
            zip::result::ZipError::InvalidPassword => FileError::WrongPassword,
            other => FileError::Archive { detail: other.to_string() },
        }
    }
}
