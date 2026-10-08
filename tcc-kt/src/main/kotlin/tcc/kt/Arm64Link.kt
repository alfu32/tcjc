package tcc.kt

/** AArch64 ELF relocation policy, PLT emission, and instruction patching. */
object Arm64Link {
    enum class Relocation(val elfType: Int) {
        ABS64(257), ABS32(258), PREL32(261), MOVW_G0_NC(263), MOVW_G1_NC(265),
        MOVW_G2_NC(267), MOVW_G3(269), ADR_PREL_PG_HI21(275), ADD_ABS_LO12_NC(277),
        LDST8_ABS_LO12_NC(278), TSTBR14(279), CONDBR19(280), JUMP26(282), CALL26(283),
        LDST16_ABS_LO12_NC(284), LDST32_ABS_LO12_NC(285), LDST64_ABS_LO12_NC(286),
        LDST128_ABS_LO12_NC(299), ADR_GOT_PAGE(311), LD64_GOT_LO12_NC(312),
        COPY(1024), GLOB_DAT(1025), JUMP_SLOT(1026), RELATIVE(1027),
        TLSLE_ADD_TPREL_HI12(549), TLSLE_ADD_TPREL_LO12(551), OTHER(-1)
    }

    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val AUTO_GOTPLT_ENTRY = 2
    const val ALWAYS_GOTPLT_ENTRY = 3
    private const val NOP = 0xd503201f.toInt()

    data class Symbol(val dynamicIndex: Int = 0, val gotOffset: Int = 0, val weakUndefined: Boolean = false, val name: String = "")
    data class RelocationEntry(val offset: Int, val info: Long, val addend: Long = 0)
    data class State(
        var gotAddress: Long = 0,
        var pltAddress: Long = 0,
        var outputDynamic: Boolean = false,
        var outputDll: Boolean = false,
        var targetPe: Boolean = false,
        var imageBase: Long = 0,
        var tlsStart: Long = 0,
        var got: ByteArray = byteArrayOf(),
        var plt: ByteArray = byteArrayOf(),
        val symbols: MutableList<Symbol> = mutableListOf(),
        val pltRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val dynamicRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf()
    )

    @JvmStatic fun codeReloc(type: Relocation): Int = when (type) {
        Relocation.ABS32, Relocation.ABS64, Relocation.PREL32, Relocation.MOVW_G0_NC,
        Relocation.MOVW_G1_NC, Relocation.MOVW_G2_NC, Relocation.MOVW_G3,
        Relocation.ADR_PREL_PG_HI21, Relocation.ADD_ABS_LO12_NC, Relocation.ADR_GOT_PAGE,
        Relocation.LD64_GOT_LO12_NC, Relocation.LDST128_ABS_LO12_NC,
        Relocation.LDST64_ABS_LO12_NC, Relocation.LDST32_ABS_LO12_NC,
        Relocation.LDST16_ABS_LO12_NC, Relocation.LDST8_ABS_LO12_NC,
        Relocation.TLSLE_ADD_TPREL_HI12, Relocation.TLSLE_ADD_TPREL_LO12,
        Relocation.GLOB_DAT, Relocation.COPY -> 0
        Relocation.JUMP26, Relocation.CALL26, Relocation.JUMP_SLOT,
        Relocation.CONDBR19, Relocation.TSTBR14 -> 1
        else -> -1
    }

    @JvmStatic fun gotpltEntryType(type: Relocation): Int = when (type) {
        Relocation.PREL32, Relocation.MOVW_G0_NC, Relocation.MOVW_G1_NC,
        Relocation.MOVW_G2_NC, Relocation.MOVW_G3, Relocation.ADR_PREL_PG_HI21,
        Relocation.ADD_ABS_LO12_NC, Relocation.LDST128_ABS_LO12_NC,
        Relocation.LDST64_ABS_LO12_NC, Relocation.LDST32_ABS_LO12_NC,
        Relocation.LDST16_ABS_LO12_NC, Relocation.LDST8_ABS_LO12_NC,
        Relocation.GLOB_DAT, Relocation.JUMP_SLOT, Relocation.COPY,
        Relocation.CONDBR19, Relocation.TSTBR14,
        Relocation.TLSLE_ADD_TPREL_HI12, Relocation.TLSLE_ADD_TPREL_LO12 -> NO_GOTPLT_ENTRY
        Relocation.ABS32, Relocation.ABS64, Relocation.JUMP26, Relocation.CALL26 -> AUTO_GOTPLT_ENTRY
        Relocation.ADR_GOT_PAGE, Relocation.LD64_GOT_LO12_NC -> ALWAYS_GOTPLT_ENTRY
        else -> -1
    }

    @JvmStatic
    fun createPltEntry(state: State, gotOffset: Long): Int {
        if (state.plt.isEmpty()) state.plt = ByteArray(32)
        val offset = state.plt.size
        state.plt += ByteArray(16)
        put64(state.plt, offset, gotOffset)
        return offset
    }

