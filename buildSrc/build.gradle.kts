plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Pinned to 0.35.0: 0.36.0+ requires Kotlin Gradle Plugin 2.2.0+, but this project is
    // pinned to Kotlin 2.1.21 (see gradle.properties — 2.0.0 has a parser bug on Java 25).
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.35.0")

    // Plugin marker coordinate (not the raw artifact) so Gradle recognizes this as fulfilling
    // `apply(plugin = "org.jetbrains.kotlin.jvm")` in the root build, instead of conflicting
    // with it — the publish plugin needs Kotlin JVM plugin classes on buildSrc's classloader
    // to detect it at runtime.
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.1.21")
}
