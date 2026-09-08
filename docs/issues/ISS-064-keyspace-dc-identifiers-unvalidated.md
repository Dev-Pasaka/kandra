# ISS-064: Keyspace and DC-name identifiers are spliced unvalidated into CQL/DDL

**Status:** Fixed (GH #65, PR #75)

## Problem

Filed as GH #65.

Filed from a pre-cluster-testing deep security review. `SchemaRegistry.buildSchema` validates every
table/column/lookup-table identifier against `CqlNaming.isValidIdentifier` before it can enter the
system (confirmed comprehensive — `SchemaRegistry.kt:65-71,102-112,138-146`) — the same class of
validation `ISS-051` added for `KandraColumnRef`'s public constructor. `config.keyspace`, however, is
only checked for `isBlank()` (`Kandra.kt:65`) before being spliced directly into CQL as both a
quasi-identifier and a string literal:

```kotlin
// Kandra.kt:72 — unquoted identifier position
bootstrapSession.execute("USE ${config.keyspace}")

// Kandra.kt:110, :155 — single-quoted string-literal position
"SELECT column_name, type FROM system_schema.columns WHERE keyspace_name = '${config.keyspace}' AND table_name = '${schema.tableName}'"
```

(`schema.tableName` here is already validated via `SchemaRegistry`, so only the `keyspace_name`
literal is the new exposure.) `builder.withKeyspace(config.keyspace)` (`CqlSessionBuilder.kt:50`) is
not part of this finding — that's the DataStax driver's own API, which validates/quotes internally.
Similarly, `ReplicationStrategy.NetworkTopologyStrategy.dcReplicationMap`'s DC-name keys are spliced
into the `CREATE KEYSPACE ... replication = {...}` literal (`CqlSessionBuilder.kt` keyspace-DDL
builder) with no identifier validation.

**Impact:** a `config.keyspace` value containing a single quote breaks out of the string literal at
lines 110/155 — genuine CQL injection into a `system_schema.columns` query (the query result only
drives internal schema-validation logic today, not user-facing output, which limits blast radius, but
the injection primitive itself is real). A value containing whitespace or a statement separator
corrupts the `USE`/`CREATE KEYSPACE` statements. Today `config.keyspace` and `dcReplicationMap` keys
are normally static, developer-chosen startup configuration, so exploitability is low in the common
case — but this is the exact same class of gap `ISS-051` closed for `KandraColumnRef`, left open here.
Any deployment that derives the keyspace from a tenant name, environment variable, or other
less-trusted source at startup (a realistic pattern for a multi-tenant "one keyspace per tenant"
architecture) inherits this immediately, with no validation layer to catch it.

## Suggested fix direction

Run `config.keyspace` (and `dcReplicationMap`'s keys) through the same `CqlNaming.isValidIdentifier`
check `SchemaRegistry` already applies to table/column names, at config-validation time (alongside the
existing `isBlank()` check at `Kandra.kt:65`), so a malformed or malicious keyspace name fails fast at
startup with a clear error rather than reaching string-interpolated CQL.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`.

## Resolution

Fixed in PR #75. `config.keyspace` and, when `autoCreateKeyspace = true`,
`ReplicationStrategy.NetworkTopologyStrategy.dcReplicationMap`'s keys are now both validated with
`CqlNaming.isValidIdentifier` at the very top of the plugin's install block, immediately after the
existing blank-check and before any connection is attempted — a malformed value throws
`KandraSchemaException` with a clear message, failing fast at startup exactly as suggested.
`keyspaceDdl` (`CqlSessionBuilder.kt`) re-validates both as defense in depth for any other caller of
that internal function. The two `system_schema.columns` queries in `AUTO_MIGRATE`/`VALIDATE` modes
(previously the string-interpolated `keyspace_name = '${config.keyspace}'` literal) now use bound `?`
parameters instead, closing the injection primitive directly in addition to the upstream identifier
check.

Verified with unit tests (`CqlSessionBuilderTest` — `keyspaceDdl`'s identifier validation and DDL
rendering for both `SimpleStrategy` and `NetworkTopologyStrategy`) and Testcontainers-backed
integration tests (`KandraPluginTest`) proving install throws `KandraSchemaException` for an invalid
keyspace name and for an invalid `NetworkTopologyStrategy` DC name, in both cases before any connection
is attempted, and that a valid keyspace name still installs and round-trips queries normally.
