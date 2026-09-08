# ISS-065: `KandraCredentials`' auto-generated `toString()` would print the plaintext password

**Status:** Fixed

## Problem

`kandra-core/src/main/kotlin/io/kandra/core/KandraAuth.kt`:

```kotlin
data class KandraCredentials(val username: String, val password: String)
```

Kotlin `data class`es generate a `toString()` that includes every constructor property by name
and value: `KandraCredentials(username=..., password=<plaintext>)`. Kandra itself does not log
this object directly (no call site interpolates a `KandraCredentials` instance into a log
statement) — this was a latent footgun rather than an active leak.

## Impact

Any custom `KandraAuthProvider` implementation that logs its own return value for debugging, an
IDE debugger watch/expression evaluator, or a future maintainer writing
`logger.debug { "creds=$creds" }` or letting one flow into an exception's message/`toString()`
(e.g. via `KandraAuthException`) would get the plaintext password in logs or a debugger transcript
with no indication anything unusual happened — a `data class` looks safe to log.

## Fix

Kept `KandraCredentials` a `data class` (Kotlin allows overriding a data class's generated
`toString()`) and overrode `toString()` to redact the password:

```kotlin
data class KandraCredentials(val username: String, val password: String) {
    override fun toString(): String = "KandraCredentials(username=$username, password=***)"
}
```

`equals()`/`hashCode()` are left as the data class defaults — they still compare `password`, which
is correct and expected for legitimate equality checks (e.g. comparing two resolved credential
sets). Only the string representation needed redaction.

## Tests

`kandra-core/src/test/kotlin/io/kandra/core/KandraAuthTest.kt`:

- `toString()` does not contain the raw password value.
- `toString()` shows `username=...` and a `***` redaction marker, and does not contain
  `password=<raw value>`.
- `equals()` still compares password for legitimate equality checks (equal instances are equal,
  differing-password instances are not).
- `hashCode()` stays consistent with `equals()`.

`./gradlew :kandra-core:test --no-daemon` — all tests pass.

## Files

`kandra-core/src/main/kotlin/io/kandra/core/KandraAuth.kt`,
`kandra-core/src/test/kotlin/io/kandra/core/KandraAuthTest.kt` (new).
