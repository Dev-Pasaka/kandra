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
