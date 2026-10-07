package org.tinycc.core.collections

/** Returns one canonical JVM string instance for each compiler identifier. */
class StringInterner {
    private val strings = OpenAddressHashTable<String, String>()

    fun intern(value: String): String {
        val existing = strings[value]
        if (existing != null) return existing
        strings.put(value, value)
        return value
    }

    val size: Int
        get() = strings.size
}
