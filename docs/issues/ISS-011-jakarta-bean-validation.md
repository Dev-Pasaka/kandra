# ISS-011: Jakarta Bean Validation (`jakarta.validation`) not auto-detected

**Status:** Fixed

## Problem

The 0.4.0 spec mentioned auto-detecting Jakarta Validation annotations (`@NotNull`, `@Size`, etc.)
on entity fields and running them via `Validator.validate()` before save/update. Only the custom
`KandraValidator<T>` hook was implemented.

## Fix

Added a new **`kandra-jakarta`** module:

- `JakartaKandraValidator<T>` — adapts a `jakarta.validation.Validator` into `KandraValidator<T>`,
  translating `ConstraintViolation`s into `KandraValidationError`s. `jakarta.validation-api` is a
  `compileOnly` dependency of the module — bring your own Bean Validation implementation (e.g.
  Hibernate Validator) alongside it.
- `KandraJakartaSupport.isAvailable` — detects whether a usable provider is resolvable at runtime.
- `KandraConfig.validateJakarta<T>()` extension — registers the adapter from inside
  `install(Kandra) { ... }`; logs a WARN and skips registration (rather than failing plugin
  install) if no provider is found on the classpath:

```kotlin
install(Kandra) {
    register(User::class)
    validateJakarta<User>()
}
```

Covered by `JakartaKandraValidatorTest` using Hibernate Validator as the test-time provider.

**Files:** `kandra-jakarta/` (new module — `JakartaKandraValidator.kt`, `KandraConfigJakarta.kt`);
`settings.gradle.kts`, `kandra-bom/build.gradle.kts`, `gradle/libs.versions.toml` updated to wire
the module in.
