---
name: kandra-koin
description: Exhaustive API reference for kandra-koin — auto-binding Kandra repositories into a Koin DI container. Load when wiring Koin DI for a Kandra-based Ktor app or injecting KandraRepository/KandraSuspendRepository via Koin.
---

# kandra-koin

Bridges Kandra's `KandraRepository<T>`/`KandraSuspendRepository<T>` into a [Koin](https://insert-koin.io/)
container. A single entry point auto-wires one repo pair per entity registered with Kandra's
`SchemaRegistry`. Source: `kandra-koin/src/main/kotlin/io/kandra/koin/KandraKoin.kt`.

## `Application.kandraKoin()`

```kotlin
@Suppress("OPT_IN_USAGE")
fun Application.kandraKoin()
```

Extension on Ktor's `Application`. Iterates `SchemaRegistry.all()` (every entity class passed to
`register(...)` inside `install(Kandra) { }`), builds a Koin `module { }` with two `single` (eager-safe
singleton) bindings per entity, and loads it into the running Koin instance via
`getKoin().loadModules(listOf(repoModule))`.

Bindings use **named qualifiers** (`org.koin.core.qualifier.named`), not generic-type-only lookups:

| Binding | Qualifier | Scope |
|---|---|---|
| `KandraRepository<*>` (inferred as `KandraRepository<Any>`-shaped) | `named("${EntityName}Repo")` | `single` |
| `KandraSuspendRepository<*>` | `named("${EntityName}SuspendRepo")` | `single` |

e.g. for an entity `User`, the qualifiers are exactly `"UserRepo"` and `"UserSuspendRepo"`.

Both bindings construct the repo with the plugin's own `CqlSession` (`Application.kandraSession`), the
entity's `TableSchema` from the registry, the entity's `KClass`, and — critically — the **same `BatchEngine`**
as the installed `Kandra` plugin (`Application.kandra.batchEngine`). Koin-resolved repos therefore share the
plugin's shutdown-drain guard and in-flight query counter, same as the raw repos Kandra itself would hand you.

If an entity class has no `simpleName` (anonymous class), throws `io.kandra.core.exception.KandraException`.

Because Koin's generic-type resolution can't distinguish `KandraRepository<User>` from
`KandraRepository<Wallet>` at runtime (type erasure) and every entity's repo would otherwise collide on the
same raw type, **the named qualifier is mandatory** — there is no unqualified binding to fall back on.

**Call order**: must run after `install(Kandra) { register(...) }` (needs `SchemaRegistry` populated and the
`Application.kandraSession`/`Application.kandra` attributes set) **and** after Koin itself is installed on the
application (`install(Koin) { ... }` or equivalent `KoinApplication` startup) — `kandraKoin()` calls
`getKoin()`, which throws if no Koin instance is attached to the app yet.

### Example

```kotlin
import io.kandra.ktor.Kandra
import io.kandra.koin.kandraKoin
import org.koin.core.qualifier.named
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin

fun Application.module() {
    install(Koin) {
        // your own app-level Koin modules, if any
    }

    install(Kandra) {
        contactPoints = "localhost:9042"
        keyspace = "coinx"
        localDatacenter = "datacenter1"
        register(User::class, Wallet::class)
    }

    kandraKoin()   // after both install(Koin) and install(Kandra) —
                    // binds "UserRepo", "WalletRepo", "UserSuspendRepo", "WalletSuspendRepo"

    routing {
        get("/users/{id}") {
            val repo: KandraSuspendRepository<*> by inject(qualifier = named("UserSuspendRepo"))
            @Suppress("UNCHECKED_CAST")
            val userRepo = repo as KandraSuspendRepository<User>
            val user = userRepo.findById(call.parameters["id"]!!)
            call.respond(user ?: HttpStatusCode.NotFound)
        }
    }
}
```

Inside a plain route/service class (not `Application` receiver), the same lookup works via Koin's
`KoinComponent`:

```kotlin
class UserService : KoinComponent {
    private val userRepo: KandraSuspendRepository<*> by inject(named("UserSuspendRepo"))

    suspend fun getUser(id: String): User? {
        @Suppress("UNCHECKED_CAST")
        return (userRepo as KandraSuspendRepository<User>).findById(id)
    }
}
```

## Gotchas

- Qualifier suffixes are exactly `Repo` and `SuspendRepo` — **`"UserRepo"`** and **`"UserSuspendRepo"`**, not
  `"UserRepository"` / `"UserSuspendRepository"` and not `"UserSuspendRepo"` reordered as `"SuspendUserRepo"`.
  Read the qualifier off `KandraKoin.kt` rather than guessing from the class names.
- `kandraKoin()` resolves bindings as `KandraRepository<*>`/`KandraSuspendRepository<*>` at the type level —
  Koin can't recover the entity type parameter, so every `inject()`/`get()` call needs an unchecked cast to
  the concrete `KandraRepository<User>` (or similar) if you want typed `save`/`update` calls.
- `kandraKoin()` calls `getKoin()`, which requires Koin to already be installed/started on the application.
  Calling `kandraKoin()` before `install(Koin) { }` (or before `startKoin { }` in a non-Ktor bootstrap) throws.
- `kandraKoin()` also requires `install(Kandra)` to have already run — it reads `Application.kandraSession`
  and `Application.kandra`, both Ktor `AttributeKey`s populated inside the `Kandra` plugin install block.
  Order between the two installs relative to each other doesn't matter as long as `kandraKoin()` runs after
  both.
- Unlike `kandra-kodein`, there is **no manual/standalone single-entity binder** in `kandra-koin` — the only
  public entry point is `Application.kandraKoin()`, which always binds every entity in `SchemaRegistry` at
  once. If you need Koin bindings outside a Ktor `Application`, you'll have to write the `module { single(...) }`
  block yourself, following the same qualifier convention (`"${Entity}Repo"` / `"${Entity}SuspendRepo"`).
- Repos bound by `kandraKoin()` share the plugin's `BatchEngine` and thus its shutdown-drain behavior
  (`install(Kandra) { shutdown { graceful = true; drainTimeoutMs = ... } }`) — there's no separate lifecycle
  to manage, unlike a hand-rolled Koin module that constructs its own `BatchEngine`.
