package tcc.kt

/** x86-64 ELF relocation classification, PLT handling, and relocation writes. */
object X8664Link {
    enum class Relocation(val elfType: Int) {
        NONE(0), ABS64(1), PC32(2), GOT32(3), PLT32(4), COPY(5), GLOB_DAT(6),
        JUMP_SLOT(7), RELATIVE(8), GOTPCREL(9), ABS32(10), ABS32S(11), PC64(24),
        GOTOFF64(25), GOTPC32(26), GOT64(27), GOTPC64(29), GOTTP_OFF(22),
        TLSGD(19), TLSLD(20), DTPOFF32(21), TPOFF32(23), DTPOFF64(17),
        TPOFF64(18), PLTOFF64(31), GOTPCRELX(41), REX_GOTPCRELX(42), OTHER(-1)
    }

    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val AUTO_GOTPLT_ENTRY = 2
    const val ALWAYS_GOTPLT_ENTRY = 3

    data class Symbol(
        val dynamicIndex: Int = 0,
        val gotOffset: Long = 0,
        val weakUndefined: Boolean = false,
        val name: String = "",
        val value: Long = 0,
        val sectionAddress: Long = 0,
        val sectionDataOffset: Long = 0
    )
    data class RelocationEntry(var offset: Int, var info: Long, var addend: Long = 0)
    data class State(
        var outputDynamic: Boolean = false,
        var outputDll: Boolean = false,
        var outputPe: Boolean = false,
        var gotAddress: Long = 0,
        var pltAddress: Long = 0,
        var imageBase: Long = 0,
        var tlsEnd: Long = 0,
        var relocationEntrySize: Int = 24,
        var got: ByteArray = byteArrayOf(),
        val plt: MutableList<Byte> = mutableListOf(),
        val symbols: MutableList<Symbol> = mutableListOf(),
        val pltRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val dynamicRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf()
    )

    @JvmStatic fun codeReloc(type: Relocation): Int = when (type) {
        Relocation.ABS32, Relocation.ABS32S, Relocation.ABS64, Relocation.GOTPC32,
        Relocation.GOTPC64, Relocation.GOTPCREL, Relocation.GOTPCRELX,
        Relocation.REX_GOTPCRELX, Relocation.GOTTP_OFF, Relocation.GOT32,
        Relocation.GOT64, Relocation.GLOB_DAT, Relocation.COPY, Relocation.RELATIVE,
        Relocation.GOTOFF64, Relocation.TLSGD, Relocation.TLSLD, Relocation.DTPOFF32,
        Relocation.TPOFF32, Relocation.DTPOFF64, Relocation.TPOFF64 -> 0
        Relocation.PC32, Relocation.PC64, Relocation.PLT32, Relocation.PLTOFF64,
        Relocation.JUMP_SLOT -> 1
        else -> -1
    }

    @JvmStatic fun gotpltEntryType(type: Relocation): Int = when (type) {
        Relocation.GLOB_DAT, Relocation.JUMP_SLOT, Relocation.COPY, Relocation.RELATIVE,
        Relocation.TPOFF32, Relocation.TPOFF64 -> NO_GOTPLT_ENTRY
        Relocation.ABS32, Relocation.ABS32S, Relocation.ABS64, Relocation.PC32,
        Relocation.PC64 -> AUTO_GOTPLT_ENTRY
        Relocation.GOTTP_OFF -> BUILD_GOT_ONLY
        Relocation.GOT32, Relocation.GOT64, Relocation.GOTPC32, Relocation.GOTPC64,
        Relocation.GOTOFF64, Relocation.GOTPCREL, Relocation.GOTPCRELX,
        Relocation.REX_GOTPCRELX, Relocation.TLSGD, Relocation.TLSLD,
        Relocation.DTPOFF32, Relocation.DTPOFF64, Relocation.PLT32,
        Relocation.PLTOFF64 -> ALWAYS_GOTPLT_ENTRY
        else -> -1
    }

