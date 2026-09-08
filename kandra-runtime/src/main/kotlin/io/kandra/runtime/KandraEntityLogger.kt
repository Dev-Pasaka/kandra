package io.kandra.runtime

import io.kandra.core.InternalKandraApi
import io.kandra.core.schema.TableSchema
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

@InternalKandraApi
internal object KandraEntityLogger {
    fun safeToString(entity: Any, schema: TableSchema): String {
        val klass = entity::class
        val columnsByProperty = schema.reflection.columnsByProperty
        return klass.memberProperties.joinToString(", ", "${klass.simpleName}(", ")") { prop ->
            val column = columnsByProperty[prop.name]
            // isAccessible: entity classes need not be public (e.g. a private/internal nested
            // entity in a consumer module) — without this, kotlin-reflect's JVM access check
            // throws IllegalAccessException for anything less than a public top-level class.
            prop.isAccessible = true
            val value = if (column?.isSensitive == true) "***" else prop.call(entity)
            "${prop.name}=$value"
        }
    }
}
