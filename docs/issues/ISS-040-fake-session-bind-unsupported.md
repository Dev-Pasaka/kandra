# ISS-040: `FakeKandraSession` couldn't execute any prepared statement — `bind()` always threw

**Status:** Fixed

## Category

Bug (test infrastructure).

## Problem

Filed as GH #28. `FakePreparedStatement.bind()` (`kandra-test/src/main/kotlin/io/kandra/test/FakeHelpers.kt`)
unconditionally threw `UnsupportedOperationException`. Every real repository operation — `save`,
`findById`, `delete`, `update`, batch writes — routes through `StatementBuilder.prepare(cql).bind(...)`,
so calling any of these against a `KandraTestUtils.inMemory()` runtime failed immediately with
`UnsupportedOperationException`, not just the LWT/optimistic-locking paths already documented as out
of scope (see [ISS-020](ISS-020-fake-session-lwt-semantics.md)) — the entire read/write surface,
which is `kandra-test`'s stated purpose. Nothing in-repo exercised this path, since the only relevant
test file explicitly opts for real Testcontainers instead.

## Fix

`com.datastax.oss.driver.api.core.cql.BoundStatement` is an interface, not final, so it can be faked
directly. Added `FakeBoundStatement`, a thin positional-value recorder: a backing `MutableList<Any?>`
indexed positionally, with `set`/`setBytesUnsafe`/`unset` (the three methods `StatementBuilder`
actually calls) writing into it; the rest of the large `BoundStatement`/`Statement`/`Bindable`
interface surface is implemented only to satisfy the compiler and never interpreted. A `FakeUnset`
sentinel distinguishes "column left unset" (`stmt.unset(idx)`, the no-tombstone optional-column path)
from "column explicitly bound to null" (`setBytesUnsafe(idx, null)`, the tombstone-write path) —
these are semantically different CQL writes and the fake preserves the distinction instead of
collapsing both to Kotlin `null`. `boundValues(): List<Any?>` is the test-facing accessor.
`FakePreparedStatement.bind(vararg values)` now constructs and seeds a `FakeBoundStatement`.
`FakeKandraSession.execute()` now records every `Statement` handed to it (not just batches);
`capturedBatches()` remains a filtered view for existing callers.

Added `FakeKandraSessionBindTest`, exercising `repo.save()`/`findById()`/`delete()` end-to-end
against `KandraTestUtils.inMemory()` — the regression test the issue asked for, since nothing
previously caught this.

## Files

- `kandra-test/src/main/kotlin/io/kandra/test/FakeHelpers.kt`
- `kandra-test/src/main/kotlin/io/kandra/test/FakeKandraSession.kt`
- `kandra-test/src/test/kotlin/io/kandra/test/FakeKandraSessionBindTest.kt`
