package tcc.kt

/** AArch64 assembler operand parsing and instruction encoding from arm64-asm.c. */
class Arm64Asm(
    private val output: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val expression: (String) -> Expression = { Expression(parseInteger(it)) },
    private val expect: (String) -> Unit = { throw IllegalArgumentException("expected $it") },
    private val error: (String) -> Unit = { throw IllegalArgumentException(it) },
) {
    enum class OperandType { NONE, REGISTER, IMMEDIATE, ADDRESS, CONDITION }
    enum class RegisterType(val flag: Int) { X(1), W(2), V(4), D(8), S(16), H(32), B(64) }
    enum class AddressMode { OFFSET, PRE, POST }
    data class Expression(val value: Long, val symbol: String? = null)
    data class Operand(
        var type: OperandType = OperandType.NONE, var register: Int = -1, var secondRegister: Int = -1,
        var registerType: RegisterType? = null, var shift: Int = 0, var addressMode: AddressMode = AddressMode.OFFSET,
        var tokenName: String = "", var value: Expression = Expression(0),
    )

    companion object {
        const val REG_X = 0x01; const val REG_W = 0x02; const val REG_V = 0x04
        const val REG_D = 0x08; const val REG_S = 0x10; const val REG_H = 0x20; const val REG_B = 0x40
        const val FREG_BASE = 20
        private fun parseInteger(text: String): Long {
            val s = text.trim().replace("_", "")
            val sign = if (s.startsWith('-')) -1 else 1
            val digits = s.removePrefix("-").removePrefix("+")
            val radix = when { digits.startsWith("0x", true) -> 16; digits.startsWith("0b", true) -> 2; else -> 10 }
            val body = digits.removePrefix("0x").removePrefix("0X").removePrefix("0b").removePrefix("0B")
            return sign * body.toLong(radix)
        }
    }

    fun parseRegister(name: String): Pair<Int, RegisterType>? {
        val text = name.trim().lowercase()
        when (text) { "sp", "xzr" -> return 31 to RegisterType.X; "wzr" -> return 31 to RegisterType.W }
        val match = Regex("([xwvdshb])([0-9]+)").matchEntire(text) ?: return null
        val type = when (match.groupValues[1]) {
            "x" -> RegisterType.X; "w" -> RegisterType.W; "v" -> RegisterType.V
            "d" -> RegisterType.D; "s" -> RegisterType.S; "h" -> RegisterType.H; else -> RegisterType.B
        }
        val number = match.groupValues[2].toIntOrNull() ?: return null
        val maximum = if (type == RegisterType.X || type == RegisterType.W) 30 else 31
        return if (number in 0..maximum) (number + if (type == RegisterType.X || type == RegisterType.W) 0 else 32) to type else null
    }

    fun registerType(name: String): RegisterType = parseRegister(name)?.second ?: RegisterType.X

    fun parseRegisterVariable(name: String): Int? {
        val register = parseRegister(name) ?: return null
        if (register.first in 0..30 && register.second == RegisterType.X) return register.first
        val match = Regex("([vdshb])([0-7])", RegexOption.IGNORE_CASE).matchEntire(name) ?: return null
        return FREG_BASE + match.groupValues[2].toInt()
    }

    fun parseCondition(name: String): Int = when (name.lowercase()) {
        "eq" -> 0; "ne" -> 1; "cs", "hs" -> 2; "cc", "lo" -> 3; "mi" -> 4; "pl" -> 5
        "vs" -> 6; "vc" -> 7; "hi" -> 8; "ls" -> 9; "ge" -> 10; "lt" -> 11
        "gt" -> 12; "le" -> 13; "al" -> 14; else -> -1
    }

    fun parseBarrierOption(name: String): Int = when (name.lowercase()) {
        "oshld" -> 1; "oshst" -> 2; "osh" -> 3; "nshld" -> 5; "nshst" -> 6; "nsh" -> 7
        "ishld" -> 9; "ishst" -> 10; "ish" -> 11; "ld" -> 13; "st" -> 14; "sy" -> 15; else -> -1
    }

    fun parseOperand(source: String): Operand {
        val text = source.trim()
        if (text.startsWith('[')) return parseAddressOperand(text)
        parseRegister(text)?.let { return Operand(OperandType.REGISTER, it.first, registerType = it.second, tokenName = text) }
        parseCondition(text).takeIf { it >= 0 }?.let { return Operand(OperandType.CONDITION, register = it) }
        val immediate = text.removePrefix("#").removePrefix(":").removePrefix("@").removePrefix("$")
        if (Regex("[A-Za-z_][A-Za-z0-9_.$]*").matches(immediate) && !text.startsWith('#') && !text.startsWith(':') && !text.startsWith('@') && !text.startsWith('$')) {
            error("invalid operand '$text'")
            return Operand(OperandType.IMMEDIATE)
        }
        return Operand(OperandType.IMMEDIATE, value = expression(immediate))
    }

    fun parseExpressionOperand(source: String): Operand {
        val text = source.trim().removePrefix("#").removePrefix(":").removePrefix("@").removePrefix("$")
        return Operand(OperandType.IMMEDIATE, value = expression(text))
    }

    fun parseAddressOperand(source: String): Operand {
        val text = source.trim()
        val close = text.indexOf(']')
        if (!text.startsWith('[') || close < 0) { expect("address operand"); return Operand(OperandType.ADDRESS) }
        val inside = text.substring(1, close).split(',', limit = 2)
        val base = parseRegister(inside[0])
        if (base == null || base.first !in 0..31) { error("invalid register in address operand"); return Operand(OperandType.ADDRESS) }
        var value = Expression(0)
        if (inside.size == 2) value = expression(inside[1].trim().removePrefix("#").removePrefix("@").removePrefix("$"))
        var suffix = text.substring(close + 1).trim()
        var mode = AddressMode.OFFSET
        if (suffix.startsWith('!')) { mode = AddressMode.PRE; suffix = suffix.drop(1).trim() }
        else if (suffix.startsWith(',')) {
            mode = AddressMode.POST
            suffix = suffix.drop(1).trim().removePrefix("#").removePrefix("@").removePrefix("$")
            value = expression(suffix)
        }
        return Operand(OperandType.ADDRESS, register = base.first, registerType = base.second,
            addressMode = mode, tokenName = inside[0].trim(), value = value)
    }

    fun emitInstruction(word: Int): Int { if (!noCode()) output(word); return word }
    fun emitWord(value: Long) = emitInstruction(value.toInt())

    fun emitMovWithBase(rd: Int, immediate: Int, shift: Int, is64Bit: Boolean, base: Int): Int =
        (base or (if (is64Bit) 0x80000000.toInt() else 0) or ((immediate and 0xffff) shl 5) or ((shift and 3) shl 21) or (rd and 31)).also(::emitInstruction)
    fun emitMovz(rd: Int, imm: Int, shift: Int, is64Bit: Boolean) = emitMovWithBase(rd, imm, shift, is64Bit, if (is64Bit) 0xd2800000.toInt() else 0x52800000)
    fun emitMovn(rd: Int, imm: Int, shift: Int, is64Bit: Boolean) = emitMovWithBase(rd, imm, shift, is64Bit, if (is64Bit) 0x92800000.toInt() else 0x12800000)
    fun emitMovk(rd: Int, imm: Int, shift: Int, is64Bit: Boolean) = emitMovWithBase(rd, imm, shift, is64Bit, 0xf2800000.toInt())

    fun emitAddImmediate(rd: Int, rn: Int, immediate: Long, is64Bit: Boolean, setFlags: Boolean = false) = emitArithmeticImmediate(rd, rn, immediate, is64Bit, setFlags, false)
    fun emitSubImmediate(rd: Int, rn: Int, immediate: Long, is64Bit: Boolean, setFlags: Boolean = false) = emitArithmeticImmediate(rd, rn, immediate, is64Bit, setFlags, true)
    private fun emitArithmeticImmediate(rd: Int, rn: Int, immediate: Long, is64Bit: Boolean, setFlags: Boolean, subtract: Boolean) {
        var instruction = if (subtract) 0x51000000 else 0x11000000
        if (is64Bit) instruction = instruction or 0x80000000.toInt()
        if (setFlags) instruction = instruction or 0x20000000
        val imm12: Long
        if (immediate in 0..0xfff) imm12 = immediate
        else if (immediate and 0xfff == 0L && immediate ushr 12 <= 0xfff) { instruction = instruction or 0x400000; imm12 = immediate ushr 12 }
        else { error("${if (subtract) "sub" else "add"} immediate out of range"); return }
        emitInstruction(instruction or ((imm12.toInt() and 0xfff) shl 10) or ((rn and 31) shl 5) or (rd and 31))
    }

    fun emitDataProcessingRegister(opcode: Int, rd: Int, rn: Int, rm: Int, is64Bit: Boolean) =
        emitInstruction(opcode or (if (is64Bit) 0x80000000.toInt() else 0) or ((rm and 31) shl 16) or ((rn and 31) shl 5) or (rd and 31))

    fun emitLoadStoreImmediate(baseOpcode: Int, rt: Int, rn: Int, offset: Int, sizeLog2: Int): Int {
        var instruction = baseOpcode
        if (offset >= 0 && offset and ((1 shl sizeLog2) - 1) == 0) {
            val imm12 = offset ushr sizeLog2
            if (imm12 <= 0xfff) {
                instruction = instruction or (imm12 shl 10) or ((rn and 31) shl 5) or (rt and 31)
                emitInstruction(instruction)
                return instruction
            }
        }
        val unscaled = when (baseOpcode) {
            0xf9400000.toInt() -> 0xf8400000.toInt(); 0xb9400000.toInt() -> 0xb8400000.toInt()
            0x39400000 -> 0x38400000; 0x79400000 -> 0x78400000
            0xfd400000.toInt() -> 0xfc400000.toInt(); 0xf9000000.toInt() -> 0xf8000000.toInt()
            0xb9000000.toInt() -> 0xb8000000.toInt(); 0x39000000 -> 0x38000000
            0x79000000 -> 0x78000000; 0xfd000000.toInt() -> 0xfc000000.toInt()
            else -> 0
        }
        if (unscaled != 0 && offset in -256..255) {
            instruction = unscaled or ((offset and 0x1ff) shl 12) or ((rn and 31) shl 5) or (rt and 31)
            emitInstruction(instruction)
            return instruction
        }
        if (offset and ((1 shl sizeLog2) - 1) != 0) error("invalid load/store offset")
        error("load/store offset out of range")
        return 0
    }

    fun emitLoadStorePair(baseOpcode: Int, rt: Int, rt2: Int, rn: Int, offset: Int, sizeLog2: Int): Int {
        if (offset and ((1 shl sizeLog2) - 1) != 0) error("invalid pair load/store offset")
        val imm7 = offset shr sizeLog2
        if (imm7 !in -64..63) error("pair load/store offset out of range")
        val instruction = baseOpcode or ((imm7 and 0x7f) shl 15) or ((rt2 and 31) shl 10) or ((rn and 31) shl 5) or (rt and 31)
        emitInstruction(instruction)
        return instruction
    }

    /** Encodes an AArch64 bitmask immediate in N:immr:imms form. */
    fun encodeBitmaskImmediate(input: Long): Int {
        var value = input
        val negative = value and 1L != 0L
        if (negative) value = value.inv()
        if (value == 0L) return -1
        var repetition: Int
        fun periodic(bits: Int): Boolean {
            val mask = (1L shl bits) - 1
            return (value ushr bits) == (value and ((1L shl (64 - bits)) - 1))
        }
        repetition = when {
            periodic(2) -> { value = value and 3; 2 }
            periodic(4) -> { value = value and 15; 4 }
            periodic(8) -> { value = value and 255; 8 }
            periodic(16) -> { value = value and 65535; 16 }
            periodic(32) -> { value = value and 0xffffffffL; 32 }
            else -> 64
        }
        var position = 0
        for (bits in listOf(32, 16, 8, 4, 2, 1)) {
            val mask = (1L shl bits) - 1
            if (value and mask == 0L) { value = value ushr bits; position += bits }
        }
        var length = 0
        for (bits in listOf(32, 16, 8, 4, 2, 1)) {
            val mask = (1L shl bits) - 1
            if (value.inv() and mask == 0L) { value = value ushr bits; length += bits }
        }
        if (value != 0L) return -1
        if (negative) {
            position = (position + length) and (repetition - 1)
            length = repetition - length
        }
        return ((if (repetition == 64) 1 else 0) shl 12) or
            ((((repetition - 1) xor 31) shl 1) and 63) or
            (((repetition - position) and (repetition - 1)) shl 6) or (length - 1)
    }

    fun emitLogicalImmediate(opcode: Int, rd: Int, rn: Int, immediate: Long, is64Bit: Boolean): Int {
        var value = immediate
        if (!is64Bit) { value = immediate.toInt().toLong() and 0xffffffffL; value = value or (value shl 32) }
        val encoded = encodeBitmaskImmediate(value)
        if (encoded < 0) { error("logical immediate out of range"); return 0 }
        val instruction = opcode or (if (is64Bit) 0x80000000.toInt() else 0) or
            (((encoded ushr 12) and 1) shl 22) or (((encoded ushr 6) and 63) shl 16) or
            ((encoded and 63) shl 10) or ((rn and 31) shl 5) or (rd and 31)
        emitInstruction(instruction)
        return instruction
    }

    fun constraintPriority(constraint: String, warning: (String) -> Unit = {}): Int {
        var priority = 0
        var i = 0
        while (i < constraint.length) {
            val c = constraint[i++]
            val rank = when (c) {
                '=', '+', '&' -> continue
                'r' -> 1
                'w', 'f', 'x', 'y' -> 3
                'm', 'Q' -> 4
                'i', 'S' -> 5
                'U' -> if (constraint.startsWith("mp", i)) { i += 2; 4 } else { warning("unknown constraint 'U'"); 0 }
                'I', 'J', 'K', 'L', 'M', 'N', 'Z' -> 6
                'n' -> 7
                'g' -> 8
                else -> { warning("unknown constraint '$c'"); 0 }
            }
            priority = maxOf(priority, rank)
        }
        return priority
    }

    fun skipConstraintModifiers(constraint: String): String = constraint.dropWhile { it in "=&+%" }
    fun validAddImmediate(value: Long): Boolean = value in 0..4095
    fun validLogicalImmediate(value: Long, bits: Int): Boolean {
        val normalized = if (bits == 32) {
            val word = value.toInt().toLong() and 0xffffffffL
            word or (word shl 32)
        } else value
        return encodeBitmaskImmediate(normalized) >= 0
    }
    fun validMoveWideImmediate(value: Long): Boolean {
        val unsigned = value.toULong()
        return unsigned <= 0xffffuL ||
            unsigned >= 0xffff0000uL && (unsigned and 0xffffuL) == 0uL ||
            unsigned >= 0xffff00000000uL && (unsigned and 0xffffffffuL) == 0uL ||
            (unsigned and 0xffffffff00000000uL) == 0uL
    }
    fun validMoveWideShift(shift: Int, is64Bit: Boolean): Boolean =
        shift >= 0 && shift and 15 == 0 && shift <= if (is64Bit) 48 else 16

    enum class MemoryValueLocation { CONSTANT, LOCAL, INDIRECT_LOCAL, REGISTER }
    data class MemoryValue(
        val location: MemoryValueLocation, val lvalue: Boolean = false, val bounded: Boolean = false,
        val nonConstant: Boolean = false, val offset: Long = 0, val register: Int = -1,
    )

    fun memoryIsBaseOnly(value: MemoryValue): Boolean = when (value.location) {
        MemoryValueLocation.CONSTANT, MemoryValueLocation.LOCAL -> false
        MemoryValueLocation.INDIRECT_LOCAL -> true
        MemoryValueLocation.REGISTER -> value.lvalue
    }

    fun memoryIsPairSuitable(value: MemoryValue): Boolean = memoryIsBaseOnly(value) ||
        value.location == MemoryValueLocation.LOCAL && value.offset and 7L == 0L && value.offset in -512L..504L

    fun integerRegisterIsAllocatable(register: Int, peTarget: Boolean): Boolean =
        register in 0..if (peTarget) 17 else 30

    fun memoryNeedsAddressRegister(value: MemoryValue): Boolean = value.lvalue &&
        value.location in setOf(MemoryValueLocation.LOCAL, MemoryValueLocation.INDIRECT_LOCAL, MemoryValueLocation.CONSTANT)

    fun prepareMemoryOperand(value: MemoryValue, allocated: ByteArray, peTarget: Boolean): Int? {
        if (!memoryNeedsAddressRegister(value)) return -1
        for (register in 0 until minOf(31, allocated.size)) {
            if (integerRegisterIsAllocatable(register, peTarget) && allocated[register].toInt() and 2 == 0) {
                allocated[register] = (allocated[register].toInt() or 2).toByte()
                return register
            }
        }
        return null
    }

    fun memoryBaseToLoad(value: MemoryValue): MemoryValue = when (value.location) {
        MemoryValueLocation.INDIRECT_LOCAL -> value.copy(location = MemoryValueLocation.LOCAL, lvalue = true)
        MemoryValueLocation.CONSTANT, MemoryValueLocation.LOCAL -> value.copy(lvalue = false)
        else -> throw IllegalArgumentException("unsupported ARM64 memory operand base")
    }

    fun isStackPointer(operand: Operand): Boolean = operand.tokenName.equals("sp", ignoreCase = true)
    fun parseSystemRegister(name: String): Int = when (name.lowercase()) { "fpcr" -> 0; "fpsr" -> 1; else -> -1 }
    fun emitMrs(rt: Int, systemRegister: Int): Int {
        val base = when (systemRegister) { 0 -> 0xd53b4400.toInt(); 1 -> 0xd53b4420.toInt(); else -> { error("unsupported system register"); return 0 } }
        return emitInstruction(base or (rt and 31))
    }
    fun emitMsr(rt: Int, systemRegister: Int): Int {
        val base = when (systemRegister) { 0 -> 0xd51b4400.toInt(); 1 -> 0xd51b4420.toInt(); else -> { error("unsupported system register"); return 0 } }
        return emitInstruction(base or (rt and 31))
    }
    fun emitNop() = emitInstruction(0xd503201f.toInt())

    fun emitShift(rd: Int, rn: Int, operand: Int, shiftType: Int, immediate: Boolean, is64Bit: Boolean): Int {
        val width = if (is64Bit) 64 else 32
        var instruction: Int
        if (immediate) {
            if (operand !in 0 until width) { error("shift immediate out of range"); return 0 }
            instruction = when (shiftType) {
                0 -> (if (is64Bit) 0xd3400000.toInt() else 0x53000000) or (((width - operand) and (width - 1)) shl 16) or ((width - operand - 1) shl 10)
                1 -> (if (is64Bit) 0xd3400000.toInt() else 0x53000000) or (operand shl 16) or ((width - 1) shl 10)
                2 -> (if (is64Bit) 0x93400000.toInt() else 0x13400000) or (operand shl 16) or ((width - 1) shl 10)
                3 -> (if (is64Bit) 0x93c00000.toInt() else 0x13800000) or ((rn and 31) shl 16) or (operand shl 10) or ((rn and 31) shl 5) or (rd and 31)
                else -> { error("unknown shift type"); return 0 }
            }
            if (shiftType == 3) return emitInstruction(instruction)
        } else {
            instruction = when (shiftType) {
                0 -> 0x1ac02000; 1 -> 0x1ac02400; 2 -> 0x1ac02800; 3 -> 0x1ac02c00
                else -> { error("unknown shift type"); return 0 }
            }
            if (is64Bit) instruction = instruction or 0x80000000.toInt()
            instruction = instruction or ((operand and 31) shl 16)
        }
        instruction = instruction or ((rn and 31) shl 5) or (rd and 31)
        return emitInstruction(instruction)
    }

    fun emitBarrier(type: Int, option: Int): Int {
        val base = when (type) { 0 -> 0xd50330df.toInt(); 1 -> 0xd503309f.toInt(); 2 -> 0xd50330bf.toInt(); else -> { error("unknown barrier type"); return 0 } }
        return emitInstruction(base or ((option and 15) shl 8))
    }

    data class ConstraintValue(val isConstant: Boolean = false, val symbolic: Boolean = false,
        val constant: Long = 0, val memory: MemoryValue = MemoryValue(MemoryValueLocation.CONSTANT),
        val localVariableRegister: Int = -1)
    data class ConstraintOperand(
        val constraint: String, val id: String = "", val value: ConstraintValue = ConstraintValue(),
        var register: Int = -1, var reference: Int = -1, var inputReference: Int = -1,
        var isMemory: Boolean = false, var isReadWrite: Boolean = false, var isLongLong: Boolean = false,
    )
    data class ConstraintAllocation(val operands: List<ConstraintOperand>, val outputRegister: Int)

    fun allocateConstraints(operands: MutableList<ConstraintOperand>, outputs: Int, clobbers: ByteArray,
        peTarget: Boolean = false): ConstraintAllocation {
        val occupied = ByteArray(64)
        for (i in occupied.indices) if (i < clobbers.size && clobbers[i].toInt() != 0) occupied[i] = 3
        val priority = IntArray(operands.size)
        operands.forEachIndexed { index, operand ->
            val text = skipConstraintModifiers(operand.constraint)
            val reference = when {
                text.startsWith('[') && ']' in text -> operands.indexOfFirst { it.id == text.substringAfter('[').substringBefore(']') }
                text.firstOrNull()?.isDigit() == true -> text.takeWhile(Char::isDigit).toIntOrNull() ?: -1
                else -> -1
            }
            if (reference >= 0) {
                require(reference < index && index >= outputs) { "invalid reference in constraint $index ('$text')" }
                require(operands[reference].inputReference < 0) { "cannot reference twice the same operand" }
                operand.reference = reference
                operands[reference].inputReference = index
                priority[index] = 5
            } else if (operand.value.localVariableRegister >= 0) {
                priority[index] = 1; operand.register = operand.value.localVariableRegister
            } else priority[index] = constraintPriority(text)
        }
        val order = operands.indices.sortedBy { priority[it] }
        order.forEach { index ->
            val operand = operands[index]
            if (operand.reference >= 0) return@forEach
            val output = index < outputs
            var mask = when {
                operand.inputReference >= 0 -> 3
                output -> 1
                else -> 2
            }
            if (operand.register >= 0) {
                require(occupied[operand.register].toInt() and mask == 0) { "asm regvar requests register that's taken already" }
                allocateRegister(operand, operand.register, mask, occupied)
                return@forEach
            }
            val constraint = operand.constraint
            var cursor = 0
            var assigned = false
            while (cursor < constraint.length && !assigned) {
                when (val code = constraint[cursor++]) {
                    '=' -> Unit
                    '+' -> { operand.isReadWrite = true; if (!output) error("'+' modifier can only be applied to outputs"); mask = 3 }
                    '&' -> { if (!output) error("'&' modifier can only be applied to outputs"); mask = 3 }
                    'r' -> {
                        val reg = (0..30).firstOrNull { integerRegisterIsAllocatable(it, peTarget) && occupied[it].toInt() and mask == 0 }
                        if (reg != null) { allocateRegister(operand, reg, mask, occupied); assigned = true }
                    }
                    'w', 'f', 'x', 'y' -> {
                        val reg = (FREG_BASE..FREG_BASE + 7).firstOrNull { occupied[it].toInt() and mask == 0 }
                        if (reg != null) { allocateRegister(operand, reg, mask, occupied); assigned = true }
                    }
                    'm', 'g' -> {
                        if (index < outputs || code == 'm') {
                            val prep = prepareMemoryOperand(operand.value.memory, occupied, peTarget)
                            if (prep == null) continue
                            if (prep >= 0) { operand.register = prep; operand.isMemory = true }
                        }
                        assigned = true
                    }
                    'Q' -> {
                        if (memoryIsBaseOnly(operand.value.memory)) {
                            val prep = prepareMemoryOperand(operand.value.memory, occupied, peTarget)
                            if (prep != null) { if (prep >= 0) { operand.register = prep; operand.isMemory = true }; assigned = true }
                        }
                    }
                    'S' -> assigned = operand.value.isConstant && operand.value.symbolic
                    'U' -> if (constraint.startsWith("Ump", cursor - 1) && memoryIsPairSuitable(operand.value.memory)) {
                        cursor += 2
                        val prep = prepareMemoryOperand(operand.value.memory, occupied, peTarget)
                        if (prep != null) { if (prep >= 0) { operand.register = prep; operand.isMemory = true }; assigned = true }
                    }
                    'i', 'n' -> assigned = operand.value.isConstant
                    'I' -> assigned = operand.value.isConstant && !operand.value.symbolic && validAddImmediate(operand.value.constant)
                    'J' -> assigned = operand.value.isConstant && !operand.value.symbolic && validAddImmediate(-operand.value.constant)
                    'K' -> assigned = operand.value.isConstant && !operand.value.symbolic && validLogicalImmediate(operand.value.constant, 32)
                    'L' -> assigned = operand.value.isConstant && !operand.value.symbolic && validLogicalImmediate(operand.value.constant, 64)
                    'M', 'N' -> assigned = operand.value.isConstant && !operand.value.symbolic && validMoveWideImmediate(operand.value.constant)
                    'Z' -> assigned = operand.value.isConstant && !operand.value.symbolic && operand.value.constant == 0L
                    '%', ' ' -> Unit
                    else -> throw IllegalArgumentException("asm constraint $index ('${operand.constraint}') could not be satisfied")
                }
            }
            require(assigned) { "asm constraint $index ('${operand.constraint}') could not be satisfied" }
            if (operand.inputReference >= 0) {
                operands[operand.inputReference].register = operand.register
                operands[operand.inputReference].isLongLong = operand.isLongLong
            }
        }
        operands.forEach { if (it.reference >= 0) it.register = operands[it.reference].register }
        var out = -1
        operands.forEach { operand ->
            if (operand.register >= 0 && operand.value.memory.location == MemoryValueLocation.INDIRECT_LOCAL && !operand.isMemory) {
                out = (0..30).firstOrNull { occupied[it].toInt() and 1 == 0 } ?: -1
                require(out >= 0) { "could not find free output register for reloading" }
            }
        }
        return ConstraintAllocation(operands, out)
    }

    private fun allocateRegister(operand: ConstraintOperand, register: Int, mask: Int, occupied: ByteArray) {
        operand.isLongLong = false
        operand.register = register
        occupied[register] = (occupied[register].toInt() or mask).toByte()
    }

    fun markClobber(name: String, clobbers: ByteArray) {
        if (name in setOf("memory", "cc", "flags")) return
        val register = parseRegisterVariable(name) ?: throw IllegalArgumentException("invalid clobber register '$name'")
        require(register in clobbers.indices) { "invalid clobber register '$name'" }
        clobbers[register] = 1
    }

    enum class SubstitutionLocation { CONSTANT, LOCAL, INDIRECT_LOCAL, REGISTER_LVALUE, REGISTER }
    data class SubstitutionValue(
        val location: SubstitutionLocation, val value: Long = 0, val register: Int = -1,
        val symbol: String? = null, val lvalue: Boolean = false, val symbolic: Boolean = false,
        val typeSize: Int = 4,
    )

    fun substituteAsmOperand(value: SubstitutionValue, modifier: Char = '\u0000', leadingUnderscore: Boolean = false,
        registerSymbol: (String) -> Unit = {}): String = when (value.location) {
        SubstitutionLocation.CONSTANT -> {
            if ((modifier == 'w' || modifier == 'x') && !value.lvalue && !value.symbolic && value.value == 0L)
                if (modifier == 'w') "wzr" else "xzr"
            else buildString {
                if (!value.lvalue && modifier !in setOf('c', 'n', 'P')) append('#')
                value.symbol?.let { symbol ->
                    if (leadingUnderscore) append('_')
                    append(symbol)
                    registerSymbol(symbol)
                    if (value.value.toInt() == 0) return@buildString
                    append('+')
                }
                if (modifier == 'n') append(-value.value)
                else if (value.value.toULong() > Long.MAX_VALUE.toULong()) append("0x${value.value.toULong().toString(16)}")
                else append(value.value)
            }
        }
        SubstitutionLocation.LOCAL, SubstitutionLocation.INDIRECT_LOCAL -> "[x29,#${value.value.toInt()}]"
        SubstitutionLocation.REGISTER_LVALUE -> "[x${value.register}]"
        SubstitutionLocation.REGISTER -> {
            if (value.register in FREG_BASE..FREG_BASE + 7) {
                val fp = value.register - FREG_BASE
                val selected = if (modifier == '\u0000') when { value.typeSize <= 4 -> 's'; value.typeSize == 8 -> 'd'; else -> 'q' } else modifier
                if (selected !in setOf('b', 'h', 's', 'd', 'q', 'Z')) error("invalid operand modifier for SIMD/FP register")
                "${if (selected == 'Z') 'z' else selected}$fp"
            } else {
                val size = when (modifier) {
                    'x', 'q' -> 8
                    'w', 'k' -> 4
                    'b' -> 1
                    'h' -> 2
                    else -> value.typeSize
                }
                if (size <= 4) "w${value.register}" else "x${value.register}"
            }
        }
    }

    data class AsmCodegenOperand(
        val register: Int, val value: SubstitutionValue, val isMemory: Boolean = false, val isReadWrite: Boolean = false,
    )

    /** Emits AArch64 extended-asm callee-save, operand load/store, and stack-restore sequences. */
    fun emitAsmCode(operands: List<AsmCodegenOperand>, outputs: Int, isOutput: Boolean, clobbers: ByteArray,
        outputRegister: Int, load: (Int, SubstitutionValue) -> Unit, store: (Int, SubstitutionValue) -> Unit,
        loadMemoryBase: (Int, SubstitutionValue) -> Unit = load) {
        val allocated = ByteArray(64)
        clobbers.copyInto(allocated, endIndex = minOf(clobbers.size, allocated.size))
        operands.forEach { if (it.register in allocated.indices && it.register >= 0) allocated[it.register] = 1 }
        val saved = (19..30).filter { allocated[it].toInt() != 0 }
        val stackSize = ((saved.size + 1) / 2) * 16
        if (!isOutput) {
            if (saved.isNotEmpty()) {
                emitSubImmediate(31, 31, stackSize.toLong(), true)
                var i = 0
                var offset = 0
                while (i < saved.size) {
                    if (i + 1 < saved.size) {
                        emitLoadStorePair(0xa9000000.toInt(), saved[i], saved[i + 1], 31, offset, 3)
                        offset += 16; i += 2
                    } else { emitLoadStoreImmediate(0xf9000000.toInt(), saved[i], 31, offset, 3); i++ }
                }
            }
            operands.forEachIndexed { index, operand ->
                if (operand.register < 0) return@forEachIndexed
                if (operand.isMemory) loadMemoryBase(operand.register, operand.value)
                else if (index >= outputs || operand.isReadWrite) load(operand.register, operand.value)
            }
        } else {
            operands.take(outputs).forEach { operand ->
                if (operand.register < 0 || operand.isMemory) return@forEach
                if (operand.value.location == SubstitutionLocation.INDIRECT_LOCAL ||
                    operand.value.location == SubstitutionLocation.LOCAL && operand.value.lvalue) {
                    val address = operand.value.copy(lvalue = false, typeSize = 8)
                    load(outputRegister, address)
                    store(operand.register, operand.value.copy(location = SubstitutionLocation.REGISTER_LVALUE, register = outputRegister))
                } else store(operand.register, operand.value)
            }
            if (saved.isNotEmpty()) {
                var i = 0
                var offset = 0
                while (i < saved.size) {
                    if (i + 1 < saved.size) {
                        emitLoadStorePair(0xa9400000.toInt(), saved[i], saved[i + 1], 31, offset, 3)
                        offset += 16; i += 2
                    } else { emitLoadStoreImmediate(0xf9400000.toInt(), saved[i], 31, offset, 3); i++ }
                }
                emitAddImmediate(31, 31, stackSize.toLong(), true)
            }
        }
    }

    fun emitMoveImmediate(register: Int, immediate: Long, is64Bit: Boolean) {
        var first = true
        for (halfword in 0 until if (is64Bit) 4 else 2) {
            val value = (immediate.toULong() shr (halfword * 16)).toInt() and 0xffff
            if (value != 0 || halfword == 0) {
                if (first) { emitMovz(register, value, halfword, is64Bit); first = false }
                else emitMovk(register, value, halfword, is64Bit)
            } else if (!first) emitMovk(register, value, halfword, is64Bit)
        }
    }

    enum class RelocationType { JUMP26, CALL26, CONDBR19 }
    data class Relocation(val symbol: String, val offset: Int, val type: RelocationType)

    /** Translates one parsed AArch64 instruction and returns any emitted relocation records. */
    fun assemble(mnemonic: String, operands: List<String>, position: Int,
        relocate: (Relocation) -> Unit = {}): List<Relocation> {
        val name = mnemonic.lowercase()
        val branchConditions = mapOf("beq" to "eq", "bne" to "ne", "bcs" to "cs", "bhs" to "cs", "bcc" to "cc", "blo" to "cc",
            "bmi" to "mi", "bpl" to "pl", "bvs" to "vs", "bvc" to "vc", "bhi" to "hi", "bls" to "ls",
            "bge" to "ge", "blt" to "lt", "bgt" to "gt", "ble" to "le")
        val branchExpression = name in setOf("b", "bl") || name in branchConditions
        val ops = operands.mapIndexed { index, operand ->
            if (name == "mrs" && index == 1 || name == "msr" && index == 0)
                Operand(tokenName = operand.trim())
            else if (name in setOf("movz", "movn", "movk") && index == 2)
                Operand(OperandType.IMMEDIATE)
            else if (name in setOf("isb", "dsb", "dmb") && parseBarrierOption(operand) >= 0)
                Operand(OperandType.IMMEDIATE, value = Expression(parseBarrierOption(operand).toLong()))
            else if (branchExpression && index == 0 || name in setOf("cbz", "cbnz") && index == 1) parseExpressionOperand(operand)
            else parseOperand(operand)
        }
        val relocations = mutableListOf<Relocation>()
        fun relocation(expression: Expression, type: RelocationType) {
            val symbol = expression.symbol ?: return
            val record = Relocation(symbol, position, type)
            relocations += record; relocate(record)
        }
        fun requireCount(count: Int): Boolean {
            if (ops.size != count) { expect("$count operands"); return false }
            return true
        }
        when (name) {
            "nop" -> { requireCount(0); emitNop() }
            "mov" -> if (requireCount(2)) {
                val destination = ops[0]; val source = ops[1]
                if (destination.type != OperandType.REGISTER) { expect("register in first operand"); return relocations }
                val is64 = destination.registerType == RegisterType.X
                when (source.type) {
                    OperandType.IMMEDIATE -> {
                        if (isStackPointer(destination)) error("cannot move an immediate into sp")
                        emitMoveImmediate(destination.register, source.value.value, is64)
                    }
                    OperandType.REGISTER -> if (isStackPointer(destination) || isStackPointer(source))
                        emitAddImmediate(destination.register, source.register, 0, true)
                    else emitMoveRegister(destination.register, source.register, is64)
                    else -> error("invalid operand for mov")
                }
            }
            "add", "adds", "sub", "subs", "and", "ands", "orr", "eor", "mul" -> {
                if (!requireCount(3)) return relocations
                val (destination, first, second) = ops
                if (destination.type != OperandType.REGISTER || first.type != OperandType.REGISTER) { expect("register operands"); return relocations }
                val opcode = when (name) {
                    "add" -> 0x0b000000; "adds" -> 0x2b000000; "sub" -> 0x4b000000; "subs" -> 0x6b000000
                    "and" -> 0x0a000000; "ands" -> 0x6a000000; "orr" -> 0x2a000000; "eor" -> 0x4a000000; else -> 0x1b000000
                }
                val is64 = destination.registerType == RegisterType.X
                if (is64 != (first.registerType == RegisterType.X)) { error("mismatched register widths"); return relocations }
                if (second.type == OperandType.IMMEDIATE) {
                    if (second.value.symbol != null) { error("immediate operand not valid for this instruction"); return relocations }
                    when (name) {
                        "add", "adds" -> emitAddImmediate(destination.register, first.register, second.value.value, is64, name == "adds")
                        "sub", "subs" -> emitSubImmediate(destination.register, first.register, second.value.value, is64, name == "subs")
                        "and" -> emitLogicalImmediate(0x12000000, destination.register, first.register, second.value.value, is64)
                        "ands" -> emitLogicalImmediate(0x72000000, destination.register, first.register, second.value.value, is64)
                        "orr" -> emitLogicalImmediate(0x32000000, destination.register, first.register, second.value.value, is64)
                        "eor" -> emitLogicalImmediate(0x52000000, destination.register, first.register, second.value.value, is64)
                        else -> error("immediate operand not valid for this instruction")
                    }
                } else if (second.type == OperandType.REGISTER) {
                    if (is64 != (first.registerType == RegisterType.X) || is64 != (second.registerType == RegisterType.X)) { error("mismatched register widths"); return relocations }
                    emitDataProcessingRegister(opcode, destination.register, first.register, second.register, is64)
                } else { expect("register in third operand"); return relocations }
            }
            "lsl", "lsr", "asr", "ror" -> {
                if (!requireCount(3)) return relocations
                val (destination, source, shift) = ops
                if (destination.type != OperandType.REGISTER || source.type != OperandType.REGISTER) { error("expected register operands"); return relocations }
                val is64 = destination.registerType == RegisterType.X
                if (is64 != (source.registerType == RegisterType.X)) { error("mismatched register widths"); return relocations }
                val type = when (name) { "lsl" -> 0; "lsr" -> 1; "asr" -> 2; else -> 3 }
                if (shift.type == OperandType.IMMEDIATE) emitShift(destination.register, source.register, shift.value.value.toInt(), type, true, is64)
                else if (shift.type == OperandType.REGISTER && is64 == (shift.registerType == RegisterType.X))
                    emitShift(destination.register, source.register, shift.register, type, false, is64)
                else error("shift requires immediate or register operand")
            }
            "ldr", "ldrb", "ldrh", "str", "strb", "strh" -> {
                if (!requireCount(2)) return relocations
                val data = ops[0]; val address = ops[1]
                if (data.type != OperandType.REGISTER) { error("expected register in first operand"); return relocations }
                if (address.type != OperandType.ADDRESS) { error("expected address operand in second operand"); return relocations }
                if (address.addressMode != AddressMode.OFFSET) { error("only offset addressing is implemented for ldr/str"); return relocations }
                val load = name.startsWith("ldr")
                val (base, size) = when (name) {
                    "ldr", "str" -> when (data.registerType) {
                        RegisterType.X -> (if (load) 0xf9400000.toInt() else 0xf9000000.toInt()) to 3
                        RegisterType.W -> (if (load) 0xb9400000.toInt() else 0xb9000000.toInt()) to 2
                        RegisterType.D -> (if (load) 0xfd400000.toInt() else 0xfd000000.toInt()) to 3
                        else -> { error("${name} requires a w, x, or d register"); return relocations }
                    }
                    "ldrb", "strb" -> (if (load) 0x39400000 else 0x39000000) to 0
                    else -> (if (load) 0x79400000 else 0x79000000) to 1
                }
                emitLoadStoreImmediate(base, data.register, address.register, address.value.value.toInt(), size)
            }
            "ldp", "stp" -> {
                if (!requireCount(3)) return relocations
                val first = ops[0]; val second = ops[1]; val address = ops[2]
                if (first.type != OperandType.REGISTER || second.type != OperandType.REGISTER || address.type != OperandType.ADDRESS) { error("pair load/store requires registers and an address"); return relocations }
                val base = when {
                    first.registerType == RegisterType.X && second.registerType == RegisterType.X -> when (name to address.addressMode) {
                        "ldp" to AddressMode.OFFSET -> 0xa9400000.toInt(); "ldp" to AddressMode.PRE -> 0xa9c00000.toInt(); "ldp" to AddressMode.POST -> 0xa8c00000.toInt()
                        "stp" to AddressMode.OFFSET -> 0xa9000000.toInt(); "stp" to AddressMode.PRE -> 0xa9800000.toInt(); else -> 0xa8800000.toInt()
                    }
                    first.registerType == RegisterType.D && second.registerType == RegisterType.D -> when (name to address.addressMode) {
                        "ldp" to AddressMode.OFFSET -> 0x6d400000; "ldp" to AddressMode.PRE -> 0x6dc00000; "ldp" to AddressMode.POST -> 0x6cc00000
                        "stp" to AddressMode.OFFSET -> 0x6d000000; "stp" to AddressMode.PRE -> 0x6d800000; else -> 0x6c800000
                    }
                    else -> { error("stp/ldp requires matching x or d registers"); return relocations }
                }
                emitLoadStorePair(base, first.register, second.register, address.register, address.value.value.toInt(), 3)
            }
            "br", "blr", "ret" -> {
                if (name == "ret" && ops.isEmpty()) emitReturn()
                else if (requireCount(1) && ops[0].type == OperandType.REGISTER) {
                    when (name) { "br" -> emitBranchRegister(ops[0].register); "blr" -> emitBranchRegister(ops[0].register, true); else -> emitReturn(ops[0].register) }
                } else if (ops.isNotEmpty()) error("expected register for $name")
            }
            "b", "bl" -> if (requireCount(1)) {
                val target = ops[0].value
                if (target.symbol != null) {
                    emitBranch(0, name == "bl")
                    relocation(target, if (name == "bl") RelocationType.CALL26 else RelocationType.JUMP26)
                } else emitBranch((target.value - position).toInt(), name == "bl")
            }
            in branchConditions.keys -> if (requireCount(1)) {
                val condition = parseCondition(branchConditions.getValue(name))
                val target = ops[0].value
                if (target.symbol != null) {
                    emitConditionalBranch(condition, 0)
                    relocation(target, RelocationType.CONDBR19)
                } else emitConditionalBranch(condition, (target.value - position).toInt())
            }
            "cbz", "cbnz" -> if (requireCount(2)) {
                val register = ops[0]
                val target = ops[1].value
                if (register.type != OperandType.REGISTER) { expect("register"); return relocations }
                val is64 = register.registerType == RegisterType.X
                val offset = if (target.symbol == null) (target.value - position).toInt() else 0
                emitCompareBranch(register.register, offset, is64, name == "cbnz")
                if (target.symbol != null) relocation(target, RelocationType.CONDBR19)
            }
            "movz", "movn", "movk" -> if (ops.size in 2..3) {
                val destination = ops[0]; val immediate = ops[1]
                if (destination.type != OperandType.REGISTER) { expect("register"); return relocations }
                if (immediate.type != OperandType.IMMEDIATE || immediate.value.symbol != null || immediate.value.value.toULong() > 0xffffuL) {
                    error("move wide immediate out of range"); return relocations
                }
                val is64 = destination.registerType == RegisterType.X
                val shift = if (ops.size == 3) {
                    val shiftText = operands[2].trim().lowercase().removePrefix("lsl").trim().removePrefix("#").trim()
                    val shiftValue = shiftText.toIntOrNull()
                    if (shiftValue == null || !validMoveWideShift(shiftValue, is64)) { error("move wide shift out of range"); return relocations }
                    shiftValue / 16
                } else 0
                when (name) { "movz" -> emitMovz(destination.register, immediate.value.value.toInt(), shift, is64)
                    "movn" -> emitMovn(destination.register, immediate.value.value.toInt(), shift, is64)
                    else -> emitMovk(destination.register, immediate.value.value.toInt(), shift, is64) }
            }
            "mrs", "msr" -> if (requireCount(2)) {
                val register = if (name == "mrs") ops[0] else ops[1]
                val sysregName = if (name == "mrs") operands[1] else operands[0]
                if (register.type != OperandType.REGISTER) { expect("register"); return relocations }
                val sysreg = parseSystemRegister(sysregName)
                if (sysreg < 0) { error("unsupported system register"); return relocations }
                if (name == "mrs") emitMrs(register.register, sysreg) else emitMsr(register.register, sysreg)
            }
            "isb", "dsb", "dmb" -> {
                if (ops.size > 1) { expect("at most one barrier option"); return relocations }
                val option = if (operands.isEmpty()) 15 else parseBarrierOption(operands[0]).takeIf { it >= 0 }
                    ?: ops[0].value.value.toInt().takeIf { ops[0].type == OperandType.IMMEDIATE && ops[0].value.symbol == null && it in 0..15 }
                    ?: run { error("barrier option out of range"); return relocations }
                emitBarrier(when (name) { "isb" -> 0; "dsb" -> 1; else -> 2 }, option)
            }
            else -> error("ARM64 instruction '$mnemonic' not implemented")
        }
        return relocations
    }

    fun emitBranch(offset: Int, link: Boolean = false) = emitInstruction((if (link) 0x94000000.toInt() else 0x14000000) or ((offset shr 2) and 0x03ffffff))
    fun emitBranchRegister(register: Int, link: Boolean = false) = emitInstruction((if (link) 0xd63f0000.toInt() else 0xd61f0000.toInt()) or ((register and 31) shl 5))
    fun emitReturn(register: Int = 30) = emitInstruction(0xd65f0000.toInt() or ((register and 31) shl 5))
    fun emitConditionalBranch(condition: Int, offset: Int) = emitInstruction(0x54000000 or (((offset shr 2) and 0x7ffff) shl 5) or (condition and 15))
    fun emitCompareBranch(register: Int, offset: Int, is64Bit: Boolean, nonZero: Boolean = false) =
        emitInstruction((if (nonZero) 0x35000000 else 0x34000000) or (if (is64Bit) 0x80000000.toInt() else 0) or (((offset shr 2) and 0x7ffff) shl 5) or (register and 31))
    fun emitMoveRegister(destination: Int, source: Int, is64Bit: Boolean) =
        emitInstruction((if (is64Bit) 0xaa0003e0.toInt() else 0x2a0003e0) or ((source and 31) shl 16) or (destination and 31))
}
