package tcc.kt

/** ARM ELF relocation classification, PLT handling, and instruction patching. */
object ArmLink {
    enum class Relocation(val elfType: Int) {
        NONE(0), PC24(1), ABS32(2), REL32(3), THM_PC22(10),
        COPY(20), GLOB_DAT(21), JUMP_SLOT(22), RELATIVE(23), GOTPC(25),
        GOT32(26), PLT32(27), CALL(28), JUMP24(29), THM_JUMP24(30),
        GOTOFF(24), GOT_PREL(96), TARGET1(38), V4BX(40), PREL31(42),
        MOVW_ABS_NC(43), MOVT_ABS(44), MOVW_PREL_NC(45), MOVT_PREL(46),
        THM_MOVW_ABS_NC(47), THM_MOVT_ABS(48), TLS_LE32(90), OTHER(-1)
    }

    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val AUTO_GOTPLT_ENTRY = 2
    const val ALWAYS_GOTPLT_ENTRY = 3

    data class Symbol(val weakUndefined: Boolean = false, val thumbStub: Boolean = false, val gotOffset: Int = 0, val dynamicIndex: Int = 0, val sectionAddress: Int = 0, val sectionDataOffset: Int = 0, val symbolValue: Int = 0, val size: Int = 0, val name: String = "")
    data class PltRelocation(val offset: Int)
    data class State(
        var gotAddress: Int = 0,
        var pltAddress: Int = 0,
        var outputDynamic: Boolean = false,
        var targetPe: Boolean = false,
        var blxAvailable: Boolean = true,
        var imageBase: Int = 0,
        var tlsStart: Int = 0,
        var tlsEnd: Int = 0,
        val plt: MutableList<Byte> = mutableListOf(),
        val got: ByteArray = byteArrayOf(),
        val pltRelocations: MutableList<PltRelocation> = mutableListOf(),
        val dynamicRelocations: MutableList<I386Link.RelocationEntry> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
        val symbols: MutableList<Symbol> = mutableListOf(),
        val generatedThumbStubs: MutableList<ThumbStub> = mutableListOf(),
        val stubRelocations: MutableList<StubRelocation> = mutableListOf()
    )
    data class ThumbStub(val symbolName: String, val address: Int, val targetValue: Int, val code: ByteArray)
    data class StubRelocation(val address: Int, val type: Relocation, val symbolIndex: Int)

    @JvmStatic fun codeReloc(type: Relocation): Int = when (type) {
        Relocation.MOVT_ABS, Relocation.MOVW_ABS_NC, Relocation.THM_MOVT_ABS,
        Relocation.THM_MOVW_ABS_NC, Relocation.ABS32, Relocation.REL32,
        Relocation.GOTPC, Relocation.GOTOFF, Relocation.GOT32, Relocation.GOT_PREL,
        Relocation.COPY, Relocation.GLOB_DAT, Relocation.NONE, Relocation.TARGET1,
        Relocation.MOVT_PREL, Relocation.MOVW_PREL_NC, Relocation.TLS_LE32 -> 0
        Relocation.PC24, Relocation.CALL, Relocation.JUMP24, Relocation.PLT32,
        Relocation.THM_PC22, Relocation.THM_JUMP24, Relocation.PREL31,
        Relocation.V4BX, Relocation.JUMP_SLOT -> 1
        else -> -1
    }

