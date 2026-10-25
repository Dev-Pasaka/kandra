plugins {
    // Kotlin JVM and Dokka versions are pinned via buildSrc's plugin-marker dependencies
    // (see buildSrc/build.gradle.kts) so the publish convention plugin can see their
    // classes — declaring versions again here conflicts with that.
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

apply(plugin = "org.jetbrains.dokka")

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    val nonKotlinModules = setOf("kandra-bom")

    if (name !in nonKotlinModules) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jetbrains.dokka")

        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<Test> {
            useJUnitPlatform()
            // KANDRA_TEST_BACKEND=cloud: install(Kandra) against a WAN cluster spends 30-200 s in DDL
            // (1-5 s per statement with schema agreement), past the 60 s default kotlinx-coroutines-test
            // applies to the runTest that Ktor's testApplication runs under (no parameter in Ktor 3.6).
            if (providers.environmentVariable("KANDRA_TEST_BACKEND").orNull?.lowercase() == "cloud") {
                systemProperty("kotlinx.coroutines.test.default_timeout", "10m")
            }
        }
    }

    apply(plugin = "publish")
}

// GH #179: these modules expose DataStax driver types (CqlSession, BatchStatement, ...) in their public
// API, so the driver must be an `api` dependency or consumers fail to compile against them. This guard
// fails `check` if someone flips it back to `implementation`.
val driverApiModules = setOf("kandra-runtime", "kandra-ktor", "kandra-test")
subprojects {
    if (name in driverApiModules) {
        val verifyDriverIsApi = tasks.register("verifyDriverIsApi") {
            val apiDeps = provider {
                configurations.getByName("apiElements").allDependencies.map { "${it.group}:${it.name}" }
            }
            doLast {
                check("com.datastax.oss:java-driver-core" in apiDeps.get()) {
                    "$path must declare the DataStax driver as `api` (it appears in the module's public API); found: ${apiDeps.get()}"
                }
            }
        }
        tasks.named("check") { dependsOn(verifyDriverIsApi) }
    }
}

// GH #190: kandra-test's public API exposes Testcontainers types (KandraTestcontainers.container is a
// CassandraContainer; KandraMultiDcTestcontainers uses ComposeContainer), so both modules must reach
// consumers through `api`. This guard fails `check` if either drifts back to compileOnly/implementation.
project(":kandra-test") {
    val verifyTestcontainersIsApi = tasks.register("verifyTestcontainersIsApi") {
        val apiDeps = provider {
            configurations.getByName("apiElements").allDependencies.map { "${it.group}:${it.name}" }
        }
        doLast {
            listOf("org.testcontainers:cassandra", "org.testcontainers:testcontainers").forEach { dep ->
                check(dep in apiDeps.get()) {
                    "$path must declare $dep as `api` (it appears in the module's public API); found: ${apiDeps.get()}"
                }
            }
        }
    }
    tasks.named("check") { dependsOn(verifyTestcontainersIsApi) }
}
