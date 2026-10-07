plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":compiler-core"))
}

kotlin {
    jvmToolchain(17)
}
