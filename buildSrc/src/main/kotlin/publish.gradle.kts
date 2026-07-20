plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    // false = stops after upload/validation; a human clicks "Release" in central.sonatype.com.
    // Flip to true once the pipeline has proven itself on a real release.
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()

    pom {
        name.set(project.name)
        description.set("Kotlin-first ScyllaDB ORM as a Ktor plugin")
        inceptionYear.set("2024")
        url.set("https://github.com/Dev-Pasaka/kandra")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
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
            url.set("https://github.com/Dev-Pasaka/kandra")
            connection.set("scm:git:git://github.com/Dev-Pasaka/kandra.git")
            developerConnection.set("scm:git:ssh://git@github.com/Dev-Pasaka/kandra.git")
        }
    }
}
