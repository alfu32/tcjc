package tcc.kt

/** RISC-V ELF relocation classification, PLT emission, and patching. */
object Riscv64Link {
    enum class Relocation(val elfType: Int) {
        NONE(0), ABS32(1), ABS64(2), RELATIVE(3), COPY(4), JUMP_SLOT(5),
        BRANCH(16), JAL(17), CALL(18), CALL_PLT(19), GOT_HI20(20), PCREL_HI20(23),
        PCREL_LO12_I(24), PCREL_LO12_S(25), TPREL_HI20(29), TPREL_LO12_I(30),
        ADD16(34), ADD32(35), ADD64(36), SUB8(37), SUB16(38), SUB32(39), SUB64(40),
        RVC_BRANCH(44), RVC_JUMP(45), ALIGN(43), RELAX(51), SUB6(52), SET6(53),
        SET8(54), SET16(55), PCREL32(57), SET_ULEB128(60), SUB_ULEB128(61),
        OTHER(-1)
    }

    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val AUTO_GOTPLT_ENTRY = 2
    const val ALWAYS_GOTPLT_ENTRY = 3

    data class Symbol(val dynamicIndex: Int = 0, val gotOffset: Long = 0, val name: String = "")
    data class RelocationEntry(val offset: Int, val info: Long, val addend: Long = 0)
    data class State(
        var outputDynamic: Boolean = false,
        var gotAddress: Long = 0,
        var pltAddress: Long = 0,
        var tlsStart: Long = 0,
        var got: ByteArray = byteArrayOf(),
        var plt: ByteArray = byteArrayOf(),
        val symbols: MutableList<Symbol> = mutableListOf(),
        val pcrelHiValues: MutableMap<Long, Long> = mutableMapOf(),
        val pltRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val dynamicRelocations: MutableList<RelocationEntry> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf()
    )

    @JvmStatic fun codeReloc(type: Relocation): Int = when (type) {
        Relocation.BRANCH, Relocation.CALL, Relocation.JAL, Relocation.CALL_PLT -> 1
        Relocation.GOT_HI20, Relocation.PCREL_HI20, Relocation.PCREL_LO12_I,
        Relocation.PCREL_LO12_S, Relocation.PCREL32, Relocation.SET6, Relocation.SET8,
        Relocation.SET16, Relocation.SUB6, Relocation.ADD16, Relocation.ADD32,
        Relocation.ADD64, Relocation.SUB8, Relocation.SUB16, Relocation.SUB32,
        Relocation.SUB64, Relocation.ABS32, Relocation.ABS64, Relocation.SET_ULEB128,
        Relocation.SUB_ULEB128, Relocation.TPREL_HI20, Relocation.TPREL_LO12_I -> 0
        else -> -1
    }

