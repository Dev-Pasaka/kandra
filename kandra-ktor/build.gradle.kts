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
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.host.common)
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)

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

tasks.named<Test>("test") {
    // SslRoundTripIntegrationTest (GH #84 / ISS-076) is tagged "manual" and excluded here -- see
    // its class doc for why (slower, more Docker-environment-sensitive than the rest of this
    // module's suite). Run it explicitly via `./gradlew :kandra-ktor:sslIntegrationTest`.
    useJUnitPlatform {
        excludeTags("manual")
    }
}

tasks.register<Test>("sslIntegrationTest") {
    description = "Runs the real self-signed-cert SSL round-trip test (GH #84 / ISS-076) against a " +
        "live Testcontainers Cassandra instance configured for client-to-node encryption. Not part " +
        "of the default `test`/`check`/`build` lifecycle -- run explicitly when validating SSL wiring."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("manual")
    }
}
