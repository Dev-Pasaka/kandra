plugins {
    id("com.google.devtools.ksp") version "2.1.21-2.0.1"
}

dependencies {
    implementation(project(":kandra-core"))
    implementation(libs.ksp.api)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)

    // Test-only: kandra-runtime supplies KandraRepository/KandraSuspendRepository, which the
    // generated Koin/Kodein accessor source needs to resolve when compiled in the tests below.
    // koin-core/kodein-di are ALSO test-only, deliberately never a main dependency of this module
    // (see KandraProcessor's classpath-presence detection) — their jars are located at test
    // runtime via `java.class.path` and selectively included/excluded per KotlinCompilation to
    // simulate "Koin present"/"Kodein present"/"neither present"/"both present".
    testImplementation(project(":kandra-runtime"))
    testImplementation(libs.koin.core)
    testImplementation(libs.kodein.di)
    testImplementation(libs.junit)
    testImplementation(libs.junit.params)
    testImplementation("dev.zacsweers.kctfork:core:0.7.1")
    testImplementation("dev.zacsweers.kctfork:ksp:0.7.1")
    testRuntimeOnly(libs.junit.launcher)
}
