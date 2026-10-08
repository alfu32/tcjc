package tcc.kt

import java.io.File
import java.io.Closeable
import java.io.PrintStream

/** Kotlin translation of the command-line driver in the original tcc.c. */
object TccDriver {
    const val HELP = """Tiny C Compiler @VERSION@ - Copyright (C) 2001-2006 Fabrice Bellard
Usage: tcc [options...] [-o outfile] [-c] infile(s)...
       tcc [options...] -run infile (or --) [arguments...]
General options:
  -c           compile only - generate an object file
  -o outfile   set output filename
  -run         run compiled source
  -fflag       set or reset (with 'no-' prefix) 'flag' (see tcc -hh)
  -Wwarning    set or reset (with 'no-' prefix) 'warning' (see tcc -hh)
  -w           disable all warnings
  -v --version show version
  -vv          show search paths or loaded files
  -h -hh       show this, show more help
  -bench       show compilation statistics
  -            use stdin pipe as infile
  @listfile    read arguments from listfile
Preprocessor options:
  -Idir        add include path 'dir'
  -Dsym[=val]  define 'sym' with value 'val'
  -Usym        undefine 'sym'
  -E           preprocess only
  -nostdinc    do not use standard system include paths
Linker options:
  -Ldir        add library path 'dir'
  -llib        link with dynamic or static library 'lib'
  -nostdlib    do not link with standard crt and libraries
  -r           generate (relocatable) object file
  -rdynamic    export all global symbols to dynamic linker
  -shared      generate a shared library/dll
  -soname      set name for shared library to be used at runtime
  -Wl,-opt[=val]  set linker option (see tcc -hh)
Debugger options:
  -g           generate stab runtime debug info
  -gdwarf[-x]  generate dwarf runtime debug info
  -g.pdb       create .pdb debug database (PE targets)
  -b           compile with built-in memory and bounds checker (if enabled)
  -bt[N]       link with backtrace support (if enabled)
Misc. options:
  -std=version define __STDC_VERSION__ according to version (c11/gnu11)
  -x[c|a|b|n]  specify type of the next infile (C,ASM,BIN,NONE)
  -Bdir        set tcc's private include/library dir
  -M[M]D       generate make dependency file [ignore system files]
  -M[M]        as above but no other output
  -MF file     specify dependency file name
  -m32/64      defer to i386/x86_64 cross compiler
Tools:
  create library  : tcc -ar [crstvx] lib [files]
  create def file : tcc -impdef lib.dll [-v] [-o lib.def] (PE targets)
Discussion & bug reports:
  https://lists.nongnu.org/mailman/listinfo/tinycc-devel
"""

