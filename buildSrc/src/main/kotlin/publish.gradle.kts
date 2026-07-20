plugins {
    `maven-publish`
    signing
}

// Sources JAR (required by Maven Central)
val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(project.extensions.getByType<SourceSetContainer>()["main"].allSource)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar)
            pom {
                name.set(project.name)
                description.set("Kotlin-first ScyllaDB ORM as a Ktor plugin")
                url.set("https://github.com/pasakamutuku/kandra")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("pasakamutuku")
                        name.set("Pasaka Mutuku")
                        email.set("dev.pasaka@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/pasakamutuku/kandra")
                    connection.set("scm:git:git://github.com/pasakamutuku/kandra.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sonatype"
            url = uri(
                if (version.toString().endsWith("SNAPSHOT"))
                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                else
                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            )
            credentials {
                username = providers.gradleProperty("sonatypeUsername").orNull
                password = providers.gradleProperty("sonatypePassword").orNull
            }
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["maven"])
}
