package tcc.kt

import java.io.Closeable
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val SHF_ALLOC = 0x2
private const val SHF_WRITE = 0x1
private const val SHF_EXECINSTR = 0x4
private const val SHF_TLS = 0x400
private const val SHT_NOBITS = 8
private const val RT_EXIT_ZERO = 0xE0E00E0E.toInt()
private const val INCLUDE_STACK_SIZE = 32
private const val STAB_FUN = 0x24
private const val STAB_SLINE = 0x44
private const val STAB_SO = 0x64
private const val STAB_BINCL = 0x82
private const val STAB_SOL = 0x84
private const val STAB_EINCL = 0xa2

data class TccRunSection(
    val name: String,
    var data: ByteArray?,
    val dataOffset: Int,
    val sectionType: Int,
    val flags: Int,
    var alignment: Int,
    var address: Long = 0,
)

data class TccRunFrame(val instructionPointer: Long, val framePointer: Long, val stackPointer: Long)
enum class TccRunArchitecture { I386, X86_64, ARM32, ARM64, RISCV64, OTHER }
fun interface TccRunMemoryReader { fun readPointer(address: Long): Long? }
data class TccRunStabSymbol(val type: Int, val stringIndex: Int, val value: Long, val line: Int = 0)
data class TccRunElfSymbol(val type: Int, val value: Long, val size: Long, val name: String)

data class TccRunDebugContext(
    val stabSymbols: List<TccRunStabSymbol> = emptyList(),
    val stabStrings: String = "",
    val elfSymbols: List<TccRunElfSymbol> = emptyList(),
    val dwarfLine: ByteArray = byteArrayOf(),
    val dwarfLineStrings: ByteArray = byteArrayOf(),
    var programBase: Long = 0,
    var boundsStart: Any? = null,
    val pointerSize: Int = 8,
    val targetIsMachO: Boolean = false,
    var topFunction: Long = 0,
    var numberOfCallers: Int = 0,
    var dwarf: Boolean = false,
    var dwarfLineResolver: ((Long, TccRunBacktraceInfo) -> Long)? = null,
    var next: TccRunDebugContext? = null,
)

data class TccRunBacktraceInfo(
    var file: String = "",
    var line: Int = 0,
    var function: String = "",
    var functionAddress: Long = 0,
)

fun interface TccBacktraceCallback {
    fun call(data: Any?, pc: Long, file: String?, line: Int, function: String?, message: String?): Int
}

interface TccRunMemory : Closeable {
    val baseAddress: Long
    val writableAliasDifference: Long
    val pageSize: Int
    fun write(address: Long, source: ByteArray?, length: Int, zeroFill: Boolean)
    fun protect(address: Long, length: Int, mode: Int): Boolean
}

enum class TccRunMemoryMode { HEAP, VIRTUAL_ALLOC, SELINUX_DUAL_MAP }

class TccRunState(
    val sections: MutableList<TccRunSection>,
    var outputType: Int = TccOutputType.MEMORY,
) {
    var runMemory: TccRunMemory? = null
    var runSize: Int = 0
    var memoryMode: TccRunMemoryMode = TccRunMemoryMode.HEAP
    var errorCount: Int = 0
    var verbose: Int = 0
    var doBacktrace: Boolean = false
    var doBoundsCheck: Boolean = false
    var debugFlags: Int = 0
    var runTest: Int = 0
    var entryName: String? = null
    var runMainName: String = "_runmain"
    var runStdin: String? = null
    var preprocessorOutput: PrintStream? = null
    var debugContext: TccRunDebugContext? = null
    var backtraceCallback: TccBacktraceCallback? = null
    var backtraceData: Any? = null
    var runJumpBuffer: Any? = null
    var runLongJump: ((Any?, Int) -> Unit)? = null
    var next: TccRunState? = null
}

