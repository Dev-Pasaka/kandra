package io.kandra.runtime.dsl

import io.kandra.core.SchemaRegistry
import io.kandra.core.annotations.Column
import io.kandra.core.annotations.LookupIndex
import io.kandra.core.annotations.PartitionKey
import io.kandra.core.annotations.ScyllaTable
import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

@ScyllaTable("cr_widgets")
data class CrWidget(
    @PartitionKey val id: UUID,
    val displayName: String,
    @Column(name = "custom_col") val weirdPropName: String = "",
    @LookupIndex(tableSuffix = "by_email") val emailAddress: String = ""
)

/**
 * Regression coverage for GH-33: [KandraColumnRef]'s public constructor accepted any string as
 * `cqlName` with no validation, so a `KandraColumnRef` built by hand from a dynamic/user-influenced
 * value (e.g. mapping a request parameter to a column in generic admin/filtering tooling) could
 * splice an unvalidated column name straight into `QueryExecutor.buildWhere()`'s generated `WHERE`
 * clause — a second, independent CQL-injection surface from the one tracked for `raw()`/
 * `rawQuery()` under GH-32.
 *
 * The fix validates `cqlName` in [KandraColumnRef]'s `init` block against the exact same
 * [io.kandra.core.CqlNaming.isValidIdentifier] check `SchemaRegistry.buildSchema` already applies
 * to every column name resolved from an entity's properties (GH-30) — the same check
 * `kandra-codegen`'s `KandraProcessor` relies on transitively, since both delegate to
 * `CqlNaming.resolveColumnName`/`isValidIdentifier`. This suite proves: (a) legitimate identifiers
 * construct fine, (b) obviously-bad ones throw, and (c) every `cqlName` actually produced by
 * `SchemaRegistry` for a realistic entity — including a camelCase property, a `@Column` name
 * override, and a `@LookupIndex` column — still passes.
 */
class KandraColumnRefValidationTest {

    @AfterEach
    fun tearDown() {
        SchemaRegistry.clear()
    }

    // ── Valid identifiers construct fine ────────────────────────────────────

    @Test
    fun `simple lowercase identifier constructs fine`() {
        val ref = KandraColumnRef<String>("email")
        assertEquals("email", ref.cqlName)
    }

    @Test
    fun `snake_case identifier constructs fine`() {
        val ref = KandraColumnRef<String>("display_name")
        assertEquals("display_name", ref.cqlName)
    }

    @Test
    fun `identifier starting with underscore constructs fine`() {
        val ref = KandraColumnRef<String>("_internal")
        assertEquals("_internal", ref.cqlName)
    }

    @Test
    fun `identifier with digits (not leading) constructs fine`() {
        val ref = KandraColumnRef<String>("col_2fa")
        assertEquals("col_2fa", ref.cqlName)
    }

    @Test
    fun `isLookup flag does not affect validation`() {
        val ref = KandraColumnRef<String>("email", isLookup = true)
        assertEquals("email", ref.cqlName)
    }

    // ── Invalid identifiers throw ────────────────────────────────────────────

    @Test
    fun `blank name throws KandraSchemaException`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("")
        }
    }

    @Test
    fun `name starting with a digit throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("1email")
        }
    }

    @Test
    fun `name containing a space throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("email address")
        }
    }

    @Test
    fun `name containing a single quote throws`() {
        // The classic injection payload shape: closing the string literal early in a spliced
        // WHERE clause, e.g. "email = ?" -> "x' OR '1'='1 = ?".
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("x' OR '1'='1")
        }
    }

    @Test
    fun `name containing a semicolon throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("email; DROP TABLE users")
        }
    }

    @Test
    fun `name containing a CQL comment marker throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("email -- ")
        }
    }

    @Test
    fun `name containing punctuation like a dot or dash throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("email.address")
        }
        assertThrows(KandraSchemaException::class.java) {
            KandraColumnRef<String>("email-address")
        }
    }

    // ── Every cqlName kandra-codegen (via SchemaRegistry's identical CqlNaming logic)
    //    actually produces must still pass ──────────────────────────────────

    @Test
    fun `every cqlName SchemaRegistry resolves for a realistic entity passes KandraColumnRef validation`() {
        val schema = SchemaRegistry.register(CrWidget::class)

        val allCqlNames = (schema.partitionKeys + schema.columns + schema.lookupTables.map { it.indexColumn })
            .map { it.cqlName }
            .distinct()

        // Sanity check: this entity actually exercises camelCase-to-snake_case conversion, a
        // @Column name override, and a @LookupIndex column — not just the trivial partition key.
        assert(allCqlNames.contains("display_name")) { "expected camelCase->snake_case conversion in $allCqlNames" }
        assert(allCqlNames.contains("custom_col")) { "expected @Column override in $allCqlNames" }
        assert(allCqlNames.contains("email_address")) { "expected @LookupIndex column in $allCqlNames" }

        allCqlNames.forEach { cqlName ->
            // Must not throw for any name SchemaRegistry (and therefore kandra-codegen, which
            // delegates to the same io.kandra.core.CqlNaming logic) actually produces.
            val ref = KandraColumnRef<Any>(cqlName)
            assertEquals(cqlName, ref.cqlName)
        }
    }
}
