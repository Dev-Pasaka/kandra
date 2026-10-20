---
name: kandra-jakarta
description: API reference for kandra-jakarta — running Jakarta Bean Validation constraints (@NotBlank, @Size, ...) on Kandra entities before every save/update via validateJakarta<T>(). Load when adding Bean Validation to a Kandra entity or debugging validation that silently doesn't run.
---

# kandra-jakarta

Adapts `jakarta.validation` into Kandra's `KandraValidator<T>` hook. Sources:
`kandra-jakarta/src/main/kotlin/io/kandra/jakarta/` (`JakartaKandraValidator.kt`, `KandraConfigJakarta.kt`).

## Dependencies

```kotlin
implementation("com.kandraorm.kandra:kandra-jakarta")
implementation("org.hibernate.validator:hibernate-validator:<version>")   // a provider is REQUIRED
implementation("org.glassfish:jakarta.el:<version>")             // jakarta.el, needed by Hibernate Validator
```

`jakarta.validation-api` is `compileOnly` in this module: you must bring the API **and** an implementation.
`kandra-core` and `kandra-ktor` come transitively (`api`).

## Usage

```kotlin
data class User(
    @PartitionKey val userId: UUID,
    @field:NotBlank val email: String,     // use-site target @field: — see pitfalls
    @field:Size(min = 8) val password: String,
)

install(Kandra) {
    register(User::class)
    validateJakarta<User>()
}
```

```kotlin
inline fun <reified T : Any> KandraConfig.validateJakarta()
fun <T : Any> KandraConfig.validateJakarta(klass: KClass<T>)
```

Both delegate to `validate(klass, JakartaKandraValidator())`. Violations become
`KandraValidationError(propertyPath, message)` and surface as `KandraValidationException(errors)`.

## Classes

- `JakartaKandraValidator<T : Any>(validator: Validator = sharedValidatorFactory.validator) : KandraValidator<T>` —
  `validate(entity)` maps each `ConstraintViolation` to `KandraValidationError(propertyPath.toString(), message)`.
  Pass your own `Validator` for custom message interpolation or groups config.
- `KandraJakartaSupport.isAvailable: Boolean` (lazy) — true if a provider can be resolved.
- The `ValidatorFactory` is one process-wide lazy instance (never closed); sharing it across entity validators is safe.

## Pitfalls

- **No provider on classpath → silent skip.** `validateJakarta` logs a WARN and returns without registering; install does
  not fail, so entities are saved unvalidated. Check logs if validation never fires.
- **Kotlin use-site targets.** Constructor-property annotations default to the *parameter*, which Bean Validation does not
  see. Write `@field:NotBlank` (or `@get:`), not bare `@NotBlank`.
- Must be called per entity type; there is no "validate everything" switch.
- `validate(klass, v)` stores one validator per entity class (`validators[klass] = validator`), so `validateJakarta<T>()` and a
  hand-written `validate<T> { }` for the same `T` **replace** each other — the last call wins. Do the extra checks in a custom validator.
- Validation runs on save/update paths, not on reads.
