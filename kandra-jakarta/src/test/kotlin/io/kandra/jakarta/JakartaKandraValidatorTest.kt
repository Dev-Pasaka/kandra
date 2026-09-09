package io.kandra.jakarta

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

data class Account(
    @field:NotBlank val email: String,
    @field:Size(min = 8) val password: String
)

class JakartaKandraValidatorTest {

    private val validator = JakartaKandraValidator<Account>()

    @Test
    fun `valid entity produces no errors`() {
        val errors = validator.validate(Account(email = "a@b.com", password = "longenough"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `blank and short fields are reported`() {
        val errors = validator.validate(Account(email = "", password = "short"))
        assertEquals(2, errors.size)
        assertTrue(errors.any { it.field == "email" })
        assertTrue(errors.any { it.field == "password" })
    }

    @Test
    fun `support detection finds Hibernate Validator on the test classpath`() {
        assertTrue(KandraJakartaSupport.isAvailable)
    }

    /**
     * GH-109 item 2: KandraJakartaSupport.isAvailable used to probe availability by building and
     * immediately closing its own throwaway ValidatorFactory via
     * Validation.buildDefaultValidatorFactory().close(), separate from
     * JakartaKandraValidator.sharedValidatorFactory — meaning classpath scanning + constraint
     * metadata resolution happened twice on cold start. The probe now goes through
     * sharedValidatorFactory directly instead of building its own factory, so a validator that
     * shares the exact instance the probe touched can be constructed successfully right after
     * (a throwaway factory the probe built and closed would instead have left a *closed* factory
     * behind if it had been reused, and `Validator.validate` would fail against it).
     */
    @Test
    fun `support probe reuses the shared ValidatorFactory instead of building a throwaway one`() {
        assertTrue(KandraJakartaSupport.isAvailable)
        val factoryAfterProbe = JakartaKandraValidator.sharedValidatorFactory

        val validator = JakartaKandraValidator<Account>()
        val errors = validator.validate(Account(email = "a@b.com", password = "longenough"))

        assertTrue(errors.isEmpty())
        assertTrue(
            factoryAfterProbe === JakartaKandraValidator.sharedValidatorFactory,
            "The probe must not have replaced or duplicated the shared factory"
        )
    }

    /**
     * GH-36 item 1: default-constructed validators for different entity types must share one
     * `ValidatorFactory` instead of each building (and leaking) their own. Before the fix, the
     * default parameter called `Validation.buildDefaultValidatorFactory()` directly in the
     * constructor, so every one of the instances below would have carried a distinct
     * `ValidatorFactory` — this asserts they're all backed by the exact same one.
     */
    @Test
    fun `default-constructed validators for different entity types share one ValidatorFactory`() {
        val factoryBefore = JakartaKandraValidator.sharedValidatorFactory

        JakartaKandraValidator<Account>()
        JakartaKandraValidator<Account>()
        data class Other(@field:NotBlank val name: String)
        JakartaKandraValidator<Other>()

        assertTrue(
            factoryBefore === JakartaKandraValidator.sharedValidatorFactory,
            "Constructing further validators must not replace or duplicate the shared factory"
        )
    }
}
