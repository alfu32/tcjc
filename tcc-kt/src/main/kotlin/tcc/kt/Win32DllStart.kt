package tcc.kt

/** Win32 DLL startup and constructor/finalizer sequencing from dllcrt1.c. */
object Win32DllStart {
    const val DLL_PROCESS_ATTACH: Int = 1
    const val DLL_PROCESS_DETACH: Int = 0

    interface Runtime {
        fun runConstructors(argc: Int, argv: Array<String>?, envp: Array<String>?)
        fun dllMain(hDll: Long, reason: Int, reserved: Long): Int
        fun runDestructors()
    }

    @JvmStatic
    fun dllStart(hDll: Long, reason: Int, reserved: Long, runtime: Runtime): Int {
        if (reason == DLL_PROCESS_ATTACH) runtime.runConstructors(0, null, null)
        val result = runtime.dllMain(hDll, reason, reserved)
        if (reason == DLL_PROCESS_DETACH) runtime.runDestructors()
        return result
    }
}
