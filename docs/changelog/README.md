# Changelog

All notable changes to Kandra, one file per version — newest first.

| Version | Highlights |
|---|---|
| [0.4.3](0.4.3.md) | Fixes all 5 Critical/High bugs found by the first real-cluster test plan: codegen `Set`/`Map` codegen, cache reflection crash, empty-collection decode, clustering-key WHERE-clause omission, `KandraBatchScope` non-atomicity |
| [0.4.2](0.4.2.md) | Async read path, `findActive()`, `kandra-jakarta`, real-cluster integration tests, migration locking/checksum fixes |
| [0.3.0-SNAPSHOT](0.3.0.md) | Retry policy, `kandra-multidc`, auth/SSL, idempotency, secondary indexes |
| [0.2.0-SNAPSHOT](0.2.0.md) | Composite keys, TTL, LWT, pagination, counters, collections |
| [0.1.0-SNAPSHOT](0.1.0.md) | Initial release |

For the reasoning behind individual fixes, see [`docs/issues/`](../issues/README.md). For the
current feature surface, see [`docs/features/`](../features/README.md).
