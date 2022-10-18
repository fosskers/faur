//! Errors.

use std::path::PathBuf;

#[derive(Debug)]
pub enum Error {
    ReadDbPath(PathBuf, std::io::Error),
    Json(serde_json::Error),
    Log(log::SetLoggerError),
}

impl From<serde_json::Error> for Error {
    fn from(v: serde_json::Error) -> Self {
        Self::Json(v)
    }
}

impl From<log::SetLoggerError> for Error {
    fn from(v: log::SetLoggerError) -> Self {
        Self::Log(v)
    }
}