/** Compiler operations not owned by tccrun.c; supplied by the rest of the Kotlin port. */
interface TccRunBackend {
    val nativeTarget: Boolean
    val targetIsPe: Boolean
    val targetIsX86: Boolean
    val readOnlyTextPages: Boolean
    val pageSize: Int
    fun allocateMemory(size: Int, mode: TccRunMemoryMode): TccRunMemory?
    fun prepareRelocation(state: TccRunState)
    fun addBacktraceSymbol(state: TccRunState)
    fun addRuntime(state: TccRunState)
    fun resolveCommonSymbols(state: TccRunState)
    fun buildGotEntries(state: TccRunState)
    fun relocateSymbols(state: TccRunState)
    fun relocatePlt(state: TccRunState)
    fun relocateSections(state: TccRunState)
    fun cleanupLocalSymbols(state: TccRunState)
    fun cleanupSections(state: TccRunState)
    fun addSupport(state: TccRunState, objectName: String)
    fun addSymbol(state: TccRunState, name: String, value: Any)
    fun hasSymbol(state: TccRunState, name: String): Boolean
    fun setEntryPoint(state: TccRunState, symbolName: String): Boolean
    fun reopenStandardInput(path: String): Boolean
    fun invokeEntry(state: TccRunState, argc: Int, argv: Array<String>, envp: Array<String>): Int
    fun setJump(state: TccRunState, entryName: String): Int
    fun linkState(state: TccRunState)
    fun unlinkState(state: TccRunState)
    fun unloadLibrary(handle: Any)
    fun loadedLibraries(state: TccRunState): List<Any?>
    fun addUnwindFunctionTable(state: TccRunState): Any?
    fun deleteUnwindFunctionTable(table: Any?)
    fun runtimeError(message: String): Int
    fun closeRuntimeMemory(memory: TccRunMemory)
    val standardOutput: PrintStream
    val standardError: PrintStream
}

/** Core memory-relocation and -run support translated from tccrun.c. */
object TccRun {
    private val stateLock = ReentrantLock()
    private val linkedStates = mutableListOf<TccRunState>()
    private val debugContexts = mutableListOf<TccRunDebugContext>()
    private val unwindTables = mutableMapOf<TccRunState, Any?>()

    fun tcc_relocate(state: TccRunState, backend: TccRunBackend): Int {
        if (state.runMemory != null) {
            kotlin.system.exitProcess(backend.runtimeError("'tcc_relocate()' twice is no longer supported"))
        }
        if (state.doBacktrace) backend.addBacktraceSymbol(state)
        val requiredSize = relocateEx(state, null, 0, backend)
        if (requiredSize < 0) return -1
        val allocationSize = when (state.memoryMode) {
            TccRunMemoryMode.HEAP -> requiredSize + backend.pageSize
            TccRunMemoryMode.VIRTUAL_ALLOC -> requiredSize
            TccRunMemoryMode.SELINUX_DUAL_MAP -> requiredSize * 2
        }
        val memory = backend.allocateMemory(allocationSize, state.memoryMode)
            ?: return backend.runtimeError("tccrun: could not allocate memory")
        state.runMemory = memory
        state.runSize = when (state.memoryMode) {
            TccRunMemoryMode.HEAP -> requiredSize + memory.pageSize
            TccRunMemoryMode.VIRTUAL_ALLOC -> requiredSize
            TccRunMemoryMode.SELINUX_DUAL_MAP -> requiredSize * 2
        }
        val result = relocateEx(state, memory, memory.writableAliasDifference, backend)
        if (result == 0) st_link(state, backend)
        return result
    }

    fun tcc_run_free(state: TccRunState, backend: TccRunBackend) {
        backend.loadedLibraries(state).forEach { handle -> if (handle != null) backend.unloadLibrary(handle) }
        val memory = state.runMemory ?: return
        st_unlink(state, backend)
        backend.deleteUnwindFunctionTable(unwindTables.remove(state))
        backend.closeRuntimeMemory(memory)
        state.runMemory = null
    }

    fun tcc_run(state: TccRunState, args: Array<String>, backend: TccRunBackend): Int {
        if (!backend.nativeTarget) return -1
        if ((state.debugFlags and 16) != 0 && !backend.hasSymbol(state, "main")) return 0

        backend.addSymbol(state, "__rt_exit", ::rt_exitFrame)
        state.runMainName = "_runmain"
        var topSymbol = "main"
        if (state.entryName != null) {
            state.runMainName = state.entryName!!
            topSymbol = state.entryName!!
        }
        backend.addSupport(state, "runmain.o")
        if (tcc_relocate(state, backend) < 0) return -1
        if (!backend.setEntryPoint(state, state.runMainName)) return -1

        state.runStdin?.let { path ->
            if (!backend.reopenStandardInput(path)) {
                backend.runtimeError("failed to reopen stdin from '$path'")
                return -1
            }
        }

        backend.standardOutput.flush()
        backend.standardError.flush()
        val jumpResult = backend.setJump(state, topSymbol)
        val result = when (jumpResult) {
            0 -> backend.invokeEntry(state, args.size, args, System.getenv().map { "${it.key}=${it.value}" }.toTypedArray())
            RT_EXIT_ZERO -> 0
            else -> jumpResult
        }
        if ((state.debugFlags and 16) != 0 && result != 0) {
            state.preprocessorOutput?.let {
                it.printf("[returns %d]%n", result)
                it.flush()
            }
        }
        return result
    }