    @JvmStatic
    fun relocatePlt(state: State) {
        if (state.plt.isNotEmpty()) {
            val gotPage = state.gotAddress + 16
            val pageDelta = (gotPage ushr 12) - (state.pltAddress ushr 12)
            if (pageDelta !in -(1L shl 20) until (1L shl 20)) {
                state.errors += "Failed relocating AArch64 PLT"
                return
            }
            put32(state.plt, 0, 0xa9800000.toInt() or (16) or (30 shl 10) or (31 shl 5) or ((-2 and 0x7f) shl 15))
            put32(state.plt, 4, adrp(16, pageDelta))
            put32(state.plt, 8, 0xf9400000.toInt() or 17 or (16 shl 5) or (((gotPage and 0xff8) shl 7).toInt()))
            put32(state.plt, 12, 0x91000000.toInt() or 16 or (16 shl 5) or (((gotPage and 0xfff) shl 10).toInt()))
            put32(state.plt, 16, 0xd61f0000.toInt() or (17 shl 5))
            put32(state.plt, 20, NOP); put32(state.plt, 24, NOP); put32(state.plt, 28, NOP)
            var offset = 32
            while (offset < state.plt.size) {
                val pc = state.pltAddress + offset
                val target = state.gotAddress + read64(state.plt, offset)
                val delta = (target ushr 12) - (pc ushr 12)
                if (delta !in -(1L shl 20) until (1L shl 20)) {
                    state.errors += "Failed relocating AArch64 PLT slot"
                    return
                }
                put32(state.plt, offset, adrp(16, delta))
                put32(state.plt, offset + 4, 0xf9400000.toInt() or 17 or (16 shl 5) or (((target and 0xff8) shl 7).toInt()))
                put32(state.plt, offset + 8, 0x91000000.toInt() or 16 or (16 shl 5) or (((target and 0xfff) shl 10).toInt()))
                put32(state.plt, offset + 12, 0xd61f0000.toInt() or (17 shl 5))
                offset += 16
            }
        }
        state.pltRelocations.forEach { put64(state.got, it.offset, state.pltAddress) }
    }

