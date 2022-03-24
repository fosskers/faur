//! Errors unique to `faur`.

#[derive(Debug)]
pub(crate) enum Error {
    Io(std::io::Error),
    Json(serde_json::Error),
}

impl From<serde_json::Error> for Error {
    fn from(v: serde_json::Error) -> Self {
        Self::Json(v)
    }
}

impl From<std::io::Error> for Error {
    fn from(v: std::io::Error) -> Self {
        Self::Io(v)
    }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Io(e) => write!(f, "{}", e),
            Error::Json(e) => write!(f, "{}", e),
        }
    }
}
