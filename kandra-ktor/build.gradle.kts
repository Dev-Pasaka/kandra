tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    // kandra-core/kandra-runtime types (annotations, KandraAuth, repositories) and Ktor's own
    // ApplicationPlugin/Application types all appear in this module's public API (install(Kandra),
    // Application.kandra) -- api, not implementation, so a consumer depending on kandra-ktor alone
    // can compile against them without adding kandra-core/kandra-runtime itself (GH #152).
    api(project(":kandra-core"))
    api(project(":kandra-runtime"))
    api(libs.ktor.server.core)
    implementation(libs.datastax.driver)
    implementation(libs.ktor.server.host.common)
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)

    testImplementation(project(":kandra-core"))
    testImplementation(project(":kandra-runtime"))
    testImplementation(project(":kandra-test"))
    testImplementation(project(":kandra-migrate"))
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
