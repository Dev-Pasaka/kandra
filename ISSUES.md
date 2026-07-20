# Kandra — Open Issues

Issues discovered during development that have not yet been addressed. Remove an item when it is fixed and verified.

---

## Medium priority

### ISS-007: `findActive()` for `@SoftDelete` entities is not implementable without a marker column
**File:** `kandra-runtime/…/KandraRepository.kt`, `KandraSuspendRepository.kt`
**Detail:** The 0.4.0 spec called for a `findActive()` method. Kandra's soft-delete writes non-key columns with a TTL via `UPDATE … USING TTL`. After the TTL expires, ScyllaDB removes the column values but retains the primary-key row as a tombstone until `gc_grace_seconds` passes. There is no CQL predicate to filter "rows whose TTL has expired" — ScyllaDB exposes `TTL(col)` in SELECT, but that requires knowing which specific column to check, and it returns 0 (not null) for columns with no TTL set.
**Fix:** Add a dedicated `deleted_at TIMESTAMP` or `is_deleted BOOLEAN` column to soft-deletable tables. Soft-delete writes that column; `findActive()` filters `WHERE deleted_at = null` or `WHERE is_deleted = false`. This is a schema contract change and must be opt-in.

### ISS-011: Jakarta Bean Validation (`jakarta.validation`) not auto-detected
**Detail:** The 0.4.0 spec mentioned auto-detecting Jakarta Validation annotations (`@NotNull`, `@Size`, etc.) on entity fields and running them via `Validator.validate()` before save/update. Only the custom `KandraValidator<T>` hook was implemented.
**Fix:** Add a `JakartaKandraValidator<T>` adapter class (in a separate `kandra-jakarta` module or as an optional extension) that wraps `jakarta.validation.Validator` (declared `compileOnly`). Auto-register it via reflection if Jakarta Validation is on the classpath.

---

## Low priority

### ISS-013: No integration tests against a real ScyllaDB cluster
**Detail:** All Ktor/runtime tests are `@Disabled`. The entire execution path from plugin install through `BatchEngine.save/findById/delete` is untested against a live driver. `KandraTestcontainers` exists and provides the scaffolding but no actual test cases are wired up.
**Fix:** Enable the `kandra-test` Testcontainers setup; add integration tests covering at minimum: `AUTO_CREATE`, basic CRUD, `@Version` LWT optimistic locking, `@SoftDelete`, `saveAll` auto-chunking, and graceful shutdown drain.
