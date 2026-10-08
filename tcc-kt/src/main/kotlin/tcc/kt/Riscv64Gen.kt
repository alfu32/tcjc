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
    data class CallTarget(val value: Value)
    data class FieldType(val type: AbiType, val offset: Int)
    data class AbiType(
        val baseType: Int,
        val size: Int,
        val isFloat: Boolean = false,
        val isArray: Boolean = false,
        val arrayCount: Int = 0,
        val isUnion: Boolean = false,
        val fields: List<FieldType> = emptyList(),
    )
    data class RegisterPass(val classes: IntArray, val fieldOffsets: IntArray)
    data class FunctionFrame(
        val prologPosition: Int,
        var localOffset: Int = -16,
        var variadicRegisterCount: Int = 0,
        var variadicListOffset: Int = 0,
    )
    enum class IntegerOperation { ADD, SUBTRACT, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_ARITHMETIC, MULTIPLY, DIVIDE, DIVIDE_UNSIGNED, REMAINDER, REMAINDER_UNSIGNED, AND, OR, XOR, LESS_THAN, LESS_THAN_UNSIGNED }

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

    /** Emits an indirect call or jump, preserving the C backend's ra/t0 selection. */
    fun callOrJump(target: CallTarget, doCall: Boolean) {
        val linkRegister = if (doCall) 1 else 5
        val value = target.value
        if (value.kind == ValueKind.CONSTANT && value.symbol != null && value.value == value.value.toInt().toLong()) {
            addRelocation(value.symbol, "CALL_PLT", position, value.value)
            emitInstruction(0x17 or (linkRegister shl 7)) // auipc link, %call(symbol)
            emitImmediate(0x67, 0, linkRegister, linkRegister, 0)
        } else if (value.kind == ValueKind.REGISTER) {
            val source = integerRegister(value.register)
            emitImmediate(0x67, 0, linkRegister, source, 0)
        } else {
            load(TREG_RA, value)
            emitImmediate(0x67, 0, linkRegister, integerRegister(TREG_RA), 0)
        }
    }

    /** Classifies a named aggregate for the two RISC-V argument registers. */
    fun registerPass(type: AbiType, named: Boolean = true): RegisterPass {
        val classes = IntArray(3)
        val offsets = IntArray(3)
        fun visit(current: AbiType, offset: Int) {
            when {
                current.baseType == VT_STRUCT -> {
                    if (current.isUnion) classes[0] = -1
                    else current.fields.forEach { visit(it.type, offset + it.offset) }
                }
                current.isArray -> {
                    if (current.arrayCount < 0 || current.arrayCount > 2) classes[0] = -1
                    else {
                        val before = classes[0]
                        visit(current.fields.firstOrNull()?.type ?: current, offset)
                        if (classes[0] > 2 || (classes[0] == 2 && current.arrayCount > 1)) classes[0] = -1
                        else if (current.arrayCount == 2 && classes[0] > 0 && classes[1] == RC_FLOAT) {
                            val field = current.fields.firstOrNull()?.type ?: current
                            classes[++classes[0]] = RC_FLOAT
                            offsets[classes[0]] = ((offset + field.size) shl 4) or field.baseType
                        } else if (current.arrayCount == 2 && classes[0] == before + 1) classes[0] = -1
                    }
                }
                classes[0] == 2 || classes[0] < 0 || current.baseType == VT_LDOUBLE -> classes[0] = -1
                classes[0] == 0 || classes[1] == RC_FLOAT || current.isFloat -> {
                    val next = ++classes[0]
                    classes[next] = if (current.isFloat) RC_FLOAT else RC_INT
                    offsets[next] = (offset shl 4) or if (current.baseType == VT_PTR) VT_LLONG else current.baseType
                }
                else -> classes[0] = -1
            }
        }
        visit(type, 0)
        if (classes[0] <= 0 || !named) {
            classes[0] = (type.size + 7) shr 3
            classes[1] = RC_INT
            classes[2] = RC_INT
            offsets[1] = if (type.size <= 1) VT_BYTE else if (type.size <= 2) VT_SHORT else if (type.size <= 4) VT_INT else VT_LLONG
            offsets[2] = (8 shl 4) or if (type.size <= 9) VT_BYTE else if (type.size <= 10) VT_SHORT else if (type.size <= 12) VT_INT else VT_LLONG
        }
        return RegisterPass(classes, offsets)
    }

    fun fillNops(byteCount: Int) {
        require(byteCount and 3 == 0) { "alignment of code section not multiple of 4" }
        repeat(byteCount / 4) { emitInstruction(0x00000013) }
    }

    /** Emits an unresolved jump word and returns its position, matching gjmp(). */
    fun jump(targetWord: Int): Int {
        if (noCode()) return targetWord
        emitInstruction(targetWord)
        return position - 4
    }

    fun jumpAddress(address: Int) {
        val relative = address - position
        if ((relative + (1 shl 21)) and ((1 shl 22) - 2).inv() != 0) {
            emitInstruction(0x17 or (5 shl 7) or lowOverflow(relative))
            emitImmediate(0x67, 0, 0, 5, sign11(relative))
        } else {
            val immediate = (((relative ushr 12) and 0xff) shl 12) or (((relative ushr 11) and 1) shl 20) or
                (((relative ushr 1) and 0x3ff) shl 21) or (((relative ushr 20) and 1) shl 31)
            emitInstruction(0x6f or immediate)
        }
    }

    /** Encodes the inverse conditional branch followed by the unresolved jump chain word. */
    fun conditionalJump(function3: Int, left: Int, right: Int, targetWord: Int, reverseOperands: Boolean = false): Int {
        require(function3 in 0..7)
        val first = if (reverseOperands) right else left
        val second = if (reverseOperands) left else right
        emitInstruction(0x63 or ((function3 xor 1) shl 12) or (first shl 15) or (second shl 20) or (8 shl 7))
        return jump(targetWord)
    }

    fun appendJumpChain(first: Int, second: Int): Int {
        if (first == 0) return second
        var tail = first
        while (read32(tail) != 0) tail = read32(tail)
        write32(tail, second)
        return first
    }

    /** Reserves the five instruction words patched by the C backend's epilog. */
    fun beginFunctionFrame(): FunctionFrame {
        val frame = FunctionFrame(position)
        repeat(5) { emitInstruction(0) }
        return frame
    }

    /** Saves the variadic integer argument registers in the frame, as gfunc_prolog does. */
    fun saveVariadicRegisters(frame: FunctionFrame, firstRegister: Int) {
        var register = firstRegister
        while (register < 8) {
            frame.variadicRegisterCount++
            emitStore(0x23, 3, 8, integerRegister(register), -8 + frame.variadicRegisterCount * 8)
            register++
        }
    }

    /** Patches the reserved entry sequence and emits the shared function return sequence. */
    fun endFunctionFrame(frame: FunctionFrame) {
        val frameSize = (-frame.localOffset + 15) and -16
        val afterProlog = position
        val epilogPosition = position
        emitImmediate(0x13, 0, 2, 8, frame.variadicRegisterCount * 8) // sp = s0 + vararg save area
        emitImmediate(0x03, 3, 1, 8, -8) // restore ra
        emitImmediate(0x03, 3, 8, 8, -16) // restore s0
        emitImmediate(0x67, 0, 0, 1, 0) // ret
        if (frameSize >= (1 shl 11)) {
            emitImmediate(0x13, 0, 8, 2, 16 - frame.variadicRegisterCount * 8)
            emitInstruction(0x37 or (5 shl 7) or lowOverflow(frameSize - 16))
            emitImmediate(0x13, 0, 5, 5, sign11(frameSize - 16))
            emitRegister(0x33, 0, 2, 2, 5, 0x20)
            jumpAddress(frame.prologPosition + 20)
        }
        val savedPosition = position
        codePosition.offset = frame.prologPosition
        val smallSize = if (frameSize >= (1 shl 11)) 16 else frameSize
        emitImmediate(0x13, 0, 2, 2, -smallSize)
        emitStore(0x23, 3, 2, 1, smallSize - 8 - frame.variadicRegisterCount * 8)
        emitStore(0x23, 3, 2, 8, smallSize - 16 - frame.variadicRegisterCount * 8)
        if (frameSize < (1 shl 11)) emitImmediate(0x13, 0, 8, 2, smallSize - frame.variadicRegisterCount * 8)
        else jumpAddress(epilogPosition + 16)
        while (position < frame.prologPosition + 20) emitImmediate(0x13, 0, 0, 0, 0)
        codePosition.offset = maxOf(savedPosition, afterProlog)
    }

    fun vaListOffset(frame: FunctionFrame): Int = frame.variadicListOffset

    /** Emits the register-register integer operation table from gen_opil(). */
    fun integerOperation(operation: IntegerOperation, left: Int, right: Int, destination: Int, word: Boolean = false) {
        val opcode = when (operation) {
            IntegerOperation.REMAINDER -> if (word) 0x3b else 0x33
            else -> 0x33
        } or if (word && operation != IntegerOperation.AND && operation != IntegerOperation.OR && operation != IntegerOperation.XOR) 8 else 0
        val (function3, function7) = when (operation) {
            IntegerOperation.ADD -> 0 to 0
            IntegerOperation.SUBTRACT -> 0 to 0x20
            IntegerOperation.SHIFT_LEFT -> 1 to 0
            IntegerOperation.SHIFT_RIGHT -> 5 to 0
            IntegerOperation.SHIFT_ARITHMETIC -> 5 to 0x20
            IntegerOperation.MULTIPLY -> 0 to 1
            IntegerOperation.DIVIDE -> 4 to 1
            IntegerOperation.DIVIDE_UNSIGNED -> 5 to 1
            IntegerOperation.REMAINDER -> 6 to 1
            IntegerOperation.REMAINDER_UNSIGNED -> 7 to 1
            IntegerOperation.AND -> 7 to 0
            IntegerOperation.OR -> 6 to 0
            IntegerOperation.XOR -> 4 to 0
            IntegerOperation.LESS_THAN -> 2 to 0
            IntegerOperation.LESS_THAN_UNSIGNED -> 3 to 0
        }
        emitRegister(opcode, function3, integerRegister(destination), integerRegister(left), integerRegister(right), function7)
    }

    /** Emits the immediate arithmetic and comparison forms used by gen_opil(). */
    fun integerImmediate(operation: IntegerOperation, source: Int, destination: Int, immediate: Int, word: Boolean = false) {
        val opcode = 0x13 or if (word && operation in setOf(IntegerOperation.ADD, IntegerOperation.SHIFT_LEFT, IntegerOperation.SHIFT_RIGHT, IntegerOperation.SHIFT_ARITHMETIC)) 8 else 0
        val encoding: Pair<Int, Int> = when (operation) {
            IntegerOperation.ADD -> 0 to immediate
            IntegerOperation.SUBTRACT -> 0 to -immediate
            IntegerOperation.SHIFT_LEFT -> 1 to (immediate and if (word) 31 else 63)
            IntegerOperation.SHIFT_RIGHT -> 5 to (immediate and if (word) 31 else 63)
            IntegerOperation.SHIFT_ARITHMETIC -> 5 to (0x400 or (immediate and if (word) 31 else 63))
            IntegerOperation.LESS_THAN -> 2 to immediate
            IntegerOperation.LESS_THAN_UNSIGNED -> 3 to immediate
            IntegerOperation.AND -> 7 to immediate
            IntegerOperation.OR -> 6 to immediate
            IntegerOperation.XOR -> 4 to immediate
            else -> throw IllegalArgumentException("operation has no immediate form: $operation")
        }
        val (function3, encodedImmediate) = encoding
        emitImmediateUnsigned(opcode, function3, integerRegister(destination), integerRegister(source), encodedImmediate)
    }

    fun floatingArithmetic(operation: Int, left: Int, right: Int, destination: Int, double: Boolean) {
        require(operation in 0..3)
        emitRegister(0x53, 7, floatingRegister(destination), floatingRegister(left), floatingRegister(right), (if (double) 1 else 0) or (operation shl 2))
    }

    fun floatingCompare(operation: Int, left: Int, right: Int, destination: Int, double: Boolean, invert: Boolean = false) {
        require(operation in 0..2)
        val rd = integerRegister(destination)
        emitRegister(0x53, operation, rd, floatingRegister(left), floatingRegister(right), (if (double) 1 else 0) or 0x50)
        if (invert) emitImmediate(0x13, 4, rd, rd, 1)
    }

    fun convertIntegerToFloat(source: Int, destination: Int, double: Boolean, unsigned: Boolean, wide: Boolean) {
        val format = (0x68 or if (double) 1 else 0) shl 5
        emitImmediateUnsigned(0x53, 7, floatingRegister(destination), integerRegister(source), format or (if (unsigned) 1 else 0) or (if (wide) 2 else 0))
    }

    fun convertFloatToInteger(source: Int, destination: Int, double: Boolean, unsigned: Boolean, wide: Boolean) {
        val format = (0x60 or if (double) 1 else 0) shl 5
        emitImmediateUnsigned(0x53, 1, integerRegister(destination), floatingRegister(source), format or (if (unsigned) 1 else 0) or (if (wide) 2 else 0))
    }

    fun convertFloatWidth(source: Int, destination: Int, sourceDouble: Boolean, destinationDouble: Boolean) {
        if (sourceDouble == destinationDouble) return
        val immediate = if (destinationDouble) (0x21 shl 5) else ((0x20 shl 5) or 1)
        emitImmediateUnsigned(0x53, if (destinationDouble) 0 else 7, floatingRegister(destination), floatingRegister(source), immediate)
    }

    fun convertIntegerWidth(register: Int, fromType: Int, unsigned: Boolean = false) {
        val rd = integerRegister(register)
        if (fromType == VT_SHORT) {
            emitImmediate(0x13, 1, rd, rd, 48)
            if (!unsigned) emitImmediateUnsigned(0x13, 5, rd, rd, 0x430)
            else emitImmediate(0x13, 5, rd, rd, 48)
        } else if (fromType == VT_BYTE) {
            if (unsigned) emitImmediate(0x13, 7, rd, rd, 0xff)
            else {
                emitImmediate(0x13, 1, rd, rd, 56)
                emitImmediateUnsigned(0x13, 5, rd, rd, 0x438)
            }
        }
    }

    fun saveVlaStackPointer(offset: Int) {
        val address = vlaAddress(offset)
        emitStore(0x23, 3, address.register, 2, address.offset)
    }

    fun restoreVlaStackPointer(offset: Int) {
        val address = vlaAddress(offset)
        emitImmediate(0x03, 3, 2, address.register, address.offset)
    }

    private fun vlaAddress(offset: Int): AddressOffset {
        if (lowOverflow(offset) == 0) return AddressOffset(8, offset)
        emitInstruction(0x37 or (5 shl 7) or lowOverflow(offset))
        emitRegister(0x33, 0, 5, 5, 8, 0)
        return AddressOffset(5, sign11(offset))
    }

    fun allocateVla(sizeRegister: Int) {
        val register = integerRegister(sizeRegister)
        emitImmediate(0x13, 0, register, register, 15)
        emitImmediate(0x13, 7, register, register, -16)
        emitRegister(0x33, 0, 2, 2, register, 0x20)
    }

    fun clearInstructionCache() {
        emitInstruction(0x0ff0000f)
        emitInstruction(0x0000100f)
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
