package tcc.kt

/** Direct port of the default Win32 DLL entry point in win32/lib/dllmain.c. */
object Win32DllMain {
    const val TRUE: Int = 1

    @JvmStatic
    fun DllMain(hDll: Long, dwReason: Int, lpReserved: Long): Int = TRUE
}
