package org.tinycc.core.diagnostics

import org.tinycc.core.collections.DynamicArray

enum class DiagnosticSeverity {
    NOTE,
    WARNING,
    ERROR,
    FATAL,
}

data class Diagnostic(
    val severity: DiagnosticSeverity,
    val location: SourceLocation,
    val message: String,
    val includeTrace: List<IncludeFrame> = emptyList(),
)

fun interface DiagnosticSink {
    fun publish(diagnostic: Diagnostic)
}

class CollectingDiagnosticSink : DiagnosticSink {
    private val published = DynamicArray<Diagnostic>()

    override fun publish(diagnostic: Diagnostic) {
        published.add(diagnostic)
    }

    fun diagnostics(): List<Diagnostic> = published.toList()
}

class DiagnosticEngine(
    private val sink: DiagnosticSink? = null,
    private val maxErrors: Int = DEFAULT_MAX_ERRORS,
) {
    private val diagnostics = DynamicArray<Diagnostic>()
    private var limitReported = false

    var errorCount: Int = 0
        private set

    val hasErrors: Boolean
        get() = errorCount > 0

    fun note(location: SourceLocation, message: String, includeTrace: List<IncludeFrame> = emptyList()) =
        report(DiagnosticSeverity.NOTE, location, message, includeTrace)

    fun warning(location: SourceLocation, message: String, includeTrace: List<IncludeFrame> = emptyList()) =
        report(DiagnosticSeverity.WARNING, location, message, includeTrace)

    fun error(location: SourceLocation, message: String, includeTrace: List<IncludeFrame> = emptyList()) =
        report(DiagnosticSeverity.ERROR, location, message, includeTrace)

    fun fatal(location: SourceLocation, message: String, includeTrace: List<IncludeFrame> = emptyList()) =
        report(DiagnosticSeverity.FATAL, location, message, includeTrace)

    fun report(
        severity: DiagnosticSeverity,
        location: SourceLocation,
        message: String,
        includeTrace: List<IncludeFrame> = emptyList(),
    ): Diagnostic {
        if (severity == DiagnosticSeverity.ERROR || severity == DiagnosticSeverity.FATAL) {
            if (errorCount >= maxErrors) {
                if (!limitReported) {
                    limitReported = true
                    return reportUnchecked(
                        DiagnosticSeverity.FATAL,
                        location,
                        "too many errors; compilation aborted",
                        includeTrace,
                    )
                }
                return diagnostics[diagnostics.size - 1]
            }
            errorCount++
        }
        return reportUnchecked(severity, location, message, includeTrace)
    }

    fun diagnostics(): List<Diagnostic> = diagnostics.toList()

    fun render(formatter: DiagnosticFormatter = DiagnosticFormatter.DEFAULT): String =
        diagnostics.joinToString("\n") { formatter.format(it) }

    fun failIfErrors() {
        if (hasErrors) throw CompilationFailedException(diagnostics())
    }

    private fun reportUnchecked(
        severity: DiagnosticSeverity,
        location: SourceLocation,
        message: String,
        includeTrace: List<IncludeFrame>,
    ): Diagnostic {
        val diagnostic = Diagnostic(severity, location, message, includeTrace)
        diagnostics.add(diagnostic)
        sink?.publish(diagnostic)
        return diagnostic
    }

    private companion object {
        const val DEFAULT_MAX_ERRORS = 100
    }
}

class DiagnosticFormatter private constructor() {
    fun format(diagnostic: Diagnostic): String {
        val location = diagnostic.location
        val position = if (location.isKnown()) ":${location.line}:${location.column}" else ""
        val severity = diagnostic.severity.name.lowercase()
        val base = "${location.displayPath()}$position: $severity: ${diagnostic.message}"
        if (diagnostic.includeTrace.isEmpty()) return base
        return buildString {
            append(base)
            diagnostic.includeTrace.forEach { frame ->
                append("\n  included from ")
                append(frame.file)
                frame.includedAt?.let {
                    if (it.isKnown()) append(":${it.line}:${it.column}")
                }
            }
        }
    }

    companion object {
        val DEFAULT = DiagnosticFormatter()
    }
}

class CompilationFailedException(diagnostics: List<Diagnostic>) :
    IllegalStateException(DiagnosticFormatter.DEFAULT.formatAll(diagnostics))

private fun DiagnosticFormatter.formatAll(diagnostics: List<Diagnostic>): String =
    diagnostics.joinToString("\n") { format(it) }
