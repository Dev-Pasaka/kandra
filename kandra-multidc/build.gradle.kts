tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    api(project(":kandra-core"))
    api(project(":kandra-runtime"))
    api(project(":kandra-ktor"))
    implementation("com.datastax.oss:java-driver-core:4.17.0")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(project(":kandra-test"))
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.junit)
    testImplementation(libs.testcontainers.cassandra)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<Test>("test") {
    // The multi-DC failover suite (GH #84 / ISS-076) is tagged "manual" and excluded here -- see
    // MultiDcFailoverTest's class doc for why (a 2-container Cassandra topology with real gossip
    // convergence is much slower than this module's other tests). Run it explicitly via
    // `./gradlew :kandra-multidc:multiDcTest`.
    useJUnitPlatform {
        excludeTags("manual")
    }
}

tasks.register<Test>("multiDcTest") {
    description = "Runs the real two-datacenter Cassandra failover/load-balancing suite (GH #84 / " +
        "ISS-076) against a live Testcontainers-managed docker-compose topology. Not part of the " +
        "default `test`/`check`/`build` lifecycle -- run explicitly when validating multi-DC wiring."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("manual")
    }
}
