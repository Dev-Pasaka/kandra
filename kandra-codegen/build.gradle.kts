plugins {
    id("com.google.devtools.ksp") version "2.1.21-2.0.1"
}

dependencies {
    implementation(project(":kandra-core"))
    implementation(libs.ksp.api)
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j.simple)
}
