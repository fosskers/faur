# faur

A `faur` instance is a mirror of all package data on the AUR. `faur` is...

- **Simple**
  - There is only a single endpoint (i.e. no difference between `info` and `search`).
  - JSON format is identical to the AUR RPC (but failure is always an empty list).
  - All possible fields are always returned (i.e. no crash-inducing missing JSON fields).
- **Fast**
  - All data is held in memory with custom indices for near-instant lookups.
  - No rate limits.
- **Featureful**
  - Searching by "provides".
  - Searching by multiple terms at once (has "AND" semantics).
- **Small**
  - < 200 lines of Rust.
  - 3mb release binary.

For instance, visit:

- <https://faur.fosskers.ca/packages?names=aura&by=prov>
- <https://faur.fosskers.ca/packages?names=nintendo,switch&by=desc>

### API

There is only a single endpoint: `packages`.

| Endpoint                          | Function                                                              | Big-O Efficiency |
| --------------------------------- | --------------------------------------------------------------------- | ---------------- |
| `packages?names=<TOKENS>`         | Look up `m`-many packages by name                                     | `O(mlogn)`       |
| `packages?names=<TOKEN>&by=prov`  | Find packages that satisfy `TOKEN`                                    | `O(logn)`        |
| `packages?names=<TOKENS>&by=desc` | Find packages that contain all `TOKENS` in their names / descriptions | `O(mlogn)`       |

Where multiple `TOKENS` are accepted, these are separated by commas, as in:

```
packages?names=spotify,teams,zoom
```

**Caveat:** `by=desc` is term-based, not regex based. This is for performance
reasons. So, `packages?names=aura&by=desc` will match on `aura-bin` but not on
`auralcap`.
