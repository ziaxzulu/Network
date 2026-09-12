import java.util.Properties

plugins {
    application
}

repositories {
    maven { url = uri(".inputs/.native-deps/maven") }
    maven { url = uri("https://repo.opencollab.dev/maven-snapshots/") }
    mavenCentral()
}

val pins = Properties().apply {
    file(".inputs/native-dependencies.properties").inputStream().use { load(it) }
}

dependencies {
    // A published immutable artifact: never substitute the local improved RakNet project.
    implementation("org.cloudburstmc.netty:netty-transport-raknet:1.1.0.CR1-20260820.174333-6")
    implementation("comparison:nethernet:1")
    implementation("comparison:signalling:1")
    runtimeOnly("${pins.getProperty("nativeJavaGroup")}:libdatachannel-java:${pins.getProperty("nativeJavaVersion")}:x86_64")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.2")
}

// Match the production dependency catalog for both adapters; do not inherit
// different Netty versions from the two library POMs.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.netty") useVersion("4.2.15.Final")
    }
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(26)) } }
application {
    mainClass.set("org.cloudburstmc.netty.benchmark.ComparisonMain")
    applicationDefaultJvmArgs = listOf("-Xms1g", "-Xmx1g", "-XX:ActiveProcessorCount=4",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn")
}
tasks.test { useJUnitPlatform() }
tasks.register("runtimeClasspath") {
    dependsOn(tasks.classes, configurations.runtimeClasspath)
    doLast { println(sourceSets.main.get().runtimeClasspath.asPath) }
}