    fun _tcc_setjmp(
        state: TccRunState,
        jumpBuffer: Any?,
        functionAddress: Long,
        longJump: (Any?, Int) -> Unit,
    ): Any? {
        state.runLongJump = longJump
        state.runJumpBuffer = jumpBuffer
        state.debugContext?.topFunction = functionAddress
        return jumpBuffer
    }

    fun tcc_set_backtrace_func(state: TccRunState, data: Any?, function: TccBacktraceCallback?) {
        state.backtraceData = data
        state.backtraceCallback = function
    }

    /** Architecture-specific frame-chain walk from rt_get_caller_pc(). */
    fun rt_get_caller_pc(
        frame: TccRunFrame,
        level: Int,
        architecture: TccRunArchitecture,
        pointerSize: Int,
        memory: TccRunMemoryReader,
    ): Long? {
        if (level < 0) return null
        if (level == 0) return frame.instructionPointer
        var remaining = level
        var fp = frame.framePointer
        val chainOffset: Long
        val returnOffset: Long
        when (architecture) {
            TccRunArchitecture.I386, TccRunArchitecture.X86_64 -> {
                chainOffset = 0
                returnOffset = pointerSize.toLong()
            }
            TccRunArchitecture.ARM32 -> {
                chainOffset = 0
                returnOffset = 2L * pointerSize
            }
            TccRunArchitecture.ARM64 -> {
                chainOffset = 0
                returnOffset = pointerSize.toLong()
            }
            TccRunArchitecture.RISCV64 -> {
                chainOffset = -2L * pointerSize
                returnOffset = -pointerSize.toLong()
            }
            TccRunArchitecture.OTHER -> return null
        }
        while (true) {
            if (fp < 0x1000) return null
            remaining--
            if (remaining == 0) break
            fp = memory.readPointer(fp + chainOffset) ?: return null
        }
        return memory.readPointer(fp + returnOffset)
    }

    /** Map the platform's saved general registers to the internal frame shape. */
    fun rt_getcontext(registers: Map<String, Long>, architecture: TccRunArchitecture): TccRunFrame = when (architecture) {
        TccRunArchitecture.I386 -> TccRunFrame(registers.getValue("eip"), registers.getValue("ebp"), registers.getValue("esp"))
        TccRunArchitecture.X86_64 -> TccRunFrame(registers.getValue("rip"), registers.getValue("rbp"), registers.getValue("rsp"))
        TccRunArchitecture.ARM32 -> TccRunFrame(registers.getValue("pc"), registers.getValue("fp"), registers.getValue("sp"))
        TccRunArchitecture.ARM64 -> TccRunFrame(registers.getValue("pc"), registers.getValue("x29"), registers.getValue("sp"))
        TccRunArchitecture.RISCV64 -> TccRunFrame(registers.getValue("pc"), registers.getValue("s0"), registers.getValue("sp"))
        TccRunArchitecture.OTHER -> TccRunFrame(0, 0, 0)
    }

    fun rt_find_state(frame: TccRunFrame, callerProgramCounters: List<Long>): TccRunState? = stateLock.withLock {
        if (linkedStates.size <= 1) return@withLock linkedStates.firstOrNull()
        for (pc in callerProgramCounters.take(8)) {
            linkedStates.firstOrNull { state ->
                val base = state.runMemory?.baseAddress ?: return@firstOrNull false
                pc >= base && pc < base + state.runSize
            }?.let { return@withLock it }
        }
        null
    }

    fun rt_exit(
        frame: TccRunFrame,
        code: Int,
        callerProgramCounters: List<Long> = listOf(frame.instructionPointer),
    ): Nothing {
        val state = rt_find_state(frame, callerProgramCounters)
        val longJump = state?.runLongJump
        if (longJump != null) {
            longJump(state?.runJumpBuffer, if (code == 0) RT_EXIT_ZERO else code)
        }
        RunMain.exit(code)
    }

