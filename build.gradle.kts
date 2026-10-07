import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    base
    kotlin("jvm") version "2.2.20" apply false
}

group = "org.tinycc"
version = providers.fileContents(layout.projectDirectory.file("VERSION"))
    .asText
    .map(String::trim)
    .getOrElse("0.0.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version

    dependencyLocking {
        lockAllConfigurations()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}

val verifyKotlinStyle = tasks.register("verifyKotlinStyle") {
    group = "verification"
    description = "Reject tabs and trailing whitespace in Kotlin and Gradle Kotlin sources."

    doLast {
        val files = fileTree(rootDir) {
            include("*.kts")
            include("compiler-*/src/**/*.kt")
            exclude("**/build/**")
        }.files.sortedBy { it.path }
        val trailingWhitespace = Regex("[ \\t]+$")
        val violations = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                when {
                    '\\t' in line -> "${file.relativeTo(rootDir)}:${index + 1}: tab indentation"
                    trailingWhitespace.containsMatchIn(line) -> "${file.relativeTo(rootDir)}:${index + 1}: trailing whitespace"
                    else -> null
                }
            }
        }
        check(violations.isEmpty()) {
            "Kotlin style violations:\\n${violations.joinToString("\\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyKotlinStyle)
    dependsOn(subprojects.map { it.tasks.named("check") })
}
