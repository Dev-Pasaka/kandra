# ISS-044: `SchemaRegistry` didn't validate several illegal annotation states at registration time

**Status:** Fixed

## Category

Bug (fail-fast violation).

## Problem

Filed as GH #30. `SchemaRegistry.buildSchema()` already did extensive eager validation (duplicate
`@PartitionKey` indices, duplicate `@LookupIndex` suffixes, mixed counter/non-counter columns, wrong
types on `@CreatedAt`/`@Version`/`@GeneratedUuid`) but missed three real, easy-to-hit
misconfigurations that only surfaced once DDL was executed against a live cluster — undermining the
module's own stated goal that "all validation happens eagerly so schema errors surface before any
query":

1. A property could be annotated both `@PartitionKey` and `@ClusteringKey` — each column's flags were
   computed independently with no mutual-exclusion check, so it landed in both `partitionKeys` and
   `clusteringKeys`, and `DdlGenerator.primaryTable()` emitted it on both sides of `PRIMARY KEY (...)`,
   which ScyllaDB rejects at `CREATE TABLE` time.
2. `@Column("")` silently produced a blank column name — the Elvis operator (`columnAnn?.name ?: ...`)
   only triggers on `null`, so an explicit empty string was used as-is, producing malformed CQL.
   `kandra-codegen`'s independent `resolveCqlName` already guarded against this, so a blank `@Column`
   name produced two *different* column names depending on whether you looked at the runtime schema
   or the generated `*Table` accessor for the same entity.
3. No identifier-format validation anywhere — `@ScyllaTable.name` and resolved `cqlName`s were spliced
   directly into DDL/DML strings with no check for illegal characters or a leading digit.

## Fix

Added a new shared `io.kandra.core.CqlNaming` object with `resolveColumnName(annotationName,
propertyName)` (uses `takeIf { it.isNotBlank() }` instead of a bare Elvis, so `@Column("")` correctly
falls back to `camelToSnake(propertyName)`) and `isValidIdentifier(name)` (non-blank, starts with a
letter/underscore, rest alphanumeric/underscore).

`SchemaRegistry.buildSchema()` now: throws `KandraSchemaException` if any column has both
`isPartitionKey == true` and `clusteringKey != null`; uses `CqlNaming.resolveColumnName` for every
column, closing the blank-name gap; and validates `@ScyllaTable.name`, every resolved column
`cqlName`, and the composed `@LookupIndex` table name (also spliced directly into DDL by
`DdlGenerator.lookupTable`) against `CqlNaming.isValidIdentifier`.

`kandra-codegen`'s `KandraProcessor.resolveCqlName` now delegates to the same `CqlNaming.resolveColumnName`
(kandra-codegen already depends on kandra-core for the annotation classes) instead of re-implementing
the blank-name fallback independently — this closes the literal root cause of #2: the runtime schema
and the generated `*Table` accessor can no longer disagree on a blank `@Column` name, since there's
only one implementation now. `SchemaRegistry.camelToSnake` delegates to `CqlNaming.camelToSnake` for
backward compatibility with existing callers/tests.

Added 6 new tests to `SchemaRegistryTest` (one per new validation, a fallback-behavior check, and a
regression guard confirming legitimate composite-key entities still register correctly).

## Files

- `kandra-core/src/main/kotlin/io/kandra/core/CqlNaming.kt` (new)
- `kandra-core/src/main/kotlin/io/kandra/core/SchemaRegistry.kt`
- `kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`
- `kandra-core/src/test/kotlin/io/kandra/core/SchemaRegistryTest.kt`
