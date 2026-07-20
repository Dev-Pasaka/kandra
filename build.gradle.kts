plugins {
    // Kotlin JVM and Dokka versions are pinned via buildSrc's plugin-marker dependencies
    // (see buildSrc/build.gradle.kts) so the publish convention plugin can see their
    // classes — declaring versions again here conflicts with that.
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
}

apply(plugin = "org.jetbrains.dokka")

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
