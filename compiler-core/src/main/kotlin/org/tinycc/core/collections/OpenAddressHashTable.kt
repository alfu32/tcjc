package org.tinycc.core.collections

/** Deterministic open-addressed hash table for compiler symbol-like maps. */
class OpenAddressHashTable<K : Any, V>(initialCapacity: Int = 16) {
    private var states = ByteArray(nextPowerOfTwo(initialCapacity.coerceAtLeast(4)))
    private var keys = arrayOfNulls<Any>(states.size)
    private var values = arrayOfNulls<Any>(states.size)
    private var used = 0
    private var tombstones = 0

    var size: Int = 0
        private set

    operator fun get(key: K): V? {
        val slot = findSlot(key, forInsert = false)
        if (slot < 0) return null
        @Suppress("UNCHECKED_CAST")
        return values[slot] as V
    }

    fun containsKey(key: K): Boolean = findSlot(key, forInsert = false) >= 0

    fun put(key: K, value: V): V? {
        if (used + tombstones + 1 > states.size * LOAD_FACTOR) rehash(states.size shl 1)
        val slot = findSlot(key, forInsert = true)
        val wasUsed = states[slot].toInt() == USED
        @Suppress("UNCHECKED_CAST")
        val previous = if (wasUsed) values[slot] as V else null
        if (!wasUsed) {
            if (states[slot].toInt() == EMPTY) used++
            if (states[slot].toInt() == TOMBSTONE) tombstones--
            size++
        }
        states[slot] = USED.toByte()
        keys[slot] = key
        values[slot] = value
        return previous
    }

    fun remove(key: K): V? {
        val slot = findSlot(key, forInsert = false)
        if (slot < 0) return null
        @Suppress("UNCHECKED_CAST")
        val previous = values[slot] as V
        states[slot] = TOMBSTONE.toByte()
        keys[slot] = null
        values[slot] = null
        size--
        tombstones++
        return previous
    }

    fun entries(): Sequence<Pair<K, V>> = sequence {
        for (slot in states.indices) {
            if (states[slot].toInt() == USED) {
                @Suppress("UNCHECKED_CAST")
                val key = keys[slot] as K
                @Suppress("UNCHECKED_CAST")
                val value = values[slot] as V
                yield(key to value)
            }
        }
    }

    fun clear() {
        states.fill(EMPTY.toByte())
        keys.fill(null)
        values.fill(null)
        used = 0
        tombstones = 0
        size = 0
    }

    private fun findSlot(key: K, forInsert: Boolean): Int {
        var slot = spread(key.hashCode()) and (states.size - 1)
        var firstTombstone = -1
        while (true) {
            when (states[slot].toInt()) {
                EMPTY -> return if (forInsert && firstTombstone >= 0) firstTombstone else if (forInsert) slot else -1
                TOMBSTONE -> if (firstTombstone < 0) firstTombstone = slot
                USED -> if (keys[slot] == key) return slot
            }
            slot = (slot + 1) and (states.size - 1)
        }
    }

    private fun rehash(newCapacity: Int) {
        val oldEntries = entries().toList()
        states = ByteArray(nextPowerOfTwo(newCapacity))
        keys = arrayOfNulls(states.size)
        values = arrayOfNulls(states.size)
        used = 0
        tombstones = 0
        size = 0
        oldEntries.forEach { (key, value) -> put(key, value) }
    }

    private companion object {
        const val EMPTY = 0
        const val USED = 1
        const val TOMBSTONE = 2
        const val LOAD_FACTOR = 0.70

        fun spread(hash: Int): Int = hash xor (hash ushr 16)

        fun nextPowerOfTwo(value: Int): Int {
            var result = 1
            while (result < value) result = result shl 1
            return result
        }
    }
}
