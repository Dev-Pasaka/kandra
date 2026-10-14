tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    // kandra-core annotations/types appear directly in this module's own public API
    // (TableSchema, repositories' constructor params, etc.) -- api, not implementation,
    // so a consumer depending on kandra-runtime alone can compile against them (GH #152).
    api(project(":kandra-core"))
    implementation(libs.datastax.driver)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.jdk8)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)

    compileOnly(libs.caffeine)

    testImplementation(project(":kandra-core"))
    testImplementation(libs.junit)
    testImplementation(libs.junit.params)
    testImplementation(libs.caffeine)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<Test>("test") {
    // SoftDeleteMarkerTtlLiveClusterTest (GH #153/#154) is tagged "manual" and excluded here --
    // it connects directly to an already-running real cluster on localhost:9042 rather than a
    // Testcontainers-managed one, so it isn't portable to CI. Same convention as kandra-ktor's
    // SslRoundTripIntegrationTest. Run it explicitly via
    // `./gradlew :kandra-runtime:softDeleteLiveClusterTest`.
    useJUnitPlatform {
        excludeTags("manual")
    }
}

tasks.register<Test>("softDeleteLiveClusterTest") {
    description = "Runs SoftDeleteMarkerTtlLiveClusterTest (GH #153/#154) against a real cluster " +
        "at localhost:9042 -- not part of the default test/check/build lifecycle."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("manual")
    }
}
