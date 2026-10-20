# ISS-020: `FakeKandraSession` never exercises real LWT/`bind()` semantics

**Status:** Known limitation — by design, not fixed (test-infra, not a product bug)

> **Update (0.4.7):** the `FakePreparedStatement.bind()` half of the problem described below was fixed separately in [ISS-040](ISS-040-fake-session-bind-unsupported.md) (GH #28) — repository calls now run to completion under the fake. What remains by design is the lack of real LWT/`wasApplied()` semantics and stored data. The text below is the original 2026-07-21 audit finding.

## Detail

Found during a pre-real-database-testing audit (2026-07-21). `FakeKandraSession.execute()` always
returns `FakeResultSet.empty()`. Since `BatchEngine.saveIfNotExists`/optimistic-lock `update` check
`rs.one()?.getBoolean("[applied]") ?: false`, under the fake this is always `false` and
`FakePreparedStatement.bind()` unconditionally throws — meaning `saveIfNotExists`, optimistic-lock
`update()`, and anything going through the real `StatementBuilder`/`BatchEngine` pipeline (not just
batch-capture assertions) have effectively zero coverage from the 43 currently-passing unit tests.

## Why this isn't "fixed"

`FakeKandraSession`'s own doc comment says it exists for asserting *structural* behaviour (batch
composition, save/delete ordering) — "Use it to verify structural behaviour ... rather than data
round-trips." Making it simulate real LWT semantics would mean re-implementing a chunk of Cassandra
data-plane behavior in a fake, which is exactly what `KandraTestcontainers` (real Cassandra via
Testcontainers) exists to avoid needing. See [ISS-013](ISS-013-no-integration-tests.md) — the fix
here is real-database integration tests, not a smarter fake.

**Takeaway:** don't read "43 unit tests passing" as evidence that LWT/optimistic-locking/collection
mutation paths work — they are exercised for the first time by the Testcontainers suite.
