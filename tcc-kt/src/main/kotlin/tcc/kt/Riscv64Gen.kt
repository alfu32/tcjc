package tcc.kt

/** RISC-V 64-bit code generation helpers mechanically translated from riscv64-gen.c. */
class Riscv64Gen(
    private val noCode: () -> Boolean = { false },
    private val error: (String) -> Unit = { throw IllegalStateException(it) },
) {
    companion object {
        const val NB_REGS = 19
        const val TREG_RA = 17
        const val TREG_SP = 18
        const val RC_INT = 1 shl 0
        const val RC_FLOAT = 1 shl 1
        const val RC_IRET = 1 shl 2
        const val RC_IRE2 = 1 shl 3
        const val RC_FRET = 1 shl 10
        const val REG_IRET = 0
        const val REG_IRE2 = 1
        const val REG_FRET = 8
        const val PTR_SIZE = 8
        const val LDOUBLE_SIZE = 16
        const val LDOUBLE_ALIGN = 16
        const val MAX_ALIGN = 16
        const val VT_BYTE = 1
        const val VT_SHORT = 2
        const val VT_INT = 3
        const val VT_LLONG = 4
        const val VT_PTR = 5
        const val VT_FUNC = 6
        const val VT_STRUCT = 7
        const val VT_FLOAT = 8
        const val VT_DOUBLE = 9
        const val VT_LDOUBLE = 10
        const val RC_R0 = 1 shl 2
        const val RC_F0 = 1 shl 10
        val TARGET_MACHINE_DEFS = listOf("__riscv", "__riscv_xlen 64", "__riscv_flen 64", "__riscv_div", "__riscv_mul", "__riscv_fdiv", "__riscv_fsqrt", "__riscv_float_abi_double")
        val REGISTER_CLASSES = IntArray(NB_REGS).apply {
            for (index in 0..7) this[index] = RC_INT or (1 shl (2 + index))
            for (index in 8..15) this[index] = RC_FLOAT or (1 shl (10 + index - 8))
            this[16] = 0
            this[TREG_RA] = 1 shl TREG_RA
            this[TREG_SP] = 1 shl TREG_SP
        }
    }

    data class CodePosition(var offset: Int = 0)
    enum class ValueKind { CONSTANT, LOCAL, LOCAL_LVALUE, REGISTER, OTHER }
    data class Value(
        val value: Long = 0, val symbol: String? = null, val isExternal: Boolean = false,
        val isStatic: Boolean = false, val isTls: Boolean = false, val kind: ValueKind = ValueKind.CONSTANT,
        val isLValue: Boolean = false, val isFloating: Boolean = false, val isDouble: Boolean = false,
        val isUnsigned: Boolean = false, val isLongLong: Boolean = false, val register: Int = -1,
        val baseType: Int = 0, val typeSize: Int = 8, val alignment: Int = 8,
    )
    data class AddressOffset(val register: Int, val offset: Int)
    data class Relocation(val symbol: String, val type: String, val offset: Int, val addend: Long = 0)

    private var bytes = ByteArray(256)
    val relocations = mutableListOf<Relocation>()
    val position get() = codePosition.offset
    private val codePosition = CodePosition()

    fun isIntegerRegister(register: Int): Boolean = register in 0..7 || register == TREG_RA || register == TREG_SP
    fun isFloatingRegister(register: Int): Boolean = register in 8..15
    fun integerRegister(register: Int): Int {
        if (register == TREG_RA) return 1
        if (register == TREG_SP) return 2
        require(register in 0..7)
        return register + 10
    }
    fun floatingRegister(register: Int): Int { require(register in 8..15); return register - 8 + 10 }

    fun emitInstruction(instruction: Int) {
        if (noCode()) return
        ensureCapacity(position + 4)
        put32(position, instruction)
        codePosition.offset += 4
    }

    private fun ensureCapacity(size: Int) { if (size > bytes.size) bytes = bytes.copyOf(maxOf(size, bytes.size * 2)) }
    fun read32(offset: Int): Int = read32From(bytes, offset)
    fun write32(offset: Int, value: Int) { ensureCapacity(offset + 4); put32(offset, value) }
    fun codeBytes(): ByteArray = bytes.copyOf(position)
    private fun put32(offset: Int, value: Int) {
        bytes[offset] = value.toByte(); bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte(); bytes[offset + 3] = (value ushr 24).toByte()
    }
    private fun read32From(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xff) or
        ((data[offset + 1].toInt() and 0xff) shl 8) or ((data[offset + 2].toInt() and 0xff) shl 16) or (data[offset + 3].toInt() shl 24)

    fun emitImmediateUnsigned(opcode: Int, function3: Int, rd: Int, rs1: Int, immediate: Int) =
        emitInstruction(opcode or (function3 shl 12) or (rd shl 7) or (rs1 shl 15) or (immediate shl 20))

    fun emitRegister(opcode: Int, function3: Int, rd: Int, rs1: Int, rs2: Int, function7: Int) =
        emitInstruction(opcode or (function3 shl 12) or (rd shl 7) or (rs1 shl 15) or (rs2 shl 20) or (function7 shl 25))

    fun lowOverflow(value: Int): Int = ((value.toUInt() + 0x800u) and 0xfffff000u).toInt()
    fun sign7(value: Int): Int = ((value and 0xff) xor 0x80) - 0x80
    fun sign11(value: Int): Int = ((value and 0xfff) xor 0x800) - 0x800

    fun emitImmediate(opcode: Int, function3: Int, rd: Int, rs1: Int, immediate: Int) {
        check(lowOverflow(immediate) == 0) { "immediate out of range" }
        emitImmediateUnsigned(opcode, function3, rd, rs1, immediate)
    }

    fun emitStore(opcode: Int, function3: Int, rs1: Int, rs2: Int, immediate: Int) {
        check(lowOverflow(immediate) == 0) { "store immediate out of range" }
        emitInstruction(opcode or (function3 shl 12) or ((immediate and 0x1f) shl 7) or (rs1 shl 15) or
            (rs2 shl 20) or ((immediate ushr 5) shl 25))
    }

    fun addRelocation(symbol: String, type: String, offset: Int = position, addend: Long = 0) {
        relocations += Relocation(symbol, type, offset, addend)
    }

    /** Resolves the base register and low displacement for a symbol/local address. */
    fun loadSymbolOffset(register: Int, value: Value, forStore: Boolean, initialOffset: Int = value.value.toInt()): AddressOffset {
        var offset = initialOffset
        if (value.symbol != null) {
            val symbol = value.symbol
            if (value.isTls) {
                val target = if (isIntegerRegister(register)) integerRegister(register) else 5
                addRelocation(symbol, "TPREL_HI20", position, value.value)
                emitInstruction(0x37 or (target shl 7))
                addRelocation(symbol, "TPREL_LO12_I", position, value.value)
                emitImmediate(0x13, 0, target, target, 0)
                emitRegister(0x33, 0, target, target, 4, 0)
                return AddressOffset(target, 0)
            }
            val loadFromGot = !value.isStatic
            val largeAddend = loadFromGot && lowOverflow(offset) != 0
            if (value.isStatic) addRelocation(symbol, "PCREL_HI20", position, value.value)
            else addRelocation(symbol, "GOT_HI20", position)
            val label = "$symbol@pcrel${position}"
            val target = if (isIntegerRegister(register)) integerRegister(register) else 5
            emitInstruction(0x17 or (target shl 7))
            addRelocation(label, if (loadFromGot || !forStore) "PCREL_LO12_I" else "PCREL_LO12_S", position)
            if (loadFromGot) {
                emitImmediate(0x03, 3, target, target, 0)
                if (largeAddend) {
                    emitInstruction(0x37 or (6 shl 7) or lowOverflow(offset))
                    emitRegister(0x33, 0, target, target, 6, 0)
                    offset = sign11(offset)
                }
            } else offset = 0
            return AddressOffset(target, offset)
        }
        if (value.kind == ValueKind.LOCAL || value.kind == ValueKind.LOCAL_LVALUE) {
            var target = 8 // s0
            if (lowOverflow(offset) != 0) {
                target = if (isIntegerRegister(register)) integerRegister(register) else 5
                emitInstruction(0x37 or (target shl 7) or lowOverflow(offset))
                emitRegister(0x33, 0, target, target, 8, 0)
                offset = sign11(offset)
            }
            return AddressOffset(target, offset)
        }
        error("invalid symbol offset value")
        return AddressOffset(0, offset)
    }

    fun loadLargeConstant(register: Int, low: Int, upperPart: Int) {
        var upper = upperPart
        if (low < 0) upper++
        emitInstruction(0x37 or (register shl 7) or lowOverflow(upper))
        emitImmediate(0x13, 0, register, register, sign11(upper))
        emitImmediate(0x13, 1, register, register, 12)
        emitImmediate(0x13, 0, register, register, sign11((low.toUInt() + (1 shl 19).toUInt()).toInt() ushr 20))
        emitImmediate(0x13, 1, register, register, 12)
        emitImmediate(0x13, 0, register, register, sign11((low shl 12 shr 12) shr 8))
        emitImmediate(0x13, 1, register, register, 8)
    }

    fun load(register: Int, value: Value) {
        val destination = if (isIntegerRegister(register)) integerRegister(register) else floatingRegister(register)
        var offset = value.value.toInt()
        val floating = !isIntegerRegister(register)
        if (value.isLValue) {
            var size = value.typeSize
            if (value.baseType == VT_PTR || value.baseType == VT_FUNC) size = PTR_SIZE
            val unsigned = value.isUnsigned && value.baseType != VT_FLOAT && value.baseType != VT_DOUBLE
            var function3 = when (size) { 1 -> 0; 2 -> 1; 4 -> 2; else -> 3 }
            if (size < 4 && unsigned) function3 = function3 or 4
            var base: Int
            when {
                value.kind == ValueKind.LOCAL || value.symbol != null -> {
                    val resolved = loadSymbolOffset(register, value, false, offset)
                    base = resolved.register; offset = resolved.offset
                }
                value.kind == ValueKind.REGISTER -> { base = integerRegister(value.register); offset = 0 }
                value.kind == ValueKind.LOCAL_LVALUE -> {
                    val resolved = loadSymbolOffset(register, value, false, offset)
                    emitImmediate(0x03, 3, destination, resolved.register, resolved.offset)
                    base = destination; offset = 0
                }
                value.kind == ValueKind.CONSTANT -> {
                    val upper = (value.value shr 32).toInt()
                    if (upper != 0) { loadLargeConstant(destination, offset, upper); offset = sign7(offset) }
                    else { emitInstruction(0x37 or (destination shl 7) or lowOverflow(offset)); offset = sign11(offset) }
                    base = destination
                }
                else -> { error("unimp: load(non-local lval)"); return }
            }
            emitImmediate(if (floating) 0x07 else 0x03, function3, destination, base, offset)
            return
        }

        if (value.kind == ValueKind.CONSTANT) {
            if ((value.baseType == VT_FLOAT || value.baseType == VT_DOUBLE) && value.baseType != VT_LDOUBLE) {
                val isDouble = value.baseType == VT_DOUBLE
                if (value.value == 0L) {
                    emitInstruction(0x53 or (destination shl 7) or ((0x78 or if (isDouble) 1 else 0) shl 25))
                    return
                }
                if (isDouble) loadLargeConstant(6, value.value.toInt(), (value.value shr 32).toInt())
                else {
                    if (lowOverflow(offset) != 0) emitInstruction(0x37 or (6 shl 7) or lowOverflow(offset))
                    emitImmediate(0x1b, 0, 6, if (lowOverflow(offset) != 0) 6 else 0, sign11(offset))
                }
                emitInstruction(0x53 or (destination shl 7) or (6 shl 15) or ((0x78 or if (isDouble) 1 else 0) shl 25))
                return
            }
            require(isIntegerRegister(register) || value.baseType == VT_LDOUBLE)
            var base = 0
            var useWord = 8
            var zeroExtend = false
            if (value.symbol != null) {
                val resolved = loadSymbolOffset(register, value, false, offset)
                base = resolved.register; offset = resolved.offset; useWord = 0
            }
            if (useWord != 0 && offset.toLong() != value.value) {
                val upper = (value.value shr 32).toInt()
                if (upper != 0) { loadLargeConstant(destination, offset, upper); offset = sign7(offset); base = destination; useWord = 0 }
                else if (value.isLongLong) zeroExtend = true
            }
            if (lowOverflow(offset) != 0) { emitInstruction(0x37 or (destination shl 7) or lowOverflow(offset)); base = destination }
            if (offset != 0 || destination != base || useWord != 0 || value.symbol != null)
                emitImmediate(0x13 or useWord, 0, destination, base, sign11(offset))
            if (zeroExtend) { emitImmediate(0x13, 1, destination, destination, 32); emitImmediate(0x13, 5, destination, destination, 32) }
            return
        }
        if (value.kind == ValueKind.LOCAL) {
            val address = loadSymbolOffset(register, value, false, offset)
            require(isIntegerRegister(register))
            emitImmediate(0x13, 0, destination, address.register, address.offset)
            return
        }
        if (value.kind == ValueKind.REGISTER) {
            val source = if (isFloatingRegister(value.register)) floatingRegister(value.register) else integerRegister(value.register)
            if (floating && isFloatingRegister(value.register)) {
                emitRegister(0x53, 0, destination, source, source, if (value.baseType == VT_DOUBLE) 0x11 else 0x10)
            } else if (!floating && isIntegerRegister(value.register)) emitImmediate(0x13, 0, destination, source, 0)
            else {
                val size = value.typeSize
                require(size == 4 || size == 8)
                var function7 = if (isIntegerRegister(register)) 0x70 else 0x78
                if (size == 8) function7 = function7 or 1
                emitInstruction(0x53 or (destination shl 7) or (source shl 15) or (function7 shl 25))
            }
            return
        }
        error("unimp: load(non-const)")
    }

    fun store(register: Int, value: Value) {
        val source = if (isIntegerRegister(register)) integerRegister(register) else floatingRegister(register)
        var offset = value.value.toInt()
        val size = when (value.baseType) { VT_LDOUBLE -> 8; else -> value.typeSize }
        if (value.baseType == VT_STRUCT || size > 8) { error("unimp: large sized store"); return }
        if (!value.isLValue) { error("store expects lvalue"); return }
        if (value.isFloating && !isFloatingRegister(register) && value.baseType != VT_LDOUBLE) { error("float store requires floating register"); return }
        val base = when {
            value.kind == ValueKind.LOCAL || value.symbol != null -> loadSymbolOffset(register, value, true, offset).also { offset = it.offset }.register
            value.kind == ValueKind.REGISTER -> integerRegister(value.register).also { offset = 0 }
            value.kind == ValueKind.CONSTANT -> {
                val upper = (value.value shr 32).toInt()
                if (upper != 0) { loadLargeConstant(8, offset, upper); offset = sign7(offset) }
                else { emitInstruction(0x37 or (8 shl 7) or lowOverflow(offset)); offset = sign11(offset) }
                8
            }
            else -> { error("implement store of non-local lvalue"); return }
        }
        val function3 = when (size) { 1 -> 0; 2 -> 1; 4 -> 2; else -> 3 }
        emitStore(if (isFloatingRegister(register)) 0x27 else 0x23, function3, base, source, offset)
    }

    /** Patches a linked branch chain, writing a NOP for a branch to the next instruction. */
    fun patchBranchChain(chain: Int, target: Int) {
        var current = chain
        while (current != 0) {
            val next = read32(current)
            val relative = target - current
            if ((relative + (1 shl 21)) and ((1 shl 22) - 2).inv() != 0) {
                error("out-of-range branch chain")
                return
            }
            val immediate = (((relative ushr 12) and 0xff) shl 12) or (((relative ushr 11) and 1) shl 20) or
                (((relative ushr 1) and 0x3ff) shl 21) or (((relative ushr 20) and 1) shl 31)
            write32(current, if (relative == 4) 0x33 else 0x6f or immediate)
            current = next
        }
    }
}
