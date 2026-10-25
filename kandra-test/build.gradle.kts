plugins {
    alias(libs.plugins.ksp)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    // KandraRuntimeHandle/KandraTestUtils/FakeKandraSession's public surface directly returns
    // kandra-core/kandra-runtime types (TableSchema, repositories, CqlSession) -- api, not
    // implementation (GH #152).
    api(project(":kandra-core"))
    api(project(":kandra-runtime"))
    api(libs.datastax.driver)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)
    compileOnly(libs.junit) // KandraCloud.assumeConfigured() (JUnit assumptions)
    // GH #190: KandraTestcontainers.container is a public CassandraContainer, and
    // KandraMultiDcTestcontainers (GH #84 / ISS-076) is built on ComposeContainer from the base
    // `testcontainers` artifact. Both were compileOnly, so calling KandraTestcontainers.freshKeyspace()
    // without hand-adding org.testcontainers:cassandra failed with NoClassDefFoundError. kandra-test is
    // a test-scope artifact anyway, so ship both as `api` at the version it was built against.
    api(libs.testcontainers.cassandra)
    api(libs.testcontainers.core)

    testImplementation(libs.junit)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.caffeine)
    testRuntimeOnly(libs.junit.launcher)
    kspTest(project(":kandra-codegen"))
}
