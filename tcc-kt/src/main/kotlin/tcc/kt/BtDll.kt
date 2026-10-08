package tcc.kt

/** DLL-side symbol redirection table from lib/bt-dll.c. */
object BtDll {
    val redirectNames = listOf(
        "__bt_init", "__bt_exit", "tcc_backtrace",
        "__bound_ptr_add", "__bound_ptr_indir1", "__bound_ptr_indir2", "__bound_ptr_indir4",
        "__bound_ptr_indir8", "__bound_ptr_indir12", "__bound_ptr_indir16",
        "__bound_local_new", "__bound_local_delete", "__bound_new_region",
        "__bound_free", "__bound_malloc", "__bound_realloc", "__bound_memcpy",
        "__bound_memcmp", "__bound_memmove", "__bound_memset", "__bound_strlen",
        "__bound_strcpy", "__bound_strncpy", "__bound_strcmp", "__bound_strncmp",
        "__bound_strcat", "__bound_strchr", "__bound_strdup", "__bound_strncat",
        "__bound_strrchr", "__bound_setjmp", "__bound_longjmp",
    )

    private val targetPointers = linkedMapOf<String, Any>()

    /** Native trampoline layer invokes these resolved function pointers by symbol name. */
    var invokeTarget: ((Any, Array<out Any?>) -> Any?)? = null
    var showWindowsError: ((String, String) -> Unit)? = null

    fun __bt_init_dll(
        boundsChecking: Boolean,
        getExecutableSymbol: (String) -> Any?,
        hasStandardError: Boolean = true,
    ): Nothing? {
        targetPointers.clear()
        val redirectCount = if (boundsChecking) redirectNames.size else redirectNames.indexOf("__bound_ptr_add")
        for (name in redirectNames.take(redirectCount)) {
            val pointer = getExecutableSymbol(name)
            if (pointer == null) {
                val message = "Error: function '$name()' not found in executable. (Need -bt or -b for linking the exe.)"
                if (hasStandardError) {
                    System.err.println("TCC/BCHECK: $message")
                    System.err.flush()
                } else {
                    showWindowsError?.invoke(message, "TCC/BCHECK")
                }
                kotlin.system.exitProcess(1)
            }
            targetPointers[name] = pointer
        }
        return null
    }

    fun invoke(name: String, vararg arguments: Any?): Any? {
        val pointer = targetPointers[name] ?: error("no executable function pointer registered for $name")
        return (invokeTarget ?: error("native redirect trampoline is not installed"))(pointer, arguments)
    }

    fun registeredNames(): Set<String> = targetPointers.keys.toSet()
}
