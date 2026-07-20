plugins {
    kotlin("jvm") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
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
        apply(plugin = "maven-publish")

        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }

        afterEvaluate {
            configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("mavenLocal") {
                        from(components["java"])
                        groupId    = project.group.toString()
                        artifactId = project.name
                        version    = project.version.toString()
                    }
                }
            }
        }
    }

    if (name == "kandra-bom") {
        apply(plugin = "maven-publish")
        afterEvaluate {
            configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("mavenLocal") {
                        from(components["javaPlatform"])
                        groupId    = project.group.toString()
                        artifactId = project.name
                        version    = project.version.toString()
                    }
                }
            }
        }
    }
}
