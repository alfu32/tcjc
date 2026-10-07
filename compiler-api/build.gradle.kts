plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":compiler-core"))
    implementation(project(":compiler-backends"))
    implementation(project(":compiler-runtime"))
}

kotlin {
    jvmToolchain(17)
}
