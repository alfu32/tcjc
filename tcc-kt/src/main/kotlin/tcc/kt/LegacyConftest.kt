package tcc.kt

/** The legacy-c copy is byte-identical to conftest.c and shares its Kotlin port. */
object LegacyConftest {
    fun run(args: Array<String>): Int = Conftest.run(args)

    @JvmStatic
    fun main(args: Array<String>) {
        kotlin.system.exitProcess(run(args))
    }
}
