# ISS-023: `KandraCache` crashed with `IllegalAccessException` on every real-Caffeine call

**Status:** Fixed, verified live

## Problem

Found during real-cluster test plan execution (`docs/report:1.0/`, Finding #7). `KandraCache.resolveMethod`
resolved `getIfPresent`/`put`/`invalidate` via `target.javaClass.getMethod(...)`, where `target` is the
object returned by `Caffeine.newBuilder()...build()`. Caffeine's concrete cache implementations are
package-private (only the `Cache`/`LocalManualCache` interfaces are public); a `Method` obtained this
way still finds the method (it's declared public on an interface) but throws `IllegalAccessException`
on `invoke()` because the method's *resolved declaring class* isn't itself public, and `resolveMethod`
never called `setAccessible(true)`. This crashed every write to every `@CacheResult` entity the moment
a real Caffeine dependency was on the classpath — the exact scenario `@CacheResult` exists for.

## Fix

`resolveMethod` now resolves against Caffeine's public `Cache` interface
(`Class.forName("com.github.benmanes.caffeine.cache.Cache")`) instead of the instance's concrete
runtime class. The resulting `Method`'s declaring class is public, so `invoke()` no longer needs
`setAccessible(true)` to avoid the access check.

**File:** `kandra-runtime/.../cache/KandraCache.kt`.

**Verification:** live save/findById round-trip against a real Caffeine-backed `@CacheResult` entity
on a real 3-node ScyllaDB cluster — no exception, second read served from cache.
