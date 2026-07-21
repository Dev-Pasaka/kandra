plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api("${project.group}:kandra-core:${project.version}")
        api("${project.group}:kandra-runtime:${project.version}")
        api("${project.group}:kandra-ktor:${project.version}")
        api("${project.group}:kandra-kodein:${project.version}")
        api("${project.group}:kandra-koin:${project.version}")
        api("${project.group}:kandra-codegen:${project.version}")
        api("${project.group}:kandra-test:${project.version}")
        api("${project.group}:kandra-multidc:${project.version}")
        api("${project.group}:kandra-migrate:${project.version}")
        api("${project.group}:kandra-jakarta:${project.version}")
    }
}
