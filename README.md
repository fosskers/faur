# faur

A `faur` instance is a mirror of all package data on the AUR. `faur` is...

- **Simple**
  - There is only a single endpoint (i.e. no difference between `info` and `search`).
  - JSON format is identical to the AUR RPC.
  - All possible fields are always returned (i.e. no crash-inducing missing JSON fields).
- **Fast**
  - All data is held in memory with custom indices for near-instant lookups.
  - No rate limits.
- **Featureful**
  - Searching by "provides".
  - Searching by multiple terms at once.
- **Small**
  - < 200 lines of Rust.
  - 3mb release binary.

For instance, visit: <https://faur.fosskers.ca/packages?names=aura&by=prov>

### API

There is only a single endpoint: `packages`.

| Endpoint                          | Function                                              | Big-O Efficiency |
| --------------------------------- | ----------------------------------------------------- | ---------------- |
| `packages?names=<TOKENS>`         | Look up packages by name                              | `O(logn)`        |
| `packages?names=<TOKEN>&by=prov`  | Find packages that satisfy `TOKEN`                    | `O(logn)`        |
| `packages?names=<TOKENS>&by=desc` | Search for `TOKENS` in package names and descriptions | `O(n)`           |

Where multiple `TOKENS` are accepted, these are separated by commas, as in:

```
packages?names=spotify,teams,zoom
```
