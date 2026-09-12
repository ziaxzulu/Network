rootProject.name = "transport-comparison"
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
includeBuild(".inputs") {
    dependencySubstitution {
        substitute(module("comparison:nethernet")).using(project(":transport-nethernet"))
        substitute(module("comparison:signalling")).using(project(":external-signalling"))
    }
}
