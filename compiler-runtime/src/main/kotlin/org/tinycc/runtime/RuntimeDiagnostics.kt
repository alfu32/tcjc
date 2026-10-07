package org.tinycc.runtime

/** A source location retained by generated code and runtime checks. */
data class RuntimeSourceLocation(
    val file: String,
    val line: Int,
    val column: Int = 1,
    val function: String? = null,
) {
    init {
        require(file.isNotEmpty()) { "runtime source file must not be empty" }
        require(line > 0) { "runtime source line must be positive" }
        require(column > 0) { "runtime source column must be positive" }
    }

    override fun toString(): String = buildString {
        append(file)
        append(':')
        append(line)
        append(':')
        append(column)
        function?.let {
            append(" in ")
            append(it)
        }
    }
}

data class RuntimeDebugInfo(
    val module: String,
    val functions: Map<String, RuntimeSourceLocation> = emptyMap(),
) {
    init {
        require(module.isNotEmpty()) { "runtime debug module must not be empty" }
    }

    fun locationFor(function: String): RuntimeSourceLocation? = functions[function]
}

data class RuntimeStackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int,
) {
    override fun toString(): String = buildString {
        append(className)
        append('.')
        append(methodName)
        fileName?.let {
            append('(')
            append(it)
            if (lineNumber >= 0) {
                append(':')
                append(lineNumber)
            }
            append(')')
        }
    }
}

object RuntimeBacktrace {
    fun capture(limit: Int = 32): List<RuntimeStackFrame> {
        require(limit >= 0) { "backtrace frame limit must not be negative" }
        return Throwable().stackTrace.asSequence()
            .drop(1)
            .filterNot { it.className.startsWith("org.tinycc.runtime.Runtime") }
            .take(limit)
            .map { frame ->
                RuntimeStackFrame(frame.className, frame.methodName, frame.fileName, frame.lineNumber)
            }
            .toList()
    }
}

enum class RuntimeCheckKind { BOUNDS, STACK }

data class RuntimeDiagnostic(
    val kind: RuntimeCheckKind,
    val message: String,
    val location: RuntimeSourceLocation?,
    val backtrace: List<RuntimeStackFrame>,
) {
    fun format(): String = buildString {
        append("runtime ")
        append(kind.name.lowercase())
        append(" failure: ")
        append(message)
        location?.let {
            append(" at ")
            append(it)
        }
        if (backtrace.isNotEmpty()) {
            append("\nbacktrace:")
            backtrace.forEach { frame ->
                append("\n  at ")
                append(frame)
            }
        }
    }
}

class RuntimeSanitizerException(val diagnostic: RuntimeDiagnostic) : IllegalArgumentException(diagnostic.format())

fun interface RuntimeDiagnosticSink {
    fun report(diagnostic: RuntimeDiagnostic)
}

sealed interface RuntimeProfileEvent {
    val timestampNanos: Long

    data class FunctionEntered(
        val name: String,
        val location: RuntimeSourceLocation?,
        override val timestampNanos: Long = System.nanoTime(),
    ) : RuntimeProfileEvent

    data class FunctionExited(
        val name: String,
        val location: RuntimeSourceLocation?,
        override val timestampNanos: Long = System.nanoTime(),
    ) : RuntimeProfileEvent

    data class BoundsChecked(
        val index: Long,
        val length: Long,
        val location: RuntimeSourceLocation?,
        override val timestampNanos: Long = System.nanoTime(),
    ) : RuntimeProfileEvent

    data class DiagnosticRaised(
        val diagnostic: RuntimeDiagnostic,
        override val timestampNanos: Long = System.nanoTime(),
    ) : RuntimeProfileEvent
}

fun interface RuntimeProfiler {
    fun record(event: RuntimeProfileEvent)
}

data class RuntimeInstrumentationConfig(
    val captureBacktrace: Boolean = true,
    val maxBacktraceFrames: Int = 32,
    val debugInfo: RuntimeDebugInfo? = null,
    val diagnosticSink: RuntimeDiagnosticSink? = null,
    val profiler: RuntimeProfiler? = null,
) {
    init {
        require(maxBacktraceFrames >= 0) { "backtrace frame limit must not be negative" }
    }
}

object RuntimeInstrumentation {
    private val currentConfig = ThreadLocal.withInitial { RuntimeInstrumentationConfig() }

    fun <T> scoped(config: RuntimeInstrumentationConfig, block: () -> T): T {
        val previous = currentConfig.get()
        currentConfig.set(config)
        return try {
            block()
        } finally {
            currentConfig.set(previous)
        }
    }

    fun current(): RuntimeInstrumentationConfig = currentConfig.get()

    fun functionEntered(name: String, location: RuntimeSourceLocation? = null) {
        require(name.isNotEmpty()) { "profiled function name must not be empty" }
        val config = currentConfig.get()
        config.profiler?.record(RuntimeProfileEvent.FunctionEntered(name, location ?: config.debugInfo?.locationFor(name)))
    }

    fun functionExited(name: String, location: RuntimeSourceLocation? = null) {
        require(name.isNotEmpty()) { "profiled function name must not be empty" }
        val config = currentConfig.get()
        config.profiler?.record(RuntimeProfileEvent.FunctionExited(name, location ?: config.debugInfo?.locationFor(name)))
    }

    inline fun <T> traceFunction(
        name: String,
        location: RuntimeSourceLocation? = null,
        block: () -> T,
    ): T {
        functionEntered(name, location)
        return try {
            block()
        } finally {
            functionExited(name, location)
        }
    }

    internal fun boundsChecked(index: Long, length: Long, location: RuntimeSourceLocation?) {
        currentConfig.get().profiler?.record(RuntimeProfileEvent.BoundsChecked(index, length, location))
    }

    internal fun fail(
        kind: RuntimeCheckKind,
        message: String,
        location: RuntimeSourceLocation?,
    ): Nothing {
        val config = currentConfig.get()
        val diagnostic = RuntimeDiagnostic(
            kind = kind,
            message = message,
            location = location,
            backtrace = if (config.captureBacktrace) RuntimeBacktrace.capture(config.maxBacktraceFrames) else emptyList(),
        )
        config.diagnosticSink?.report(diagnostic)
        config.profiler?.record(RuntimeProfileEvent.DiagnosticRaised(diagnostic))
        throw RuntimeSanitizerException(diagnostic)
    }
}
