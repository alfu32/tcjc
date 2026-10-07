package org.tinycc.core.diagnostics

import java.nio.file.Path

data class SourceLocation(
    val path: Path? = null,
    val line: Int = 0,
    val column: Int = 0,
    val offset: Int = 0,
) {
    fun displayPath(): String = path?.toString() ?: "<input>"

    fun isKnown(): Boolean = line > 0 && column > 0
}

data class SourceSpan(val start: SourceLocation, val end: SourceLocation)

/** Maps UTF-16 source offsets to stable one-based diagnostic positions. */
class LineMap(private val text: String) {
    private val lineStarts: IntArray = buildLineStarts(text)

    fun locationAt(path: Path?, offset: Int): SourceLocation {
        val safeOffset = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStarts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lineStarts[middle] <= safeOffset) low = middle + 1 else high = middle - 1
        }
        val lineIndex = high.coerceAtLeast(0)
        return SourceLocation(
            path = path,
            line = lineIndex + 1,
            column = safeOffset - lineStarts[lineIndex] + 1,
            offset = safeOffset,
        )
    }

    private companion object {
        fun buildLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>()
            starts.add(0)
            text.forEachIndexed { index, character ->
                if (character == '\n') starts.add(index + 1)
            }
            return starts.toIntArray()
        }
    }
}
