package tcc.kt

/** GUI CRT startup routines from win32/lib/wincrt1.c. */
object Win32Wincrt1 {
    const val UNKNOWN_APP = 0
    const val CONSOLE_APP = 1
    const val GUI_APP = 2
    const val STARTF_USESHOWWINDOW = 1
    const val SW_SHOWDEFAULT = 10

    data class StartupInfo(val flags: Int, val showWindow: Int)

    interface Runtime {
        fun startupInfo(): StartupInfo
        fun commandLine(): String
        fun duplicateEmptyString(): String
        fun setUnhandledExceptionFilter()
        fun setAppType(type: Int)
        fun getMainArgs(): Triple<Array<String>, Array<String>, Array<String>>
        fun setControlWord(value: Int, mask: Int)
        fun constructors(argc: Int, argv: Array<String>, envp: Array<String>)
        fun winMain(commandTail: String, show: Int): Int
        fun destructors()
        fun exit(status: Int): Nothing
    }

    @JvmStatic
    fun goWinMain(argument: String?, runtime: Runtime, isX86: Boolean): Int {
        val startup = runtime.startupInfo()
        val show = if (startup.flags and STARTF_USESHOWWINDOW != 0) startup.showWindow else SW_SHOWDEFAULT
        val fullCommand = runtime.commandLine()
        val tail = if (argument == null) runtime.duplicateEmptyString() else {
            val index = fullCommand.indexOf(argument)
            if (index < 0) runtime.duplicateEmptyString()
            else if (index > 0 && fullCommand[index - 1] == '"') fullCommand.substring(index - 1)
            else fullCommand.substring(index)
        }
        if (isX86) runtime.setControlWord(0x10000, 0x30000)
        val (argv, envp, _) = runtime.getMainArgs()
        runtime.constructors(argv.size, argv, envp)
        val status = runtime.winMain(tail, show)
        runtime.destructors()
        return status
    }

    @JvmStatic
    fun start(runtime: Runtime): Nothing {
        runtime.setUnhandledExceptionFilter()
        runtime.setAppType(GUI_APP)
        val (argv, _, _) = runtime.getMainArgs()
        runtime.exit(goWinMain(argv.getOrNull(1), runtime, isX86 = false))
    }
}
