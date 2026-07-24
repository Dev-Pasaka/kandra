---
name: kandra-kodein
description: Exhaustive API reference for kandra-kodein — auto-binding Kandra repositories into a Kodein DI container. Load when wiring Kodein DI for a Kandra-based Ktor app or injecting KandraRepository/KandraSuspendRepository via Kodein.
---

# kandra-kodein

Bridges Kandra's `KandraRepository<T>`/`KandraSuspendRepository<T>` into a [Kodein-DI](https://kosi-libs.org/kodein/)
container. Two entry points: an auto-binder that wires one repo pair per entity registered with Kandra's
`SchemaRegistry`, and a manual type-safe binder for a single entity outside the Ktor plugin. Source:
`kandra-kodein/src/main/kotlin/io/kandra/kodein/KandraKodein.kt`.

## `Application.kandraKodein()`

```kotlin
@Suppress("OPT_IN_USAGE")
fun Application.kandraKodein()
```

Extension on Ktor's `Application`. Iterates `SchemaRegistry.all()` (every entity class passed to
`register(...)` inside `install(Kandra) { }`) and, for each, opens a Kodein `di { }` block (from
`org.kodein.di.ktor.di`) and registers two `singleton` bindings, **tagged by entity simple name**:

| Binding | Tag | Scope |
|---|---|---|
| `KandraRepository<*>` | `"${EntityName}"` | `singleton` |
| `KandraSuspendRepository<*>` | `"${EntityName}Suspend"` | `singleton` |

Both bindings construct the repo with the **plugin's own** `CqlSession` (`Application.kandraSession`), the
entity's `TableSchema` from the registry, the entity's `KClass`, and — critically — the **same `BatchEngine`
instance** as the installed `Kandra` plugin (`Application.kandra.batchEngine`). This means DI-resolved repos
share the plugin's shutdown-drain guard (`isShuttingDown`) and in-flight query counter; they are not
independent instances with their own lifecycle.

If an entity class has no `simpleName` (anonymous class), throws `io.kandra.core.exception.KandraException`.

**Call order**: must run *after* `install(Kandra) { register(...) }` (needs `SchemaRegistry` populated and
`Application.kandraSession`/`Application.kandra` attributes set) and after Kodein's own `di { }` extension
is available on the application (i.e. after `install(DIFeature)` / Kodein-Ktor is set up so a `di { }` block
can be opened — `kandraKodein()` opens its own `di { }` block, it does not require you to have called `di { }`
yourself first, but the `org.kodein.di.ktor.di` machinery must be on the classpath and attached to the app).

### Example

```kotlin
import io.kandra.ktor.Kandra
import io.kandra.kodein.kandraKodein
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI

fun Application.module() {
    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        localDatacenter = "datacenter1"
        register(User::class, Wallet::class)
    }

    kandraKodein()   // after install(Kandra) — binds KandraRepository<*>/"User", "Wallet",
                      // KandraSuspendRepository<*>/"UserSuspend", "WalletSuspend"

    routing {
        get("/users/{id}") {
            val di = closestDI()
            val users by di.instance<KandraSuspendRepository<*>>(tag = "UserSuspend")
            @Suppress("UNCHECKED_CAST")
            val repo = users as KandraSuspendRepository<User>
            val user = repo.findById(call.parameters["id"]!!)
            call.respond(user ?: HttpStatusCode.NotFound)
        }
    }
}
```

Note the binding is `KandraRepository<*>`/`KandraSuspendRepository<*>` (star-projected) — Kodein has no
compile-time knowledge of the entity type parameter, so resolved instances need an unchecked cast to the
concrete `KandraRepository<User>`/`KandraSuspendRepository<User>` if you want typed access to `save`/`update`
overloads that accept `T`.

## `DI.MainBuilder.bindKandraRepository<T>(session, schema, scope)`

```kotlin
@Suppress("OPT_IN_USAGE")
@OptIn(InternalKandraApi::class)
inline fun <reified T : Any> DI.MainBuilder.bindKandraRepository(
    session: CqlSession,
    schema: TableSchema,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
)
```

Type-safe, single-entity binder for use inside a **standalone Kodein `DI { }` module built outside the Kandra
Ktor plugin** (e.g. a CLI tool, batch job, or test harness that talks to ScyllaDB without `install(Kandra)`).
Unlike `kandraKodein()`, this is `inline`/`reified` so bindings are keyed by the **actual generic type**
(`KandraRepository<T>`, `KandraSuspendRepository<T>`) — no tag, no star-projection, no unchecked cast needed
on resolution.

