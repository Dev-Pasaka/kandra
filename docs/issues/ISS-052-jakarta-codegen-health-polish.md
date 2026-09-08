# ISS-052: Assorted polish — Jakarta validator factory reuse, codegen nullability, health endpoint debounce

**Status:** Fixed

Three small, independent polish items from GH #36 (items 1, 2, and 4). **Item 3** (`StatementBuilder.counterUpdate` overflowing on `Long.MIN_VALUE`) is deliberately **not** covered here — it's being folded into a separate, already-in-flight rewrite of `counterUpdate` on GH #27 / ISS-049, to avoid two agents editing the same function concurrently.

## Item 1: `JakartaKandraValidator` built a new `ValidatorFactory` per instance, never closed

### Problem

```kotlin
// kandra-jakarta/.../JakartaKandraValidator.kt:17-19 (before)
class JakartaKandraValidator<T : Any>(
    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator
) : KandraValidator<T> {
```

`Validation.buildDefaultValidatorFactory()` does classpath scanning and constraint metadata resolution — relatively expensive — and returns a `Closeable` `ValidatorFactory` that this default parameter discarded immediately after pulling `.validator` off it, never closing it. `KandraConfig`'s `validate<T> { }` pattern registers one `JakartaKandraValidator` per entity type, so an app validating N entity types built and leaked N separate factories instead of sharing one.

### Fix

A companion-object-level `by lazy` `ValidatorFactory`, shared by every default-constructed `JakartaKandraValidator`, regardless of the entity type parameter:

```kotlin
class JakartaKandraValidator<T : Any>(
    private val validator: Validator = sharedValidatorFactory.validator
) : KandraValidator<T> {
    ...
    companion object {
        internal val sharedValidatorFactory by lazy { Validation.buildDefaultValidatorFactory() }
    }
}
```

`Validator` instances handed out by a `ValidatorFactory` are documented by the Bean Validation spec as thread-safe and stateless, so sharing one factory (and effectively one validator) across every entity type's `JakartaKandraValidator` is safe. The factory is intentionally never explicitly closed — it's meant to live for the process lifetime, same as before; closing it would invalidate every `Validator` already handed out to other `JakartaKandraValidator` instances still holding one.

Callers who pass their own `Validator` explicitly (e.g. a custom-configured one) are unaffected — the shared factory is only used as the *default* parameter value.

### Regression test

`kandra-jakarta/src/test/kotlin/io/kandra/jakarta/JakartaKandraValidatorTest.kt`:

```kotlin
@Test
fun `default-constructed validators for different entity types share one ValidatorFactory`() {
    val factoryBefore = JakartaKandraValidator.sharedValidatorFactory
    JakartaKandraValidator<Account>()
    JakartaKandraValidator<Account>()
    data class Other(@field:NotBlank val name: String)
    JakartaKandraValidator<Other>()
    assertTrue(factoryBefore === JakartaKandraValidator.sharedValidatorFactory)
}
```

**Before/after verified:** with the source fix reverted (test kept), `kandra-jakarta:compileTestKotlin` fails outright — `sharedValidatorFactory` is unresolved, since the pre-fix code never exposed a shared factory at all. With the fix restored, `kandra-jakarta:test` passes (4/4).

## Item 2: KSP-generated column refs dropped nullability

### Problem

```kotlin
// kandra-codegen/.../KandraProcessor.kt:230-237 (before)
private fun resolveTypeName(type: KSType): String {
    val qualifiedName = type.declaration.qualifiedName?.asString() ?: "kotlin.Any"
    if (type.arguments.isEmpty()) return qualifiedName
    ...
}
```

`resolveTypeName` never consulted `type.isMarkedNullable`, so `String` and `String?` properties both generated `KandraColumnRef<kotlin.String>(...)` — the generated DSL had no compile-time null-safety distinction between nullable and non-nullable columns.

### Design question: does this need a `QueryDsl.kt` change?

