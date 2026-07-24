package io.kandra.core.schema

import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1

/**
 * The reflection surface needed to read/write an entity's fields at runtime, resolved **once**
 * per entity [kotlin.reflect.KClass] at [io.kandra.core.SchemaRegistry.register] time and cached
 * on [TableSchema.reflection].
 *
 * Before this existed, `kandra-runtime` re-resolved `entity::class.memberFunctions.find { it.name
 * == "copy" }` and `entity::class.memberProperties.associateBy { it.name }` via Kotlin reflection
 * on every single `save()`/`update()`/`delete()`/row-decode call — a linear scan repeated per call
 * instead of once per entity type. See ISS-034 / GitHub #13.
 */
data class EntityReflection(
    /** The entity's `copy(...)` function, or `null` if it has none (e.g. not a data class). */
    val copyFunction: KFunction<*>?,
    /** `copyFunction.parameters`, cached alongside it — the receiver parameter is `[0]`. */
    val copyParameters: List<KParameter>,
    /** Every member property of the entity, keyed by Kotlin property name (not CQL column name). */
    val propertiesByName: Map<String, KProperty1<*, *>>,
    /** The entity's primary constructor, or `null` if it has none. */
    val primaryConstructor: KFunction<*>?,
    /** `primaryConstructor.parameters`, cached alongside it — used by `QueryExecutor.decodeEntity`. */
    val constructorParameters: List<KParameter>
)
