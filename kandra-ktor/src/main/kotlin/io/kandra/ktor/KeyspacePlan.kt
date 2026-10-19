package io.kandra.ktor

import io.kandra.core.CqlNaming
import io.kandra.core.exception.KandraSchemaException
import io.kandra.core.schema.TableSchema

/** One keyspace Kandra bootstraps: the default keyspace and each [KandraConfig.keyspace] declaration. */
internal data class KeyspaceSpec(
    val name: String,
    val autoCreate: Boolean,
    val strategy: ReplicationStrategy,
    val isDefault: Boolean
)

private const val INVALID_ID_HINT =
    "Identifiers must start with a letter or underscore and contain only letters, digits, and underscores."

/**
 * Validates keyspace configuration before any connection is attempted (GH #65 checks, extended for
 * ISS-105) and returns the keyspaces to bootstrap, default first.
 *
 * - Default keyspace blank: still an error (unchanged message) unless extra keyspaces are declared;
 *   then it is allowed provided every entity names its keyspace (checked by [validateEntityKeyspaces]).
 * - Extra names must be valid identifiers, unique, and different from the default (case-insensitive,
 *   as unquoted CQL identifiers are).
 * - NetworkTopologyStrategy DC names are validated for every keyspace that is auto-created.
 */
internal fun resolveKeyspaceSpecs(config: KandraConfig): List<KeyspaceSpec> {
    val default = config.keyspace
    if (default.isBlank() && config.extraKeyspaces.isEmpty()) throw KandraSchemaException(
        "Kandra: 'keyspace' must be set in the plugin configuration."
    )
    if (default.isNotBlank() && !CqlNaming.isValidIdentifier(default)) throw KandraSchemaException(
        "Kandra: keyspace '$default' is not a valid CQL identifier. $INVALID_ID_HINT"
    )
    val seen = mutableSetOf<String>()
    if (default.isNotBlank()) seen.add(default.lowercase())
    config.extraKeyspaces.forEach { (name, _) ->
        if (!CqlNaming.isValidIdentifier(name)) throw KandraSchemaException(
            "Kandra: keyspace '$name' declared via keyspace(\"$name\") { } is not a valid CQL identifier. $INVALID_ID_HINT"
        )
        if (default.isNotBlank() && name.equals(default, ignoreCase = true)) throw KandraSchemaException(
            "Kandra: keyspace(\"$name\") duplicates the default keyspace '$default'. The default keyspace " +
            "is configured with 'keyspace = \"$default\"' and must not be declared again."
        )
        if (!seen.add(name.lowercase())) throw KandraSchemaException(
            "Kandra: keyspace '$name' is declared more than once via keyspace(\"$name\") { }."
        )
    }

    val specs = buildList {
        if (default.isNotBlank()) add(KeyspaceSpec(default, config.autoCreateKeyspace, config.replicationStrategy, true))
        config.extraKeyspaces.forEach { (name, ks) ->
            add(KeyspaceSpec(name, ks.autoCreate, ks.replicationStrategy ?: config.replicationStrategy, false))
        }
    }
    specs.filter { it.autoCreate }.forEach { spec ->
        val strategy = spec.strategy
        if (strategy is ReplicationStrategy.NetworkTopologyStrategy) {
            strategy.dcReplicationMap.keys.forEach { dc ->
                if (!CqlNaming.isValidIdentifier(dc)) throw KandraSchemaException(
                    "Kandra: replicationStrategy datacenter name '$dc'" +
                    (if (spec.isDefault) "" else " (keyspace '${spec.name}')") +
                    " is not a valid CQL identifier. $INVALID_ID_HINT"
                )
            }
        }
    }
    return specs
}

/**
 * After entities are registered: every entity's keyspace must be the default or a declared one, and
 * an entity without a keyspace requires a default keyspace.
 */
internal fun validateEntityKeyspaces(config: KandraConfig, schemas: List<TableSchema>) {
    val declared = config.extraKeyspaces.map { it.first }
    val known = buildList { if (config.keyspace.isNotBlank()) add(config.keyspace); addAll(declared) }
    schemas.forEach { schema ->
        val ks = schema.keyspace
        if (ks == null) {
            if (config.keyspace.isBlank()) throw KandraSchemaException(
                "Kandra: entity '${schema.entityClass.simpleName}' has no keyspace (@ScyllaTable(keyspace) is " +
                "empty) and no default keyspace is configured. Set 'keyspace = \"...\"' in install(Kandra), or " +
                "set @ScyllaTable(keyspace = ...) to one of the declared keyspaces: $known."
            )
        } else if (known.none { it.equals(ks, ignoreCase = true) }) {
            throw KandraSchemaException(
                "Kandra: entity '${schema.entityClass.simpleName}' declares @ScyllaTable(keyspace = \"$ks\"), " +
                "but keyspace '$ks' is neither the default keyspace nor declared via keyspace(\"$ks\") { }. " +
                "Declared keyspaces: $known. Add keyspace(\"$ks\") { } to install(Kandra), or fix the annotation."
            )
        }
    }
}

/** Entities that belong to [spec]: explicit keyspace match, or (for the default) no keyspace. */
internal fun schemasForKeyspace(all: List<TableSchema>, spec: KeyspaceSpec, defaultKeyspace: String): List<TableSchema> =
    all.filter { (it.keyspace ?: defaultKeyspace).equals(spec.name, ignoreCase = true) }

/** Lock-name keyspace component: none for the default keyspace (lock names unchanged from single-keyspace). */
internal fun lockKeyspaceSuffix(spec: KeyspaceSpec): String? = if (spec.isDefault) null else spec.name

/** Keyspace qualifying the `kandra_ddl_locks` table: unqualified (session keyspace) for the default. */
internal fun lockTableKeyspace(spec: KeyspaceSpec): String? = if (spec.isDefault) null else spec.name

internal fun ddlLockTableName(keyspace: String?): String =
    if (keyspace == null) "kandra_ddl_locks" else "$keyspace.kandra_ddl_locks"