    @JvmStatic fun gotpltEntryType(type: Relocation): Int = when (type) {
        Relocation.ALIGN, Relocation.RELAX, Relocation.RVC_BRANCH, Relocation.RVC_JUMP,
        Relocation.JUMP_SLOT, Relocation.SET6, Relocation.SET8, Relocation.SET16,
        Relocation.SUB6, Relocation.ADD16, Relocation.SUB8, Relocation.SUB16,
        Relocation.SET_ULEB128, Relocation.SUB_ULEB128,
        Relocation.TPREL_HI20, Relocation.TPREL_LO12_I -> NO_GOTPLT_ENTRY
        Relocation.BRANCH, Relocation.CALL, Relocation.PCREL_HI20,
        Relocation.PCREL_LO12_I, Relocation.PCREL_LO12_S, Relocation.PCREL32,
        Relocation.ADD32, Relocation.ADD64, Relocation.SUB32, Relocation.SUB64,
        Relocation.ABS32, Relocation.ABS64, Relocation.JAL, Relocation.CALL_PLT -> AUTO_GOTPLT_ENTRY
        Relocation.GOT_HI20 -> ALWAYS_GOTPLT_ENTRY
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
            val got = state.gotAddress
            val plt = state.pltAddress
            val off = (got - plt + 0x800) shr 12
            if (off !in -(1L shl 20) until (1L shl 20)) {
                state.errors += "Failed relocating RISC-V PLT"
                return
            }
            put32(state.plt, 0, 0x397 or (off.toInt() shl 12))
            put32(state.plt, 4, 0x41c30333)
            put32(state.plt, 8, 0x0003be03 or (((got - plt) and 0xfff).toInt() shl 20))
            put32(state.plt, 12, 0xfd430313.toInt())
            put32(state.plt, 16, 0x00038293 or (((got - plt) and 0xfff).toInt() shl 20))
            put32(state.plt, 20, 0x00135313)
            put32(state.plt, 24, 0x0082b283)
            put32(state.plt, 28, 0x000e0067)
            var offset = 32
            while (offset < state.plt.size) {
                val pc = plt + offset
                val target = got + read64(state.plt, offset)
                val pageOffset = (target - pc + 0x800) shr 12
                if (pageOffset !in -(1L shl 20) until (1L shl 20)) {
                    state.errors += "Failed relocating RISC-V PLT entry"
                    return
                }
                put32(state.plt, offset, 0xe17 or (pageOffset.toInt() shl 12))
                put32(state.plt, offset + 4, 0x000e3e03 or (((target - pc) and 0xfff).toInt() shl 20))
                put32(state.plt, offset + 8, 0x000e0367)
                put32(state.plt, offset + 12, 0x00000013)
                offset += 16
            }
        }
        state.pltRelocations.forEach { put64(state.got, it.offset, state.pltAddress) }
    }

    @JvmStatic
    fun relocate(state: State, type: Relocation, data: ByteArray, offset: Int, address: Long, value: Long, symbolIndex: Int = 0, addend: Long = 0, relOffset: Int = offset) {
        val symbol = state.symbols.getOrNull(symbolIndex) ?: Symbol()
        var off64: Long
        var off32: Int
        when (type) {
            Relocation.ALIGN, Relocation.RELAX -> Unit
            Relocation.BRANCH -> {
                off64 = value - address
                if ((((off64 + (1 shl 12)) and 0xffffffffffffe001UL.toLong()) != 0L)) state.errors += "R_RISCV_BRANCH relocation failed"
                off32 = (off64 shr 1).toInt()
                val insn = read32(data, offset)
                put32(data, offset, (insn and 0xfe000f80.toInt().inv()) or
                    ((off32 and 0x800) shl 20) or ((off32 and 0x3f0) shl 21) or
                    ((off32 and 0x00f) shl 8) or ((off32 and 0x400) shr 3))
            }
            Relocation.JAL -> {
                off64 = value - address
                if (off64 < -(1L shl 20) || off64 >= (1L shl 20) || (off64 and 1L) != 0L) state.errors += "R_RISCV_JAL relocation failed"
                off32 = off64.toInt()
                val insn = read32(data, offset)
                put32(data, offset, (insn and 0xfff) or (((off32 shr 12) and 0xff) shl 12) or
                    (((off32 shr 11) and 1) shl 20) or (((off32 shr 1) and 0x3ff) shl 21) or (((off32 shr 20) and 1) shl 31))
            }
            Relocation.CALL, Relocation.CALL_PLT -> {
                put32(data, offset, (read32(data, offset) and 0xfff) or (((value - address + 0x800).toInt()) and -0x1000))
                put32(data, offset + 4, (read32(data, offset + 4) and 0xfffff) or (((value - address).toInt() and 0xfff) shl 20))
            }
            Relocation.PCREL_HI20, Relocation.GOT_HI20 -> {
                val target = if (type == Relocation.GOT_HI20) state.gotAddress + symbol.gotOffset else value
                off64 = (target - address + 0x800) shr 12
                if (off64 !in -(1L shl 20) until (1L shl 20)) state.errors += "R_RISCV_${type.name} relocation failed"
                put32(data, offset, (read32(data, offset) and 0xfff) or ((off64.toInt() and 0xfffff) shl 12))
                state.pcrelHiValues[address] = target
            }
            Relocation.PCREL_LO12_I -> {
                val hiAddress = value
                val hiValue = state.pcrelHiValues[hiAddress]
                if (hiValue == null) state.errors += "unsupported hi/lo pcrel reloc scheme"
                else put32(data, offset, (read32(data, offset) and 0xfffff) or (((hiValue - hiAddress).toInt() and 0xfff) shl 20))
            }
            Relocation.PCREL_LO12_S -> {
                val hiAddress = value
                val hiValue = state.pcrelHiValues[hiAddress]
                if (hiValue == null) state.errors += "unsupported hi/lo pcrel reloc scheme"
                else {
                    off32 = (hiValue - hiAddress).toInt()
                    put32(data, offset, (read32(data, offset) and 0xfe000f80.toInt().inv()) or ((off32 and 0xfe0) shl 20) or ((off32 and 0x01f) shl 7))
                }
            }
            Relocation.RVC_BRANCH -> {
                off64 = value - address
                if (off64 < -256 || off64 > 254 || (off64 and 1L) != 0L) state.errors += "R_RISCV_RVC_BRANCH relocation failed"
                off32 = off64.toInt()
                put16(data, offset, (read16(data, offset) and 0xe383) or (((off32 shr 5) and 1) shl 2) or (((off32 shr 1) and 3) shl 3) or (((off32 shr 6) and 3) shl 5) or (((off32 shr 3) and 3) shl 10) or (((off32 shr 8) and 1) shl 12))
            }
            Relocation.RVC_JUMP -> {
                off64 = value - address
                if (off64 < -2048 || off64 > 2046 || (off64 and 1L) != 0L) state.errors += "R_RISCV_RVC_JUMP relocation failed"
                off32 = off64.toInt()
                put16(data, offset, (read16(data, offset) and 0xe003) or (((off32 shr 5) and 1) shl 2) or (((off32 shr 1) and 7) shl 3) or (((off32 shr 7) and 1) shl 6) or (((off32 shr 6) and 1) shl 7) or (((off32 shr 10) and 1) shl 8) or (((off32 shr 8) and 3) shl 9) or (((off32 shr 4) and 1) shl 11) or (((off32 shr 11) and 1) shl 12))
            }
            Relocation.ABS32 -> {
                if (state.outputDynamic) state.dynamicRelocations += RelocationEntry(relOffset, rInfo(0, Relocation.RELATIVE), read32(data, offset).toLong() + value)
                add32(data, offset, value.toInt())
            }
            Relocation.ABS64 -> {
                if (state.outputDynamic) {
                    if (symbol.dynamicIndex != 0) {
                        state.dynamicRelocations += RelocationEntry(relOffset, rInfo(symbol.dynamicIndex, type), addend)
                        return
                    }
                    state.dynamicRelocations += RelocationEntry(relOffset, rInfo(0, Relocation.RELATIVE), read64(data, offset) + value)
                }
                add64(data, offset, value)
            }
            Relocation.JUMP_SLOT -> add64(data, offset, value)
            Relocation.ADD64 -> put64(data, offset, read64(data, offset) + value)
            Relocation.ADD32 -> put32(data, offset, read32(data, offset) + value.toInt())
            Relocation.SUB64 -> put64(data, offset, read64(data, offset) - value)
            Relocation.SUB32 -> put32(data, offset, read32(data, offset) - value.toInt())
            Relocation.ADD16 -> put16(data, offset, read16(data, offset) + value.toInt())
            Relocation.SUB8 -> data[offset] = (data[offset] - value.toByte()).toByte()
            Relocation.SUB16 -> put16(data, offset, read16(data, offset) - value.toInt())
            Relocation.SET6 -> data[offset] = ((data[offset].toInt() and 0xc0) or (value.toInt() and 0x3f)).toByte()
            Relocation.SET8 -> data[offset] = value.toByte()
            Relocation.SET16 -> put16(data, offset, value.toInt())
            Relocation.SUB6 -> data[offset] = ((data[offset].toInt() and 0xc0) or ((data[offset] - value.toByte()).toInt() and 0x3f)).toByte()
            Relocation.PCREL32 -> {
                if (state.outputDynamic && symbol.dynamicIndex != 0) {
                    state.dynamicRelocations += RelocationEntry(relOffset, rInfo(symbol.dynamicIndex, type), read32(data, offset).toLong() + addend)
                    return
                }
                add32(data, offset, (value - address).toInt())
            }
            Relocation.SET_ULEB128, Relocation.SUB_ULEB128, Relocation.COPY, Relocation.RELATIVE -> Unit
            Relocation.NONE, Relocation.OTHER -> state.errors += "unknown RISC-V relocation at 0x${address.toString(16)}"
            Relocation.TPREL_HI20, Relocation.TPREL_LO12_I -> {
                val tpOffset = value - state.tlsStart
                if (type == Relocation.TPREL_HI20) {
                    off64 = (tpOffset + 0x800) shr 12
                    if (off64 !in -(1L shl 20) until (1L shl 20)) state.errors += "R_RISCV_TPREL_HI20 relocation failed"
                    put32(data, offset, (read32(data, offset) and 0xfff) or ((off64.toInt() and 0xfffff) shl 12))
                } else put32(data, offset, (read32(data, offset) and 0xfffff) or ((tpOffset.toInt() and 0xfff) shl 20))
            }
        }
    }

    private fun rInfo(symbol: Int, type: Relocation) = (symbol.toLong() shl 32) or type.elfType.toLong()
    private fun read16(data: ByteArray, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8)
    private fun put16(data: ByteArray, o: Int, v: Int) { data[o] = v.toByte(); data[o + 1] = (v ushr 8).toByte() }
    private fun read32(data: ByteArray, o: Int) = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
    private fun put32(data: ByteArray, o: Int, v: Int) { repeat(4) { data[o + it] = (v ushr (it * 8)).toByte() } }
    private fun read64(data: ByteArray, o: Int) = (read32(data, o).toLong() and 0xffffffffL) or (read32(data, o + 4).toLong() shl 32)
    private fun put64(data: ByteArray, o: Int, v: Long) { put32(data, o, v.toInt()); put32(data, o + 4, (v ushr 32).toInt()) }
    private fun add32(data: ByteArray, o: Int, v: Int) = put32(data, o, read32(data, o) + v)
    private fun add64(data: ByteArray, o: Int, v: Long) = put64(data, o, read64(data, o) + v)
}
