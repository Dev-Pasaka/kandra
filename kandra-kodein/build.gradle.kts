plugins {
    alias(libs.plugins.ksp)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    // Same rationale as kandra-koin's identical block (GH #152): bound repositories and
    // kandraKodein() itself expose kandra-core/kandra-runtime/kandra-ktor types directly.
    api(project(":kandra-core"))
    api(project(":kandra-runtime"))
    api(project(":kandra-ktor"))
    implementation(libs.datastax.driver)
    implementation(libs.ktor.server.core)
    implementation(libs.coroutines.core)
    implementation(libs.kodein.di)
    implementation(libs.kodein.ktor)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)

    // kodein-di is already a main dependency of this module, so kandra-codegen's KSP processor
    // detects Kodein on this module's own compilation classpath and generates typed accessors
    // (`*KodeinDi.kt`) for every @ScyllaTable entity declared here — including test entities,
    // which is what KandraKodeinDiAccessorsTest below exercises end-to-end against a real cluster.
    kspTest(project(":kandra-codegen"))

    testImplementation(project(":kandra-core"))
    testImplementation(project(":kandra-runtime"))
    testImplementation(project(":kandra-test"))
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.junit)
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.launcher)
}
