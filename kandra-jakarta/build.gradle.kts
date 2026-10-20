tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    // JakartaKandraValidator implements io.kandra.core.KandraValidator directly, and
    // validateJakarta() is an extension function on io.kandra.ktor.KandraConfig -- both module's
    // types are part of this module's own public API, so both go api (GH #152).
    api(project(":kandra-core"))
    api(project(":kandra-ktor"))
    compileOnly(libs.jakarta.validation.api)
    implementation(libs.kotlin.logging)

    testImplementation(libs.jakarta.validation.api)
    testImplementation(libs.hibernate.validator)
    testImplementation(libs.jakarta.el)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}
