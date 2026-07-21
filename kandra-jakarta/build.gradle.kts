tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-opt-in=io.kandra.core.InternalKandraApi",
        "-opt-in=io.kandra.core.ExperimentalKandraApi"
    )
}

dependencies {
    implementation(project(":kandra-core"))
    implementation(project(":kandra-ktor"))
    compileOnly(libs.jakarta.validation.api)
    implementation(libs.kotlin.logging)

    testImplementation(libs.jakarta.validation.api)
    testImplementation(libs.hibernate.validator)
    testImplementation(libs.jakarta.el)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}
