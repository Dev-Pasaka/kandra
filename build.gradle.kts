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
