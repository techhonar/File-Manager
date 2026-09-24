# Test fixtures

RAR archives can only be made with RARLAB's own tools, so the tests use these
instead of building their own. All three come from the test data of the
[unrar](https://github.com/muja/unrar.rs) crate (MIT OR Apache-2.0), unchanged.

| File | Password | Holds |
| --- | --- | --- |
| `version.rar` | none | `VERSION`: `unrar-0.4.0` |
| `crypted.rar` | `unrar` | `.gitignore`, encrypted; the list of names is not |
| `comment-hpw-password.rar` | `password` | `.gitignore`, with the list of names encrypted too |
