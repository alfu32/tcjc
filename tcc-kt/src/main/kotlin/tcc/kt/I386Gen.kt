package tcc.kt

/** i386 instruction emission and address encoding from i386-gen.c. */
class I386Gen(
    private val noCodeWanted: () -> Boolean = { false },
    private val picEnabled: Boolean = false,
    private val staticCall: (String) -> Unit = {},
) {
    enum class RelocType { R386_32, R386_PC32, R386_PLT32, R386_GOT32X, R386_GOTPC, R386_TLS_LE }
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

    /** Emits the C backend's load operation for common scalar value forms. */
    fun load(register: Int, value: I386Value) {
        val kind = value.kind
        val reg = if (kind == I386ValueKind.FLOAT || kind == I386ValueKind.DOUBLE) 0
            else if (kind == I386ValueKind.LONG_DOUBLE) 5 else register
        val opcode = when (kind) {
            I386ValueKind.FLOAT -> 0xd9
            I386ValueKind.DOUBLE -> 0xdd
            I386ValueKind.LONG_DOUBLE -> 0xdb
            I386ValueKind.BYTE, I386ValueKind.BOOL -> 0xbe0f
            I386ValueKind.UBYTE, I386ValueKind.UBOOL -> 0xb60f
            I386ValueKind.SHORT -> 0xbf0f
            I386ValueKind.USHORT -> 0xb70f
            else -> 0x8b
        }
        when (val location = value.location) {
            is I386ValueLocation.Memory -> genModRm(opcode, reg, location.address)
            is I386ValueLocation.Immediate -> {
                val symbol = location.symbol
                if (symbol?.isTls == true) {
                    oad(0x058b65 or ((register and 7) shl 19), 0)
                    oad(0xc081 or ((register and 7) shl 8), value.addend + location.value)
                    relocations += Relocation(position - 4, RelocType.R386_TLS_LE, symbol, value.addend + location.value)
                } else if (picEnabled && symbol != null) {
                    if (symbol.isStatic) {
                        getPcThunk(register, false)
                        o(0x808d or ((register and 7) * 0x900))
                        genAddrPc32(true, symbol, value.addend + location.value + 6)
                    } else {
                        getPcThunk(register, true)
                        o(0x808b or ((register and 7) * 0x900))
                        genGotPcRel(register, symbol, value.addend + location.value)
                    }
                } else {
                    o(0xb8 + (register and 7))
                    genAddr32(symbol != null, symbol, value.addend + location.value)
                }
            }
            is I386ValueLocation.Register -> if (location.register != register) {
                o(0x89)
                o(0xc0 + (register and 7) + (location.register and 7) * 8)
            }
            is I386ValueLocation.Compare -> {
                o(0x0f); o(location.condition); o(0xc0 + (register and 7))
                o(0xc0b60f + (register and 7) * 0x90000)
            }
        }
    }

    /** Emits a scalar store from a register into memory or another register. */
    fun store(register: Int, destination: I386Value) {
        val opcode: Int
        val opRegister: Int
        when (destination.kind) {
            I386ValueKind.FLOAT -> { opcode = 0xd9; opRegister = 2 }
            I386ValueKind.DOUBLE -> { opcode = 0xdd; opRegister = 2 }
            I386ValueKind.LONG_DOUBLE -> { o(0xc0d9); opcode = 0xdb; opRegister = 7 }
            I386ValueKind.SHORT, I386ValueKind.USHORT -> { opcode = 0x8966; opRegister = register }
            I386ValueKind.BYTE, I386ValueKind.UBYTE, I386ValueKind.BOOL, I386ValueKind.UBOOL -> { opcode = 0x88; opRegister = register }
            else -> { opcode = 0x89; opRegister = register }
        }
        when (val location = destination.location) {
            is I386ValueLocation.Memory -> {
                val address = location.address
                if (picEnabled && address is Address.Immediate && address.symbol != null && !address.symbol.isStatic && !address.symbol.isTls) {
                    getPcThunk(3, true)
                    o(0x9b8b)
                    genGotPcRel(3, address.symbol, destination.addend + address.value)
                    o(opcode)
                    o(3 + (opRegister shl 3))
                } else genModRm(opcode, opRegister, address)
            }
            is I386ValueLocation.Immediate -> genModRm(opcode, opRegister, Address.Immediate(location.value + destination.addend, location.symbol))
            is I386ValueLocation.Register -> if (location.register != register) {
                o(opcode)
                o(0xc0 + (location.register and 7) + ((register and 7) shl 3))
            }
            is I386ValueLocation.Compare -> Unit
        }
    }

    /** Emits an unresolved near jump and returns its patch-chain node. */
    fun gjmp(next: Int = 0): Int = oad(0xe9, next)

    /** Emits a jump to a known address, using the short encoding when possible. */
    fun gjmpAddr(target: Int) {
        val shortDelta = target - position - 2
        if (shortDelta in -128..127) { g(0xeb); g(shortDelta) }
        else oad(0xe9, target - position - 5)
    }

    fun gjmpCond(condition: Int, next: Int = 0): Int {
        g(0x0f)
        return oad(condition - 16, next)
    }

    /** Joins a linked list of unresolved jump fields into another chain. */
    fun gjmpAppend(head: Int, tail: Int): Int {
        if (head == 0) return tail
        var last = head
        while (true) {
            val next = read32(last)
            if (next == 0) break
            last = next
        }
        write32(last, tail)
        return head
    }

    fun gaddSp(amount: Int) {
        if (amount in -128..127) { o(0xc483); g(amount) }
        else oad(0xc481, amount)
    }

    /** Emits a direct relocated call/jump or an indirect register call/jump. */
    fun callOrJump(isJump: Boolean, target: Any, addend: Int = 0) {
        if (target is Symbol) {
            val opcode = 0xe8 + if (isJump) 1 else 0
            if (picEnabled && !target.isStatic) {
                getPcThunk(3, true)
                val at = oad(opcode, addend - 4)
                relocations += Relocation(at, RelocType.R386_PLT32, target, addend)
            } else {
                val at = oad(opcode, addend - 4)
                relocations += Relocation(at, RelocType.R386_PC32, target, addend)
            }
        } else if (target is Int) {
            o(0xff)
            o(0xd0 + (target and 7) + (if (isJump) 0x10 else 0))
        } else throw IllegalArgumentException("i386 call target must be a symbol or register")
    }

    fun read32(offset: Int): Int = (code[offset].toInt() and 0xff) or
        ((code[offset + 1].toInt() and 0xff) shl 8) or
        ((code[offset + 2].toInt() and 0xff) shl 16) or
        (code[offset + 3].toInt() shl 24)

    private fun write32(offset: Int, value: Int) {
        repeat(4) { code[offset + it] = (value ushr (it * 8)).toByte() }
    }
}

enum class I386ValueKind { INT, UNSIGNED_INT, FLOAT, DOUBLE, LONG_DOUBLE, BYTE, UBYTE, BOOL, UBOOL, SHORT, USHORT }
sealed interface I386ValueLocation {
    data class Memory(val address: I386Gen.Address) : I386ValueLocation
    data class Immediate(val value: Int, val symbol: I386Gen.Symbol? = null) : I386ValueLocation
    data class Register(val register: Int) : I386ValueLocation
    data class Compare(val condition: Int) : I386ValueLocation
}
data class I386Value(val kind: I386ValueKind, val location: I386ValueLocation, val addend: Int = 0)