    /** Find the ELF function symbol containing the requested code address. */
    fun rt_elfsym(context: TccRunDebugContext, pc: Long): TccRunElfSymbol? =
        context.elfSymbols.drop(1).firstOrNull { symbol ->
            (symbol.type == 2 || symbol.type == 10) && pc >= symbol.value && pc < symbol.value + symbol.size
        }

    fun rt_vprintf(format: String, vararg arguments: Any?): Int {
        val text = String.format(format, *arguments)
        System.err.print(text)
        System.err.flush()
        return text.toByteArray(Charset.defaultCharset()).size
    }

    fun rt_printf(format: String, vararg arguments: Any?): Int = rt_vprintf(format, *arguments)

    /** Walk and report the translated debug records for a runtime frame. */
    fun _tcc_backtrace(
        frame: TccRunFrame,
        format: String,
        arguments: Array<out Any?>,
        callerProgramCounters: List<Long>,
    ): Int = stateLock.withLock {
        var fmt = format
        var skip = ""
        if (fmt.startsWith('^')) {
            val second = fmt.indexOf('^', 1)
            if (second >= 0) {
                skip = fmt.substring(1, second)
                fmt = fmt.substring(second + 1)
            }
        }
        var oneFrame = false
        if (fmt.startsWith('\u0001')) {
            fmt = fmt.substring(1)
            oneFrame = true
        }
        val message = String.format(fmt, *arguments).take(199)
        val context = debugContexts.firstOrNull()
        val maxCallers = context?.numberOfCallers?.takeIf { it != 0 } ?: 6
        val state = rt_find_state(frame, callerProgramCounters)
        var level = 0
        for (pc in callerProgramCounters.take(maxCallers)) {
            val info = TccRunBacktraceInfo()
            var foundContext: TccRunDebugContext? = null
            for (candidate in debugContexts) {
                val functionPc = if (candidate.dwarf)
                    candidate.dwarfLineResolver?.invoke(pc, info) ?: DwarfLine.rt_printline_dwarf(candidate, pc, info)
                else rt_printline(candidate, pc, info)
                if (functionPc != 0L) {
                    foundContext = candidate
                    break
                }
                val symbol = rt_elfsym(candidate, pc)
                if (symbol != null) {
                    info.function = symbol.name.take(99)
                    info.functionAddress = symbol.value
                    foundContext = candidate
                    break
                }
            }
            if (skip.isNotEmpty() && info.file.contains(skip)) continue

            val callback = state?.backtraceCallback
            if (callback != null) {
                val callbackResult = callback.call(
                    state.backtraceData,
                    pc,
                    info.file.ifEmpty { null },
                    info.line,
                    info.function.ifEmpty { null },
                    if (level == 0) message else null,
                )
                if (callbackResult == 0) break
            } else {
                if (info.file.isNotEmpty()) rt_printf("%s:%d", info.file, info.line)
                else rt_printf("0x%08x", pc)
                rt_printf(": %s %s", if (level == 0) "at" else "by", info.function.ifEmpty { "???" })
                if (level == 0) {
                    rt_printf(": %s", message)
                    if (oneFrame) break
                }
                rt_printf("\n")
            }
            if (foundContext != null && info.functionAddress != 0L &&
                info.functionAddress == foundContext.topFunction) break
            level++
        }
        0
    }

    fun rt_error(frame: TccRunFrame, format: String, vararg arguments: Any?, callerProgramCounters: List<Long>): Int {
        val runtimeFormat = "RUNTIME ERROR: $format"
        return _tcc_backtrace(frame, runtimeFormat, arguments, callerProgramCounters)
    }

    fun set_exception_handler(report: (TccRunFrame, String) -> Unit) {
        Thread.setDefaultUncaughtExceptionHandler { _, exception ->
            val message = when (exception) {
                is ArithmeticException -> "division by zero"
                is StackOverflowError -> "stack overflow"
                is IndexOutOfBoundsException, is SecurityException -> "invalid memory access"
                is LinkageError -> "illegal instruction"
                else -> "caught exception ${exception.javaClass.simpleName}"
            }
            report(TccRunFrame(0, 0, 0), message)
            RunMain.exit(255)
        }
    }

