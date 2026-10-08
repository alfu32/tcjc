package tcc.kt

import java.nio.charset.Charset

/** Backtrace and bounds-checker registration from lib/bt-exe.c. */
object BtExe {
    var boundInit: ((Any, Int) -> Unit)? = null
    var boundExitDll: ((Any) -> Unit)? = null
    var installExceptionHandler: (() -> Unit)? = null

    fun __bt_init(
        context: TccRunDebugContext,
        isExecutable: Boolean,
        mainFunctionAddress: Long = 0,
        win64AllocationBase: Long? = null,
    ) {
        context.boundsStart?.let { boundInit?.invoke(it, -1) }
        if (win64AllocationBase != null && context.programBase != 0L)
            bt_init_pe_prog_base(context, win64AllocationBase)

        TccRun.registerDebugContext(context)
        if (isExecutable) {
            context.topFunction = mainFunctionAddress
            installExceptionHandler?.invoke()
        }
    }

    fun __bt_exit(context: TccRunDebugContext) {
        context.boundsStart?.let { boundExitDll?.invoke(it) }
        TccRun.unregisterDebugContext(context)
    }

    /** Applies the image-base correction used by the Win64 VirtualQuery path. */
    fun bt_init_pe_prog_base(context: TccRunDebugContext, allocationBase: Long) {
        if (context.programBase == 0L) return
        val imageBase = allocationBase - context.programBase
        context.programBase = allocationBase - (imageBase and 0xffff_ffffL)
    }

    /** Bounded NUL-terminated copy corresponding to pstrcpy(). */
    fun pstrcpy(buffer: ByteArray, bufferSize: Int, source: String): ByteArray {
        if (bufferSize <= 0 || buffer.isEmpty()) return buffer
        val bytes = source.toByteArray(Charset.defaultCharset())
        val count = minOf(bytes.size, bufferSize - 1, buffer.size - 1)
        bytes.copyInto(buffer, endIndex = count)
        buffer[count] = 0
        return buffer
    }
}