    @JvmStatic
    fun createPltEntry(state: State, gotOffset: Long, relocationDataOffset: Int): Int {
        if (state.plt.isEmpty()) {
            val zero = ByteArray(16)
            zero[0] = 0xff.toByte(); zero[1] = 0x35
            write32(zero, 2, 4)
            zero[6] = 0xff.toByte(); zero[7] = 0x25
            write32(zero, 8, 8)
            state.plt.addAll(zero.toList())
        }
        val pltOffset = state.plt.size
        val slot = ByteArray(16)
        slot[0] = 0xff.toByte(); slot[1] = 0x25
        write32(slot, 2, gotOffset.toInt())
        slot[6] = 0x68
        write32(slot, 7, relocationDataOffset / state.relocationEntrySize - 1)
        slot[11] = 0xe9.toByte()
        write32(slot, 12, -(pltOffset + 16))
        state.plt.addAll(slot.toList())
        return pltOffset
    }

    @JvmStatic
    fun relocatePlt(state: State) {
        if (state.plt.isNotEmpty()) {
            val delta = (state.gotAddress - state.pltAddress - 6).toInt()
            add32(state.plt, 2, delta)
            add32(state.plt, 8, delta - 6)
            var offset = 16
            while (offset < state.plt.size) {
                add32(state.plt, offset + 2, delta - offset)
                offset += 16
            }
        }
        var address = state.pltAddress + 16 + 6
        state.pltRelocations.forEach { relocation ->
            write64(state.got, relocation.offset, address)
            address += 16
        }
    }

    @JvmStatic
    fun relocate(state: State, type: Relocation, data: ByteArray, offset: Int, address: Long, value: Long, symbolIndex: Int = 0, addend: Long = 0, nextRelocation: RelocationEntry? = null, stabRange: LongRange? = null) {
        val symbol = state.symbols.getOrNull(symbolIndex) ?: Symbol()
        when (type) {
            Relocation.ABS64 -> {
                if (state.outputDynamic) {
                    if (symbol.dynamicIndex != 0) {
                        state.dynamicRelocations += RelocationEntry(offset, rInfo(symbol.dynamicIndex, type), addend)
                        return
                    }
                    state.dynamicRelocations += RelocationEntry(offset, rInfo(0, Relocation.RELATIVE), read64(data, offset) + value)
                }
                add64(data, offset, value)
            }
            Relocation.ABS32, Relocation.ABS32S -> {
                if (state.outputDynamic) state.dynamicRelocations += RelocationEntry(offset, rInfo(0, Relocation.RELATIVE), read32(data, offset).toLong() + value)
                val inRange = if (type == Relocation.ABS32) value in 0..0xffffffffL else value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                if (!inRange && stabRange?.contains(address) != true) state.errors += "relocation 'R_X86_64_32[S]' out of range"
                add32(data, offset, value.toInt())
            }
            Relocation.PC32 -> {
                if (state.outputDll && symbol.dynamicIndex != 0) {
                    state.dynamicRelocations += RelocationEntry(offset, rInfo(symbol.dynamicIndex, type), read32(data, offset).toLong() + addend)
                    return
                }
                relative32(state, type, data, offset, address, value, symbol)
            }
            Relocation.PLT32 -> relative32(state, type, data, offset, address, value, symbol)
            Relocation.COPY -> Unit
            Relocation.PLTOFF64 -> add64(data, offset, value - state.gotAddress + addend)
            Relocation.PC64 -> {
                if (state.outputDll && symbol.dynamicIndex != 0) {
                    state.dynamicRelocations += RelocationEntry(offset, rInfo(symbol.dynamicIndex, type), read64(data, offset) + addend)
                    return
                }
                add64(data, offset, value - address)
            }
            Relocation.GLOB_DAT, Relocation.JUMP_SLOT -> write64(data, offset, value - addend)
            Relocation.GOTPCREL, Relocation.GOTPCRELX, Relocation.REX_GOTPCRELX -> add32(data, offset, state.gotAddress - address + symbol.gotOffset - 4)
            Relocation.GOTPC32 -> add32(data, offset, state.gotAddress - address + addend)
            Relocation.GOTPC64 -> add64(data, offset, state.gotAddress - address + addend)
            Relocation.GOTTP_OFF -> add32(data, offset, value - state.gotAddress)
            Relocation.GOT32 -> add32(data, offset, symbol.gotOffset)
            Relocation.GOT64 -> add64(data, offset, symbol.gotOffset)
            Relocation.GOTOFF64 -> add64(data, offset, value - state.gotAddress)
            Relocation.TLSGD -> relaxTls(data, offset - 4, TLSGD_EXPECT, TLSGD_REPLACE, nextRelocation, state) {
                add32(data, offset + 8, (symbol.value - symbol.sectionAddress - symbol.sectionDataOffset).toInt())
            }
            Relocation.TLSLD -> relaxTls(data, offset - 3, TLSLD_EXPECT, TLSLD_REPLACE, nextRelocation, state) {}
            Relocation.DTPOFF32, Relocation.TPOFF32, Relocation.DTPOFF64, Relocation.TPOFF64 -> {
                val tlsOffset = if (state.tlsEnd != 0L) value - state.tlsEnd else value - symbol.sectionAddress - symbol.sectionDataOffset
                if (type == Relocation.DTPOFF64 || type == Relocation.TPOFF64) add64(data, offset, tlsOffset)
                else add32(data, offset, tlsOffset.toInt())
            }
            Relocation.NONE -> Unit
            Relocation.RELATIVE -> if (state.outputPe) add32(data, offset, value - state.imageBase)
            Relocation.OTHER -> state.errors += "unknown x86-64 relocation at 0x${address.toString(16)}"
        }
    }

