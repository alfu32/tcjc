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
    private var localCursor = 0
    private var functionReturnPop = 0
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

    enum class IntegerOperation { ADD, ADC, SUB, SBB, AND, XOR, OR, COMPARE, MULTIPLY, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_ARITHMETIC, DIVIDE, UDIVIDE, MODULO, UMODULO, MULTIPLY_UNSIGNED_WIDE }
    data class IntegerResult(val register: Int, val highRegister: Int? = null, val comparison: Boolean = false)

    enum class FloatingOperation { NEGATE, ADD, SUBTRACT, MULTIPLY, DIVIDE, EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL }
    data class FloatingResult(val comparison: FloatingOperation? = null)

    /** Emits x87 scalar arithmetic and compare sequences. */
    fun floatingOperation(operation: FloatingOperation, kind: I386ValueKind, right: I386Value? = null, initiallySwapped: Boolean = false): FloatingResult {
        if (operation == FloatingOperation.NEGATE) { o(0xe0d9); return FloatingResult() }
        var swapped = initiallySwapped
        val isCompare = operation in setOf(FloatingOperation.EQUAL, FloatingOperation.NOT_EQUAL, FloatingOperation.LESS, FloatingOperation.LESS_EQUAL, FloatingOperation.GREATER, FloatingOperation.GREATER_EQUAL)
        if ((isCompare || kind == I386ValueKind.LONG_DOUBLE) && right != null) load(0, right)
        if (kind == I386ValueKind.LONG_DOUBLE && !isCompare) swapped = !swapped
        if (isCompare) {
            when (operation) {
                FloatingOperation.GREATER_EQUAL, FloatingOperation.GREATER -> swapped = !swapped
                FloatingOperation.EQUAL, FloatingOperation.NOT_EQUAL -> swapped = false
                else -> Unit
            }
            if (swapped) o(0xc9d9)
            if (operation == FloatingOperation.EQUAL || operation == FloatingOperation.NOT_EQUAL) o(0xe9da) else o(0xd9de)
            o(0xe0df)
            when (operation) {
                FloatingOperation.EQUAL -> { o(0x45e480); o(0x40fc80) }
                FloatingOperation.NOT_EQUAL -> { o(0x45e480); o(0x40f480) }
                FloatingOperation.GREATER_EQUAL, FloatingOperation.LESS_EQUAL -> { o(0x05c4f6) }
                else -> o(0x45c4f6)
            }
            return FloatingResult(operation)
        }
        val field = when (operation) {
            FloatingOperation.ADD -> 0
            FloatingOperation.SUBTRACT -> if (swapped) 5 else 4
            FloatingOperation.MULTIPLY -> 1
            FloatingOperation.DIVIDE -> if (swapped) 7 else 6
            else -> throw IllegalArgumentException("unsupported floating operation $operation")
        }
        if (kind == I386ValueKind.LONG_DOUBLE) {
            o(0xde); o(0xc1 + (field shl 3))
        } else {
            val address = right?.location as? I386ValueLocation.Memory
                ?: throw IllegalArgumentException("i386 x87 arithmetic expects its right operand in memory")
            genModRm(if (kind == I386ValueKind.DOUBLE) 0xdc else 0xd8, field, address.address)
        }
        return FloatingResult()
    }

    /** Converts an integer register pair or word to the x87 stack. */
    fun convertIntToFloat(register: Int, unsigned: Boolean = false, wide: Boolean = false, highRegister: Int = 2) {
        if (wide) {
            o(0x50 + (highRegister and 7)); o(0x50 + (register and 7))
            o(0x242cdf); o(0x08c483)
        } else if (unsigned) {
            o(0x6a); g(0); o(0x50 + (register and 7)); o(0x242cdf); o(0x08c483)
        } else {
            o(0x50 + (register and 7)); o(0x2404db); o(0x04c483)
        }
    }

    /** Emits the two-word coverage counter increment used by i386-gen.c. */
    fun incrementCoverage(symbol: Symbol) {
        val indirect = if (picEnabled) 0x8300 else 0x0500
        val relocation = if (picEnabled) RelocType.R386_PC32 else RelocType.R386_32
        val firstAddend = if (picEnabled) 2 else 0
        val secondAddend = if (picEnabled) 13 else 4
        if (picEnabled) getPcThunk(3, false)
        o(0x0083 + indirect)
        relocations += Relocation(position, relocation, symbol, firstAddend)
        genLe32(firstAddend)
        o(1)
        o(0x1083 + indirect)
        relocations += Relocation(position, relocation, symbol, secondAddend)
        genLe32(secondAddend)
        g(0)
    }

    /** Emits an indirect computed goto through the requested register. */
    fun computedGoto(register: Int) = callOrJump(isJump = true, target = register)

    /** Saves and restores the stack pointer in an EBP relative VLA slot. */
    fun saveVlaStackPointer(offset: Int) = genModRm(0x89, 4, Address.Local(offset))
    fun restoreVlaStackPointer(offset: Int) = genModRm(0x8b, 4, Address.Local(offset))

    /** Allocates a VLA stack block and rounds the stack to 16 byte alignment. */
    fun allocateVla(sizeRegister: Int, useAllocaHelper: Boolean = false) {
        if (useAllocaHelper) {
            o(0x50 + (sizeRegister and 7))
            callOrJump(false, Symbol("alloca", isStatic = true))
            gaddSp(4)
        } else {
            o(0x2b); o(0xe0 or (sizeRegister and 7))
            o(0xf0e483)
        }
    }

    /** Calls the selected libtcc conversion helper and reports the result registers. */
    fun convertFloatToInteger(source: I386ValueKind, targetIsLongLong: Boolean, invokeHelper: (String) -> Unit): IntegerResult {
        invokeHelper(floatToIntegerHelper(source))
        return IntegerResult(0, if (targetIsLongLong) 2 else null)
    }

    /** Selects the runtime helper used for a floating point to integer cast. */
    fun floatToIntegerHelper(source: I386ValueKind): String = when (source) {
        I386ValueKind.FLOAT -> "__fixsfdi"
        I386ValueKind.LONG_DOUBLE -> "__fixxfdi"
        else -> "__fixdfdi"
    }

    /** Emits the signed/unsigned byte or short extension into a 32 bit register. */
    fun convertCharShortToInt(register: Int, kind: I386ValueKind) {
        val signed = kind == I386ValueKind.BYTE || kind == I386ValueKind.SHORT
        val isShort = kind == I386ValueKind.SHORT || kind == I386ValueKind.USHORT
        o(0xc0b60f or (((if (signed) 1 else 0) shl 3 or (if (isShort) 1 else 0)) shl 8) or (((register and 7) shl 3 or (register and 7)) shl 16))
    }

    /** Emits the integer instruction sequences selected by i386-gen.c's gen_opi. */
    fun integerOperation(operation: IntegerOperation, destination: Int, sourceRegister: Int? = null, immediate: Int? = null): IntegerResult {
        val dst = destination and 7
        val src = sourceRegister?.and(7)
        val group = when (operation) {
            IntegerOperation.ADD -> 0; IntegerOperation.OR -> 1; IntegerOperation.ADC -> 2
            IntegerOperation.SBB -> 3; IntegerOperation.AND -> 4; IntegerOperation.SUB -> 5
            IntegerOperation.XOR -> 6; IntegerOperation.COMPARE -> 7
            else -> -1
        }
        if (group >= 0) {
            if (immediate != null) {
                if (immediate in -128..127 && (operation == IntegerOperation.ADD || operation == IntegerOperation.SUB) && (immediate == 1 || immediate == -1)) {
                    val decrement = (immediate == 1) xor (operation == IntegerOperation.ADD)
                    o((if (decrement) 0x48 else 0x40) + dst)
                } else if (immediate in -128..127) {
                    o(0x83); o(0xc0 or (group shl 3) or dst); g(immediate)
                } else {
                    o(0x81); oad(0xc0 or (group shl 3) or dst, immediate)
                }
            } else {
                val rhs = src ?: throw IllegalArgumentException("register source required")
                o((group shl 3) or 0x01)
                o(0xc0 + dst + (rhs shl 3))
            }
            return IntegerResult(dst, comparison = operation == IntegerOperation.COMPARE)
        }
        when (operation) {
            IntegerOperation.MULTIPLY -> {
                val rhs = src ?: throw IllegalArgumentException("register source required")
                o(0xaf0f); o(0xc0 + rhs + (dst shl 3)); return IntegerResult(dst)
            }
            IntegerOperation.SHIFT_LEFT, IntegerOperation.SHIFT_RIGHT, IntegerOperation.SHIFT_ARITHMETIC -> {
                val groupCode = when (operation) { IntegerOperation.SHIFT_LEFT -> 4; IntegerOperation.SHIFT_RIGHT -> 5; else -> 7 }
                if (immediate != null) { o(0xc1); o(0xc0 or (groupCode shl 3) or dst); g(immediate and 0x1f) }
                else { o(0xd3); o(0xc0 or (groupCode shl 3) or dst) }
                return IntegerResult(dst)
            }
            IntegerOperation.DIVIDE, IntegerOperation.UDIVIDE, IntegerOperation.MODULO, IntegerOperation.UMODULO -> {
                val rhs = src ?: throw IllegalArgumentException("register source required")
                if (operation == IntegerOperation.UDIVIDE || operation == IntegerOperation.UMODULO) { o(0xf7d231); o(0xf0 + rhs) }
                else { o(0xf799); o(0xf8 + rhs) }
                return IntegerResult(if (operation == IntegerOperation.MODULO || operation == IntegerOperation.UMODULO) 2 else 0)
            }
            IntegerOperation.MULTIPLY_UNSIGNED_WIDE -> {
                val rhs = src ?: throw IllegalArgumentException("register source required")
                o(0xf7); o(0xe0 + rhs); return IntegerResult(0, 2)
            }
            else -> throw IllegalArgumentException("unsupported i386 integer operation $operation")
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

    data class CallArgument(val kind: I386ValueKind, val register: Int, val size: Int = 4, val secondRegister: Int? = null, val emitStructure: ((I386Gen, Int) -> Unit)? = null)

    /** Emits already-evaluated arguments and performs the i386 call cleanup. */
    fun functionCall(arguments: List<CallArgument>, target: Any, convention: CallingConvention = CallingConvention.CDECL, returnsStructurePointer: Boolean = false, isJump: Boolean = false) {
        var argumentBytes = 0
        for (argument in arguments) {
            when (argument.kind) {
                I386ValueKind.FLOAT, I386ValueKind.DOUBLE, I386ValueKind.LONG_DOUBLE -> {
                    val size = when (argument.kind) { I386ValueKind.FLOAT -> 4; I386ValueKind.DOUBLE -> 8; else -> 12 }
                    oad(0xec81, size)
                    if (size == 12) o(0x7cdb) else { o(0x5cd9 + size - 4); g(0x24); g(0) }
                    argumentBytes += size
                }
                else -> if (argument.emitStructure != null) {
                    val size = (argument.size + 3) and -4
                    oad(0xec81, size)
                    argument.emitStructure.invoke(this, size)
                    argumentBytes += size
                } else {
                    if (argument.secondRegister != null) {
                        o(0x50 + (argument.secondRegister and 7))
                        argumentBytes += 4
                    }
                    o(0x50 + (argument.register and 7))
                    argumentBytes += if (argument.secondRegister != null) 4 else 4
                }
            }
        }
        val fastRegisters = when (convention) {
            CallingConvention.FASTCALL1 -> intArrayOf(0)
            CallingConvention.FASTCALL2 -> intArrayOf(0, 2)
            CallingConvention.FASTCALL3 -> intArrayOf(0, 2, 1)
            CallingConvention.FASTCALLW -> intArrayOf(1, 2)
            CallingConvention.THISCALL -> intArrayOf(1)
            else -> intArrayOf()
        }
        var left = argumentBytes
        for (register in fastRegisters) {
            if (left <= 0) break
            o(0x58 + register)
            left -= 4
        }
        if (returnsStructurePointer && convention !in setOf(CallingConvention.FASTCALL1, CallingConvention.FASTCALL2, CallingConvention.FASTCALL3, CallingConvention.FASTCALLW, CallingConvention.THISCALL))
            left -= 4
        callOrJump(isJump, target)
        if (left > 0 && convention != CallingConvention.STDCALL && convention != CallingConvention.THISCALL && convention != CallingConvention.FASTCALLW)
            gaddSp(left)
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
                relocations += Relocation(at, RelocType.R386_PLT32, target, addend - 4)
            } else {
                val at = oad(opcode, addend - 4)
                relocations += Relocation(at, RelocType.R386_PC32, target, addend - 4)
            }
        } else if (target is Int) {
            o(0xff)
            o(0xd0 + (target and 7) + (if (isJump) 0x10 else 0))
        } else throw IllegalArgumentException("i386 call target must be a symbol or register")
    }

    enum class CallingConvention { CDECL, STDCALL, FASTCALL1, FASTCALL2, FASTCALL3, FASTCALLW, THISCALL }
    enum class ReturnKind { POINTER, BYTE, SHORT, INT }
    data class StructReturn(val registerCount: Int, val kind: ReturnKind, val alignment: Int, val registerSize: Int)
    data class FunctionFrame(val prologOffset: Int, val prologSize: Int, val parameterOffsets: List<Int>, val cleanupBytes: Int, val localBytes: Int)

    /** Port of the i386 ABI's small-structure return selection. */
    fun structReturn(size: Int, targetOs: String): StructReturn {
        val registerAbi = targetOs == "windows" || targetOs == "freebsd" || targetOs == "openbsd"
        if (!registerAbi || size > 8 || size <= 0 || (size and (size - 1)) != 0)
            return StructReturn(0, ReturnKind.POINTER, 1, 4)
        val kind = when (size) { 1 -> ReturnKind.BYTE; 2 -> ReturnKind.SHORT; else -> ReturnKind.INT }
        return StructReturn(if (size == 8) 2 else 1, kind, 1, 4)
    }

    /** Reserves and later fills the fixed size i386 function prolog. */
    fun functionProlog(parameterSizes: List<Int>, convention: CallingConvention, hasStructureReturnPointer: Boolean, targetOs: String = "linux", peTarget: Boolean = false): FunctionFrame {
        val prologOffset = position
        val prologSize = (if (peTarget) 10 else 9) + if (picEnabled) 1 else 0
        repeat(prologSize) { g(0) }
        localCursor = 0
        var stackAddress = 8
        val offsets = ArrayList<Int>(parameterSizes.size)
        if (hasStructureReturnPointer) { offsets += stackAddress; stackAddress += 4 }
        val fastRegisters = when (convention) {
            CallingConvention.FASTCALL1 -> intArrayOf(0)
            CallingConvention.FASTCALL2 -> intArrayOf(0, 2)
            CallingConvention.FASTCALL3 -> intArrayOf(0, 2, 1)
            CallingConvention.FASTCALLW -> intArrayOf(1, 2)
            CallingConvention.THISCALL -> intArrayOf(1)
            else -> intArrayOf()
        }
        parameterSizes.forEachIndexed { index, rawSize ->
            val size = (rawSize + 3) and -4
            if (index < fastRegisters.size) {
                localCursor -= 4
                genModRm(0x89, fastRegisters[index], Address.Local(localCursor))
                offsets += localCursor
            } else {
                offsets += stackAddress
                stackAddress += size
            }
        }
        functionReturnPop = when {
            convention == CallingConvention.STDCALL || convention == CallingConvention.FASTCALLW || convention == CallingConvention.THISCALL -> stackAddress - 8
            hasStructureReturnPointer && targetOs != "windows" && targetOs != "freebsd" -> 4
            else -> 0
        }
        return FunctionFrame(prologOffset, prologSize, offsets, functionReturnPop, (-localCursor + 3) and -4)
    }

    fun functionEpilog(frame: FunctionFrame, peTarget: Boolean = false) {
        val localSize = (-localCursor + 3) and -4
        o(0xc9)
        if (functionReturnPop == 0) o(0xc3) else { o(0xc2); g(functionReturnPop); g(functionReturnPop ushr 8) }
        val prolog = ArrayList<Byte>()
        fun emit(value: Int) { prolog += value.toByte() }
        fun emitWord(value: Int) {
            var word = value
            while (word != 0) { emit(word); word = word ushr 8 }
        }
        fun emitLong(value: Int) { repeat(4) { emit(value ushr (it * 8)) } }
        if (peTarget && localSize >= 4096) {
            emit(0xb8); emitLong(localSize)
            emit(0xe8)
            val callOffset = frame.prologOffset + prolog.size
            val relocation = Symbol("__chkstk", isStatic = true)
            repeat(4) { emit(0) }
            relocations += Relocation(callOffset, RelocType.R386_PC32, relocation, -4)
        } else {
            emitWord(0xe58955)
            emitWord(0xec81)
            emitLong(localSize)
            if (peTarget) emit(0x90)
        }
        if (picEnabled) emit(0x53)
        while (prolog.size < frame.prologSize) emit(0x90)
        if (prolog.size > frame.prologSize) throw IllegalStateException("i386 prolog exceeded its reserved size")
        prolog.forEachIndexed { index, byte -> code[frame.prologOffset + index] = byte }
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
