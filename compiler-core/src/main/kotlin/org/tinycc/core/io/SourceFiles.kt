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
        val bytes = Files.readAllBytes(normalized)
        val text = if (charset == StandardCharsets.UTF_8) {
            SourceDecoder.decode(bytes).text
        } else {
            normalizeNewlines(String(bytes, charset))
        }
        return SourceFile(normalized, text)
    }

    fun exists(path: Path): Boolean = Files.isRegularFile(normalize(path))

    fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
}

data class DecodedSource(val text: String, val hadUtf8Bom: Boolean)

/** Decodes source bytes using the C front-end's UTF-8 and newline rules. */
object SourceDecoder {
    fun decode(bytes: ByteArray): DecodedSource {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val start = if (hasBom) 3 else 0
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val decoded = decoder.decode(java.nio.ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
        return DecodedSource(normalizeNewlines(decoded), hasBom)
    }

}

private fun normalizeNewlines(text: String): String = buildString(text.length) {
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '\r' -> {
                append('\n')
                if (index + 1 < text.length && text[index + 1] == '\n') index++
            }
            else -> append(text[index])
        }
        index++
    }
}
