package tcc.kt

/** i386 ELF relocation policy, PLT construction, and relocation patching. */
object I386Link {
    enum class Relocation(val elfType: Int) {
        NONE(0), ABS32(1), PC32(2), GOT32(3), PLT32(4), COPY(5),
        GLOB_DAT(6), JMP_SLOT(7), RELATIVE(8), GOTOFF(9), GOTPC(10),
        TLS_LE(17), TLS_GD(18), TLS_LDM(19), ABS16(20), PC16(21),
        TLS_LDO_32(32), GOT32X(43), OTHER(-1)
    }

    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val AUTO_GOTPLT_ENTRY = 2
    const val ALWAYS_GOTPLT_ENTRY = 3
    const val PTR_SIZE = 4

    data class Symbol(val dynamicIndex: Int = 0, val gotOffset: Int = 0, val sectionAddress: Long = 0, val sectionDataOffset: Long = 0)
    data class RelocationEntry(var offset: Int, var info: Int)
    data class State(
        var outputDynamic: Boolean = false,
        var outputDll: Boolean = false,
        var outputBinary: Boolean = false,
        var gotAddress: Int = 0,
        var pltAddress: Int = 0,
        var imageBase: Int = 0,
        var tlsEnd: Int = 0,
        val symbols: MutableList<Symbol> = mutableListOf(),
        val plt: MutableList<Byte> = mutableListOf(),
        val got: ByteArray = byteArrayOf(),
        val pltRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val dynamicRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf()
    )

    @JvmStatic
    fun codeReloc(type: Relocation): Int = when (type) {
        Relocation.RELATIVE, Relocation.ABS16, Relocation.ABS32, Relocation.GOTPC,
        Relocation.GOTOFF, Relocation.GOT32, Relocation.GOT32X, Relocation.GLOB_DAT,
        Relocation.COPY, Relocation.TLS_GD, Relocation.TLS_LDM,
        Relocation.TLS_LDO_32, Relocation.TLS_LE -> 0
        Relocation.PC16, Relocation.PC32, Relocation.PLT32, Relocation.JMP_SLOT -> 1
        else -> -1
    }

    @JvmStatic
    fun gotpltEntryType(type: Relocation): Int = when (type) {
        Relocation.RELATIVE, Relocation.ABS16, Relocation.GLOB_DAT,
        Relocation.JMP_SLOT, Relocation.COPY -> NO_GOTPLT_ENTRY
        Relocation.ABS32, Relocation.PC16, Relocation.PC32 -> AUTO_GOTPLT_ENTRY
        Relocation.GOTPC, Relocation.GOTOFF -> BUILD_GOT_ONLY
        Relocation.GOT32, Relocation.GOT32X, Relocation.PLT32,
        Relocation.TLS_GD, Relocation.TLS_LDM, Relocation.TLS_LDO_32,
        Relocation.TLS_LE -> ALWAYS_GOTPLT_ENTRY
        else -> -1
    }

    /** Appends the 16-byte i386 PLT0 (when needed) and a 16-byte symbol slot. */
    @JvmStatic
    fun createPltEntry(state: State, gotOffset: Int, dynamicOutput: Boolean): Int {
        val modrm = if (dynamicOutput) 0xa3 else 0x25
        if (state.plt.isEmpty()) {
            val plt0 = ByteArray(16)
            plt0[0] = 0xff.toByte(); plt0[1] = (modrm + 0x10).toByte()
            write32(plt0, 2, PTR_SIZE)
            plt0[6] = 0xff.toByte(); plt0[7] = modrm.toByte()
            write32(plt0, 8, PTR_SIZE * 2)
            state.plt.addAll(plt0.toList())
        }
        val pltOffset = state.plt.size
        val relocationOffset = state.pltRelocations.size * 8
        val slot = ByteArray(16)
        slot[0] = 0xff.toByte(); slot[1] = modrm.toByte()
        write32(slot, 2, gotOffset)
        slot[6] = 0x68
        write32(slot, 7, relocationOffset - 8)
        slot[11] = 0xe9.toByte()
        write32(slot, 12, -(pltOffset + 16))
        state.plt.addAll(slot.toList())
        return pltOffset
    }

    @JvmStatic
    fun relocatePlt(state: State, dynamicOutput: Boolean) {
        if (!dynamicOutput && state.plt.isNotEmpty()) {
            add32(state.plt, 2, state.gotAddress)
            add32(state.plt, 8, state.gotAddress)
            var offset = 16
            while (offset < state.plt.size) {
                add32(state.plt, offset + 2, state.gotAddress)
                offset += 16
            }
        }
        var address = state.pltAddress + 16 + 6
        state.pltRelocations.forEach { relocation ->
            write32(state.got, relocation.offset, address)
            address += 16
        }
    }