    @JvmStatic
    fun relocate(state: State, type: Relocation, data: ByteArray, offset: Int, address: Long, value: Long, symbolIndex: Int = 0, addend: Long = 0) {
        val symbol = state.symbols.getOrNull(symbolIndex) ?: Symbol()
        val instruction = read32(data, offset)
        when (type) {
            Relocation.ABS64 -> {
                if (state.outputDynamic) {
                    if (symbol.dynamicIndex != 0) {
                        state.dynamicRelocations += RelocationEntry(offset, rInfo(symbol.dynamicIndex, type), addend)
                        return
                    }
                    state.dynamicRelocations += RelocationEntry(offset, rInfo(0, Relocation.RELATIVE), read64(data, offset) + value)
                }
                put64(data, offset, read64(data, offset) + value)
            }
            Relocation.ABS32 -> {
                if (state.outputDynamic) state.dynamicRelocations += RelocationEntry(offset, rInfo(0, Relocation.RELATIVE), read32(data, offset).toLong() + value)
                put32(data, offset, instruction + value.toInt())
            }
            Relocation.PREL32 -> {
                if (state.outputDll && symbol.dynamicIndex != 0) {
                    state.dynamicRelocations += RelocationEntry(offset, rInfo(symbol.dynamicIndex, type), read32(data, offset).toLong() + addend)
                    return
                }
                put32(data, offset, instruction + (value - address).toInt())
            }
            Relocation.MOVW_G0_NC, Relocation.MOVW_G1_NC, Relocation.MOVW_G2_NC, Relocation.MOVW_G3 -> {
                val shift = when (type) { Relocation.MOVW_G0_NC -> 0; Relocation.MOVW_G1_NC -> 16; Relocation.MOVW_G2_NC -> 32; else -> 48 }
                put32(data, offset, (instruction and 0xffe0001f.toInt()) or ((((value ushr shift).toInt()) and 0xffff) shl 5))
            }
            Relocation.ADR_PREL_PG_HI21 -> {
                val pages = (value shr 12) - (address shr 12)
                if (pages !in -(1L shl 20) until (1L shl 20)) {
                    if (state.targetPe && symbol.weakUndefined) {
                        put32(data, offset, 0xd2800000.toInt() or (instruction and 0x1f))
                        return
                    }
                    state.errors += "R_AARCH64_ADR_PREL_PG_HI21 relocation failed"
                    return
                }
                put32(data, offset, (instruction and 0x9f00001f.toInt()) or (((pages and 0x1ffffc) shl 3).toInt()) or (((pages and 3) shl 29).toInt()))
            }
            Relocation.ADD_ABS_LO12_NC, Relocation.LDST8_ABS_LO12_NC -> put32(data, offset, (instruction and 0xffc003ff.toInt()) or (((value and 0xfff) shl 10).toInt()))
            Relocation.LDST16_ABS_LO12_NC -> put32(data, offset, (instruction and 0xffc003ff.toInt()) or (((value and 0xffe) shl 9).toInt()))
            Relocation.LDST32_ABS_LO12_NC -> put32(data, offset, (instruction and 0xffc003ff.toInt()) or (((value and 0xffc) shl 8).toInt()))
            Relocation.LDST64_ABS_LO12_NC -> put32(data, offset, (instruction and 0xffc003ff.toInt()) or (((value and 0xff8) shl 7).toInt()))
            Relocation.LDST128_ABS_LO12_NC -> put32(data, offset, (instruction and 0xffc003ff.toInt()) or (((value and 0xff0) shl 6).toInt()))
            Relocation.CONDBR19 -> {
                val delta = value - address
                if (((delta + (1L shl 20)) and 0xffe00000L) != 0L) state.errors += "R_AARCH64_CONDBR19 relocation failed"
                put32(data, offset, (instruction and 0xff00001f.toInt()) or (((delta shr 2).toInt() and 0x7ffff) shl 5))
            }
            Relocation.TSTBR14 -> {
                val delta = value - address
                if (((delta + (1L shl 15)) and 0xffff8000L) != 0L) state.errors += "R_AARCH64_TSTBR14 relocation failed"
                put32(data, offset, (instruction and 0xfff8001f.toInt()) or (((delta shr 2).toInt() and 0x3fff) shl 5))
            }
            Relocation.JUMP26, Relocation.CALL26 -> {
                val delta = value - address
                if (delta < -(1L shl 27) || delta >= (1L shl 27) || (delta and 3L) != 0L) {
                    if (state.targetPe && symbol.weakUndefined) { put32(data, offset, NOP); return }
                    state.errors += "R_AARCH64_(JUMP|CALL)26 relocation failed for '${symbol.name}'"
                    return
                }
                put32(data, offset, 0x14000000 or (if (type == Relocation.CALL26) 0x80000000.toInt() else 0) or (((delta shr 2).toInt()) and 0x3ffffff))
            }
            Relocation.ADR_GOT_PAGE -> {
                val gotAddress = state.gotAddress + symbol.gotOffset
                val pages = (gotAddress shr 12) - (address shr 12)
                if (pages !in -(1L shl 20) until (1L shl 20)) state.errors += "R_AARCH64_ADR_GOT_PAGE relocation failed"
                put32(data, offset, (instruction and 0x9f00001f.toInt()) or (((pages and 0x1ffffc) shl 3).toInt()) or (((pages and 3) shl 29).toInt()))
            }
            Relocation.LD64_GOT_LO12_NC -> {
                val gotAddress = state.gotAddress + symbol.gotOffset
                put32(data, offset, (instruction and 0xfff803ff.toInt()) or (((gotAddress and 0xff8) shl 7).toInt()))
            }
            Relocation.GLOB_DAT, Relocation.JUMP_SLOT -> put64(data, offset, value - addend)
            Relocation.TLSLE_ADD_TPREL_HI12, Relocation.TLSLE_ADD_TPREL_LO12 -> {
                val tlsOffset = value - state.tlsStart + if (state.targetPe) 0 else 16
                val immediate = if (type == Relocation.TLSLE_ADD_TPREL_HI12) (tlsOffset shr 12) and 0xfff else tlsOffset and 0xfff
                put32(data, offset, (instruction and 0xffc003ff.toInt()) or ((immediate.toInt()) shl 10))
            }
            Relocation.RELATIVE -> if (state.targetPe) put32(data, offset, instruction + (value - state.imageBase).toInt())
            Relocation.COPY, Relocation.OTHER -> Unit
        }
    }

    private fun adrp(register: Int, pages: Long): Int = 0x90000000.toInt() or register or (((pages and 0x1ffffc) shl 3).toInt()) or (((pages and 3) shl 29).toInt())
    private fun rInfo(symbol: Int, type: Relocation) = (symbol.toLong() shl 32) or (type.elfType.toLong() and 0xffffffffL)
    private fun read32(data: ByteArray, o: Int): Int = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
    private fun put32(data: ByteArray, o: Int, v: Int) { repeat(4) { data[o + it] = (v ushr (it * 8)).toByte() } }
    private fun read64(data: ByteArray, o: Int): Long = (read32(data, o).toLong() and 0xffffffffL) or (read32(data, o + 4).toLong() shl 32)
    private fun put64(data: ByteArray, o: Int, v: Long) { put32(data, o, v.toInt()); put32(data, o + 4, (v ushr 32).toInt()) }
}
