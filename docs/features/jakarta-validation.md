# Jakarta Bean Validation (`kandra-jakarta`)

Adapts standard `jakarta.validation` constraint annotations (`@NotNull`, `@Size`, etc.) into
Kandra's validation hook, so they run automatically before every save/update — as an alternative
to (or alongside) a custom `KandraValidator<T>`.

```kotlin
data class User(
    @field:NotBlank val email: String,
    @field:Size(min = 8) val password: String,
    ...
)

install(Kandra) {
    register(User::class)
    validateJakarta<User>()
}
```

- `JakartaKandraValidator<T>` — wraps a `jakarta.validation.Validator`; `jakarta.validation-api`
  is a `compileOnly` dependency of this module, so bring your own Bean Validation implementation
  (e.g. `org.hibernate.validator:hibernate-validator`) alongside it.
- `KandraJakartaSupport.isAvailable` — detects a usable provider at runtime.
- `validateJakarta<T>()` logs a WARN and skips registration (rather than failing plugin install)
  if no provider is found on the classpath.

Added in [ISS-011](../issues/ISS-011-jakarta-bean-validation.md).
