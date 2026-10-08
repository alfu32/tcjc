package tcc.kt

/** Executes the linker-provided initializer and finalizer arrays from crtinit.c. */
object Win32CrtInit {
    @JvmStatic
    fun runConstructors(argc: Int, argv: Array<String>?, envp: Array<String>?, constructors: List<(Int, Array<String>?, Array<String>?) -> Unit>) {
        constructors.forEach { it(argc, argv, envp) }
    }

    @JvmStatic
    fun runDestructors(destructors: List<() -> Unit>) {
        destructors.asReversed().forEach { it() }
    }
}
