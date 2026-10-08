package tcc.kt

import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.Path
import java.io.RandomAccessFile

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
        var elfInterpreter: String? = null, var textAddress: ULong = 0uL, var hasTextAddress: Boolean = false,
        var sectionAlignment: ULong = 0uL, var symbolic: Boolean = false, var exportDynamic: Boolean = false,
        var enableNewDtags: Boolean = false, var noDelete: Boolean = false, var linkerArgumentIndex: Int = 0,
        val linkerArguments: MutableList<String> = mutableListOf(), var outputFormatName: String? = null,
        var optionPthread: Boolean = false, var doBench: Boolean = false, var optionR: Boolean = false,
        var charIsUnsigned: Boolean = false, var leadingUnderscore: Boolean = false,
        var dollarsInIdentifiers: Boolean = true, var testCoverage: Boolean = false,
        var reverseFuncargs: Boolean = false, var gnu89Inline: Boolean = false,
        var msBitfields: Boolean = false, var noSse: Boolean = false,
        var warnAll: Boolean = false, var warnWriteStrings: Boolean = false,
        var warnUnsupported: Boolean = false, var warnNoneMode: Boolean = false,
        val warningOverrides: MutableMap<String, Int> = mutableMapOf(),
        var optimize: Int = 0, var debugLevel: Int = 0, var preprocessOnly: Boolean = false,
        var generateDependencies: Boolean = false, var justDependencies: Boolean = false,
        var includeSystemDependencies: Boolean = false, var generatePhonyDependencies: Boolean = false,
        var dependencyOutputFile: String? = null, var runCommand: String? = null,
        val files: MutableList<FileSpec> = mutableListOf(), var libraryCount: Int = 0,
        var debugFlags: Int = 0, var preprocessLineControl: Int = 1, var languageStandard: String? = null,
        var targetTriple: String? = null, var compilerVersion: String? = null,
        var installName: String? = null, var compatibilityVersion: Int = 0, var currentVersion: Int = 0,
        var armFloatAbi: String? = null, var runtimeStdin: String? = null, var backtraceCallers: Int = 0,
        var doBacktrace: Boolean = false, var boundsChecking: Boolean = false,
        var dwarfVersion: Int = 4,
        var peCharacteristics: Int = 0, var peDllCharacteristics: Int = 0,
        var peFileAlignment: ULong = 0uL, var peStackSize: ULong = 0uL, var peSubsystem: Int = 0,
    )
    data class TccOption(val name: String, val index: String, val hasArgument: Boolean = false, val noSeparateArgument: Boolean = false)
    data class ParsedArguments(val action: Int, val remaining: List<String>, val expandedArguments: List<String>)
    data class FileSpec(val filename: String, val fileType: Int)
    data class OutputStatistics(val identifiers: Int, val lines: Long, val bytes: Long, val text: Long, val writableData: Long, val readOnlyData: Long, val bss: Long)
    data class LinkOptionMatch(val result: Int, val optionArgument: String?, val pendingSeparateArgument: Boolean = false)
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
    data class StateDeleteHooks(
        val deleteSections: (CompilerState) -> Unit = {}, val deleteRuntime: (CompilerState) -> Unit = {},
        val deleteTargetState: (CompilerState) -> Unit = {}, val deleteLoadedLibrary: (DllReference) -> Unit = {},
    )
    data class FileHooks(
        val open: (String) -> Int = { -1 }, val close: (Int) -> Unit = {},
        val compile: (CompilerState, Int, String, Int) -> Int = { _, _, _, _ -> 0 },
        val binaryType: (Int) -> Int = { 0 }, val loadBinary: (CompilerState, Int, String, Int) -> Int = { _, _, _, _ -> 0 },
    )
    data class BinaryHooks(
        val objectType: (Int) -> Int = { 0 }, val loadRelocatable: (Int) -> Int = { 0 },
        val loadArchive: (Int, Boolean) -> Int = { _, _ -> 0 }, val loadDynamic: (Int, String, Boolean) -> Int = { _, _, _ -> 0 },
        val loadScript: (Int) -> Int = { -3 }, val openDynamic: (String) -> Any? = { null },
        val loadMachODynamic: (Int, String, Boolean) -> Int = { _, _, _ -> 0 },
        val loadMachOTbd: (Int, String, Boolean) -> Int = { _, _, _ -> 0 },
        val machOTbdSoname: (Int) -> String? = { null }, val loadPe: (Int, String) -> Int = { _, _ -> 0 },
        val loadCoff: (Int) -> Int = { 0 }, val close: (Int) -> Unit = {},
    )
    data class SourceFileHooks(val open: (String) -> Int = { -1 }, val close: (Int) -> Unit = {})
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
        const val OUTPUT_MEMORY = 1
        const val OUTPUT_EXECUTABLE = 2
        const val OUTPUT_OBJECT = 3
        const val OUTPUT_DLL = 4
        const val OUTPUT_PREPROCESS = 5
        const val FORMAT_ELF = 1
        const val TYPE_C = 1
        const val TYPE_ASM = 2
        const val TYPE_ASM_PREPROCESSED = 4
        const val TYPE_LIBRARY = 8
        const val TYPE_PRINT_ERROR = 16
        const val TYPE_REFERENCED_DLL = 32
        const val TYPE_BINARY = 64
        const val TYPE_WHOLE_ARCHIVE = 128
        const val TYPE_MASK = 71
        const val BINARY_RELOCATABLE = 1
        const val BINARY_DYNAMIC = 2
        const val BINARY_ARCHIVE = 3
        const val BINARY_C67 = 4
        const val BINARY_TBD = 5
        const val FILE_NOT_FOUND = -2
        const val FILE_NOT_RECOGNIZED = -3
        const val OPTION_HELP = 1
        const val OPTION_HELP2 = 2
        const val OPTION_V = 3
        const val OPTION_PRINT_DIRS = 4
        const val OPTION_AR = 5
        const val OPTION_IMPDEF = 6
        const val OPTION_ARGS_ERROR = -1
        const val WARN_ERR = 2
        const val WARN_NOE = 4

        val TCC_OPTIONS = listOf(
            TccOption("h", "help"), TccOption("-help", "help"), TccOption("?", "help"), TccOption("hh", "help2"),
            TccOption("v", "verbose", true, true), TccOption("-version", "verbose"),
            TccOption("I", "includePath", true), TccOption("D", "define", true), TccOption("U", "undefine", true),
            TccOption("P", "P", true, true), TccOption("L", "libraryPath", true), TccOption("B", "libPath", true),
            TccOption("l", "library", true), TccOption("bench", "bench"), TccOption("g", "debug", true, true),
            TccOption("compatibility_version", "compatibilityVersion", true), TccOption("current_version", "currentVersion", true),
            TccOption("dynamiclib", "dynamiclib"), TccOption("flat_namespace", "flatNamespace"),
            TccOption("install_name", "installName", true), TccOption("two_levelnamespace", "twoLevelNamespace"),
            TccOption("undefined", "undefined", true),
            TccOption("rstdin", "rstdin", true), TccOption("bt", "backtrace", true, true), TccOption("b", "bounds"),
            TccOption("impdef", "impdef"),
            TccOption("c", "object"), TccOption("dumpmachine", "dumpmachine"), TccOption("dumpversion", "dumpversion"),
            TccOption("d", "d", true, true), TccOption("static", "static"),
            TccOption("std", "std", true, true), TccOption("shared", "shared"), TccOption("soname", "soname", true),
            TccOption("o", "output", true), TccOption("pthread", "pthread"), TccOption("run", "run", true, true),
            TccOption("rdynamic", "rdynamic"), TccOption("r", "relocatable"), TccOption("Wl,", "linker", true, true),
            TccOption("Wp,", "preprocessor", true, true), TccOption("W", "warning", true, true),
            TccOption("O", "optimize", true, true), TccOption("mfloat-abi", "armFloatAbi", true),
            TccOption("m", "machine", true, true), TccOption("f", "feature", true, true),
            TccOption("isystem", "systemInclude", true), TccOption("include", "include", true),
            TccOption("nostdinc", "nostdinc"), TccOption("nostdlib", "nostdlib"),
            TccOption("print-search-dirs", "printDirs"), TccOption("w", "warnNone"), TccOption("E", "preprocess"),
            TccOption("M", "M"), TccOption("MM", "MM"), TccOption("MD", "MD", true, true), TccOption("MMD", "MMD", true, true),
            TccOption("MF", "MF", true), TccOption("MP", "MP"), TccOption("x", "language", true), TccOption("ar", "ar"),
            TccOption("arch", "ignoredArg", true), TccOption("C", "ignored"), TccOption("-param", "ignoredArg", true),
            TccOption("pedantic", "ignored"), TccOption("pie", "ignored"), TccOption("no-pie", "ignored"),
            TccOption("pipe", "ignored"), TccOption("s", "ignored"), TccOption("traditional", "ignored"),
        )
    }

    var state: CompilerState? = null
        private set
    private var nextAllocationId = 1L
    private var currentMemoryBytes = 0L
    private var maximumMemoryBytes = 0L
    private var liveStateCount = 0
    private var customReallocator: ((Allocation?, Int) -> ByteArray?)? = null
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

    fun deleteState(compilerState: CompilerState, release: (String) -> Unit = {}, hooks: StateDeleteHooks = StateDeleteHooks()) {
        hooks.deleteSections(compilerState)
        hooks.deleteRuntime(compilerState)
        hooks.deleteTargetState(compilerState)
        compilerState.listOfOwnedPaths().forEach(release)
        compilerState.loadedLibraries.forEach(hooks.deleteLoadedLibrary)
        compilerState.includePaths.clear(); compilerState.systemIncludePaths.clear()
        compilerState.libraryPaths.clear(); compilerState.crtPaths.clear()
        compilerState.inputFiles.clear(); compilerState.targetDependencies.clear()
        compilerState.pragmaLibraries.clear(); compilerState.loadedLibraries.clear()
        compilerState.files.clear(); compilerState.linkerArguments.clear()
        compilerState.commandLineDefinitions = ""; compilerState.commandLineIncludes = ""
        compilerState.currentFilename = null; compilerState.dependencyOutputFile = null
        compilerState.elfInterpreter = null; compilerState.outputFormatName = null
        if (state === compilerState) state = null
    }

    private fun CompilerState.listOfOwnedPaths(): List<String> = listOfNotNull(
        libraryPath.takeIf(String::isNotEmpty), soname, rpath, outputFile, entryName, initSymbol, finiSymbol, mapFile,
        dependencyOutput, dependencyOutputFile, elfInterpreter, outputFormatName,
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

    /** OpenBSD's linker search chooses the highest lib version matching a wildcard. */
    fun latestVersionedFile(pattern: String): String? {
        val wildcard = pattern.indexOf('*')
        if (wildcard < 0) return null
        val parent = Paths.get(pattern).parent ?: Paths.get(".")
        val filenamePattern = Paths.get(pattern).fileName.toString()
        val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$filenamePattern")
        var bestVersion = -1
        var bestPath: Path? = null
        val entries = runCatching { Files.newDirectoryStream(parent) }.getOrNull() ?: return null
        entries.use { stream ->
            for (entry in stream) {
                if (!matcher.matches(entry.fileName)) continue
                val suffixOffset = (wildcard - (pattern.lastIndexOf('/') + 1)).coerceAtLeast(0)
                val suffix = entry.fileName.toString().drop(suffixOffset)
                val version = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(suffix) ?: continue
                val major = version.groupValues[1].toIntOrNull() ?: continue
                val minor = version.groupValues[2].toIntOrNull() ?: continue
                val number = major * 1000 + minor
                if (number > bestVersion) { bestVersion = number; bestPath = entry }
            }
        }
        return bestPath?.toString()
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

    fun addBinary(compilerState: CompilerState, flags: Int, filename: String, descriptor: Int,
        hooks: BinaryHooks, targetPlatform: String = "unix", native: Boolean = true): Int {
        val savedFilename = compilerState.currentFilename
        compilerState.currentFilename = filename
        var objectType = hooks.objectType(descriptor)
        var result = 0
        try {
            if (targetPlatform == "macho" && objectType != BINARY_DYNAMIC) {
                val extension = fileExtension(filename)
                if (extension == ".tbd") objectType = BINARY_TBD
                else if (extension == ".dylib") objectType = BINARY_DYNAMIC
            }
            result = when (objectType) {
                BINARY_RELOCATABLE -> hooks.loadRelocatable(descriptor)
                BINARY_ARCHIVE -> hooks.loadArchive(descriptor, flags and TYPE_WHOLE_ARCHIVE == 0)
                BINARY_DYNAMIC -> when (targetPlatform) {
                    "macho" -> if (compilerState.outputType == OUTPUT_MEMORY) {
                        val soname = if (objectType == BINARY_TBD) hooks.machOTbdSoname(descriptor) ?: filename else filename
                        hooks.openDynamic(soname)?.let { handle -> addDllReference(compilerState, soname, 0)?.handle = handle; 0 } ?: FILE_NOT_RECOGNIZED
                    } else hooks.loadMachODynamic(descriptor, filename, flags and TYPE_LIBRARY != 0)
                    else -> if (compilerState.outputType == OUTPUT_MEMORY) {
                        if (!native) 0 else hooks.openDynamic(filename)?.let { handle -> addDllReference(compilerState, filename, 0)?.handle = handle; 0 } ?: FILE_NOT_RECOGNIZED
                    } else hooks.loadDynamic(descriptor, filename, flags and TYPE_LIBRARY != 0)
                }
                BINARY_TBD -> if (compilerState.outputType == OUTPUT_MEMORY) {
                    val soname = hooks.machOTbdSoname(descriptor) ?: filename
                    hooks.openDynamic(soname)?.let { handle -> addDllReference(compilerState, soname, 0)?.handle = handle; 0 } ?: FILE_NOT_RECOGNIZED
                } else hooks.loadMachOTbd(descriptor, filename, flags and TYPE_LIBRARY != 0)
                BINARY_C67 -> hooks.loadCoff(descriptor)
                else -> when (targetPlatform) {
                    "unix" -> hooks.loadScript(descriptor)
                    "pe" -> if (hooks.loadPe(descriptor, filename) != 0) FILE_NOT_RECOGNIZED else 0
                    else -> FILE_NOT_RECOGNIZED
                }
            }
        } finally {
            hooks.close(descriptor)
            compilerState.currentFilename = savedFilename
        }
        if (result == FILE_NOT_RECOGNIZED) reportError(compilerState, ERROR_NO_ABORT, "$filename: unrecognized file type")
        return result
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

    /** Matches the one/two-dash linker option syntax, aliases, negation, and split arguments. */
    fun matchLinkerOption(compilerState: CompilerState, option: String, patterns: String, peTarget: Boolean = false): LinkOptionMatch {
        if (!option.startsWith('-')) return LinkOptionMatch(0, null)
        val start = if (option.startsWith("--")) 2 else 1
        val negatable = patterns.startsWith('?')
        val patternSet = if (negatable) patterns.drop(1) else patterns
        var negative = false
        val normalizedOption = option.substring(start).let {
            if (negatable && it.startsWith("no-")) { negative = true; it.substring(3) }
            else if (peTarget && negatable && it.startsWith("disable-")) { negative = true; it.substring(8) }
            else it
        }
        for (pattern in patternSet.split('|')) {
            val name = pattern.trimEnd('=', ':')
            if (normalizedOption == name) {
                if (pattern.endsWith('=') || pattern.endsWith(':')) {
                    val next = compilerState.linkerArguments.getOrNull(compilerState.linkerArgumentIndex + 1)
                    if (next == null) return LinkOptionMatch(0, null, pendingSeparateArgument = true)
                    compilerState.linkerArgumentIndex++
                    return LinkOptionMatch(if (negative) -1 else 1, next)
                }
                return LinkOptionMatch(if (negative) -1 else 1, "")
            }
            if (normalizedOption.startsWith("$name=") || normalizedOption.startsWith("$name:"))
                return LinkOptionMatch(if (negative) -1 else 1, normalizedOption.substring(name.length + 1))
            if (normalizedOption.startsWith(name) && pattern.endsWith(':'))
                return LinkOptionMatch(if (negative) -1 else 1, normalizedOption.substring(name.length))
        }
        return LinkOptionMatch(0, null)
    }

    /** Consumes and applies the linker options handled by libtcc.c. */
    fun setLinkerOptions(compilerState: CompilerState, encodedOptions: String, addFile: (String, Int) -> Int = { _, _ -> 0 },
        warnUnsupported: (String) -> Unit = {}, peTarget: Boolean = false,
        setPeSubsystem: (String) -> Int = { -1 }, machoTarget: Boolean = false): Int {
        compilerState.linkerArguments += splitArguments(encodedOptions, ',')
        while (compilerState.linkerArgumentIndex < compilerState.linkerArguments.size) {
            val option = compilerState.linkerArguments[compilerState.linkerArgumentIndex]
            var matched: LinkOptionMatch
            fun match(pattern: String) = matchLinkerOption(compilerState, option, pattern, peTarget)
            when {
                match("Bsymbolic").also { matched = it }.result != 0 -> compilerState.symbolic = true
                match("nostdlib").also { matched = it }.result != 0 -> compilerState.noStandardLibraryPaths = true
                match("e=|entry=").also { matched = it }.result != 0 -> compilerState.entryName = matched.optionArgument
                match("image-base=|Ttext=").also { matched = it }.result != 0 -> {
                    compilerState.textAddress = matched.optionArgument.orEmpty().removePrefix("0x").toULongOrNull(16) ?: 0uL
                    compilerState.hasTextAddress = true
                }
                match("init=").also { matched = it }.result != 0 -> { compilerState.initSymbol = matched.optionArgument; warnUnsupported(option) }
                match("fini=").also { matched = it }.result != 0 -> { compilerState.finiSymbol = matched.optionArgument; warnUnsupported(option) }
                match("Map=").also { matched = it }.result != 0 -> { compilerState.mapFile = matched.optionArgument; warnUnsupported(option) }
                match("oformat=").also { matched = it }.result != 0 -> {
                    val format = matched.optionArgument.orEmpty()
                    if (format.startsWith("elf32-") || format.startsWith("elf64-") || (peTarget && format.startsWith("pe-"))) compilerState.outputFormatName = "elf"
                    else if (format == "binary" || format == "coff") compilerState.outputFormatName = format
                    else return reportError(compilerState, ERROR_NO_ABORT, "unsupported linker option '$option'").let { -1 }
                }
                match("export-all-symbols|export-dynamic|E").also { matched = it }.result != 0 -> compilerState.exportDynamic = true
                match("rpath=").also { matched = it }.result != 0 -> compilerState.rpath =
                    if (compilerState.rpath.isNullOrEmpty()) matched.optionArgument.orEmpty() else compilerState.rpath + ":" + matched.optionArgument.orEmpty()
                match("dynamic-linker=|I:").also { matched = it }.result != 0 -> compilerState.elfInterpreter = matched.optionArgument
                match("enable-new-dtags").also { matched = it }.result != 0 -> compilerState.enableNewDtags = true
                match("section-alignment=").also { matched = it }.result != 0 -> compilerState.sectionAlignment = matched.optionArgument.orEmpty().toULongOrNull(16) ?: 0uL
                match("soname=|install_name=").also { matched = it }.result != 0 -> compilerState.soname = matched.optionArgument
                match("?whole-archive").also { matched = it }.result != 0 -> compilerState.fileType = if (matched.result > 0) compilerState.fileType or TYPE_WHOLE_ARCHIVE else compilerState.fileType and TYPE_WHOLE_ARCHIVE.inv()
                match("znodelete").also { matched = it }.result != 0 -> compilerState.noDelete = true
                peTarget && match("large-address-aware").also { matched = it }.result != 0 -> compilerState.peCharacteristics = compilerState.peCharacteristics or 0x20
                peTarget && match("?dynamicbase").also { matched = it }.result != 0 -> compilerState.peDllCharacteristics =
                    if (matched.result > 0) compilerState.peDllCharacteristics or 0x40 else compilerState.peDllCharacteristics and 0x60.inv()
                peTarget && match("?high-entropy-va").also { matched = it }.result != 0 -> compilerState.peDllCharacteristics =
                    if (matched.result > 0) compilerState.peDllCharacteristics or 0x60 else compilerState.peDllCharacteristics and 0x20.inv()
                peTarget && match("?nxcompat").also { matched = it }.result != 0 -> compilerState.peDllCharacteristics =
                    if (matched.result > 0) compilerState.peDllCharacteristics or 0x100 else compilerState.peDllCharacteristics and 0x100.inv()
                peTarget && match("?tsaware").also { matched = it }.result != 0 -> compilerState.peDllCharacteristics =
                    if (matched.result > 0) compilerState.peDllCharacteristics or 0x8000 else compilerState.peDllCharacteristics and 0x8000.inv()
                peTarget && match("file-alignment=").also { matched = it }.result != 0 -> compilerState.peFileAlignment = matched.optionArgument.orEmpty().toULongOrNull(16) ?: 0uL
                peTarget && match("stack=").also { matched = it }.result != 0 -> compilerState.peStackSize = matched.optionArgument.orEmpty().toULongOrNull() ?: 0uL
                peTarget && match("subsystem=").also { matched = it }.result != 0 -> if (setPeSubsystem(matched.optionArgument.orEmpty()) < 0) return reportError(compilerState, ERROR_NO_ABORT, "unsupported linker option '$option'").let { -1 }
                machoTarget && match("all_load").also { matched = it }.result != 0 -> compilerState.fileType = compilerState.fileType or TYPE_WHOLE_ARCHIVE
                machoTarget && match("force_load=").also { matched = it }.result != 0 -> addFile(matched.optionArgument.orEmpty(), TYPE_LIBRARY or TYPE_WHOLE_ARCHIVE)
                machoTarget && match("single_module").also { matched = it }.result != 0 -> warnUnsupported(option)
                match("as-needed|O|z=").also { matched = it }.result != 0 -> warnUnsupported(option)
                match("L:").also { matched = it }.result != 0 -> addLibraryPath(compilerState, matched.optionArgument.orEmpty())
                match("l:").also { matched = it }.result != 0 -> addFile(matched.optionArgument.orEmpty(), TYPE_BINARY or (compilerState.fileType and TYPE_WHOLE_ARCHIVE))
                matched.pendingSeparateArgument -> return 0
                else -> return reportError(compilerState, ERROR_NO_ABORT, "unsupported linker option '$option'").let { -1 }
            }
            compilerState.linkerArgumentIndex++
        }
        return 0
    }

    /** Option-table matcher and common command line actions from tcc_parse_args. */
    fun parseArguments(compilerState: CompilerState, arguments: List<String>, readListFile: (String) -> String? = { null },
        setLinker: (String) -> Int = { 0 }, pointerBits: Int = 64, nativeRun: Boolean = true, targetPlatform: String = "unix"): ParsedArguments {
        val argv = arguments.toMutableList()
        var index = if (argv.isNotEmpty()) 1 else 0
        var empty = true
        fun fail(message: String): ParsedArguments {
            reportError(compilerState, ERROR_NO_ABORT, message)
            return ParsedArguments(-1, argv.drop(index), argv.toList())
        }
        while (index < argv.size) {
            val raw = argv[index]
            if (raw.startsWith('@') && raw.length > 1) {
                val content = readListFile(raw.substring(1)) ?: return fail("listfile '${raw.substring(1)}' not found")
                argv.removeAt(index)
                argv.addAll(index, splitArguments(content, '\u0000'))
                continue
            }
            index++
            if (!raw.startsWith('-') || raw == "-") {
                compilerState.inputFiles += raw
                addArgumentFile(compilerState, raw, compilerState.fileType)
                empty = false
                if (compilerState.runCommand != null) break
                continue
            }
            if (raw == "--") break
            var selected: TccOption? = null
            var optionArgument = ""
            for (option in TCC_OPTIONS) {
                val tail = raw.substring(1)
                if (!tail.startsWith(option.name)) continue
                val rest = tail.substring(option.name.length)
                if (!option.hasArgument && rest.isNotEmpty()) continue
                selected = option
                optionArgument = rest
                if (option.hasArgument && rest.isEmpty() && !option.noSeparateArgument) {
                    if (index >= argv.size) return fail("argument to '$raw' is missing")
                    optionArgument = argv[index++]
                }
                break
            }
            if (selected == null) return fail("invalid option -- '$raw'")
            if (selected.index in setOf("compatibilityVersion", "currentVersion", "dynamiclib", "flatNamespace", "installName", "twoLevelNamespace", "undefined") && targetPlatform != "macho") return fail("invalid option -- '$raw'")
            if (selected.index == "armFloatAbi" && targetPlatform != "arm") return fail("invalid option -- '$raw'")
            if (selected.index == "impdef" && targetPlatform != "pe") return fail("invalid option -- '$raw'")
            when (selected.index) {
                "help" -> return ParsedArguments(OPTION_HELP, argv.drop(index - 1), argv.toList())
                "help2" -> return ParsedArguments(OPTION_HELP2, argv.drop(index - 1), argv.toList())
                "printDirs" -> return ParsedArguments(OPTION_PRINT_DIRS, argv.drop(index - 1), argv.toList())
                "dumpmachine" -> { output("${compilerState.targetTriple ?: "unknown-pc-unknown"}\n"); return ParsedArguments(0, argv.drop(index), argv.toList()) }
                "dumpversion" -> { output("${compilerState.compilerVersion ?: "unknown"}\n"); return ParsedArguments(0, argv.drop(index), argv.toList()) }
                "includePath" -> addIncludePath(compilerState, optionArgument)
                "systemInclude" -> addSystemIncludePath(compilerState, optionArgument)
                "libraryPath" -> addLibraryPath(compilerState, optionArgument)
                "libPath" -> setLibraryPath(compilerState, optionArgument)
                "define" -> defineSymbol(compilerState, optionArgument)
                "undefine" -> undefineSymbol(compilerState, optionArgument)
                "include" -> compilerState.commandLineIncludes += "#include \"$optionArgument\"\n"
                "library" -> { compilerState.inputFiles += optionArgument; addArgumentFile(compilerState, optionArgument, TYPE_LIBRARY or compilerState.fileType); compilerState.linkerArguments += "-l$optionArgument" }
                "output" -> {
                    if (compilerState.outputFile != null) reportError(compilerState, ERROR_WARNING, "multiple -o option")
                    compilerState.outputFile = optionArgument
                }
                "soname" -> compilerState.soname = optionArgument
                "object" -> compilerState.outputType = OUTPUT_OBJECT
                "shared" -> compilerState.outputType = OUTPUT_DLL
                "dynamiclib" -> compilerState.outputType = OUTPUT_DLL
                "flatNamespace", "twoLevelNamespace", "undefined" -> Unit
                "installName" -> compilerState.installName = optionArgument
                "compatibilityVersion" -> compilerState.compatibilityVersion = parseVersion(compilerState, optionArgument)
                "currentVersion" -> compilerState.currentVersion = parseVersion(compilerState, optionArgument)
                "armFloatAbi" -> if (optionArgument == "softfp" || optionArgument == "hard") compilerState.armFloatAbi = optionArgument else return fail("unsupported float abi '$optionArgument'")
                "rstdin" -> compilerState.runtimeStdin = optionArgument
                "backtrace" -> { compilerState.backtraceCallers = optionArgument.toIntOrNull() ?: 0; compilerState.doBacktrace = true; compilerState.debug = true }
                "bounds" -> { compilerState.boundsChecking = true; compilerState.doBacktrace = true; compilerState.debug = true }
                "relocatable" -> { compilerState.optionR = true; compilerState.outputType = OUTPUT_OBJECT }
                "preprocess" -> { compilerState.outputType = OUTPUT_PREPROCESS; compilerState.debug = false }
                "nostdinc" -> compilerState.noStandardIncludes = true
                "nostdlib" -> compilerState.noStandardLibrary = true
                "static" -> compilerState.staticLink = true
                "pthread" -> compilerState.optionPthread = true
                "bench" -> compilerState.doBench = true
                "verbose" -> compilerState.verbose += if (selected.name == "v") optionArgument.length.coerceAtLeast(1) else 1
                "warnNone" -> { compilerState.warnNoneMode = true; compilerState.warnNone = true }
                "feature" -> if (!setFeatureFlag(compilerState, optionArgument)) return fail("unsupported option '$raw'")
                "warning" -> if (optionArgument.isNotEmpty() && !setWarningFlag(compilerState, optionArgument)) return fail("unsupported option '$raw'")
                "machine" -> {
                    val requested = optionArgument.toIntOrNull()
                    if (requested == 32 || requested == 64) {
                        if (requested != pointerBits) return ParsedArguments(requested, argv.drop(index), argv.toList())
                    } else when (optionArgument.removePrefix("no-")) {
                        "ms-bitfields" -> compilerState.msBitfields = !optionArgument.startsWith("no-")
                        "sse" -> compilerState.noSse = optionArgument.startsWith("no-")
                        else -> return fail("unsupported option '$raw'")
                    }
                }
                "optimize" -> compilerState.optimize = optionArgument.firstOrNull()?.digitToIntOrNull() ?: if (optionArgument == "s") 2 else 1
                "std" -> { compilerState.languageStandard = optionArgument; if (optionArgument == "=c11" || optionArgument == "=gnu11") compilerState.cVersion = 201112 }
                "debug" -> {
                    compilerState.debug = true
                    compilerState.debugLevel = 2
                    when {
                        optionArgument.startsWith("dwarf") -> compilerState.dwarfVersion = optionArgument.removePrefix("dwarf").toIntOrNull()?.let { -it } ?: 4
                        optionArgument == "stabs" -> compilerState.dwarfVersion = 0
                        optionArgument.firstOrNull()?.isDigit() == true -> {
                            val level = optionArgument.first().digitToInt().coerceAtMost(2)
                            compilerState.debugLevel = if (level == 0 && compilerState.doBacktrace) 1 else level
                        }
                        optionArgument == ".pdb" && targetPlatform == "pe" -> { compilerState.dwarfVersion = 5; compilerState.debugLevel = compilerState.debugLevel or 16 }
                    }
                }
                "d" -> when (optionArgument.firstOrNull()) {
                    'D' -> compilerState.debugFlags = 3
                    'M' -> compilerState.debugFlags = 7
                    't' -> compilerState.debugFlags = 16
                    else -> optionArgument.toIntOrNull()?.let { compilerState.debugLevel = it } ?: return fail("unsupported option '$raw'")
                }
                "P" -> compilerState.preprocessLineControl = (optionArgument.toIntOrNull() ?: 0) + 1
                "linker" -> if (setLinker(optionArgument) < 0) return ParsedArguments(-1, argv.drop(index), argv.toList())
                "preprocessor" -> { argv.addAll(index - 1, splitArguments(optionArgument, ',')); index-- }
                "run" -> {
                    if (!nativeRun) return fail("-run is not available in a cross compiler")
                    compilerState.runCommand = optionArgument
                    compilerState.outputType = OUTPUT_MEMORY
                }
                "M", "MM", "MD", "MMD", "MF", "MP" -> {
                    when (selected.index) {
                        "M" -> { compilerState.includeSystemDependencies = true; compilerState.justDependencies = true; compilerState.generateDependencies = true; compilerState.dependencyOutputFile = "-" }
                        "MM" -> { compilerState.justDependencies = true; compilerState.generateDependencies = true; compilerState.dependencyOutputFile = "-" }
                        "MD" -> { compilerState.includeSystemDependencies = true; compilerState.generateDependencies = true; if (optionArgument.startsWith(',')) compilerState.dependencyOutputFile = optionArgument.drop(1) }
                        "MMD" -> { compilerState.generateDependencies = true; if (optionArgument.startsWith(',')) compilerState.dependencyOutputFile = optionArgument.drop(1) }
                        "MF" -> compilerState.dependencyOutputFile = optionArgument
                        "MP" -> compilerState.generatePhonyDependencies = true
                    }
                }
                "language" -> compilerState.fileType = (compilerState.fileType and TYPE_MASK.inv()) or when (optionArgument.firstOrNull()) { 'c' -> TYPE_C; 'a' -> TYPE_ASM_PREPROCESSED; 'b' -> TYPE_BINARY; 'n' -> 0; else -> compilerState.fileType and TYPE_MASK }
                "ignored", "ignoredArg" -> Unit
                "ar" -> return ParsedArguments(OPTION_AR, argv.drop(index - 1), argv.toList())
                "impdef" -> return ParsedArguments(OPTION_IMPDEF, argv.drop(index - 1), argv.toList())
                "rdynamic" -> compilerState.exportDynamic = true
            }
            empty = false
        }
        if (compilerState.runCommand != null) return ParsedArguments(0, argv.drop(index), argv.toList())
        if (!empty) return ParsedArguments(0, argv.drop(index), argv.toList())
        return ParsedArguments(if (compilerState.verbose == 2) OPTION_PRINT_DIRS else if (compilerState.verbose != 0) OPTION_V else OPTION_HELP, argv.drop(index), argv.toList())
    }

    fun setOptions(compilerState: CompilerState, optionText: String, setLinker: (String) -> Int = { 0 },
        pointerBits: Int = 64, nativeRun: Boolean = true, targetPlatform: String = "unix"): Int {
        val parsed = parseArguments(compilerState, listOf("") + splitArguments(optionText), setLinker = setLinker,
            pointerBits = pointerBits, nativeRun = nativeRun, targetPlatform = targetPlatform)
        return if (compilerState.runCommand != null) -1 else parsed.action
    }

    private fun setFeatureFlag(s: CompilerState, flag: String): Boolean {
        val enabled = !flag.startsWith("no-")
        val name = flag.removePrefix("no-")
        when (name) {
            "unsigned-char" -> s.charIsUnsigned = enabled
            "signed-char" -> s.charIsUnsigned = !enabled
            "common" -> s.noCommon = !enabled
            "leading-underscore" -> s.leadingUnderscore = enabled
            "ms-extensions" -> s.msExtensions = enabled
            "dollars-in-identifiers" -> s.dollarsInIdentifiers = enabled
            "test-coverage" -> s.testCoverage = enabled
            "reverse-funcargs" -> s.reverseFuncargs = enabled
            "gnu89-inline" -> s.gnu89Inline = enabled
            "asynchronous-unwind-tables" -> s.unwindTables = enabled
            else -> return false
        }
        return true
    }

    private fun setWarningFlag(s: CompilerState, flag: String): Boolean {
        val enabled = !flag.startsWith("no-")
        val name = flag.removePrefix("no-")
        when {
            name == "all" -> { s.warnAll = enabled; s.warnImplicitFunction = enabled; s.warnDiscardedQualifiers = enabled }
            name == "error" -> s.warnError = enabled
            name == "write-strings" -> s.warnWriteStrings = enabled
            name == "unsupported" -> s.warnUnsupported = enabled
            name == "implicit-function-declaration" -> s.warnImplicitFunction = enabled
            name == "discarded-qualifiers" -> s.warnDiscardedQualifiers = enabled
            name.startsWith("error=") -> {
                val warningName = name.removePrefix("error=")
                if (warningName !in setOf("all", "error", "write-strings", "unsupported", "implicit-function-declaration", "discarded-qualifiers")) return false
                val optionFlags = if (enabled) WARN_ON or WARN_ERR else WARN_NOE
                s.warningOverrides[warningName] = optionFlags
                s.warningOption = optionFlags
            }
            else -> return false
        }
        return true
    }

    fun insertArguments(arguments: MutableList<String>, index: Int, insertedText: String, separator: Char? = null) {
        arguments.addAll(index.coerceIn(0, arguments.size), splitArguments(insertedText, separator))
    }

    fun addArgumentFile(compilerState: CompilerState, filename: String, fileType: Int) {
        compilerState.files += FileSpec(filename, fileType)
        if (fileType and TYPE_LIBRARY != 0) compilerState.libraryCount++
    }

    fun parseVersion(compilerState: CompilerState, version: String): Int {
        val match = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$").matchEntire(version)
        val major = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minor = match?.groupValues?.get(2)?.toLongOrNull() ?: 0L
        val patch = match?.groupValues?.get(3)?.toLongOrNull() ?: 0L
        if (match == null || major > 0xffff || minor > 0xff || patch > 0xff)
            reportError(compilerState, ERROR_NO_ABORT, "version a.b.c not correct: $version")
        return (((major and 0xffff) shl 16) or ((minor and 0xff) shl 8) or (patch and 0xff)).toInt()
    }

    fun formatStatistics(stats: OutputStatistics, elapsedMillis: Long): List<String> {
        val elapsed = elapsedMillis.coerceAtLeast(1)
        return listOf(
            "# ${stats.identifiers} idents, ${stats.lines} lines, ${stats.bytes} bytes",
            "# %.3f s, %d lines/s, %.1f MB/s".format(elapsed / 1000.0, stats.lines * 1000 / elapsed, stats.bytes * 1000.0 / elapsed / 1_000_000.0),
            "# text ${stats.text}, data.rw ${stats.writableData}, data.ro ${stats.readOnlyData}, bss ${stats.bss} bytes",
        )
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
    fun openFile(path: String, mode: String): RandomAccessFile {
        val access = if (mode.contains('w') || mode.contains('+') || mode.contains('a')) "rw" else "r"
        return RandomAccessFile(path, access).apply {
            if (mode.contains('w')) setLength(0)
            if (mode.contains('a')) seek(length())
        }
    }
    fun closeFile(file: RandomAccessFile) = file.close()

    /** Derives the executable directory used as CONFIG_TCCDIR on Windows. */
    fun windowsInstallDirectory(modulePath: String): String = normalizeSlashes(modulePath.lowercase()).let { path ->
        val end = path.lastIndexOf('/')
        if (end < 0) "" else path.substring(0, end)
    }
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
        val bytes = customReallocator?.invoke(null, size)?.copyOf(size) ?: ByteArray(size)
        val allocation = Allocation(nextAllocationId++, bytes, sourceFile, sourceLine)
        allocations[allocation.id] = allocation
        currentMemoryBytes += size
        maximumMemoryBytes = maxOf(maximumMemoryBytes, currentMemoryBytes)
        return allocation
    }

    fun resize(allocation: Allocation?, size: Int, sourceFile: String? = null, sourceLine: Int = 0): Allocation? {
        if (allocation == null) return if (size == 0) null else allocate(size, sourceFile, sourceLine)
        require(size >= 0)
        if (size == 0) { free(allocation); return null }
        val replacement = customReallocator?.invoke(allocation, size)?.copyOf(size) ?: allocation.bytes.copyOf(size)
        currentMemoryBytes += size - allocation.bytes.size
        allocation.bytes = replacement
        maximumMemoryBytes = maxOf(maximumMemoryBytes, currentMemoryBytes)
        return allocation
    }

    fun free(allocation: Allocation?) {
        if (allocation != null && allocations.remove(allocation.id) != null) {
            customReallocator?.invoke(allocation, 0)
            currentMemoryBytes -= allocation.bytes.size
        }
    }

    fun setReallocator(reallocator: ((Allocation?, Int) -> ByteArray?)?) { customReallocator = reallocator }
    fun allocateZeroed(size: Int, sourceFile: String? = null, sourceLine: Int = 0): Allocation =
        allocate(size, sourceFile, sourceLine).also { it.bytes.fill(0) }

    fun duplicate(value: String, sourceFile: String? = null, sourceLine: Int = 0): Allocation =
        allocate(value.toByteArray().size + 1, sourceFile, sourceLine).also { value.toByteArray().copyInto(it.bytes) }

    fun memoryStats(): MemoryStats = MemoryStats(currentMemoryBytes, maximumMemoryBytes, allocations.size)
    fun memoryCheck() {
        if (allocations.isNotEmpty()) output("MEM_DEBUG: mem_leak= $currentMemoryBytes bytes, mem_max_size= $maximumMemoryBytes bytes\n")
    }

    fun memoryCheck(stateDelta: Int) {
        liveStateCount += stateDelta
        if (liveStateCount == 0 && currentMemoryBytes != 0L) {
            output("MEM_DEBUG: mem_leak= $currentMemoryBytes bytes, mem_max_size= $maximumMemoryBytes bytes\n")
            allocations.values.forEach { allocation ->
                output("${allocation.sourceFile ?: "<unknown>"}:${allocation.sourceLine}: error: ${allocation.bytes.size} bytes leaked\n")
            }
            currentMemoryBytes = 0
            maximumMemoryBytes = 0
            allocations.clear()
        }
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

    fun openSourceFile(compilerState: CompilerState, filename: String, includeDepth: Int, hooks: SourceFileHooks): Int {
        val displayName = if (filename == "-") "<stdin>" else filename
        val descriptor = if (filename == "-") 0 else hooks.open(filename)
        if ((compilerState.verbose == 2 && descriptor >= 0) || compilerState.verbose == 3) {
            output("${if (descriptor < 0) "nf" else "->"} ${" ".repeat(includeDepth.coerceAtLeast(0))}$displayName\n")
        }
        if (descriptor < 0) return -1
        openBufferedSource(filename).fileDescriptor = descriptor
        return 0
    }

    fun closeBufferedSource() {
        val current = sourceFile ?: return
        if (current.fileDescriptor > 0) totalLines += current.lineNumber - 1L
        sourceFile = current.previous
        tokenFlags = current.tokenFlags
    }

    fun closeBufferedSource(hooks: SourceFileHooks) {
        val current = sourceFile ?: return
        if (current.fileDescriptor > 0) {
            hooks.close(current.fileDescriptor)
            totalLines += current.lineNumber - 1L
        }
        sourceFile = current.previous
        tokenFlags = current.tokenFlags
    }
}
