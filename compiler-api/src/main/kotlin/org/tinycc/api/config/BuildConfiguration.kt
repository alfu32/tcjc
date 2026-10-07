package org.tinycc.api.config

import java.nio.file.Path
import java.io.File
import org.tinycc.runtime.RuntimeLinkMode

enum class BuildOptimization { DEBUG, RELEASE }

/** Typed replacement for configure/Make variables used by Gradle and the embedding API. */
data class KotlinBuildConfiguration(
    val target: String = "x86_64-linux",
    val optimization: BuildOptimization = BuildOptimization.DEBUG,
    val installPrefix: Path = Path.of("build/install/tcc-jvm"),
    val outputDirectory: Path = Path.of("build/tcjc"),
    val sysroot: Path? = null,
    val includePaths: List<Path> = emptyList(),
    val libraryPaths: List<Path> = emptyList(),
    val runtimeLinkMode: RuntimeLinkMode = RuntimeLinkMode.STATIC,
    val reproducible: Boolean = true,
) {
    init {
        require(TARGET.matches(target)) { "invalid target triple: $target" }
        require(installPrefix.isAbsolute || installPrefix.toString().isNotEmpty()) { "install prefix must not be empty" }
        require(outputDirectory.isAbsolute || outputDirectory.toString().isNotEmpty()) { "output directory must not be empty" }
    }

    fun resolved(baseDirectory: Path): KotlinBuildConfiguration = copy(
        installPrefix = baseDirectory.resolve(installPrefix).normalize(),
        outputDirectory = baseDirectory.resolve(outputDirectory).normalize(),
        sysroot = sysroot?.let { baseDirectory.resolve(it).normalize() },
        includePaths = includePaths.map { baseDirectory.resolve(it).normalize() },
        libraryPaths = libraryPaths.map { baseDirectory.resolve(it).normalize() },
    )

    companion object {
        private val TARGET = Regex("^[A-Za-z0-9_+.-]+$")

        fun fromProperties(
            properties: Map<String, String>,
            baseDirectory: Path = Path.of(".").toAbsolutePath().normalize(),
        ): KotlinBuildConfiguration {
            val value = { key: String, default: String -> properties[key]?.takeIf(String::isNotBlank) ?: default }
            val paths = { key: String ->
                val separators = if (File.pathSeparatorChar == ';') arrayOf(";", ":") else arrayOf(":", ";")
                value(key, "").split(*separators)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map(Path::of)
            }
            return KotlinBuildConfiguration(
                target = value("tcjc.target", "x86_64-linux"),
                optimization = when (value("tcjc.optimization", "debug").lowercase()) {
                    "debug" -> BuildOptimization.DEBUG
                    "release" -> BuildOptimization.RELEASE
                    else -> error("tcjc.optimization must be debug or release")
                },
                installPrefix = Path.of(value("tcjc.installPrefix", "build/install/tcc-jvm")),
                outputDirectory = Path.of(value("tcjc.outputDirectory", "build/tcjc")),
                sysroot = properties["tcjc.sysroot"]?.takeIf(String::isNotBlank)?.let(Path::of),
                includePaths = paths("tcjc.includePath"),
                libraryPaths = paths("tcjc.libraryPath"),
                runtimeLinkMode = runCatching {
                    RuntimeLinkMode.valueOf(value("tcjc.runtimeLinkMode", "static").uppercase())
                }.getOrElse { error("invalid tcjc.runtimeLinkMode") },
                reproducible = value("tcjc.reproducible", "true").toBooleanStrictOrNull()
                    ?: error("tcjc.reproducible must be true or false"),
            ).resolved(baseDirectory)
        }
    }
}
