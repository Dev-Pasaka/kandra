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
