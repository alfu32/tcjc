import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.2.20"
}

group = "org.tinycc"
version = providers.fileContents(layout.projectDirectory.file("VERSION"))
    .asText
    .map(String::trim)
    .getOrElse("0.0.0-SNAPSHOT")

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
