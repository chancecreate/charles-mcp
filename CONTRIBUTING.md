# Contributing

Thanks for helping! A few ground rules keep this project safe to publish:

1. **Never commit Charles binaries, decompiled Charles source, or real captured traffic.** `*.jar`, `*.har`
   (outside `fixtures/`) and Charles session files are git-ignored for a reason. Fixtures must be synthetic
   (see `scripts/fixture_server.py` and `scripts/capture_fixture.py`).
2. **Keep the bridge read-only.** Any operation that changes Charles state must go into a separate, opt-in
   module behind an explicit flag, with its own security review.
3. **Fail closed.** New Charles internals must be verified at startup (see `CharlesApi`); unknown shapes must
   disable the feature, not guess.
4. **Nothing heavy on Charles threads.** Listener code may only copy cheap metadata and `offer()` to the queue.

## Development

```bash
cd java && ./mvnw -B verify                       # Java 11+ (CI uses 11 and 21)
cd python && pip install -e ".[dev]" && pytest && ruff check src tests ../scripts
```

Tests run against clean-room test doubles and a protocol-compatible mock bridge, so they need no Charles
installation. Live tests against a real Charles are described in [docs/testing.md](docs/testing.md); please
mention in your PR which tiers you ran.

If you change the bridge wire format, update `schemas/bridge-event.v1.schema.json` (additive changes only in
v1) and `python/tests/test_schema.py`.
