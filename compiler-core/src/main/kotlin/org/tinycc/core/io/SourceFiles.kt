package org.tinycc.core.io

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class SourceFile(val path: Path, val text: String)

/** Platform-neutral source access boundary used by the lexer and preprocessor. */
class SourceFileLoader(private val charset: Charset = StandardCharsets.UTF_8) {
    fun read(path: Path): SourceFile {
        val normalized = normalize(path)
        return SourceFile(normalized, Files.readString(normalized, charset))
    }

    fun exists(path: Path): Boolean = Files.isRegularFile(normalize(path))

    fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
}
