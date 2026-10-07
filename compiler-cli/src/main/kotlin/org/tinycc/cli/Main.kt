package org.tinycc.cli

import org.tinycc.core.BuildInfo

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--version") {
        println(BuildInfo.PROJECT_NAME)
    } else {
        println("${BuildInfo.PROJECT_NAME} Kotlin/JVM bootstrap")
    }
}