    @JvmStatic fun gotpltEntryType(type: Relocation): Int = when (type) {
        Relocation.NONE, Relocation.COPY, Relocation.GLOB_DAT, Relocation.JUMP_SLOT,
        Relocation.TLS_LE32 -> NO_GOTPLT_ENTRY
        Relocation.PC24, Relocation.CALL, Relocation.JUMP24, Relocation.PLT32,
        Relocation.THM_PC22, Relocation.THM_JUMP24, Relocation.MOVT_ABS,
        Relocation.MOVW_ABS_NC, Relocation.THM_MOVT_ABS, Relocation.THM_MOVW_ABS_NC,
        Relocation.PREL31, Relocation.ABS32, Relocation.REL32, Relocation.V4BX,
        Relocation.TARGET1, Relocation.MOVT_PREL, Relocation.MOVW_PREL_NC -> AUTO_GOTPLT_ENTRY
        Relocation.GOTPC, Relocation.GOTOFF -> BUILD_GOT_ONLY
        Relocation.GOT32, Relocation.GOT_PREL -> ALWAYS_GOTPLT_ENTRY
        else -> -1
    }

    @JvmStatic
    fun createPltEntry(state: State, gotOffset: Int, thumbStub: Boolean): Int {
        if (state.plt.isEmpty()) {
            val plt0 = ByteArray(20)
            put32(plt0, 0, 0xe52de004.toInt())
            put32(plt0, 4, 0xe59fe004.toInt())
            put32(plt0, 8, 0xe08fe00e.toInt())
            put32(plt0, 12, 0xe5bef008.toInt())
            state.plt.addAll(plt0.toList())
        }
        val offset = state.plt.size
        if (thumbStub) {
            state.plt.addAll(byteArrayOf(0x78, 0x47, 0xc0.toByte(), 0x46).toList())
        }
        val entry = ByteArray(16)
        put32(entry, 4, gotOffset)
        state.plt.addAll(entry.toList())
        return offset
    }

    @JvmStatic
    fun relocatePlt(state: State) {
        if (state.plt.isNotEmpty()) {
            val baseDelta = state.gotAddress - state.pltAddress - 12
            write32(state.plt, 16, baseDelta - 4)
            var offset = 20
            while (offset < state.plt.size) {
                val gotOffset = read32(state.plt, offset + 4)
                val target = baseDelta + gotOffset - offset + 4
                val thumb = read32(state.plt, offset) == 0x46c04778
                val codeOffset = if (thumb) offset + 4 else offset
                write32(state.plt, codeOffset, 0xe28fc200.toInt() or ((target ushr 28) and 0xf))
                write32(state.plt, codeOffset + 4, 0xe28cc600.toInt() or ((target ushr 20) and 0xff))
                write32(state.plt, codeOffset + 8, 0xe28cca00.toInt() or ((target ushr 12) and 0xff))
                write32(state.plt, codeOffset + 12, 0xe5bcf000.toInt() or (target and 0xfff))
                offset = codeOffset + 16
            }
        }
        state.pltRelocations.forEach { write32(state.got, it.offset, state.pltAddress) }
    }

