package tcc.kt

/** Runtime constructor, destructor, and exit-handler support from lib/runmain.c. */
object RunMain {
    private const val MAX_EXIT_HANDLERS = 32

    private data class ExitHandler(val function: (Int, Any?) -> Unit, val argument: Any?)

    private val exitHandlers = arrayOfNulls<ExitHandler>(MAX_EXIT_HANDLERS)
    private val constructors = mutableListOf<(Int, Array<String>, Array<String>) -> Unit>()
    private val destructors = mutableListOf<() -> Unit>()
    private var exitHandlerCount = 0

    fun addConstructor(function: (Int, Array<String>, Array<String>) -> Unit) {
        constructors.add(function)
    }

    fun addDestructor(function: () -> Unit) {
        destructors.add(function)
    }

    /** Mirrors on_exit(): register at most 32 callbacks, run in reverse registration order. */
    fun onExit(function: (Int, Any?) -> Unit, argument: Any?): Int {
        val index = exitHandlerCount
        if (index < MAX_EXIT_HANDLERS) {
            exitHandlers[index] = ExitHandler(function, argument)
            exitHandlerCount = index + 1
            return 0
        }
        return 1
    }

    fun on_exit(function: (Int, Any?) -> Unit, argument: Any?): Int = onExit(function, argument)

    fun atexit(function: () -> Unit): Int = onExit({ _, _ -> function() }, null)

    fun runExitHandlers(result: Int) {
        var index = exitHandlerCount
        while (index != 0) {
            index--
            exitHandlers[index]?.let { it.function(result, it.argument) }
        }
    }

    fun __run_on_exit(result: Int) = runExitHandlers(result)

    private fun runConstructors(argc: Int, argv: Array<String>, envp: Array<String>) {
        constructors.forEach { it(argc, argv, envp) }
    }

    private fun runDestructors() {
        for (index in destructors.indices.reversed()) destructors[index]()
    }

    /** Mirrors _runmain() around a C main entry point. */
    fun runMain(
        argc: Int,
        argv: Array<String>,
        envp: Array<String>,
        main: (Int, Array<String>, Array<String>) -> Int,
    ): Int {
        runConstructors(argc, argv, envp)
        val result = main(argc, argv, envp)
        runDestructors()
        runExitHandlers(result)
        return result
    }

    fun _runmain(
        argc: Int,
        argv: Array<String>,
        envp: Array<String>,
        main: (Int, Array<String>, Array<String>) -> Int,
    ): Int = runMain(argc, argv, envp, main)

    /** JVM equivalent of exit(): execute registered finalizers before terminating. */
    fun exit(code: Int): Nothing {
        runDestructors()
        runExitHandlers(code)
        kotlin.system.exitProcess(code)
    }
}
