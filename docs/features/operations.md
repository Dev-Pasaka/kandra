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
