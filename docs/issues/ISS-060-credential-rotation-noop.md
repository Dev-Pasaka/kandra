# ISS-060: Credential rotation config (`auth.refreshIntervalSeconds`) is a no-op — refreshed credentials are never applied to the live session

**Status:** Fixed (GH #61, PR #75)

## Problem

Filed as GH #61.

Filed from a pre-cluster-testing deep security review. `Kandra.kt:247-263` implements what looks like
credential rotation:

```kotlin
if (config.auth.refreshIntervalSeconds != null) {
    val intervalMs = config.auth.refreshIntervalSeconds!! * 1000
    pluginScope.launch {
        while (true) {
            delay(intervalMs)
            try {
                config.auth.provider.getCredentials()          // result discarded
                config.eventListener?.onCredentialRefreshed()
                logger.info { "Kandra: credentials refreshed successfully." }
            } catch (e: Exception) { ... }
        }
    }
}
```

The return value of `getCredentials()` is discarded — nothing pushes the new username/password into
the live `CqlSession`. Confirmed via grep: `withAuthCredentials(creds.username, creds.password)`
(`CqlSessionBuilder.kt:57`) is called exactly once, at session-build time; there is no
`ProgrammaticPlainTextAuthProvider.update(...)` call, no session rebuild, and no reconnect anywhere in
the module. The session keeps using the credentials it was built with at startup, indefinitely, no
matter how many times this loop "refreshes."

**Impact:** this is a security control that actively reports success (`onCredentialRefreshed()` fires,
"credentials refreshed successfully" is logged) while doing nothing. An operator who configures
`auth.refreshIntervalSeconds` specifically to limit the blast radius of a leaked or expiring credential
(a common driver for building rotation in the first place) gets no actual protection: the old
credential remains live on the connection until the process restarts, and the logs actively suggest
otherwise, making this harder to catch in an audit than if the feature didn't exist at all.

## Suggested fix direction

Either wire the refreshed `KandraCredentials` into the live session — the DataStax driver supports this
via `ProgrammaticPlainTextAuthProvider`'s `update(username, password)` method if that auth provider
class is used to build the session, or by rebuilding/reconnecting the session — or remove
`refreshIntervalSeconds`/the rotation loop and its success logging/event until it's actually
implemented, so a misconfigured expectation doesn't silently persist. Add an integration test that
changes what a fake `KandraAuthProvider` returns between two intervals and asserts the live session's
effective credentials actually change.

**Files:** `kandra-ktor/src/main/kotlin/io/kandra/ktor/Kandra.kt`,
`kandra-ktor/src/main/kotlin/io/kandra/ktor/CqlSessionBuilder.kt`.

## Resolution

Fixed in PR #75. `CqlSessionBuilder.buildCqlSession` now builds a `ProgrammaticPlainTextAuthProvider`
(instead of the simpler `withAuthCredentials(...)`) whenever the configured provider returns non-blank
initial credentials, and returns it via a new internal `CqlSessionHandle(session, liveAuthProvider)`.
The DataStax driver's `ProgrammaticPlainTextAuthProvider` exposes `setUsername`/`setPassword` (rather
than a single combined `update(...)` as originally speculated) — the credential-rotation loop in
`Kandra.kt` now calls both on every successful refresh, which the driver picks up on every subsequent
authentication (new connections, reconnects) without a session rebuild or reconnect. `
onCredentialRefreshed()`/the success log now only fire once the refreshed credentials have genuinely
been pushed into the live provider. When the session was opened without an active auth provider (the
configured provider returned a blank username at startup, e.g. against a cluster running
`AllowAllAuthenticator`), the loop now logs a clear warning on each tick instead of firing a misleading
success event, since there is no live session auth to update in that case.

Verified with a Testcontainers-backed integration test (`KandraPluginTest`) using a `KandraAuthProvider`
that returns different credentials on successive calls: after waiting for a refresh tick, the test reads
the live session's actual `ProgrammaticPlainTextAuthProvider` off `session.context.authProvider` (via
reflection, since the driver exposes no public getter for the current value) and asserts the refreshed
username is genuinely present — not just that the callback fired. A companion test confirms
`onCredentialRefreshed()` does not fire when the session has no live auth provider to update.
