package org.tinycc.core

import java.nio.file.Files
import java.nio.file.Path

object TestPaths {
    fun repositoryPath(vararg parts: String): Path {
        val relative = parts.fold(Path.of("")) { path, part -> path.resolve(part) }
        val candidates = listOf(
            relative,
            Path.of("..").resolve(relative),
            Path.of("..", "..").resolve(relative),
        ).map(Path::toAbsolutePath).map(Path::normalize)
        return candidates.firstOrNull(Files::exists)
            ?: error("repository path not found: ${parts.joinToString("/")}")
    }
}
