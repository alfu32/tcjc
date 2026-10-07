package org.tinycc.core.collections

/**
 * Lifetime container for compiler objects that are released together.
 * JVM garbage collection owns the memory; reset provides compiler-scoped lifetime semantics.
 */
class ObjectArena : AutoCloseable {
    private val objects = DynamicArray<Any>()
    private var closed = false

    fun <T : Any> allocate(factory: () -> T): T {
        check(!closed) { "arena is closed" }
        return factory().also(objects::add)
    }

    fun reset() {
        check(!closed) { "arena is closed" }
        objects.clear()
    }

    override fun close() {
        if (!closed) {
            objects.clear()
            closed = true
        }
    }
}
