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
