plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":compiler-core"))
    testImplementation(project(":compiler-api"))
    testImplementation(project(":compiler-cli"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
