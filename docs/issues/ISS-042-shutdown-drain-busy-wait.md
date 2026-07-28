# ISS-042: Graceful shutdown drain busy-waited with `Thread.sleep` instead of suspending

**Status:** Fixed

## Category

Enhancement (perf).

## Problem

Filed as GH #34. The `ApplicationStopping` drain block (`kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`)
polled `runtime.inFlightCount` with a raw `Thread.sleep(50)` loop against a manually computed
deadline. `monitor.subscribe(ApplicationStopping) { ... }` registers a plain, non-suspend callback
that Ktor invokes synchronously, in registration order, on the shutdown-triggering thread — so this
busy-wait held that thread (and thus delayed every ApplicationStopping subscriber registered after
Kandra's) for up to the full `drainTimeoutMs`, serializing shutdown across plugins instead of letting
them drain concurrently. This matters for tight container shutdown grace periods shared across every
plugin's cleanup (e.g. Kubernetes' default 30s `terminationGracePeriodSeconds`).

## Fix

Replaced the loop with `runBlocking(pluginScope.coroutineContext) { withTimeoutOrNull(config.shutdown.drainTimeoutMs) { while (runtime.inFlightCount.get() > 0) delay(50) } }`,
using the plugin's existing `pluginScope` (`SupervisorJob() + Dispatchers.IO`, already scoped to
application lifetime) instead of `GlobalScope`, and `withTimeoutOrNull` instead of hand-rolled
deadline math. The shutdown-triggering thread is still occupied for the duration (Ktor 2.3.13's
`Events.raise` invokes `ApplicationStopping` handlers via a plain non-suspend `(Application) -> Unit`
with no suspend-aware hook to restructure onto instead — confirmed by reading `io.ktor.events.Events`
source) but no longer busy-waits with a raw thread sleep; the polling itself is now `delay`-based
coroutine polling under a proper timeout construct.

Added two regression tests firing `ApplicationStopping` directly via `environment.monitor.raise(...)`
against a real Testcontainers Cassandra instance: one proving the drain exits promptly once
`inFlightCount` reaches zero (not waiting the full timeout), one proving it's capped at
`drainTimeoutMs` and forces close when a query never finishes.

## Files

- `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`
- `kandra-ktor/src/test/kotlin/io/kandra/ktor/KandraPluginTest.kt`
