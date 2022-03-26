//! Errors unique to `faur`.

#[derive(Debug)]
pub(crate) enum Error {
    Io(std::io::Error),
    Yaml(serde_yaml::Error),
}

impl From<serde_yaml::Error> for Error {
    fn from(v: serde_yaml::Error) -> Self {
        Self::Yaml(v)
    }
}

impl From<std::io::Error> for Error {
    fn from(v: std::io::Error) -> Self {
        Self::Io(v)
    }
}