    const val HELP_MORE = """Tiny C Compiler @VERSION@ - More Options
Special options:
  -P -P1                        with -E: no/alternative #line output
  -dD -dM                       with -E: output #define directives
  -pthread                      same as -D_REENTRANT and -lpthread
  -On                           same as -D__OPTIMIZE__ for n > 0
  -Wp,-opt                      same as -opt
  -include file                 include 'file' above each input file
  -nostdlib                     do not link with standard crt/libs
  -isystem dir                  add 'dir' to system include path
  -static                       link to static libraries (not recommended)
  -dumpversion                  print version
  -print-search-dirs            print search paths
  -rstdin file                  with -run: use 'file' as custom stdin
  -dt                           with -run/-E: auto-define 'test_...' macros
Ignored options:
  -arch -C --param -pedantic -pipe -s -traditional
-W[no-]... warnings:
  all                           turn on some (*) warnings
  error[=warning]               stop after warning (any or specified)
  write-strings                 strings are const
  unsupported                   warn about ignored options, pragmas, etc.
  implicit-function-declaration warn for missing prototype (*)
  discarded-qualifiers          warn when const is dropped (*)
-f[no-]... flags:
  unsigned-char                 default char is unsigned
  signed-char                   default char is signed
  common                        use common section instead of bss
  leading-underscore            decorate extern symbols
  ms-extensions                 allow anonymous struct in struct
  dollars-in-identifiers        allow '$' in C symbols
  reverse-funcargs              evaluate function arguments right to left
  gnu89-inline                  'extern inline' is like 'static inline'
  asynchronous-unwind-tables    create eh_frame section [on]
  test-coverage                 create code coverage code
-m... target specific options:
  ms-bitfields                  use MSVC bitfield layout
  float-abi                     hard/softfp on arm
  no-sse                        disable floats on x86_64
-Wl,... linker options:
  -nostdlib                     do not search standard library paths
  -[no-]whole-archive           load lib(s) fully/only as needed
  -export-all-symbols           same as -rdynamic
  -export-dynamic               same as -rdynamic
  -image-base= -Ttext=          set base address of executable
  -section-alignment=           set section alignment in executable
  -file-alignment=              set PE file alignment
  -stack=                       set PE stack reserve
  -large-address-aware          set related PE option
  -subsystem=[console/windows]  set PE subsystem
  -oformat=[pe-* binary]        set executable output format
  -rpath=                       set dynamic library search path
  -enable-new-dtags             set DT_RUNPATH instead of DT_RPATH
  -soname=                      set DT_SONAME elf tag
  -install_name=                set DT_SONAME elf tag (soname macOS alias)
  -Ipath, -dynamic-linker=path  set ELF interpreter to path
  -Bsymbolic                    set DT_SYMBOLIC elf tag
  -oformat=[elf32/64-* binary]  set executable output format
  -init= -fini= -Map= -as-needed -O -z= (ignored)
Predefined macros:
  tcc -E -dM - < /dev/null
See also the manual for more details.
"""

    /** Mirrors print_dirs(): emit a heading and each configured path. */
    fun printDirs(out: PrintStream, message: String, paths: List<String>) {
        out.println("$message:")
        if (paths.isEmpty()) out.println("  -")
        else paths.forEach { out.println("  $it") }
    }

    /** Mirrors print_search_dirs() for the common include and library paths. */
    fun printSearchDirs(
        out: PrintStream,
        installPath: String,
        includePaths: List<String>,
        libraryPaths: List<String>,
        libTcc1: String,
        crtPaths: List<String> = emptyList(),
        elfInterpreter: String? = null,
    ) {
        out.println("install: $installPath")
        printDirs(out, "include", includePaths)
        printDirs(out, "libraries", libraryPaths)
        out.println("libtcc1:")
        out.println("  ${libraryPaths.firstOrNull().orEmpty()}/$libTcc1")
        if (elfInterpreter != null) {
            printDirs(out, "crt", crtPaths)
            out.println("elfinterp:")
            out.println("  $elfInterpreter")
        }
    }

    /** Mirrors set_environment() and preserves each variable's path-list value. */
    fun setEnvironment(
        addSystemIncludePath: (String) -> Unit,
        addIncludePath: (String) -> Unit,
        addLibraryPath: (String) -> Unit,
        environment: Map<String, String> = System.getenv(),
    ) {
        environment["C_INCLUDE_PATH"]?.let(addSystemIncludePath)
        environment["CPATH"]?.let(addIncludePath)
        environment["LIBRARY_PATH"]?.let(addLibraryPath)
    }

    /** Mirrors default_outputfile(), including PE executable and DLL suffixes. */
    fun defaultOutputFile(
        firstFile: String?,
        outputType: Int,
        justDependencies: Boolean,
        relocatable: Boolean,
        peTarget: Boolean = false,
    ): String {
        var name = if (firstFile != null && firstFile != "-") File(firstFile).name else "a"
        if (name.length + 4 >= 1024) name = "a"
        val extensionAt = name.lastIndexOf('.')
        val hasExtension = extensionAt >= 0
        val stem = if (hasExtension) name.substring(0, extensionAt) else name
        if (peTarget && outputType == TccOutputType.DYNAMIC_LIBRARY) return "$stem.dll"
        if (peTarget && outputType == TccOutputType.EXECUTABLE) return "$stem.exe"
        return if ((justDependencies || outputType == TccOutputType.OBJECT) && !relocatable && hasExtension) {
            "$stem.o"
        } else {
            "a.out"
        }
    }

    /** Millisecond clock used for compilation timing. */
    fun clockMillis(): Long = System.currentTimeMillis()

