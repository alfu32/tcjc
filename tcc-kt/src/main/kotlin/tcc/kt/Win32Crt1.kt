package tcc.kt

/** Console CRT startup routines from win32/lib/crt1.c. */
object Win32Crt1 {
    const val UNKNOWN_APP = 0
    const val CONSOLE_APP = 1
    const val GUI_APP = 2
    const val MCW_PC = 0x00030000
    const val PC_53 = 0x00010000

    data class StartupInfo(val newmode: Int = 0)

    interface Runtime {
        fun setUnhandledExceptionFilter()
        fun setAppType(type: Int)
        fun setControlWord(value: Int, mask: Int)
        fun getMainArgs(wildcard: Int): Triple<Array<String>, Array<String>, Array<String>>
        fun constructors(argc: Int, argv: Array<String>, envp: Array<String>)
        fun main(argc: Int, argv: Array<String>, envp: Array<String>): Int
        fun destructors()
        fun flushStdout()
        fun flushStderr()
        fun runOnExit(status: Int)
        fun exit(status: Int): Nothing
    }

    @JvmStatic
    fun start(runtime: Runtime, isX86: Boolean, wildcard: Int = 0): Nothing {
        runtime.setUnhandledExceptionFilter()
        runtime.setAppType(CONSOLE_APP)
        if (isX86) runtime.setControlWord(PC_53, MCW_PC)
        val (argv, envp, _) = runtime.getMainArgs(wildcard)
        runtime.constructors(argv.size, argv, envp)
        val status = runtime.main(argv.size, argv, envp)
        runtime.destructors()
        runtime.exit(status)
    }

    @JvmStatic
    fun runMain(argc: Int, argv: Array<String>, runtime: Runtime, isX86: Boolean): Int {
        runtime.setControlWordIfX86(isX86)
        val (_, envp, _) = runtime.getMainArgs(0)
        runtime.constructors(argc, argv, envp)
        val status = runtime.main(argc, argv, envp)
        runtime.flushStdout()
        runtime.flushStderr()
        runtime.destructors()
        runtime.runOnExit(status)
        return status
    }

    private fun Runtime.setControlWordIfX86(enabled: Boolean) {
        if (enabled) setControlWord(PC_53, MCW_PC)
    }
}
