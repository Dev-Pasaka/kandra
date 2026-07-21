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
}
