import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":compiler-api"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("org.tinycc.cli.MainKt")
}

distributions {
    main {
        distributionBaseName.set("tcc-jvm")
    }
}

tasks.register<Zip>("windowsPackage") {
    group = "distribution"
    description = "Package the JVM CLI and generated Windows batch launcher."
    dependsOn(tasks.named("installDist"))
    archiveBaseName.set("tcc-jvm-windows")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("install/tcc-jvm"))
}
