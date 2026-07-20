package io.kandra.runtime.codec

import com.datastax.oss.driver.api.core.cql.Row
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.schema.ColumnSchema
import io.kandra.core.exception.KandraQueryException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSubclassOf

/** Sentinel returned by [KandraCodec.encode] for null nullable fields — instructs the driver to leave the column unchanged (no tombstone). */
object KandraUnset

/**
 * Converts values between Kotlin types and the DataStax Java driver's type system.
 *
 * **Null contract:** if a column is NULL in Scylla:
 * - Nullable Kotlin type → returns `null` (correct)
 * - Non-nullable Kotlin type → throws [KandraQueryException] with a clear message
 *
 * **Encode null contract:** if value is null on write:
 * - Nullable Kotlin type → returns [KandraUnset] (leave column unchanged, no tombstone)
 * - Non-nullable Kotlin type → throws [KandraQueryException]
 *
 * Custom types can be registered via [registerEncoder] and [registerDecoder].
 * A shared default instance is available via [KandraCodec.default].
 */
class KandraCodec {

    private val customEncoders = ConcurrentHashMap<KClass<*>, (Any) -> Any?>()
    private val customDecoders = ConcurrentHashMap<KClass<*>, (Row, String) -> Any?>()

    /** Registers a custom encoder: converts a Kotlin value of type [T] to a driver-compatible value. */
    @ExperimentalKandraApi
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerEncoder(klass: KClass<T>, encoder: (T) -> Any?) {
        customEncoders[klass] = encoder as (Any) -> Any?
    }

    /** Registers a custom decoder: reads a column from a [Row] and returns a Kotlin value of type [T]. */
    @ExperimentalKandraApi
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerDecoder(klass: KClass<T>, decoder: (Row, String) -> T?) {
        customDecoders[klass] = decoder as (Row, String) -> Any?
    }

    /**
     * Converts a Kotlin value to the form expected by the DataStax driver.
     *
     * Returns [KandraUnset] when [value] is null and [type] is nullable — the caller
     * should call [BoundStatement.unset] instead of binding the value, which avoids
     * writing a tombstone for columns that weren't intentionally set to null.
     */
    fun encode(value: Any?, type: KType): Any? {
        if (value == null) {
            return if (type.isMarkedNullable) KandraUnset
            else throw KandraQueryException("Non-nullable field received null value (type: $type)")
        }
        val classifier = type.classifier as? KClass<*> ?: return value
        customEncoders[classifier]?.let { return it(value) }
        return when {
            classifier.isSubclassOf(Enum::class) -> (value as Enum<*>).name
            else -> value
        }
    }

    /**
     * Reads a column from [row] using the column's metadata and returns a typed Kotlin value.
     *
     * Throws [KandraQueryException] if the column is NULL in Scylla and the Kotlin type is non-nullable.
     */
    fun decode(row: Row, column: ColumnSchema): Any? {
        val name = column.cqlName

        if (row.isNull(name)) {
            if (column.type.isMarkedNullable) return null
            throw KandraQueryException(
                "Column '${column.cqlName}' is NULL in Scylla but property '${column.propertyName}' " +
                "is non-nullable (${column.type}). Mark the property nullable or ensure the column always has a value."
            )
        }

        val type = column.type
        val classifier = type.classifier as? KClass<*> ?: return row.getObject(name)

        customDecoders[classifier]?.let { return it(row, name) }

        return when {
            classifier == UUID::class -> row.getUuid(name)
            classifier == String::class -> row.getString(name)
            classifier == Int::class -> row.getInt(name)
            classifier == Long::class -> row.getLong(name)
            classifier == Boolean::class -> row.getBoolean(name)
            classifier == Double::class -> row.getDouble(name)
            classifier == Float::class -> row.getFloat(name)
            classifier == Instant::class -> row.getInstant(name)
            classifier == LocalDate::class -> row.getLocalDate(name)
            classifier == BigDecimal::class -> row.getBigDecimal(name)
            classifier == ByteArray::class -> {
                val buf = row.getByteBuffer(name) ?: return null
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                bytes
            }
            classifier == List::class -> {
                val innerClassifier = type.arguments.firstOrNull()?.type?.classifier as? KClass<*>
                row.getList(name, innerClassifier?.java ?: Any::class.java)
            }
            classifier == Set::class -> {
                val innerClassifier = type.arguments.firstOrNull()?.type?.classifier as? KClass<*>
                row.getSet(name, innerClassifier?.java ?: Any::class.java)
            }
            classifier == Map::class -> {
                val keyClassifier = type.arguments.getOrNull(0)?.type?.classifier as? KClass<*>
                val valueClassifier = type.arguments.getOrNull(1)?.type?.classifier as? KClass<*>
                row.getMap(
                    name,
                    keyClassifier?.java ?: Any::class.java,
                    valueClassifier?.java ?: Any::class.java
                )
            }
            classifier.isSubclassOf(Enum::class) -> {
                val raw = row.getString(name) ?: return null
                @Suppress("UNCHECKED_CAST")
                java.lang.Enum.valueOf(classifier.java as Class<out Enum<*>>, raw)
            }
            else -> row.getObject(name)
        }
    }

    companion object {
        /** Shared default instance used when no custom codec is configured. */
        val default: KandraCodec = KandraCodec()
    }
}
