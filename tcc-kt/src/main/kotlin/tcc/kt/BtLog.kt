package tcc.kt

import java.nio.charset.Charset
import java.util.Locale

/** Kotlin/JVM port of lib/bt-log.c's diagnostic fallback. */
object BtLog {
    private var backtraceHandler: ((StackTraceElement, String, Array<out Any?>) -> Int)? = null

    /** Installs the equivalent of the optional weak _tcc_backtrace hook. */
    fun setBacktraceHandler(handler: ((StackTraceElement, String, Array<out Any?>) -> Int)?) {
        backtraceHandler = handler
    }

    /** Formats to stderr and returns the formatted byte count, excluding the trailing newline. */
    fun tccBacktrace(format: String, vararg arguments: Any?): Int {
        val caller = Throwable().stackTrace.getOrElse(2) { Throwable().stackTrace.last() }
        backtraceHandler?.let { return it(caller, format, arguments) }

        var messageFormat = format
        var newline = "\n"
        if (messageFormat.startsWith('^')) {
            val secondMarker = messageFormat.indexOf('^', startIndex = 1)
            if (secondMarker >= 0) messageFormat = messageFormat.substring(secondMarker + 1)
        }
        if (messageFormat.startsWith('\u0001')) {
            messageFormat = messageFormat.substring(1)
            newline = ""
        }

        val formatted = String.format(Locale.getDefault(), messageFormat, *arguments)
        System.err.print(formatted)
        System.err.print(newline)
        System.err.flush()
        return formatted.toByteArray(Charset.defaultCharset()).size
    }
}