    fun signal_error_name(signal: String, code: String? = null): String = when (signal) {
        "SIGFPE" -> if (code == "FPE_INTDIV" || code == "FPE_FLTDIV") "division by zero" else "floating point exception"
        "SIGBUS", "SIGSEGV" -> "invalid memory access"
        "SIGILL" -> "illegal instruction"
        "SIGABRT" -> "abort() called"
        else -> "caught signal $signal"
    }

    fun cpu_exception_name(code: Long): String = when (code) {
        0xC0000005L -> "invalid memory access"
        0xC00000FDL -> "stack overflow"
        0xC0000094L -> "division by zero"
        0x80000003L, 0x80000004L -> "breakpoint/single-step exception:"
        else -> "caught exception %08x".format(code and 0xffff_ffffL)
    }

    fun dlopen(filename: String?, flags: Int): Nothing? = null
    fun dlclose(handle: Any?) = Unit
    fun dlerror(): String = "error"
    fun dlsym(symbol: String, staticSymbols: Map<String, Any>): Any? = staticSymbols[symbol]

    /** Translate stab source records into a source location and function name. */
    fun rt_printline(context: TccRunDebugContext, wantedPc: Long, info: TccRunBacktraceInfo): Long {
        val includeFiles = MutableList(INCLUDE_STACK_SIZE) { "" }
        var includeIndex = 0
        var functionName = ""
        var functionAddress = 0L
        var lastPc = -1L
        var lastLine = 1
        var lastIncludeIndex = 0

        var found = false
        val absoluteBase = if (context.pointerSize == 8) context.programBase else 0L
        for (symbol in context.stabSymbols.drop(1)) {
            val string = cStringAt(context.stabStrings, symbol.stringIndex)
            var pc = symbol.value
            when (symbol.type) {
                STAB_SLINE -> if (functionAddress == 0L) pc += absoluteBase else pc += functionAddress
                STAB_SO, STAB_SOL -> pc += absoluteBase
                STAB_FUN -> if (symbol.stringIndex == 0) pc += functionAddress else pc += absoluteBase
            }
            if (symbol.type in setOf(STAB_SLINE, STAB_SO, STAB_SOL, STAB_FUN) &&
                pc >= wantedPc && wantedPc >= lastPc) {
                found = true
                break
            }

            when (symbol.type) {
                STAB_FUN -> {
                    if (symbol.stringIndex == 0) {
                        functionName = ""
                        functionAddress = 0
                        lastPc = -1L
                    } else {
                        val colon = string.indexOf(':')
                        functionName = if (colon < 0) string.take(127) else string.take(colon)
                        functionAddress = pc
                    }
                }
                STAB_SLINE -> {
                    lastPc = pc
                    lastLine = symbol.line
                    lastIncludeIndex = includeIndex
                }
                STAB_BINCL -> if (includeIndex < INCLUDE_STACK_SIZE) includeFiles[includeIndex++] = string
                STAB_EINCL -> if (includeIndex > 1) includeIndex--
                STAB_SO -> {
                    includeIndex = 0
                    if (symbol.stringIndex != 0 && string.isNotEmpty() && !string.endsWith('/'))
                        includeFiles[includeIndex++] = string
                    functionName = ""
                    functionAddress = 0
                    lastPc = -1L
                }
                STAB_SOL -> if (includeIndex != 0) includeFiles[includeIndex - 1] = string
            }
        }

        if (!found) {
            lastIncludeIndex = 0
            functionName = ""
            functionAddress = 0
        }
        if (lastIncludeIndex > 0) {
            info.file = includeFiles[lastIncludeIndex - 1].take(99)
            info.line = lastLine
        }
        info.function = functionName.take(99)
        info.functionAddress = functionAddress
        return functionAddress
    }

