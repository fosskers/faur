# faur

A `faur` instance is a mirror of all package data on the AUR. `faur` is...

- **Simple**
  - There is only a single endpoint (i.e. no difference between `info` and `search`).
  - JSON format is identical to the AUR RPC (but failure is always an empty list).
- **Fast**
  - All data is held in memory with custom indices for near-instant lookups.
  - No rate limits.
- **Featureful**
  - Searching by "provides".
  - Searching by multiple terms at once (has "AND" semantics).
- **Small**
  - ~300 lines of Clojure.
  - No external database or other infrastructure required.

For instance, visit:

- <https://faur.fosskers.ca/packages?names=aura&by=prov>
- <https://faur.fosskers.ca/packages?names=nintendo,switch&by=desc>

### API

There is only a single endpoint: `packages`.

| Endpoint                          | Function                                                                         | Big-O Efficiency |
|-----------------------------------|----------------------------------------------------------------------------------|------------------|
| `packages?names=<TOKENS>`         | Look up `m`-many packages by name                                                | `O(mlogn)`       |
| `packages?names=<TOKEN>&by=prov`  | Find packages that satisfy `TOKEN`                                               | `O(logn)`        |
| `packages?names=<TOKENS>&by=desc` | Find packages that contain all `TOKENS` in their names / descriptions / keywords | `O(mlogn)`       |

Where multiple `TOKENS` are accepted, these are separated by commas, as in:

```
packages?names=spotify,teams,zoom
```

**Caveat:** `by=desc` is term-based, not regex based. This is for performance
reasons. So, `packages?names=aura&by=desc` will match on `aura-bin` but not on
`auralcap`.

### Running a `faur` Instance

Running a personal `faur` instance is simple. First, you'll need package data.
From the top-level of the project repo:

```sh
wget https://aur.archlinux.org/packages-meta-ext-v1.json.gz
gzip -d packages-meta-ext-v1.json.gz
```

Then simply:

```sh
clojure -M -m faur
```

This will run a local `faur` server on http://0.0.0.0:8080 . To run in TLS mode,
pass `--key` and `--cert` as well and HTTPS requests will be accepted on port
443. For example, here is how the official faur instance itself is invoked:

``` sh
clojure -M -m faur --key /etc/letsencrypt/live/faur.fosskers.ca/privkey.pem --cert /etc/letsencrypt/live/faur.fosskers.ca/fullchain.pem 
```

### Live Remote REPL

For live debugging, an [nREPL][0] server is embedded and ran on
`localhost:7888`. If running `faur` on a remote server, you can access this
nREPL remotely by first doing an SSH port-forward on your local machine:

``` sh
ssh -NL 7888:localhost:7888 root@<IP-OF-REMOTE-SERVER> -v
```

and then performing a `cider-connect-clj` (or similar), selecting
`localhost:7888` as the target. Once connected, you're free to inspect the
various Atoms or redefine functions.

[0]: https://nrepl.org/nrepl/index.html
