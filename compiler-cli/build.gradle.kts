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
