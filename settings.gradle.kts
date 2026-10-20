plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "kandra"

include(
    "kandra-bom",
    "kandra-core",
    "kandra-runtime",
    "kandra-ktor",
    "kandra-kodein",
    "kandra-koin",
    "kandra-codegen",
    "kandra-test",
    "kandra-multidc",
    "kandra-migrate",
    "kandra-jakarta"
)

// CI compatibility matrix: `-PktorVersionOverride=<version>` builds and tests against a different
// Ktor 3.x than the one pinned in gradle/libs.versions.toml (GH #160). Unset for normal builds.
providers.gradleProperty("ktorVersionOverride").orNull?.let { override ->
    dependencyResolutionManagement {
        versionCatalogs {
            create("libs") {
                version("ktor", override)
            }
        }
    }
}
