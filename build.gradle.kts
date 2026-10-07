import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.util.zip.ZipFile

plugins {
    base
    kotlin("jvm") version "2.2.20" apply false
}

group = "org.tinycc"
version = providers.fileContents(layout.projectDirectory.file("VERSION"))
    .asText
    .map(String::trim)
    .getOrElse("0.0.0-SNAPSHOT")

val tcjcTarget = providers.gradleProperty("tcjc.target").orElse("x86_64-linux")
val tcjcOptimization = providers.gradleProperty("tcjc.optimization").orElse("debug")
val tcjcRuntimeLinkMode = providers.gradleProperty("tcjc.runtimeLinkMode").orElse("static")
val tcjcReproducible = providers.gradleProperty("tcjc.reproducible").orElse("true")

tasks.register("printTcjcConfiguration") {
    group = "build setup"
    description = "Print the typed Kotlin/JVM target configuration used by this build."
    doLast {
        println("target=${tcjcTarget.get()}")
        println("optimization=${tcjcOptimization.get()}")
        println("runtimeLinkMode=${tcjcRuntimeLinkMode.get()}")
        println("reproducible=${tcjcReproducible.get()}")
        println("nativeDependencies=none")
    }
}

val verifyPureKotlinArtifact = tasks.register("verifyPureKotlinArtifact") {
    group = "verification"
    description = "Ensure JVM archives do not package C or native shared-library payloads."
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "jar" } })
    doLast {
        val forbidden = listOf(".c", ".h", ".dll", ".so", ".dylib", ".a", ".o", ".obj")
        val archives = subprojects.flatMap { project ->
            project.layout.buildDirectory.dir("libs").get().asFile.listFiles()
                ?.filter { it.extension == "jar" }
                .orEmpty()
        }
        val violations = archives.flatMap { archive ->
            ZipFile(archive).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { entry -> forbidden.any { suffix -> entry.endsWith(suffix, ignoreCase = true) } }
                    .map { entry -> "${archive.name}!/$entry" }
                    .toList()
            }
        }
        check(violations.isEmpty()) {
            "pure Kotlin/JVM artifact contains forbidden native payloads:\n${violations.joinToString("\n")}"
        }
    }
}

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
        val trailingWhitespace = Regex("[ \t]+$")
        val violations = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                when {
                    '\t' in line -> "${file.relativeTo(rootDir)}:${index + 1}: tab indentation"
                    trailingWhitespace.containsMatchIn(line) -> "${file.relativeTo(rootDir)}:${index + 1}: trailing whitespace"
                    else -> null
                }
            }
        }
        check(violations.isEmpty()) {
            "Kotlin style violations:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(verifyKotlinStyle)
    dependsOn(verifyPureKotlinArtifact)
    dependsOn(subprojects.map { it.tasks.named("check") })
}
