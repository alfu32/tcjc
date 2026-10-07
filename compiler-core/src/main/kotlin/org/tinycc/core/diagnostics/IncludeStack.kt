package org.tinycc.core.diagnostics

import java.nio.file.Path
import org.tinycc.core.collections.DynamicArray

data class IncludeFrame(val file: Path, val includedAt: SourceLocation? = null)

/** Mutable include context kept separate from diagnostics for parser recovery and cycle checks. */
class IncludeStack {
    private val frames = DynamicArray<IncludeFrame>()

    val depth: Int
        get() = frames.size

    fun push(file: Path, includedAt: SourceLocation? = null) {
        frames.add(IncludeFrame(file, includedAt))
    }

    fun pop(): IncludeFrame = frames.removeAt(frames.size - 1)

    fun contains(file: Path): Boolean = frames.any { it.file == file }

    fun snapshot(): List<IncludeFrame> = frames.toList()

    inline fun <T> withFrame(
        file: Path,
        includedAt: SourceLocation? = null,
        action: () -> T,
    ): T {
        push(file, includedAt)
        return try {
            action()
        } finally {
            pop()
        }
    }
}
