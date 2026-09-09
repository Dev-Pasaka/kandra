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
    implementation(project(":kandra-core"))
    implementation(project(":kandra-runtime"))
    implementation(libs.datastax.driver)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)
    compileOnly(libs.testcontainers.cassandra)
    // ComposeContainer (org.testcontainers.containers.ComposeContainer), backing
    // KandraMultiDcTestcontainers (GH #84 / ISS-076), lives in the base `testcontainers` artifact
    // rather than the `cassandra` module above -- compileOnly here (like the cassandra module) so
    // consumers of kandra-test that never touch Testcontainers don't pull it in transitively.
    compileOnly(libs.testcontainers.core)

    testImplementation(libs.junit)
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.caffeine)
    testRuntimeOnly(libs.junit.launcher)
    kspTest(project(":kandra-codegen"))
}
