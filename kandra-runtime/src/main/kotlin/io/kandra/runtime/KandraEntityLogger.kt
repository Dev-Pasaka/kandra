package io.kandra.runtime

import io.kandra.core.InternalKandraApi
import io.kandra.core.schema.TableSchema
import kotlin.reflect.full.memberProperties

@InternalKandraApi
internal object KandraEntityLogger {
    fun safeToString(entity: Any, schema: TableSchema): String {
        val klass = entity::class
        return klass.memberProperties.joinToString(", ", "${klass.simpleName}(", ")") { prop ->
            val column = schema.columns.find { it.propertyName == prop.name }
                ?: schema.partitionKeys.find { it.propertyName == prop.name }
                ?: schema.clusteringKeys.find { it.propertyName == prop.name }
            val value = if (column?.isSensitive == true) "***" else prop.call(entity)
            "${prop.name}=$value"
        }
    }
}
