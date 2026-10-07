package org.tinycc.core.collections

/** Growable little-endian byte output used by object and instruction emitters. */
class ByteSink(initialCapacity: Int = DEFAULT_CAPACITY) {
    private var bytes = ByteArray(initialCapacity.coerceAtLeast(1))

    var size: Int = 0
        private set

    fun appendByte(value: Int) {
        ensureCapacity(size + 1)
        bytes[size++] = value.toByte()
    }

    fun appendBytes(value: ByteArray) {
        ensureCapacity(size + value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun appendShortLE(value: Int) {
        appendByte(value)
        appendByte(value ushr 8)
    }

    fun appendIntLE(value: Int) {
        appendShortLE(value)
        appendShortLE(value ushr 16)
    }

    fun appendLongLE(value: Long) {
        appendIntLE(value.toInt())
        appendIntLE((value ushr 32).toInt())
    }

    fun patchIntLE(offset: Int, value: Int) {
        require(offset >= 0 && offset + Int.SIZE_BYTES <= size) {
            "patch range [$offset, ${offset + Int.SIZE_BYTES}) exceeds size $size"
        }
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= bytes.size) return
        var capacity = bytes.size
        while (capacity < required) capacity = capacity shl 1
        bytes = bytes.copyOf(capacity)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
