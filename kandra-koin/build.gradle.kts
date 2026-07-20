tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    implementation(project(":kandra-core"))
    implementation(project(":kandra-runtime"))
    implementation(project(":kandra-ktor"))
    implementation(libs.datastax.driver)
    implementation(libs.ktor.server.core)
    implementation(libs.coroutines.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.core)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)
}
