//! Various utilities.

pub trait Apply {
    fn apply<F, U>(self, f: F) -> U
    where
        F: FnOnce(Self) -> U,
        Self: Sized;
}

impl<T> Apply for T {
    fn apply<F, U>(self, f: F) -> U
    where
        F: FnOnce(Self) -> U,
        Self: Sized,
    {
        f(self)
    }
}
