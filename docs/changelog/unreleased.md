# [Unreleased] — next: 0.5.2

Fixes for five bugs reported while building the Circaso backend (#187–#191), a migration-runner startup speed-up (#194) and a soft-delete
encoding fix (#198). Drop-in for 0.5.1 except for one behavior change to versioned `update()`, called out under **Changed**.

### Fixed

- **`@LookupIndex` on a key column (#187, PR #195).** A `@LookupIndex` on a `@ClusteringKey` or `@PartitionKey` property generated a lookup table
  that declared the indexed column twice (`Duplicate column 'request_id' declaration`), and the lookup INSERT named it twice. The lookup table now holds
  the indexed column once plus the other primary-key columns. Entities with a lookup on a regular column generate exactly the same DDL as before.
- **Reserved words as column names (#188, PR #200).** A column named after a reserved CQL word (`token`, `order`, `from`, `default`, …) made `CREATE TABLE`
  fail with `no viable alternative at input`. Kandra now double-quotes those names, in lowercase, everywhere it generates CQL: DDL, INSERT/UPDATE/DELETE/SELECT,
  lookup and index statements, and query-DSL predicates. The quoted set is measured: every keyword of the CQL grammar was tried as a bare column against
  Cassandra 4.1 and ScyllaDB 2026.1, and only the ones that fail are quoted. Unreserved words such as `timestamp`, `type`, `key`, `ttl` or `date` stay bare,
  so DDL (and the schema fingerprint) of every other entity is byte-for-byte unchanged. A reserved `@ScyllaTable` name or keyspace is rejected at registration
  with a clear message. On 0.5.1, rename the column with `@Column(name = "...")`.
- **Clearing a column in a batch or an update (#189, PR #196).** New `saveWithNullsInBatch` / `saveWithNullsInBatchAndGet` in `batch { }` and
  `batchBlocking { }`, and `updateWithNulls` / `updateWithNullsAndGet` on both repository types. A `null` property is written as `NULL` (a tombstone)
  instead of being left UNSET, so a column can be cleared atomically with other writes or through a version-checked update.
- **Migration checksum and source layout (#191, PR #193).** A migration whose `up()` calls an inline function (`forEach`, `map`, `use`, …) carries
  Kotlin's inline source map (SMAP, with line numbers and the file's line count) in the `@kotlin.jvm.internal.SourceDebugExtension` annotation's string
  constants. The checksum kept those, so reformatting the file, or adding a line anywhere in it, failed an already-applied migration. The normalizer now
  drops every string that annotation references. **Upgrading is automatic:** a row applied by 0.5.1 or earlier that matches the old checksum is accepted
  and rewritten to the new one. If a migration's lines already moved before you upgrade, update its row once
  (`UPDATE kandra_migrations SET checksum = ? WHERE version = ?`) after checking the CQL is unchanged.
- **`kandra-test` and Testcontainers (#190, PR #192).** `org.testcontainers:cassandra` and `org.testcontainers:testcontainers` are now `api` dependencies
  of `kandra-test`, so `KandraTestcontainers.freshKeyspace()` no longer fails with `NoClassDefFoundError` unless you add the Cassandra module by hand.
  A `verifyTestcontainersIsApi` task in `check` guards it.
- **Soft delete encoding (#198, PR #201).** `delete()` on a `@SoftDelete` entity bound raw property values, skipping `KandraCodec`, so an enum or custom-typed column
  failed with a driver codec error. Values now go through the codec like every other write, and a `null` column is left UNSET.

### Changed

- **Versioned `update()` leaves a `null` column UNSET (#189).** On a `@Version` entity, `update()`/`updateAndGet()` used to bind `NULL` for every null
  non-key column, writing a tombstone per null column on every update — unlike `save()` and a plain `update()`, which leave it UNSET. It now leaves it
  UNSET too. **If you relied on a versioned `update()` to clear a column, call `updateWithNulls()` instead.**

### Performance

- **Migration runner startup (#194, PR #197).** Constructing a `KandraMigrationRunner` against an already-bootstrapped keyspace now does one plain read
  instead of `CREATE TABLE IF NOT EXISTS` plus a losing `IF NOT EXISTS` LWT (Paxos). The migrations jar is indexed once per JVM instead of scanned in full
  once per migration, each checksum is computed once per `run()`, and the runner prepares its statements once. Stored checksums are unchanged.
- Further runtime findings from the same review are tracked in #199.
