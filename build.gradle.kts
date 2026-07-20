plugins {
    // Kotlin JVM version is pinned via buildSrc's plugin-marker dependency (see
    // buildSrc/build.gradle.kts) so the publish convention plugin can see its classes —
    // declaring it again here with a version conflicts with that.
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
    alias(libs.plugins.dokka)
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    val nonKotlinModules = setOf("kandra-bom")

    if (name !in nonKotlinModules) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jetbrains.dokka")

        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }

    apply(plugin = "publish")
}
