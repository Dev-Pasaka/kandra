package io.kandra.runtime.dsl

import io.kandra.core.exception.KandraSchemaException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GH #107 (finding 3): [KandraPredicate]'s public constructors (`Eq`, `Gt`,
 * `Gte`, `Lt`, `Lte`, `In`) accepted any string as `column` with zero validation. Unlike
 * [KandraColumnRef] (hardened by GH-33 / ISS-051 with an `init { CqlNaming.isValidIdentifier(...) }`
 * check), the only thing preventing an injected column string from reaching
 * `QueryExecutor.buildWhere()` (which splices `pred.column` unparameterized) was that
 * [QueryContext.predicates] happens to be `internal` to `kandra-runtime` — an access-control
 * accident, not an invariant of the predicate type. This suite proves every [KandraPredicate]
 * subclass now applies the same [io.kandra.core.CqlNaming.isValidIdentifier] check
 * [KandraColumnRef] does, at construction, regardless of how the instance is obtained.
 */
class KandraPredicateValidationTest {

    // ── Valid identifiers construct fine, for every predicate shape ──────────

    @Test
    fun `Eq with a valid column constructs fine`() {
        val pred = KandraPredicate.Eq("email", "a@b.com")
        assertEquals("email", pred.column)
    }

    @Test
    fun `Gt Gte Lt Lte In with valid columns all construct fine`() {
        assertEquals("age", KandraPredicate.Gt("age", 18).column)
        assertEquals("age", KandraPredicate.Gte("age", 18).column)
        assertEquals("age", KandraPredicate.Lt("age", 18).column)
        assertEquals("age", KandraPredicate.Lte("age", 18).column)
        assertEquals("status", KandraPredicate.In("status", listOf("a", "b")).column)
    }

    // ── Invalid identifiers throw, for every predicate shape ─────────────────

    @Test
    fun `Eq with an injected column throws KandraSchemaException`() {
        // The classic injection payload shape: closing the generated WHERE clause early.
        assertThrows(KandraSchemaException::class.java) {
            KandraPredicate.Eq("x' OR '1'='1", "ignored")
        }
    }

    @Test
    fun `Gt with a blank column throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraPredicate.Gt("", 1)
        }
    }

    @Test
    fun `Gte with a column starting with a digit throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraPredicate.Gte("1age", 1)
        }
    }

    @Test
    fun `Lt with a column containing a semicolon throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraPredicate.Lt("age; DROP TABLE users", 1)
        }
    }

    @Test
    fun `Lte with a column containing whitespace throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraPredicate.Lte("bad column", 1)
        }
    }

    @Test
    fun `In with a column containing a CQL comment marker throws`() {
        assertThrows(KandraSchemaException::class.java) {
            KandraPredicate.In("status -- ", listOf("a"))
        }
    }

    // ── The normal DSL path (QueryContext.eq/gt/... via a validated KandraColumnRef) is unaffected ──

    @Test
    fun `QueryContext eq builds a valid predicate from a validated KandraColumnRef without throwing`() {
        val col = KandraColumnRef<String>("email")
        val ctx = QueryContext().apply { col eq "a@b.com" }
        assertEquals(1, ctx.predicates.size)
        assertEquals("email", (ctx.predicates.single() as KandraPredicate.Eq).column)
    }
}
