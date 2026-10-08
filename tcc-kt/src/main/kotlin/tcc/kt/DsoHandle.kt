package tcc.kt

/** JVM identity equivalent of lib/dsohandle.c's self-referential hidden symbol. */
object DsoHandle {
    @JvmField
    val __dso_handle: Any = this
}