    @JvmStatic
    fun relocate(state: State, type: Relocation, data: ByteArray, offset: Int, address: Int, value: Int, symbolIndex: Int = 0, pairedRelocation: I386Link.RelocationEntry? = null) {
        val symbol = state.symbols.getOrNull(symbolIndex) ?: Symbol()
        when (type) {
            Relocation.PC24, Relocation.CALL, Relocation.JUMP24, Relocation.PLT32 -> relocateArmBranch(state, type, data, offset, address, value)
            Relocation.THM_PC22, Relocation.THM_JUMP24 -> relocateThumbBranch(state, type, data, offset, address, value, symbol, symbolIndex)
            Relocation.MOVT_ABS, Relocation.MOVW_ABS_NC -> {
                val v = if (type == Relocation.MOVT_ABS) value ushr 16 else value
                val immediate = (((v ushr 12) and 0xf) shl 16) or (v and 0xfff)
                if (type == Relocation.MOVT_ABS) put32(data, offset, read32(data, offset) or immediate)
                else add32(data, offset, immediate)
            }
            Relocation.MOVT_PREL, Relocation.MOVW_PREL_NC -> {
                val instruction = read32(data, offset)
                val addend = (((instruction ushr 4) and 0xf000) or (instruction and 0xfff)).let { (it xor 0x8000) - 0x8000 }
                var v = value + addend - address
                if (type == Relocation.MOVT_PREL) v = v ushr 16
                put32(data, offset, (instruction and 0xfff0f000.toInt()) or ((v and 0xf000) shl 4) or (v and 0xfff))
            }
            Relocation.THM_MOVT_ABS, Relocation.THM_MOVW_ABS_NC -> {
                val v = if (type == Relocation.THM_MOVT_ABS) value ushr 16 else value
                val immediate = (((v ushr 8) and 7) shl 28) or ((v and 0xff) shl 16) or (((v ushr 11) and 1) shl 10) or ((v ushr 12) and 0xf)
                if (type == Relocation.THM_MOVT_ABS) put32(data, offset, read32(data, offset) or immediate)
                else add32(data, offset, immediate)
            }
            Relocation.PREL31 -> {
                var displacement = read32(data, offset) and 0x7fffffff
                displacement += value - address
                if (((displacement xor (displacement shr 1)) and 0x40000000) != 0) state.errors += "can't relocate value at $address, ${type.elfType}"
                put32(data, offset, (read32(data, offset) and 0x80000000.toInt()) or (displacement and 0x7fffffff))
            }
            Relocation.ABS32, Relocation.TARGET1 -> {
                if (state.outputDynamic) {
                    val dyn = symbol.dynamicIndex
                    state.dynamicRelocations += I386Link.RelocationEntry(offset, (if (dyn == 0) 0 else dyn shl 8) or (if (dyn == 0) Relocation.RELATIVE.elfType else Relocation.ABS32.elfType))
                    if (dyn != 0) return
                }
                add32(data, offset, value)
            }
            Relocation.REL32 -> add32(data, offset, value - address)
            Relocation.GOTPC -> add32(data, offset, state.gotAddress - address)
            Relocation.GOTOFF -> add32(data, offset, value - state.gotAddress)
            Relocation.GOT32 -> add32(data, offset, symbol.gotOffset)
            Relocation.GOT_PREL -> add32(data, offset, state.gotAddress + symbol.gotOffset - address)
            Relocation.V4BX -> if ((read32(data, offset) and 0x0ffffff0) == 0x012fff10) put32(data, offset, read32(data, offset) xor 0xe12fff10.toInt() xor 0xe1a0f000.toInt())
            Relocation.GLOB_DAT, Relocation.JUMP_SLOT -> put32(data, offset, value)
            Relocation.RELATIVE -> if (state.targetPe) add32(data, offset, value - state.imageBase)
            Relocation.TLS_LE32 -> {
                val tlsOffset = if (state.tlsEnd != 0) value - state.tlsStart + 8 else value - symbol.sectionAddress - symbol.sectionDataOffset + 8
                add32(data, offset, tlsOffset)
            }
            Relocation.NONE, Relocation.COPY -> Unit
            Relocation.OTHER -> state.errors += "unknown ARM relocation at 0x${address.toString(16)}"
        }
    }

    private fun relocateArmBranch(state: State, type: Relocation, data: ByteArray, offset: Int, address: Int, value: Int) {
        var instruction = read32(data, offset)
        var displacement = (instruction and 0x00ffffff) shl 2
        if ((displacement and 0x02000000) != 0) displacement -= 0x04000000
        val thumb = value and 1
        val originalCode = instruction and -0x1000000
        val isBl = originalCode == 0xeb000000.toInt()
        val isCall = type == Relocation.CALL || (type == Relocation.PC24 && isBl)
        displacement += value - address
        val h = displacement and 2
        val thumbMismatch = (displacement and 3) != 0 && (!state.blxAvailable || !isCall)
        if (thumbMismatch || displacement >= 0x02000000 || displacement < -0x02000000) {
            state.errors += "can't relocate value at $address, ${type.elfType}"
            return
        }
        displacement = (displacement shr 2) and 0xffffff
        instruction = if (thumb != 0) 0xfa000000.toInt() or (displacement or (h shl 24)) else originalCode or displacement
        put32(data, offset, instruction)
    }