    /** Kotlin translation of tcc.c's main() orchestration loop. */
    fun run(arguments: Array<String>, api: TccApi): Int {
        val originalArguments = arguments.toList()
        var fileIndex = 0
        var testIndex = 0
        var standardOutput: Closeable? = null

        while (true) {
            val state = api.newState()
            val args = originalArguments.toMutableList()
            val option = api.parseArgs(state, args)

            if (fileIndex == 0) {
                var result = 0
                when (option) {
                    TccOption.HELP -> {
                        api.stdout.print(HELP.replace("@VERSION@", api.compilerVersion))
                        if (state.verbose != 0)
                            api.stdout.print(HELP_MORE.replace("@VERSION@", api.compilerVersion))
                    }
                    TccOption.HELP_MORE -> api.stdout.print(HELP_MORE.replace("@VERSION@", api.compilerVersion))
                    TccOption.M32, TccOption.M64 -> result = api.crossCompile(args, option)
                    else -> if (state.verbose != 0) api.stdout.print(api.versionString)
                }

                when (option) {
                    TccOption.ARCHIVE -> result = api.createArchive(args)
                    TccOption.IMPORT_DEFINITION -> result = api.createImportDefinition(args)
                    TccOption.PRINT_DIRS -> {
                        setEnvironment(
                            { api.addSystemIncludePath(state, it) },
                            { api.addIncludePath(state, it) },
                            { api.addLibraryPath(state, it) },
                        )
                        api.setOutputType(state, TccOutputType.MEMORY)
                        printSearchDirs(
                            api.stdout,
                            state.libraryInstallPath,
                            state.systemIncludePaths,
                            state.libraryPaths,
                            api.libTcc1Name,
                            state.crtPaths,
                            state.elfInterpreter,
                        )
                    }
                    else -> Unit
                }

                if (option != TccOption.NONE) {
                    api.deleteState(state)
                    return if (option == TccOption.INVALID) 1 else result
                }

                when {
                    state.files.isEmpty() -> api.errorNoAbort(state, "no input files")
                    state.outputType == TccOutputType.PREPROCESS -> {
                        val output = state.outputFile
                        if (output != null && output != "-") {
                            standardOutput = api.openOutput(state, output)
                            if (standardOutput == null)
                                api.errorNoAbort(state, "could not write '$output'")
                        }
                    }
                    state.outputType == TccOutputType.OBJECT && !state.relocatable -> {
                        if (state.libraryCount != 0)
                            api.errorNoAbort(state, "cannot specify libraries with -c")
                        else if (state.files.size > 1 && state.outputFile != null)
                            api.errorNoAbort(state, "cannot specify output file with -c many files")
                    }
                }
                if (state.errorCount != 0) {
                    api.deleteState(state)
                    standardOutput?.close()
                    return 1
                }
                if (state.benchmark) state.startTime = clockMillis()
            }

            setEnvironment(
                { api.addSystemIncludePath(state, it) },
                { api.addIncludePath(state, it) },
                { api.addLibraryPath(state, it) },
            )
            if (state.outputType == TccOutputType.UNSPECIFIED)
                state.outputType = TccOutputType.EXECUTABLE
            var result = api.setOutputType(state, state.outputType)
            if (standardOutput != null) state.preprocessorOutput = standardOutput

            if ((state.outputType == TccOutputType.MEMORY ||
                    state.outputType == TccOutputType.PREPROCESS) && (state.debugFlags and 16) != 0) {
                if (testIndex != 0) state.debugFlags = state.debugFlags or 32
                state.runTest = ++testIndex
                if (fileIndex != 0) fileIndex--
            }

            var firstFile: String? = null
            while (result == 0 && fileIndex < state.files.size) {
                val file = state.files[fileIndex]
                state.fileType = file.type
                if (api.isLibraryFile(file.type)) {
                    result = api.addLibrary(state, file.name)
                } else {
                    if (state.verbose == 1) api.stdout.println("-> ${file.name}")
                    if (firstFile == null) firstFile = file.name
                    result = api.addFile(state, file.name)
                }
                fileIndex++
                if (fileIndex == state.files.size) break
                if (state.outputType == TccOutputType.OBJECT && !state.relocatable) break
            }

            if (state.benchmark) state.endTime = clockMillis()

            if (state.runTest != 0) {
                testIndex = 0
            } else if (state.outputType != TccOutputType.PREPROCESS && result == 0) {
                if (state.outputType == TccOutputType.MEMORY) {
                    if (api.isNative) result = api.runCompiled(state, args)
                } else {
                    if (state.outputFile == null)
                        state.outputFile = defaultOutputFile(
                            firstFile,
                            state.outputType,
                            state.justDependencies,
                            state.relocatable,
                            api.peTarget,
                        )
                    if (!state.justDependencies)
                        result = api.writeOutput(state, state.outputFile!!)
                    if (result == 0 && state.generateDependencies)
                        api.generateDependencies(state, state.outputFile!!, state.dependenciesOutputFile)
                }
            }

            var done = true
            if (testIndex != 0) done = false
            else if (result != 0) {
                if (state.errorCount != 0) result = 1
            } else if (fileIndex < state.files.size) done = false
            else if (state.benchmark)
                api.printStatistics(state, state.endTime - state.startTime)

            api.deleteState(state)
            if (done) {
                standardOutput?.close()
                return result
            }
        }
    }

}

