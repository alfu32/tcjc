package tcc.kt

import java.nio.file.Paths

/** Public library and shared utility functions mechanically translated from libtcc.c. */
class LibTcc(
    private val output: (String) -> Unit = {},
    private val currentFile: () -> String? = { null },
    private val sysroot: String = "",
) {
    data class CompilerState(
        var libraryPath: String = "", var errorOpaque: Any? = null,
        var errorHandler: ((Any?, String) -> Unit)? = null,
        var warnError: Boolean = false, var warnNone: Boolean = false, var warningOption: Int = 0,
        var currentFilename: String? = null, var errors: Int = 0, var verbose: Int = 0,
    )
    data class FunctionContext(var callingConvention: Int = 0)
    data class BufferedSource(
        var filename: String, var trueFilename: String = filename, var lineNumber: Int = 1,
        var fileDescriptor: Int = -1, var previous: BufferedSource? = null,
        var tokenFlags: Int = 0, val buffer: ByteArray = ByteArray(4096), var bufferEnd: Int = 0,
    )
    data class Allocation(val id: Long, var bytes: ByteArray, val sourceFile: String? = null, val sourceLine: Int = 0)
    data class MemoryStats(val currentBytes: Long, val maximumBytes: Long, val liveAllocations: Int)

    companion object {
        const val ARG_BASE = 0x70000000
        const val WARN_ON = 1
        const val WARN_ERROR = 2
        const val WARN_NO_ERROR = 4
        const val ERROR_WARNING = 0
        const val ERROR_NO_ABORT = 1
        const val ERROR_FATAL = 2
        const val DEFAULT_IO_BUFFER_SIZE = 4096
        const val CH_EOB = 0x1a
    }

    var state: CompilerState? = null
        private set
    private var nextAllocationId = 1L
    private var currentMemoryBytes = 0L
    private var maximumMemoryBytes = 0L
    private val allocations = linkedMapOf<Long, Allocation>()
    var sourceFile: BufferedSource? = null
        private set
    var tokenFlags: Int = 0
        private set
    var totalLines: Long = 0
        private set

    fun enterState(next: CompilerState) { state = next }
    fun exitState(expected: CompilerState? = state) { if (state === expected) state = null }

    fun copyTruncated(destination: ByteArray, source: String): ByteArray {
        if (destination.isNotEmpty()) {
            val bytes = source.toByteArray()
            val count = minOf(destination.size - 1, bytes.size)
            bytes.copyInto(destination, 0, 0, count)
            destination[count] = 0
        }
        return destination
    }

    fun concatTruncated(destination: ByteArray, source: String): ByteArray {
        val end = destination.indexOf(0).let { if (it < 0) destination.size else it }
        if (end < destination.size) copyTruncated(destination.copyOfRange(end, destination.size), source).copyInto(destination, end)
        return destination
    }

    fun copyN(destination: ByteArray, source: ByteArray, count: Int): ByteArray {
        val n = minOf(count, destination.size - 1)
        if (n > 0) source.copyInto(destination, 0, 0, minOf(n, source.size))
        if (destination.isNotEmpty()) destination[n.coerceAtMost(destination.lastIndex)] = 0
        return destination
    }

    fun normalizeSlashes(path: String): String = path.replace('\\', '/')
    fun basename(path: String): String = path.substring(path.lastIndexOfAny(charArrayOf('/', '\\')) + 1)
    fun fileExtension(path: String): String {
        val base = basename(path)
        val dot = base.lastIndexOf('.')
        return if (dot >= 0) base.substring(dot) else ""
    }

    fun loadText(fileDescriptor: Int, read: (Int) -> ByteArray): ByteArray {
        val bytes = read(fileDescriptor)
        return bytes + byteArrayOf(0)
    }

    fun setString(slot: MutableList<String?>, index: Int, value: String?) {
        while (slot.size <= index) slot += null
        slot[index] = value?.toString()
    }

    fun concatString(slot: MutableList<String?>, index: Int, value: String, separator: Char = '\u0000') {
        while (slot.size <= index) slot += null
        val old = slot[index]
        slot[index] = when {
            old.isNullOrEmpty() -> value
            separator == '\u0000' -> old + value
            else -> old + separator + value
        }
    }

    fun allocate(size: Int, sourceFile: String? = null, sourceLine: Int = 0): Allocation {
        require(size >= 0)
        val allocation = Allocation(nextAllocationId++, ByteArray(size), sourceFile, sourceLine)
        allocations[allocation.id] = allocation
        currentMemoryBytes += size
        maximumMemoryBytes = maxOf(maximumMemoryBytes, currentMemoryBytes)
        return allocation
    }

    fun resize(allocation: Allocation?, size: Int, sourceFile: String? = null, sourceLine: Int = 0): Allocation? {
        if (allocation == null) return if (size == 0) null else allocate(size, sourceFile, sourceLine)
        if (size == 0) { free(allocation); return null }
        val replacement = allocation.bytes.copyOf(size)
        currentMemoryBytes += size - allocation.bytes.size
        allocation.bytes = replacement
        maximumMemoryBytes = maxOf(maximumMemoryBytes, currentMemoryBytes)
        return allocation
    }

    fun free(allocation: Allocation?) {
        if (allocation != null && allocations.remove(allocation.id) != null) currentMemoryBytes -= allocation.bytes.size
    }

    fun duplicate(value: String, sourceFile: String? = null, sourceLine: Int = 0): Allocation =
        allocate(value.toByteArray().size + 1, sourceFile, sourceLine).also { value.toByteArray().copyInto(it.bytes) }

    fun memoryStats(): MemoryStats = MemoryStats(currentMemoryBytes, maximumMemoryBytes, allocations.size)
    fun memoryCheck() {
        if (allocations.isNotEmpty()) output("MEM_DEBUG: mem_leak= $currentMemoryBytes bytes, mem_max_size= $maximumMemoryBytes bytes\n")
    }

    fun normalizedPathCompare(first: String, second: String, pathCompare: (String, String) -> Boolean = { a, b -> a == b }): Boolean =
        runCatching { pathCompare(Paths.get(first).toRealPath().toString(), Paths.get(second).toRealPath().toString()) }.getOrDefault(false)

    fun <T> dynamicArrayAdd(array: MutableList<T>, value: T) { array += value }
    fun <T> dynamicArrayReset(array: MutableList<T>, release: (T) -> Unit = {}) { array.forEach(release); array.clear() }

    /** Splits option lists, supporting C-style quotes and escaped quote/backslash characters. */
    fun splitArguments(text: String, separator: Char? = null): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            while (index < text.length && text[index].code <= 32) index++
            if (index >= text.length) break
            val token = StringBuilder()
            var quoted = false
            while (index < text.length) {
                var ch = text[index++]
                if (separator != null) { if (ch == separator) break }
                else {
                    if (ch == '\\' && index < text.length && (text[index] == '"' || text[index] == '\\')) ch = text[index++]
                    else if (ch == '"') { quoted = !quoted; continue }
                    else if (ch.code <= 32 && !quoted) break
                }
                token.append(ch)
            }
            result += token.toString()
        }
        return result
    }

    /** Expands TCC library path placeholders and appends each nonempty path element. */
    fun splitSearchPath(input: String, pathSeparator: Char = ':'): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start <= input.length) {
            val end = input.indexOf(pathSeparator, start).let { if (it < 0) input.length else it }
            val part = input.substring(start, end)
            val expanded = Regex("\\{([BRf])}").replace(part) { match ->
                when (match.groupValues[1]) {
                    "B" -> state?.libraryPath.orEmpty()
                    "R" -> sysroot
                    else -> currentFile()?.let { filePath ->
                        val base = basename(filePath)
                        if (base.length < filePath.length) filePath.dropLast(base.length + 1) else "."
                    } ?: "."
                }
            }
            if (expanded.isNotEmpty()) result += expanded
            if (end == input.length) break
            start = end + 1
        }
        return result
    }

    fun setErrorHandler(compilerState: CompilerState, opaque: Any?, handler: ((Any?, String) -> Unit)?) {
        compilerState.errorOpaque = opaque
        compilerState.errorHandler = handler
    }

    fun reportError(compilerState: CompilerState, mode: Int, message: String, warningOption: Int = 0): Boolean {
        var effectiveMode = mode
        if (mode == ERROR_WARNING) {
            if (compilerState.warnError) effectiveMode = ERROR_FATAL
            if (warningOption != 0) {
                if (warningOption and WARN_ON == 0) return false
                if (warningOption and WARN_ERROR != 0) effectiveMode = ERROR_FATAL
                if (warningOption and WARN_NO_ERROR != 0) effectiveMode = ERROR_WARNING
            }
            if (compilerState.warnNone) return false
        }
        val prefix = compilerState.currentFilename?.let { "$it: " } ?: "tcc: "
        val severity = if (effectiveMode == ERROR_WARNING) "warning" else "error"
        val formatted = "$prefix$severity: $message"
        val callback = compilerState.errorHandler
        if (callback != null) callback(compilerState.errorOpaque, formatted) else output(formatted + "\n")
        if (effectiveMode != ERROR_WARNING) compilerState.errors++
        return effectiveMode == ERROR_FATAL
    }

    fun setFunctionCallingConvention(context: FunctionContext, convention: Int) { context.callingConvention = convention }

    fun openBufferedSource(filename: String, initialLength: Int = 0): BufferedSource {
        val size = if (initialLength != 0) initialLength else DEFAULT_IO_BUFFER_SIZE
        val buffer = ByteArray(size + 1)
        buffer[initialLength.coerceIn(0, size)] = CH_EOB.toByte()
        val previous = sourceFile
        return BufferedSource(normalizeSlashes(filename), previous = previous, tokenFlags = tokenFlags, buffer = buffer,
            bufferEnd = initialLength).also { sourceFile = it; tokenFlags = 3 }
    }

    fun closeBufferedSource() {
        val current = sourceFile ?: return
        if (current.fileDescriptor > 0) totalLines += current.lineNumber - 1L
        sourceFile = current.previous
        tokenFlags = current.tokenFlags
    }
}