    @JvmStatic
    fun relocate(
        state: State,
        type: Relocation,
        data: ByteArray,
        offset: Int,
        address: Int,
        value: Int,
        symbolIndex: Int = 0,
        following: RelocationEntry? = null
    ) {
        val symbol = state.symbols.getOrNull(symbolIndex) ?: Symbol()
        when (type) {
            Relocation.ABS32 -> {
                if (state.outputDynamic) {
                    if (symbol.dynamicIndex != 0) {
                        state.dynamicRelocations.add(RelocationEntry(offset, rInfo(symbol.dynamicIndex, type)))
                        return
                    }
                    state.dynamicRelocations.add(RelocationEntry(offset, rInfo(0, Relocation.RELATIVE)))
                }
                add32(data, offset, value)
            }
            Relocation.PC32 -> {
                if (state.outputDll && symbol.dynamicIndex != 0) {
                    state.dynamicRelocations.add(RelocationEntry(offset, rInfo(symbol.dynamicIndex, type)))
                    return
                }
                add32(data, offset, value - address)
            }
            Relocation.PLT32 -> add32(data, offset, value - address)
            Relocation.GLOB_DAT, Relocation.JMP_SLOT -> write32(data, offset, value)
            Relocation.GOTPC -> add32(data, offset, state.gotAddress - address)
            Relocation.GOTOFF -> add32(data, offset, value - state.gotAddress)
            Relocation.GOT32, Relocation.GOT32X -> add32(data, offset, symbol.gotOffset)
            Relocation.ABS16 -> {
                if (!state.outputBinary) state.errors += "can only produce 16-bit binary files"
                write16(data, offset, read16(data, offset) + value)
            }
            Relocation.PC16 -> {
                if (!state.outputBinary) state.errors += "can only produce 16-bit binary files"
                write16(data, offset, read16(data, offset) + value - address)
            }
            Relocation.RELATIVE -> if (state.imageBase != 0) add32(data, offset, value - state.imageBase)
            Relocation.COPY, Relocation.NONE -> Unit
            Relocation.TLS_GD -> {
                val errors = state.errors.size
                relaxTls(data, offset - 3, GD_EXPECT, GD_REPLACE, following, state)
                if (state.errors.size == errors) {
                    val localOffset = value - symbol.sectionAddress.toInt() - symbol.sectionDataOffset.toInt()
                    add32(data, offset + 5, -localOffset)
                }
            }
            Relocation.TLS_LDM -> relaxTls(data, offset - 2, LDM_EXPECT, LDM_REPLACE, following, state)
            Relocation.TLS_LDO_32 -> add32(data, offset, value - symbol.sectionAddress.toInt() - symbol.sectionDataOffset.toInt())
            Relocation.TLS_LE -> {
                val delta = if (state.tlsEnd != 0) value - state.tlsEnd
                    else value - symbol.sectionAddress.toInt() - symbol.sectionDataOffset.toInt()
                add32(data, offset, delta)
            }
            Relocation.OTHER -> state.errors += "unknown i386 relocation at 0x${address.toString(16)}"
        }
    }

    private fun relaxTls(data: ByteArray, start: Int, expected: ByteArray, replacement: ByteArray, following: RelocationEntry?, state: State) {
        if (start < 0 || start + expected.size > data.size || !data.copyOfRange(start, start + expected.size).contentEquals(expected)) {
            state.errors += "unexpected i386 TLS relocation pattern"
            return
        }
        replacement.copyInto(data, start)
        following?.info = rInfo(0, Relocation.NONE)
    }

    private fun rInfo(symbol: Int, type: Relocation): Int = (symbol shl 8) or (type.elfType and 0xff)
    private fun read16(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    private fun write16(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }
    private fun write32(data: ByteArray, offset: Int, value: Int) { repeat(4) { data[offset + it] = (value ushr (8 * it)).toByte() } }
    private fun add32(data: ByteArray, offset: Int, value: Int) = write32(data, offset, read32(data, offset) + value)
    private fun read32(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8) or ((data[offset + 2].toInt() and 0xff) shl 16) or (data[offset + 3].toInt() shl 24)
    private fun add32(data: MutableList<Byte>, offset: Int, value: Int) {
        val raw = ByteArray(4) { data[offset + it] }
        write32(raw, 0, read32(raw, 0) + value)
        repeat(4) { data[offset + it] = raw[it] }
    }
    private fun write32(data: MutableList<Byte>, offset: Int, value: Int) { repeat(4) { data[offset + it] = (value ushr (8 * it)).toByte() } }

    private val GD_EXPECT = byteArrayOf(0x8d.toByte(), 0x04, 0x1d, 0, 0, 0, 0, 0xe8.toByte(), 0xfc.toByte(), 0xff.toByte(), 0xff.toByte())
    private val GD_REPLACE = byteArrayOf(0x65, 0xa1.toByte(), 0, 0, 0, 0, 0x81.toByte(), 0xe8.toByte(), 0, 0, 0, 0)
    private val LDM_EXPECT = byteArrayOf(0x8d.toByte(), 0x83.toByte(), 0, 0, 0, 0, 0xe8.toByte(), 0xfc.toByte(), 0xff.toByte(), 0xff.toByte())
    private val LDM_REPLACE = byteArrayOf(0x65, 0xa1.toByte(), 0, 0, 0, 0, 0x90.toByte(), 0x8d.toByte(), 0x74, 0x26, 0)
}