enum class TccOption {
    NONE, INVALID, HELP, HELP_MORE, M32, M64, ARCHIVE, IMPORT_DEFINITION, PRINT_DIRS
}

object TccOutputType {
    const val UNSPECIFIED = 0
    const val MEMORY = 1
    const val PREPROCESS = 2
    const val OBJECT = 3
    const val EXECUTABLE = 4
    const val DYNAMIC_LIBRARY = 5
}

data class TccFileSpec(val name: String, val type: Int)

/** State fields referenced by tcc.c; their ownership remains with the backend. */
class TccState {
    var verbose: Int = 0
    var outputType: Int = TccOutputType.UNSPECIFIED
    var outputFile: String? = null
    var relocatable: Boolean = false
    var files: List<TccFileSpec> = emptyList()
    var fileType: Int = 0
    var libraryCount: Int = 0
    var errorCount: Int = 0
    var benchmark: Boolean = false
    var startTime: Long = 0
    var endTime: Long = 0
    var debugFlags: Int = 0
    var runTest: Int = 0
    var justDependencies: Boolean = false
    var generateDependencies: Boolean = false
    var dependenciesOutputFile: String? = null
    var preprocessorOutput: Closeable? = null
    var libraryInstallPath: String = ""
    var systemIncludePaths: List<String> = emptyList()
    var libraryPaths: List<String> = emptyList()
    var crtPaths: List<String> = emptyList()
    var elfInterpreter: String? = null
}

/** Calls implemented by the still-to-be-translated library and tool C files. */
interface TccApi {
    val stdout: PrintStream
    val compilerVersion: String
    val versionString: String
    val libTcc1Name: String
    val isNative: Boolean
    val peTarget: Boolean

    fun newState(): TccState
    fun deleteState(state: TccState)
    fun parseArgs(state: TccState, args: MutableList<String>): TccOption
    fun crossCompile(args: List<String>, option: TccOption): Int
    fun createArchive(args: List<String>): Int
    fun createImportDefinition(args: List<String>): Int
    fun setOutputType(state: TccState, type: Int): Int
    fun addSystemIncludePath(state: TccState, path: String)
    fun addIncludePath(state: TccState, path: String)
    fun addLibraryPath(state: TccState, path: String)
    fun errorNoAbort(state: TccState, message: String)
    fun openOutput(state: TccState, path: String): Closeable?
    fun isLibraryFile(type: Int): Boolean
    fun addLibrary(state: TccState, name: String): Int
    fun addFile(state: TccState, name: String): Int
    fun runCompiled(state: TccState, args: List<String>): Int
    fun writeOutput(state: TccState, path: String): Int
    fun generateDependencies(state: TccState, outputFile: String, dependenciesFile: String?)
    fun printStatistics(state: TccState, elapsedMilliseconds: Long)
}
