import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tinycc-jvm"

include(
    ":compiler-core",
    ":compiler-backends",
    ":compiler-runtime",
    ":compiler-api",
    ":compiler-cli",
    ":compiler-tests",
)