    private fun relocateThumbBranch(state: State, type: Relocation, data: ByteArray, offset: Int, address: Int, value: Int, symbol: Symbol, symbolIndex: Int) {
        if (symbol.weakUndefined) return
        val hi = read16(data, offset); val lo = read16(data, offset + 2)
        val s = (hi ushr 10) and 1; val j1 = (lo ushr 13) and 1; val j2 = (lo ushr 11) and 1
        val i1 = (j1 xor s) xor 1; val i2 = (j2 xor s) xor 1
        var displacement = (s shl 24) or (i1 shl 23) or (i2 shl 22) or ((hi and 0x3ff) shl 12) or ((lo and 0x7ff) shl 1)
        if ((displacement and 0x01000000) != 0) displacement -= 0x02000000
        var targetValue = value
        var toThumb = targetValue and 1
        val toPlt = targetValue >= state.pltAddress && targetValue < state.pltAddress + state.plt.size
        val isCall = type == Relocation.THM_PC22
        if (toThumb == 0 && !toPlt && !isCall) {
            val stubAddress = symbol.sectionDataOffset + 1
            val generatedIndex = state.symbols.size
            val stubCode = byteArrayOf(0x78, 0x47, 0xc0.toByte(), 0x46, 0xfe.toByte(), 0xff.toByte(), 0xff.toByte(), 0xea.toByte())
            state.generatedThumbStubs += ThumbStub(symbol.name + "_from_thumb", stubAddress, targetValue, stubCode)
            state.stubRelocations += StubRelocation(stubAddress + 4, Relocation.JUMP24, symbolIndex)
            state.symbols += symbol.copy(sectionDataOffset = stubAddress, symbolValue = stubAddress, name = symbol.name + "_from_thumb")
            targetValue = stubAddress
            toThumb = 1
        }
        displacement += targetValue - address
        var blxBit = 1 shl 12
        if (toThumb == 0 && isCall) { blxBit = 0; displacement = (displacement + 3) and -4 }
        if (toThumb == 0 || displacement >= 0x1000000 || displacement < -0x1000000) {
            if (toThumb != 0 || (targetValue and 2) != 0 || (!isCall && !toPlt)) {
                state.errors += "can't relocate value at $address, ${type.elfType}"
                return
            }
        }
        val ns = (displacement ushr 24) and 1; val ni1 = (displacement ushr 23) and 1; val ni2 = (displacement ushr 22) and 1
        val nj1 = ns xor (ni1 xor 1); val nj2 = ns xor (ni2 xor 1)
        write16(data, offset, (hi and 0xf800) or (ns shl 10) or ((displacement ushr 12) and 0x3ff))
        write16(data, offset + 2, (lo and 0xc000) or (nj1 shl 13) or blxBit or (nj2 shl 11) or ((displacement ushr 1) and 0x7ff))
    }

    private fun read16(data: ByteArray, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8)
    private fun write16(data: ByteArray, o: Int, v: Int) { data[o] = v.toByte(); data[o + 1] = (v ushr 8).toByte() }
    private fun read32(data: ByteArray, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
    private fun put32(data: ByteArray, o: Int, v: Int) { repeat(4) { data[o + it] = (v ushr (it * 8)).toByte() } }
    private fun add32(data: ByteArray, o: Int, v: Int) = put32(data, o, read32(data, o) + v)
    private fun write32(data: ByteArray, o: Int, v: Int) { repeat(4) { data[o + it] = (v ushr (it * 8)).toByte() } }
    private fun read32(data: MutableList<Byte>, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
    private fun write32(data: MutableList<Byte>, o: Int, v: Int) { repeat(4) { data[o + it] = (v ushr (it * 8)).toByte() } }
}