`KandraColumnRef<T>` (`kandra-runtime/.../dsl/QueryDsl.kt`, read-only in this fix's scope — another agent may be touching it concurrently for GH #33) is declared as:

```kotlin
class KandraColumnRef<T>(val cqlName: String, val isLookup: Boolean = false)
```

`T` has no explicit upper bound, which in Kotlin defaults to `Any?` — so a nullable type *argument*, e.g. `KandraColumnRef<String?>`, is ordinary, valid Kotlin generic syntax; it is not a raw-type problem the way an unparameterized `KandraColumnRef` would be. That means nullability can be threaded through entirely by rendering `?` into the type argument string codegen already builds — **no change to `KandraColumnRef` or `QueryDsl.kt` was needed.**

### Fix

```kotlin
private fun resolveTypeName(type: KSType): String {
    val qualifiedName = type.declaration.qualifiedName?.asString() ?: "kotlin.Any"
    val nullabilitySuffix = if (type.isMarkedNullable) "?" else ""
    if (type.arguments.isEmpty()) return "$qualifiedName$nullabilitySuffix"
    val argNames = type.arguments.joinToString(", ") { arg ->
        arg.type?.resolve()?.let { resolveTypeName(it) } ?: "*"
    }
    return "$qualifiedName<$argNames>$nullabilitySuffix"
}
```

Because the function already recurses into each generic argument's own resolved `KSType`, nullability is checked at every nesting level for free: `List<String>?` renders `kotlin.collections.List<kotlin.String>?` (nullable container of non-null elements) and `List<String?>` renders `kotlin.collections.List<kotlin.String?>` (non-null container of nullable elements) — distinctly, and correctly.

`String` and `String?` properties now generate distinct declarations:

```kotlin
val name = KandraColumnRef<kotlin.String>("name")
val middleName = KandraColumnRef<kotlin.String?>("middle_name")
```

Note this fixes the *codegen output*; it does not yet make `QueryContext.eq`/`gt`/etc. reject `null` at compile time for non-nullable columns or require it for nullable ones — `KandraPredicate`'s `value: Any?` fields still accept anything at that layer regardless of the `KandraColumnRef<T>` type parameter's nullability, since `infix fun <T> KandraColumnRef<T>.eq(value: T)` only requires `value` to satisfy Kotlin's ordinary generic type-checking against `T` (which now correctly differs between `String` and `String?` columns). That type-checking behavior comes for free from this fix without any `QueryDsl.kt` change — `UserTable.middleName eq null` now type-checks only because `middleName`'s `T` is `String?`, while `UserTable.email eq null` (a non-nullable column) is now a compile error it previously wasn't.

### Regression test

`kandra-codegen/src/test/kotlin/io/kandra/codegen/KandraProcessorTableContentTest.kt` — extended the existing `Widget` fixture entity with `nullableTags: List<String>?` and `tagsOfNullable: List<String?>` alongside the existing `nullableName: String?`, and replaced the old test that asserted the (broken) erasure behavior with:

- `nullable property generates a nullable type argument - GH-36`
- `nullable generic container renders the nullability suffix on the outer type - GH-36`
- `generic container of a nullable element type threads nullability on the type argument - GH-36`

**Before/after verified:** with `KandraProcessor.kt` reverted (tests kept), `kandra-codegen:test` ran 14 tests, 3 failed — exactly the three nullability-specific assertions above; the other 11 (cql naming, collections, `@LookupIndex`, `@Column` renaming) were unaffected, confirming the fix is isolated to nullability rendering. With the fix restored, all 14 pass.

## Item 4: `/kandra/health` ran a live, uncached query per request

### Problem

```kotlin
// kandra-ktor/.../Kandra.kt:222-234 (before)
if (config.healthCheck) {
    application.routing {
        get("/kandra/health") {
            if (runtime.isHealthy()) { ... } else { ... }
        }
    }
}
```

`isHealthy()` executes `SELECT release_version FROM system.local` against the cluster on every single hit, unauthenticated, with no rate limiting. A misconfigured monitoring probe (or deliberate abuse, if the route is reachable outside a private network) translates 1:1 into real cluster queries.

### Fix

Added `KandraConfig.healthCheckCacheTtlMs` (default `500`) and a small, self-contained `HealthCheckCache` (`kandra-ktor/.../Kandra.kt`) that debounces the underlying probe:

```kotlin
internal class HealthCheckCache(private val ttlMillis: Long) {
    @Volatile private var cachedResult: Boolean = false
    @Volatile private var cachedAtMillis: Long = Long.MIN_VALUE
    val probeCount = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun check(probe: suspend () -> Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (cachedAtMillis != Long.MIN_VALUE && now - cachedAtMillis < ttlMillis) {
            return cachedResult
        }
        val result = probe()
        probeCount.incrementAndGet()
        cachedResult = result
        cachedAtMillis = now
        return result
    }
}
```

wired into the route:

```kotlin
val healthCheckCache = HealthCheckCache(config.healthCheckCacheTtlMs)
application.attributes.put(KandraHealthCheckCacheKey, healthCheckCache)
application.routing {
    get("/kandra/health") {
        if (healthCheckCache.check { runtime.isHealthy() }) { ... } else { ... }
    }
}
```

`healthCheckCacheTtlMs = 0` disables caching entirely (probes every request), for anyone who wants the old behavior back. `probeCount` and the cache instance itself are exposed via an `internal` `AttributeKey` (`KandraHealthCheckCacheKey`) purely so tests in this module can assert on "did a real cluster query happen" directly rather than inferring it from timing — it's not public API.

This is a best-effort debounce, not a linearizable one: two requests racing the exact TTL boundary can, in the worst case, both see a stale cache and both probe. That's an acceptable trade for a health check — correctness never suffers (the reported result is always either fresh or at most `healthCheckCacheTtlMs` old), only the debounce guarantee is best-effort, and a lock here would cost more than a rare extra probe is worth.

`KandraConfig.healthCheck`'s doc comment was also expanded to note the route is unauthenticated by design (Kandra has no application-level auth concept to gate it with) and should sit behind network-level access control if reachable beyond an orchestrator's private probe network.

### Regression test

`kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`:

- `HealthCheckCache does not re-probe within the TTL window, and does after it elapses` — unit-level, fake counting probe, no cluster needed.
- `HealthCheckCache with ttlMillis 0 probes on every call` — confirms the documented opt-out.
- `kandra health debounces repeated hits within the cache TTL and re-probes after it elapses` — end-to-end against the real Testcontainers cluster: 5 rapid `GET /kandra/health` hits assert `probeCount == 1`, then a hit after the TTL elapses asserts `probeCount == 2`.

**Before/after verified:** with `Kandra.kt`/`KandraConfig.kt` reverted (tests kept), `kandra-ktor:compileTestKotlin` fails — `HealthCheckCache`, `KandraHealthCheckCacheKey`, and `healthCheckCacheTtlMs` are all unresolved, since none of this existed pre-fix. With the fix restored, `kandra-ktor:test` passes in full against the real container (see below).

## Files

- `kandra-jakarta/src/main/kotlin/io/kandra/jakarta/JakartaKandraValidator.kt`
- `kandra-jakarta/src/test/kotlin/io/kandra/jakarta/JakartaKandraValidatorTest.kt`
- `kandra-codegen/src/main/kotlin/io/kandra/codegen/KandraProcessor.kt`
- `kandra-codegen/src/test/kotlin/io/kandra/codegen/KandraProcessorTableContentTest.kt`
- `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`
- `kandra-ktor/src/main/kotlin/io/kandra/ktor/KandraConfig.kt`
- `kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`

**Not touched (deliberately out of scope):** `kandra-runtime/src/main/kotlin/io/kandra/runtime/StatementBuilder.kt` (item 3 — `counterUpdate` overflow on `Long.MIN_VALUE` — tracked separately under GH #27 / ISS-049) and `kandra-runtime/src/main/kotlin/io/kandra/runtime/dsl/QueryDsl.kt` (read-only; item 2 did not end up needing changes there — see the design question above).
