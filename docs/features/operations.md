# Health check & Graceful shutdown

## Health check

When `healthCheck = true`, a route is registered:

```
GET /kandra/health
→ 200 {"status":"UP"}
→ 503 {"status":"DOWN"}
```

## Graceful shutdown

When `shutdown { graceful = true }` is set, the `ApplicationStopping` event:

1. Sets `isShuttingDown = true` on the `BatchEngine` — all new queries throw immediately.
2. Waits up to `drainTimeoutMs` for `inFlightCount` to reach zero.
3. Logs a warning if queries are still in-flight after the timeout.
4. On `ApplicationStopped`, closes the `CqlSession`.

`LookupConsistency.EVENTUAL` lookup writes (fired asynchronously after `save()`/`update()` commits)
are drained by step 2 like any other write — they're routed through the same retry/`inFlightCount`/
shutdown-gate path as synchronous queries, so they retry on transient errors and can no longer run
against an already-closed session if shutdown completes while one is in flight. A new `EVENTUAL`
write attempted after step 1 is rejected the same way, reported via
`KandraEventListener.onEventualWriteFailed`.
