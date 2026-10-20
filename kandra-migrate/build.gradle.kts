tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    // KandraMigrationRunner's constructor takes a CqlSession directly, and it throws
    // io.kandra.core.exception.KandraMigrationException/KandraSchemaException -- both appear in
    // this module's own public API, so both go api, not implementation (GH #152).
    api(project(":kandra-core"))
    api(libs.datastax.driver)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(project(":kandra-test"))
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.launcher)
}
