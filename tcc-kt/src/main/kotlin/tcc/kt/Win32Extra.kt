package tcc.kt

/** Platform declarations from win32/lib/winex.c. */
object Win32Extra {
    data class Environment(
        var environ: Array<String>? = null,
        var wideEnviron: Array<String>? = null,
        var argc: Int = 0,
        var argv: Array<String>? = null,
        var wideArgv: Array<String>? = null
    )

    /** Returns the architecture-specific intrinsic implementation name. */
    @JvmStatic
    fun fastStoreFenceInstruction(architecture: String): String? = when (architecture) {
        "aarch64" -> "dmb ish"
        "x86_64" -> "lock; orl $0,(%rsp)"
        else -> null
    }
}