    private fun relative32(state: State, type: Relocation, data: ByteArray, offset: Int, address: Long, value: Long, symbol: Symbol) {
        val difference = value - address
        if (difference !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && !(state.outputPe && symbol.weakUndefined)) {
            state.errors += "relocation '${type.elfType}' out of range"
        }
        add32(data, offset, difference.toInt())
    }

    private fun relaxTls(data: ByteArray, start: Int, expected: ByteArray, replacement: ByteArray, next: RelocationEntry?, state: State, patch: () -> Unit) {
        if (start < 0 || start + expected.size > data.size || !data.copyOfRange(start, start + expected.size).contentEquals(expected)) {
            state.errors += "unexpected x86-64 TLS relocation pattern"
            return
        }
        replacement.copyInto(data, start)
        next?.info = rInfo(0, Relocation.NONE)
        patch()
    }

    private fun rInfo(symbol: Int, type: Relocation): Long = (symbol.toLong() shl 32) or type.elfType.toLong()
    private fun read32(data: ByteArray, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
    private fun write32(data: ByteArray, o: Int, value: Int) { repeat(4) { data[o + it] = (value ushr (8 * it)).toByte() } }
    private fun add32(data: ByteArray, o: Int, value: Long) = write32(data, o, read32(data, o) + value.toInt())
    private fun add32(data: ByteArray, o: Int, value: Int) = add32(data, o, value.toLong())
    private fun read64(data: ByteArray, o: Int) = (read32(data, o).toLong() and 0xffffffffL) or (read32(data, o + 4).toLong() shl 32)
    private fun write64(data: ByteArray, o: Int, value: Long) { write32(data, o, value.toInt()); write32(data, o + 4, (value ushr 32).toInt()) }
    private fun add64(data: ByteArray, o: Int, value: Long) = write64(data, o, read64(data, o) + value)
    private fun read32(data: MutableList<Byte>, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
    private fun add32(data: MutableList<Byte>, o: Int, value: Int) { val x = read32(data, o) + value; repeat(4) { data[o + it] = (x ushr (it * 8)).toByte() } }

    private val TLSGD_EXPECT = byteArrayOf(0x66, 0x48, 0x8d.toByte(), 0x3d, 0, 0, 0, 0, 0x66, 0x66, 0x48, 0xe8.toByte(), 0, 0, 0, 0)
    private val TLSGD_REPLACE = byteArrayOf(0x64, 0x48, 0x8b.toByte(), 0x04, 0x25, 0, 0, 0, 0, 0x48, 0x8d.toByte(), 0x80.toByte(), 0, 0, 0, 0)
    private val TLSLD_EXPECT = byteArrayOf(0x48, 0x8d.toByte(), 0x3d, 0, 0, 0, 0, 0xe8.toByte(), 0, 0, 0, 0)
    private val TLSLD_REPLACE = byteArrayOf(0x66, 0x66, 0x66, 0x64, 0x48, 0x8b.toByte(), 0x04, 0x25, 0, 0, 0, 0)
}
