package tcc.kt

/** C6000 linker relocation policy and patching from c67-link.c. */
object C67Link {
    enum class Relocation {
        DATA32, LOW16, HIGH16, GOT32, GOTOFF, GOTPC, COPY, PLT32, OTHER
    }

    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val ALWAYS_GOTPLT_ENTRY = 3

    /** 1 for code, 0 for data, and -1 for an unknown relocation. */
    @JvmStatic
    fun codeReloc(type: Relocation): Int = when (type) {
        Relocation.DATA32, Relocation.LOW16, Relocation.HIGH16,
        Relocation.GOT32, Relocation.GOTOFF, Relocation.GOTPC,
        Relocation.COPY -> 0
        Relocation.PLT32 -> 1
        Relocation.OTHER -> -1
    }

    /** Indicates which GOT/PLT structures a relocation requires. */
    @JvmStatic
    fun gotpltEntryType(type: Relocation): Int = when (type) {
        Relocation.DATA32, Relocation.LOW16, Relocation.HIGH16,
        Relocation.COPY -> NO_GOTPLT_ENTRY
        Relocation.GOTOFF, Relocation.GOTPC -> BUILD_GOT_ONLY
        Relocation.PLT32, Relocation.GOT32 -> ALWAYS_GOTPLT_ENTRY
        Relocation.OTHER -> -1
    }

    /** C67 PLT generation is unimplemented in the C source as well. */
    @JvmStatic
    fun createPltEntry(): Int = 0

    /** Applies the supported data and paired low/high 16-bit relocations. */
    @JvmStatic
    fun relocate(type: Relocation, data: ByteArray, offset: Int, value: Int): Boolean {
        when (type) {
            Relocation.DATA32 -> putInt(data, offset, getInt(data, offset) + value)
            Relocation.LOW16 -> {
                var old = ((getInt(data, offset) shr 7) and 0xffff)
                old = old or (((getInt(data, offset + 4) shr 7) and 0xffff) shl 16)
                val adjusted = value + old
                val word0 = getInt(data, offset)
                val word1 = getInt(data, offset + 4)
                putInt(data, offset, (word0 and (0xffff shl 7).inv()) or ((adjusted and 0xffff) shl 7))
                putInt(data, offset + 4, (word1 and (0xffff shl 7).inv()) or (((adjusted ushr 16) and 0xffff) shl 7))
            }
            Relocation.HIGH16 -> Unit
            else -> return false
        }
        return true
    }

    private fun getInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
