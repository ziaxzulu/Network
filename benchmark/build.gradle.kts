/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

import org.gradle.api.tasks.testing.logging.TestLogEvent

description = "Transport benchmark harness"

plugins {
    application
}

val benchmarkJavaVersion = JavaLanguageVersion.of(26)

java {
    toolchain {
        languageVersion.set(benchmarkJavaVersion)
    }
}

dependencies {
    implementation(project(":transport-raknet"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.csv)

    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("org.cloudburstmc.netty.benchmark.BenchmarkMain")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(benchmarkJavaVersion.asInt())
}

fun parseBenchmarkArgs(raw: String): List<String> {
    if (raw.isBlank()) {
        return emptyList()
    }

    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false

    raw.forEach { ch ->
        when {
            escaped -> {
                current.append(ch)
                escaped = false
            }
            ch == '\\' -> escaped = true
            quote != null && ch == quote -> quote = null
            quote == null && (ch == '"' || ch == '\'') -> quote = ch
            quote == null && ch.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    args += current.toString()
                    current.setLength(0)
                }
            }
            else -> current.append(ch)
        }
    }

    if (current.isNotEmpty()) {
        args += current.toString()
    }
    return args
}

tasks.register<JavaExec>("raknetBenchmark") {
    group = "benchmark"
    description = "Runs established RakNet bandwidth-latency benchmarks. Pass arguments with -PbenchmarkArgs=\"...\"."
    mainClass.set("org.cloudburstmc.netty.benchmark.BenchmarkMain")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(benchmarkJavaVersion)
    })
    jvmArgs("-Xms1g", "-Xmx1g")
    systemProperty("benchmark.repoRoot", rootProject.projectDir.absolutePath)

    argumentProviders.add(CommandLineArgumentProvider {
        parseBenchmarkArgs(project.findProperty("benchmarkArgs")?.toString().orEmpty())
    })

    doFirst {
        systemProperty("benchmark.defaultOutputRoot", layout.buildDirectory.dir("benchmark-results").get().asFile.absolutePath)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(benchmarkJavaVersion)
    })

    testLogging {
        // Optionally pass -PshowTestLogs=true to show STDOUT/STDERR from tests
        showStandardStreams = project.findProperty("showTestLogs") == "true"
        events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}

tasks.jar {
    manifest.attributes["Automatic-Module-Name"] = "org.cloudburstmc.netty.benchmark"
}
