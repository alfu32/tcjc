package tcc.kt

/** i386 instruction emission and address encoding from i386-gen.c. */
class I386Gen(
    private val noCodeWanted: () -> Boolean = { false },
    private val picEnabled: Boolean = false,
    private val staticCall: (String) -> Unit = {},
) {
    enum class RelocType { R386_32, R386_PC32, R386_GOT32X, R386_GOTPC, R386_TLS_LE }
    data class Symbol(val name: String, val isStatic: Boolean = false, val isTls: Boolean = false)
    data class Relocation(val offset: Int, val type: RelocType, val symbol: Symbol, val addend: Int)

    sealed interface Address {
        data class Immediate(val value: Int, val symbol: Symbol? = null) : Address
        data class Local(val displacement: Int) : Address
        data class Base(val register: Int, val displacement: Int = 0) : Address
        data class Register(val register: Int) : Address
    }

    private val code = ArrayList<Byte>()
    private val TREG_MEM = 0x20
    val relocations: MutableList<Relocation> = mutableListOf()
    val bytes: ByteArray get() = code.toByteArray()
    val position: Int get() = code.size

    /** Emits one byte unless code generation is disabled. */
    fun g(value: Int) {
        if (!noCodeWanted()) code += value.toByte()
    }

    /** Emits the nonzero little endian bytes of an instruction word. */
    fun o(value: Int) {
        var remaining = value
        while (remaining != 0) {
            g(remaining)
            remaining = remaining ushr 8
        }
    }

    fun genLe16(value: Int) { g(value); g(value ushr 8) }
    fun genLe32(value: Int) { g(value); g(value ushr 8); g(value ushr 16); g(value ushr 24) }

    /** Resolves the linked list of forward jumps stored at displacement fields. */
    fun gsymAddr(head: Int, address: Int) {
        var patch = head
        while (patch != 0) {
            val next = read32(patch)
            write32(patch, address - patch - 4)
            patch = next
        }
    }

    /** Emits an opcode and its 32 bit immediate, returning the immediate offset. */
    fun oad(opcode: Int, immediate: Int): Int {
        if (noCodeWanted()) return immediate
        o(opcode)
        val offset = position
        genLe32(immediate)
        return offset
    }

    fun genFillNops(count: Int) { repeat(count.coerceAtLeast(0)) { g(0x90) } }

    fun genAddr32(symbolic: Boolean, symbol: Symbol?, constant: Int) {
        if (symbolic && symbol != null) relocations += Relocation(position, RelocType.R386_32, symbol, constant)
        genLe32(constant)
    }

    fun genAddrPc32(symbolic: Boolean, symbol: Symbol?, constant: Int) {
        if (symbolic && symbol != null) relocations += Relocation(position, RelocType.R386_PC32, symbol, constant)
        genLe32(constant - 4)
    }

    /** Emits the i386 PIC call/pop thunk and optional GOT base adjustment. */
    fun getPcThunk(register: Int, addGot: Boolean) {
        if (noCodeWanted()) return
        val names = arrayOf("__x86.get_pc_thunk.ax", "__x86.get_pc_thunk.cx", "__x86.get_pc_thunk.dx", "__x86.get_pc_thunk.bx")
        staticCall(names[register and 3])
        if (addGot) {
            val got = Symbol("_GLOBAL_OFFSET_TABLE_", isStatic = true)
            val modrm = if ((register and 7) == 0) 0x05 else 0xc081 + (register and 7) * 0x100
            val immediate = oad(modrm, 2)
            relocations += Relocation(immediate, RelocType.R386_GOTPC, got, 0)
        }
    }

    fun genGotPcRel(register: Int, symbol: Symbol, addend: Int) {
        relocations += Relocation(position, RelocType.R386_GOT32X, symbol, 0)
        genLe32(0)
        if (addend != 0) {
            val reg = register and 7
            if (reg == 0) oad(0x05, addend) else oad(0xc081 + reg * 0x100, addend)
        }
    }

    /** Encodes the address modes handled by i386-gen.c's gen_modrm. */
    fun genModRm(opcode: Int, operandRegister: Int, address: Address) {
        val opReg = (operandRegister and 7) shl 3
        when (address) {
            is Address.Immediate -> {
                val symbol = address.symbol
                if (symbol?.isTls == true) {
                    g(0x65)
                    o(opcode)
                    o(0x05 or opReg)
                    val relocationOffset = position
                    genLe32(address.value)
                    relocations += Relocation(relocationOffset, RelocType.R386_TLS_LE, symbol, address.value)
                } else if (picEnabled && symbol != null && !symbol.isStatic) {
                    val isGot = (operandRegister and TREG_MEM) != 0
                    val here = position
                    getPcThunk(3, addGot = isGot)
                    o(opcode)
                    o(0x83 or opReg)
                    if (isGot) {
                        genGotPcRel(3, symbol, address.value + (position - here - 1))
                    } else {
                        genAddrPc32(true, symbol, address.value + (position - here - 1))
                    }
                } else {
                    o(opcode)
                    o(0x05 or opReg)
                    genAddr32(symbol != null, symbol, address.value)
                }
            }
            is Address.Local -> {
                o(opcode)
                if (address.displacement in -128..127) {
                    o(0x45 or opReg)
                    g(address.displacement)
                } else {
                    oad(0x85 or opReg, address.displacement)
                }
            }
            is Address.Base -> {
                o(opcode)
                val reg = address.register and 7
                if (address.displacement != 0) {
                    g(0x80 or opReg or reg)
                    genLe32(address.displacement)
                } else if (reg == 5) {
                    g(0x40 or opReg or reg)
                    g(0)
                } else {
                    g(opReg or reg)
                    if (reg == 4) g(0x24)
                }
            }
            is Address.Register -> {
                o(opcode)
                g(opReg or (address.register and 7))
            }
        }
    }

    fun read32(offset: Int): Int = (code[offset].toInt() and 0xff) or
        ((code[offset + 1].toInt() and 0xff) shl 8) or
        ((code[offset + 2].toInt() and 0xff) shl 16) or
        (code[offset + 3].toInt() shl 24)

    private fun write32(offset: Int, value: Int) {
        repeat(4) { code[offset + it] = (value ushr (it * 8)).toByte() }
    }
}