Internally it builds its **own** `BatchEngine(session, StatementBuilder(session), scope)` — this is *not*
shared with any Kandra plugin instance (there isn't one in this usage). If `scope` isn't supplied, it defaults
to a fresh `SupervisorJob() + Dispatchers.IO` scope that is never cancelled by Kandra — you own its lifecycle
and must cancel it yourself on shutdown, or eventual/batched writes can leak past process shutdown.

| Binding | Scope |
|---|---|
| `KandraRepository<T>` (untagged, keyed by reified `T`) | `singleton` |
| `KandraSuspendRepository<T>` (untagged, keyed by reified `T`) | `singleton` |

**Call order**: no dependency on `install(Kandra)` — this is the escape hatch for using Kandra repos *without*
the Ktor plugin at all. You must build/obtain the `CqlSession` and `TableSchema` yourself (e.g. via
`SchemaRegistry.register(User::class)` then `SchemaRegistry.all().first { it.entityClass == User::class }`,
or by constructing `TableSchema` directly).

### Example

```kotlin
import io.kandra.kodein.bindKandraRepository
import org.kodein.di.DI
import org.kodein.di.instance

val appDI = DI {
    bind<CqlSession>() with singleton { buildStandaloneSession() }

    import(DI.Module("kandra") {
        val session = instance<CqlSession>()
        SchemaRegistry.register(User::class)
        val schema = SchemaRegistry.all().first { it.entityClass == User::class }
        bindKandraRepository<User>(session, schema)
    })
}

val di by lazy { appDI }
val userRepo: KandraRepository<User> by di.instance()
val userSuspendRepo: KandraSuspendRepository<User> by di.instance()
```

## Typed accessors via kandra-codegen (since 0.4.7)

If `kandra-codegen`'s KSP processor is also applied to a module that depends on `kodein-di` (which
`kandra-kodein` itself does — see its `build.gradle.kts`), it generates a typed `DIAware` extension function
per `@ScyllaTable` entity that wraps the exact `tag = "..."` lookup `kandraKodein()` binds under — no
hand-typed tag string, no unchecked cast at the call site:

```kotlin
// generated FooKodeinDi.kt, for an entity `User`
fun DIAware.userRepo(): KandraRepository<User> = ...        // wraps tag = "User"
fun DIAware.userSuspendRepo(): KandraSuspendRepository<User> = ... // wraps tag = "UserSuspend"
```

so the routing example above becomes:

```kotlin
get("/users/{id}") {
    val repo = closestDI().userSuspendRepo()
    val user = repo.findById(call.parameters["id"]!!)
    call.respond(user ?: HttpStatusCode.NotFound)
}
```

(`closestDI()` returns a `LazyDI`, which — like every `DI` — is a `DIAware`, so the generated extension is
callable on it directly.) Internally the generated accessor goes through `DIAware.direct.instance<T>(tag)`
rather than the bare `instance<T>(tag)` you'd write for `by` delegation, since a directly-returned value is
needed for an expression-bodied function — see the `kandra-codegen` skill's "Typed Koin/Kodein DI accessors"
section for why bare `instance()` doesn't work here (it returns a `LazyDelegate<T>`, not a `T`).

Prefer the generated `fooRepo()`/`fooSuspendRepo()` over hand-typed `tag = "Foo"`/`tag = "FooSuspend"` lookups
wherever `kandra-codegen` is on the build — a typo or an entity rename now fails to *compile* instead of
throwing `DI.NotFoundException` at first resolution. This only applies to `kandraKodein()`'s bindings — there
is no generated accessor for `bindKandraRepository<T>`'s untagged reified bindings, since those need no
qualifier/tag disambiguation in the first place (already type-safe by construction).

## Gotchas

- `kandraKodein()` bindings are **tagged and star-projected** (`KandraRepository<*>` tag `"User"`); the
  `bindKandraRepository<T>` binder is **untagged and reified** (`KandraRepository<User>`). They are not
  interchangeable lookup patterns — pick the one matching how the repo was bound.
- Forgetting `Suspend` in the tag is a common typo: suspend repos are tagged `"${EntityName}Suspend"`, not
  `"${EntityName}SuspendRepository"` or `"Suspend${EntityName}"`.
- `kandraKodein()` must run after `install(Kandra)` — it reads `Application.kandraSession` and
  `Application.kandra` (the runtime), both of which are Ktor `AttributeKey`s set inside the `Kandra` plugin's
  install block. Calling it earlier throws (missing attribute).
- Repos from `kandraKodein()` share the plugin's `BatchEngine`; repos from `bindKandraRepository<T>` get their
  own `BatchEngine` and their own `CoroutineScope`. Don't assume shutdown-drain behavior configured in
  `install(Kandra) { shutdown { ... } }` applies to `bindKandraRepository<T>`-created repos — it doesn't.
- `bindKandraRepository<T>`'s default `scope` is never auto-cancelled; if you don't pass your own
  lifecycle-bound scope, cancel it explicitly on shutdown to avoid leaking eventual writes.