    private fun relocateEx(
        state: TccRunState,
        memory: TccRunMemory?,
        pointerDifference: Long,
        backend: TccRunBackend,
    ): Int {
        if (memory == null) {
            state.errorCount = 0
            if (backend.targetIsPe) backend.prepareRelocation(state)
            else {
                backend.addRuntime(state)
                backend.resolveCommonSymbols(state)
                backend.buildGotEntries(state)
            }
        }

        var copy = 0
        var offset = 0L
        val base = memory?.baseAddress ?: 0L
        while (true) {
            if (state.verbose == 2 && copy != 0) backend.standardOutput.println("-----------------------------------------------------")
            if (state.errorCount != 0) return -1
            if (copy == 3) return 0

            for (kind in 0..2) {
                var sectionCount = 0
                var groupAddress = 0L
                var groupLength = 0L
                for (index in 1 until state.sections.size) {
                    val section = state.sections[index]
                    val mask = SHF_ALLOC or SHF_WRITE or SHF_EXECINSTR
                    val expected = when (kind) {
                        0 -> SHF_ALLOC or SHF_EXECINSTR
                        1 -> SHF_ALLOC
                        else -> SHF_ALLOC or SHF_WRITE
                    }
                    if (expected != (section.flags and mask)) continue
                    val length = section.dataOffset
                    if (copy == 2) {
                        if (groupAddress == 0L) groupAddress = section.address
                        groupLength = section.address - groupAddress + length
                        continue
                    }
                    if (copy != 0) {
                        val destination = section.address + if (kind == 0) pointerDifference else 0
                        memory!!.write(
                            destination,
                            section.data,
                            length,
                            section.data == null || section.sectionType == SHT_NOBITS,
                        )
                        continue
                    }
                    if ((section.flags and SHF_TLS) != 0 && length != 0)
                        return backend.runtimeError("thread-local storage not supported with -run")

                    var alignment = section.alignment
                    if (++sectionCount == 1) {
                        if (backend.targetIsX86 && alignment < 64) alignment = 64
                        if (kind <= if (backend.readOnlyTextPages) 1 else 0) alignment = backend.pageSize
                    }
                    section.alignment = alignment
                    val address = if (kind != 0) base + pointerDifference else base
                    offset += (-((address + offset)) and (alignment - 1).toLong())
                    section.address = if (memory != null) address + offset else 0
                    offset += length
                }

                if (copy == 2 && sectionCount != 0) {
                    var protection = kind
                    if (protection >= if (backend.readOnlyTextPages) 1 else 0) {
                        if (protection != 0) continue
                        protection = 3
                    }
                    val alignedLength = pageAlign(groupLength, backend.pageSize)
                    if (!memory!!.protect(groupAddress, alignedLength.toInt(), protection))
                        return backend.runtimeError("runtime memory protection failed")
                }
            }

            if (memory == null) return pageAlign(offset, backend.pageSize).toInt()
            when (copy) {
                0 -> {
                    backend.relocateSymbols(state)
                    if (state.errorCount != 0) return -1
                    if (!backend.targetIsPe) backend.relocatePlt(state)
                    backend.relocateSections(state)
                    copy = 1
                }
                1 -> copy = 2
                2 -> {
                    unwindTables[state] = backend.addUnwindFunctionTable(state)
                    backend.cleanupLocalSymbols(state)
                    backend.cleanupSections(state)
                    copy = 3
                }
            }
        }
    }

    private fun pageAlign(value: Long, pageSize: Int): Long = (value + pageSize - 1) and -(pageSize.toLong())

    private fun cStringAt(strings: String, index: Int): String {
        if (index !in strings.indices) return ""
        val end = strings.indexOf('\u0000', index).let { if (it < 0) strings.length else it }
        return strings.substring(index, end)
    }

    private fun st_link(state: TccRunState, backend: TccRunBackend) {
        stateLock.withLock {
            state.next = linkedStates.firstOrNull()
            linkedStates.add(0, state)
            if (state.doBacktrace) state.debugContext?.let(::registerDebugContext)
            backend.linkState(state)
        }
    }

    private fun st_unlink(state: TccRunState, backend: TccRunBackend) {
        stateLock.withLock {
            linkedStates.remove(state)
            state.next = null
            state.debugContext?.let(::unregisterDebugContext)
            backend.unlinkState(state)
        }
    }

    fun registerDebugContext(context: TccRunDebugContext) {
        stateLock.withLock {
            if (context !in debugContexts) {
                context.next = debugContexts.firstOrNull()
                debugContexts.add(0, context)
            }
        }
    }

    fun unregisterDebugContext(context: TccRunDebugContext) {
        stateLock.withLock {
            debugContexts.remove(context)
            context.next = null
        }
    }

    private fun rt_exit(code: Int): Nothing = RunMain.exit(code)

    private fun rt_exitFrame(frame: TccRunFrame, code: Int): Nothing = rt_exit(frame, code)
}
