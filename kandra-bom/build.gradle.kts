plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api("io.kandra:kandra-core:${project.version}")
        api("io.kandra:kandra-runtime:${project.version}")
        api("io.kandra:kandra-ktor:${project.version}")
        api("io.kandra:kandra-kodein:${project.version}")
        api("io.kandra:kandra-koin:${project.version}")
        api("io.kandra:kandra-codegen:${project.version}")
        api("io.kandra:kandra-test:${project.version}")
        api("io.kandra:kandra-multidc:${project.version}")
        api("io.kandra:kandra-migrate:${project.version}")
    }
}
