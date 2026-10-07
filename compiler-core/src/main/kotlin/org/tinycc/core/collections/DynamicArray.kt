package org.tinycc.core.collections

/** A compact growable array with predictable insertion order. */
class DynamicArray<T>(initialCapacity: Int = DEFAULT_CAPACITY) : Iterable<T> {
    private var elements: Array<Any?> = arrayOfNulls(initialCapacity.coerceAtLeast(1))

    var size: Int = 0
        private set

    val isEmpty: Boolean
        get() = size == 0

    fun add(value: T) {
        ensureCapacity(size + 1)
        elements[size++] = value
    }

    fun add(index: Int, value: T) {
        checkPositionForInsert(index)
        ensureCapacity(size + 1)
        elements.copyInto(elements, index + 1, index, size)
        elements[index] = value
        size++
    }

    operator fun get(index: Int): T {
        checkIndex(index)
        @Suppress("UNCHECKED_CAST")
        return elements[index] as T
    }

    operator fun set(index: Int, value: T) {
        checkIndex(index)
        elements[index] = value
    }

    fun removeAt(index: Int): T {
        checkIndex(index)
        @Suppress("UNCHECKED_CAST")
        val removed = elements[index] as T
        elements.copyInto(elements, index, index + 1, size)
        elements[--size] = null
        return removed
    }

    fun clear() {
        elements.fill(null, 0, size)
        size = 0
    }

    fun toList(): List<T> = buildList(size) {
        repeat(size) { add(this@DynamicArray[it]) }
    }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var nextIndex = 0

        override fun hasNext(): Boolean = nextIndex < size

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return this@DynamicArray[nextIndex++]
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= elements.size) return
        var capacity = elements.size
        while (capacity < required) capacity = capacity shl 1
        elements = elements.copyOf(capacity)
    }

    private fun checkIndex(index: Int) {
        require(index in 0 until size) { "index $index out of bounds for size $size" }
    }

    private fun checkPositionForInsert(index: Int) {
        require(index in 0..size) { "index $index out of bounds for insertion into size $size" }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
