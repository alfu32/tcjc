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
        var outputType: Int = 0, var fileType: Int = 0, var outputFormat: Int = 0,
        var noStandardIncludes: Boolean = false, var noStandardLibraryPaths: Boolean = false,
        var noStandardLibrary: Boolean = false, var staticLink: Boolean = false, var debug: Boolean = false,
        var positionIndependentExecutable: Boolean = false, var gnuExtensions: Boolean = true,
        var tccExtensions: Boolean = true, var noCommon: Boolean = true, var cVersion: Int = 199901,
        var warnImplicitFunction: Boolean = true, var warnDiscardedQualifiers: Boolean = true,
        var msExtensions: Boolean = true, var unwindTables: Boolean = true,
        var soname: String? = null, var rpath: String? = null, var outputFile: String? = null,
        var commandLineDefinitions: String = "", var commandLineIncludes: String = "",
        val includePaths: MutableList<String> = mutableListOf(), val systemIncludePaths: MutableList<String> = mutableListOf(),
        val libraryPaths: MutableList<String> = mutableListOf(), val crtPaths: MutableList<String> = mutableListOf(),
        val inputFiles: MutableList<String> = mutableListOf(), val targetDependencies: MutableList<String> = mutableListOf(),
        val pragmaLibraries: MutableList<String> = mutableListOf(), val loadedLibraries: MutableList<DllReference> = mutableListOf(),
        var entryName: String? = null, var initSymbol: String? = null, var finiSymbol: String? = null,
        var mapFile: String? = null, var dependencyOutput: String? = null,
    )
    data class DllReference(val name: String, var level: Int = 0, var found: Boolean = false, var index: Int = 0, var handle: Any? = null)
    data class CompileHooks(
        val enter: (CompilerState) -> Unit = {}, val openSource: (String, String?, Int) -> Unit = { _, _, _ -> },
        val preprocessStart: (CompilerState, Int) -> Unit = { _, _ -> }, val generatorInit: (CompilerState) -> Unit = {},
        val preprocess: () -> Unit = {}, val beginObjectFile: () -> Unit = {}, val assemble: (Boolean) -> Unit = {},
        val compile: () -> Unit = {}, val endObjectFile: () -> Unit = {}, val generatorFinish: () -> Unit = {},
        val preprocessEnd: () -> Unit = {}, val exit: (CompilerState) -> Unit = {},
    )
    data class OutputHooks(
        val addSystemIncludes: (CompilerState) -> Unit = {}, val createSections: (CompilerState) -> Unit = {},
        val addLibraryPaths: (CompilerState) -> Unit = {}, val addCrtPaths: (CompilerState) -> Unit = {},
        val addCrtBegin: (CompilerState) -> Unit = {}, val addTargetSystemPaths: (CompilerState) -> Unit = {},
    )
    data class FileHooks(
        val open: (String) -> Int = { -1 }, val close: (Int) -> Unit = {},
        val compile: (CompilerState, Int, String, Int) -> Int = { _, _, _, _ -> 0 },
        val binaryType: (Int) -> Int = { 0 }, val loadBinary: (CompilerState, Int, String, Int) -> Int = { _, _, _, _ -> 0 },
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
        const val OUTPUT_PREPROCESS = 1
        const val OUTPUT_OBJECT = 2
        const val OUTPUT_MEMORY = 3
        const val OUTPUT_EXECUTABLE = 4
        const val FORMAT_ELF = 1
        const val TYPE_ASM = 1
        const val TYPE_ASM_PREPROCESSED = 2
        const val TYPE_C = 4
        const val TYPE_BINARY = 8
        const val TYPE_PRINT_ERROR = 16
        const val TYPE_WHOLE_ARCHIVE = 32
        const val FILE_NOT_FOUND = -2
        const val FILE_NOT_RECOGNIZED = -3
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

    fun createState(libraryPath: String, configureOptions: ((CompilerState) -> Unit)? = null): CompilerState =
        CompilerState(libraryPath = libraryPath).also { configureOptions?.invoke(it) }

    fun deleteState(compilerState: CompilerState, release: (String) -> Unit = {}) {
        compilerState.listOfOwnedPaths().forEach(release)
        compilerState.includePaths.clear(); compilerState.systemIncludePaths.clear()
        compilerState.libraryPaths.clear(); compilerState.crtPaths.clear()
        compilerState.inputFiles.clear(); compilerState.targetDependencies.clear()
        compilerState.pragmaLibraries.clear(); compilerState.loadedLibraries.clear()
        if (state === compilerState) state = null
    }

    private fun CompilerState.listOfOwnedPaths(): List<String> = listOfNotNull(
        libraryPath.takeIf(String::isNotEmpty), soname, rpath, outputFile, entryName, initSymbol, finiSymbol, mapFile, dependencyOutput,
    )

    fun setOutputType(compilerState: CompilerState, requestedType: Int, headers: (CompilerState) -> List<String>,
        libraries: (CompilerState) -> List<String>, crt: (CompilerState) -> List<String>, hooks: OutputHooks = OutputHooks(),
        targetPlatform: String = "unix"): Int {
        compilerState.outputType = if (compilerState.positionIndependentExecutable && requestedType == OUTPUT_EXECUTABLE) requestedType or 0x100 else requestedType
        if (!compilerState.noStandardIncludes) {
            hooks.addSystemIncludes(compilerState)
            compilerState.systemIncludePaths.addAll(headers(compilerState))
        }
        if (requestedType == OUTPUT_PREPROCESS) { compilerState.debug = false; return 0 }
        hooks.createSections(compilerState)
        if (requestedType == OUTPUT_OBJECT) { compilerState.outputFormat = FORMAT_ELF; return 0 }
        if (!compilerState.noStandardLibraryPaths) {
            hooks.addLibraryPaths(compilerState)
            compilerState.libraryPaths.addAll(libraries(compilerState))
        }
        hooks.addTargetSystemPaths(compilerState)
        if (targetPlatform !in setOf("pe", "macho")) {
            hooks.addCrtPaths(compilerState)
            compilerState.crtPaths.addAll(crt(compilerState))
            if (requestedType != OUTPUT_MEMORY && !compilerState.noStandardLibrary) hooks.addCrtBegin(compilerState)
        }
        return if (compilerState.errors != 0) -1 else 0
    }

    fun addIncludePath(compilerState: CompilerState, path: String): Int { compilerState.includePaths += splitSearchPath(path); return 0 }
    fun addSystemIncludePath(compilerState: CompilerState, path: String): Int { compilerState.systemIncludePaths += splitSearchPath(path); return 0 }
    fun addLibraryPath(compilerState: CompilerState, path: String): Int { compilerState.libraryPaths += splitSearchPath(path); return 0 }
    fun setLibraryPath(compilerState: CompilerState, path: String) { compilerState.libraryPath = path }

    fun defineSymbol(compilerState: CompilerState, symbol: String, value: String? = null) {
        val equal = symbol.indexOf('=')
        val name = if (equal < 0) symbol else symbol.substring(0, equal)
        val resolved = value ?: if (equal >= 0) symbol.substring(equal + 1) else "1"
        compilerState.commandLineDefinitions += "#define $name $resolved\n"
    }

    fun undefineSymbol(compilerState: CompilerState, symbol: String) {
        compilerState.commandLineDefinitions += "#undef $symbol\n"
    }

    fun compileSource(compilerState: CompilerState, fileType: Int, text: String?, fileDescriptor: Int, hooks: CompileHooks): Int {
        enterState(compilerState)
        hooks.enter(compilerState)
        var failed = false
        compilerState.currentFilename = if (fileDescriptor < 0) "<string>" else text
        try {
            hooks.openSource(compilerState.currentFilename.orEmpty(), text, fileDescriptor)
            hooks.preprocessStart(compilerState, fileType)
            hooks.generatorInit(compilerState)
            if (compilerState.outputType == OUTPUT_PREPROCESS) hooks.preprocess()
            else {
                hooks.beginObjectFile()
                if (fileType and (TYPE_ASM or TYPE_ASM_PREPROCESSED) != 0) hooks.assemble(fileType and TYPE_ASM_PREPROCESSED != 0)
                else hooks.compile()
                hooks.endObjectFile()
            }
        } catch (_: RuntimeException) {
            failed = true
            compilerState.errors++
        } finally {
            runCatching(hooks.generatorFinish)
            runCatching(hooks.preprocessEnd)
            hooks.exit(compilerState)
            exitState(compilerState)
        }
        return if (failed || compilerState.errors != 0) -1 else 0
    }

    fun compileString(compilerState: CompilerState, text: String, hooks: CompileHooks): Int =
        compileSource(compilerState, compilerState.fileType, text, -1, hooks)

    fun addDllReference(compilerState: CompilerState, name: String, level: Int): DllReference? {
        val existing = compilerState.loadedLibraries.firstOrNull { it.name == name }
        if (level == -1) return existing
        if (existing != null) {
            if (level < existing.level) existing.level = level
            existing.found = true
            return existing
        }
        return DllReference(name, level, false, compilerState.loadedLibraries.size + 1).also { compilerState.loadedLibraries += it }
    }

    fun guessFileType(filename: String, caseSensitive: Boolean = true): Int {
        val extension = fileExtension(filename).removePrefix(".")
        if (extension.isEmpty()) return TYPE_C
        if (extension == "S") return TYPE_ASM_PREPROCESSED
        if (extension == "s") return TYPE_ASM
        val cExtension = if (caseSensitive) extension in setOf("c", "h", "i") else extension.lowercase() in setOf("c", "h", "i")
        return if (cExtension) TYPE_C else TYPE_BINARY
    }

    fun addFile(compilerState: CompilerState, filename: String, flags: Int, hooks: FileHooks): Int {
        val fileType = if (flags and (TYPE_ASM or TYPE_ASM_PREPROCESSED or TYPE_C or TYPE_BINARY) == 0) flags or guessFileType(filename) else flags
        if (compilerState.outputType == OUTPUT_PREPROCESS && fileType and TYPE_BINARY != 0) return 0
        val descriptor = hooks.open(filename)
        if (descriptor < 0) return FILE_NOT_FOUND
        return try {
            if (fileType and TYPE_BINARY != 0) hooks.loadBinary(compilerState, fileType, filename, descriptor)
            else {
                compilerState.targetDependencies += filename
                hooks.compile(compilerState, fileType, filename, descriptor)
            }
        } finally { hooks.close(descriptor) }
    }

    fun addLibraryInternal(compilerState: CompilerState, formats: List<String>, name: String, flags: Int,
        paths: List<String>, addFile: (String, Int) -> Int): Int {
        for (path in paths) for (format in formats) {
            val candidate = format.replaceFirst("%s", path).replace("%n", name).replaceFirst("%s", name)
            val result = addFile(candidate, flags and TYPE_PRINT_ERROR.inv())
            if (result != FILE_NOT_FOUND) return result
        }
        if (flags and TYPE_PRINT_ERROR != 0) {
            val what = if (flags and TYPE_BINARY != 0) "file" else "library"
            reportError(compilerState, ERROR_NO_ABORT, "$what '$name' not found")
        }
        return FILE_NOT_FOUND
    }

    fun addLibrary(compilerState: CompilerState, name: String, formats: List<String>, addFile: (String, Int) -> Int): Int {
        val flags = TYPE_BINARY or (compilerState.fileType and TYPE_WHOLE_ARCHIVE)
        if (name.startsWith(':')) return addLibraryInternal(compilerState, listOf("%s/%n"), name.drop(1), flags, compilerState.libraryPaths, addFile)
        val candidates = if (compilerState.staticLink) formats.takeLast(1) else formats
        for (format in candidates) {
            val result = addLibraryInternal(compilerState, listOf(format), name, flags, compilerState.libraryPaths, addFile)
            if (result != FILE_NOT_FOUND) return result
        }
        return addLibraryInternal(compilerState, listOf("%s/%n"), name, flags or TYPE_PRINT_ERROR, compilerState.libraryPaths, addFile)
    }

    fun addSupportLibrary(compilerState: CompilerState, name: String, crossPrefix: String, addFile: (String, Int) -> Int): Int =
        addLibrary(compilerState, if (crossPrefix.isEmpty()) name else crossPrefix + name, listOf("%s/%n"), addFile)

    fun addPragmaLibraries(compilerState: CompilerState, addLibrary: (String) -> Int) {
        compilerState.pragmaLibraries.toList().forEach { addLibrary(it) }
    }

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
