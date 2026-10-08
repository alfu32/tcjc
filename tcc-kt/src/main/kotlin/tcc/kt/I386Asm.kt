package tcc.kt

/** Operand representation and encoding primitives from i386-asm.c. */
class I386Asm(private val emit: (Int) -> Unit) {
    companion object {
        const val OP_REG8 = 1 shl 0
        const val OP_REG16 = 1 shl 1
        const val OP_REG32 = 1 shl 2
        const val OP_MMX = 1 shl 3
        const val OP_SSE = 1 shl 4
        const val OP_CR = 1 shl 5
        const val OP_TR = 1 shl 6
        const val OP_DB = 1 shl 7
        const val OP_SEG = 1 shl 8
        const val OP_ST = 1 shl 9
        const val OP_IM8 = 1 shl 10
        const val OP_IM8S = 1 shl 11
        const val OP_IM16 = 1 shl 12
        const val OP_IM32 = 1 shl 13
        const val OP_EAX = 1 shl 14
        const val OP_ST0 = 1 shl 15
        const val OP_CL = 1 shl 16
        const val OP_DX = 1 shl 17
        const val OP_ADDR = 1 shl 18
        const val OP_INDIR = 1 shl 19
        const val OP_EA = 0x40000000

        /** x86 condition-code aliases in the order used by TOK_ASM_jcc. */
        val conditionCodes = intArrayOf(
            0x00, 0x01, 0x02, 0x02, 0x02, 0x03, 0x03, 0x03,
            0x04, 0x04, 0x05, 0x05, 0x06, 0x06, 0x07, 0x07,
            0x08, 0x09, 0x0a, 0x0a, 0x0b, 0x0b, 0x0c, 0x0c,
            0x0d, 0x0d, 0x0e, 0x0e, 0x0f, 0x0f,
        )
        val segmentPrefixes = intArrayOf(0x26, 0x2e, 0x36, 0x3e, 0x64, 0x65)
    }

    data class Expression(val value: Int = 0, val symbol: String? = null, val pcRelative: Boolean = false)
    data class Operand(
        var type: Int = OP_EA,
        var register: Int = -1,
        var index: Int = -1,
        var shift: Int = 0,
        var expression: Expression = Expression(),
    )

    data class Instruction(
        val token: Int, val opcode: Int, val instructionType: Int,
        val operandTypes: List<Int>,
    )

    /** Selects the first instruction template whose arity and operand masks match. */
    fun selectInstruction(instructions: List<Instruction>, token: Int, operands: List<Operand>): Instruction? {
        return instructions.firstOrNull { instruction ->
            instruction.token == token && instruction.operandTypes.size == operands.size &&
                instruction.operandTypes.indices.all { index ->
                    val accepted = expandOperandType(instruction.operandTypes[index])
                    operands[index].type and accepted != 0
                }
        }
    }

    private fun expandOperandType(type: Int): Int = when (type and 0x1f) {
        in 0..19 -> 1 shl (type and 0x1f)
        20 -> OP_IM8 or OP_IM8S or OP_IM16 or OP_IM32
        21 -> OP_REG8 or OP_REG16 or OP_REG32
        22 -> OP_REG16 or OP_REG32
        23 -> OP_IM16 or OP_IM32
        24 -> OP_MMX or OP_SSE
        25, 26 -> OP_ADDR
        else -> type
    } or (type and OP_EA)

    /** Maps the legal x86 scale constants to the SIB shift field. */
    fun registerShift(scale: Int): Int = when (scale) {
        1 -> 0
        2 -> 1
        4 -> 2
        8 -> 3
        else -> throw IllegalArgumentException("expected scale 1, 2, 4 or 8")
    }

    /** Priority order used to choose among an operand's alternative constraints. */
    fun constraintPriority(constraint: String): Int {
        var priority = 0
        for (code in constraint) {
            val current = when (code) {
                'A' -> 0
                'a', 'b', 'c', 'd', 'S', 'D' -> 1
                'q' -> 2
                'r', 'R', 'p' -> 3
                'N', 'M', 'I', 'e', 'i', 'm', 'g' -> 4
                else -> throw IllegalArgumentException("unknown constraint '$code'")
            }
            priority = maxOf(priority, current)
        }
        return priority
    }

    fun skipConstraintModifiers(constraint: String): String =
        constraint.dropWhile { it == '=' || it == '&' || it == '+' || it == '%' }

    /** Records a named i386 clobber; condition-code and memory clobbers need no register slot. */
    fun markClobber(name: String, registers: BooleanArray) {
        if (name == "memory" || name == "cc" || name == "flags") return
        val names32 = listOf("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi")
        val names16 = listOf("ax", "cx", "dx", "bx", "sp", "bp", "si", "di")
        val register = names32.indexOf(name).takeIf { it >= 0 } ?: names16.indexOf(name)
        require(register >= 0 && register < registers.size) { "invalid clobber register '$name'" }
        registers[register] = true
    }

    /** Emits the register preservation and operand load/store phases around inline asm. */
    fun generateInlineAsm(
        operands: List<InlineOperand>, outputCount: Int, isOutput: Boolean,
        clobbers: BooleanArray, outputScratch: Int,
        save: (Int) -> Unit, restore: (Int) -> Unit,
        load: (InlineOperand, Int) -> Unit, store: (InlineOperand, Int) -> Unit,
        loadHigh: (InlineOperand, Int) -> Unit = { _, _ -> },
        storeHigh: (InlineOperand, Int) -> Unit = { _, _ -> },
        materializeOutputAddress: (InlineOperand, Int) -> Unit = { _, _ -> },
    ) {
        val used = clobbers.copyOf()
        operands.forEach { if (it.register >= 0 && it.register < used.size) used[it.register] = true }
        val preserved = listOf(3, 6, 7).filter { it < used.size && used[it] }
        if (!isOutput) {
            preserved.forEach(save)
            operands.forEachIndexed { index, operand ->
                if (operand.register >= 0 && (index >= outputCount || operand.readWrite)) {
                    load(operand, operand.register)
                    if (operand.isLongLong) loadHigh(operand, operand.register + 1)
                }
            }
        } else {
            operands.take(outputCount).forEach { operand ->
                if (operand.register >= 0) {
                    if (operand.isMemory) Unit
                    else {
                        materializeOutputAddress(operand, outputScratch)
                        store(operand, operand.register)
                    }
                    if (operand.isLongLong) storeHigh(operand, operand.register + 1)
                }
            }
            preserved.asReversed().forEach(restore)
        }
    }

    data class InlineOperand(val register: Int, val readWrite: Boolean = false, val isMemory: Boolean = false, val isLongLong: Boolean = false)

    data class ConstraintOperand(
        val alternatives: String, val isConstant: Boolean = false,
        val isMemory: Boolean = false, val isLocalPointer: Boolean = false,
        var tiedTo: Int = -1, var register: Int = -1,
        var isReadWrite: Boolean = false, var isLongLong: Boolean = false,
    )

    /** Performs the i386 register and tied-operand allocation phase. */
    fun allocateConstraints(
        operands: MutableList<ConstraintOperand>, outputCount: Int,
        clobbers: BooleanArray,
    ): Int {
        val allocated = BooleanArray(8)
        clobbers.indices.take(8).forEach { allocated[it] = clobbers[it] }
        allocated[4] = true // esp
        allocated[5] = true // ebp
        val priorities = operands.mapIndexed { operandIndex, operand ->
            val constraint = skipConstraintModifiers(operand.alternatives)
            val ref = constraint.toIntOrNull()
            if (ref != null) {
                require(ref < operandIndex && operandIndex >= outputCount) { "invalid tied operand reference" }
                operand.tiedTo = ref
                5
            } else if (operand.isLocalPointer) 1 else constraintPriority(constraint)
        }
        val order = operands.indices.sortedBy { priorities[it] }
        order.forEach { index ->
            val operand = operands[index]
            if (operand.tiedTo >= 0) return@forEach
            val isOutput = index < outputCount
            if (operand.alternatives.startsWith('+')) operand.isReadWrite = true
            val choices = skipConstraintModifiers(operand.alternatives)
            var assigned = false
            for (choice in choices) {
                val candidates = when (choice) {
                    'a' -> listOf(0); 'b' -> listOf(3); 'c' -> listOf(1); 'd' -> listOf(2)
                    'S' -> listOf(6); 'D' -> listOf(7)
                    'q' -> listOf(0, 3, 1, 2)
                    'r', 'R', 'p' -> (0..7).toList()
                    'e', 'i' -> if (operand.isConstant) listOf(-1) else emptyList()
                    'I', 'N', 'M' -> if (operand.isConstant) listOf(-1) else emptyList()
                    'm' -> if (operand.isMemory || operand.isLocalPointer) listOf(-1) else emptyList()
                    'g' -> if (operand.isConstant || operand.isMemory) listOf(-1) else (0..7).toList()
                    '=', '&', '+' , '%' -> emptyList()
                    else -> emptyList()
                }
                val reg = candidates.firstOrNull { candidate -> candidate < 0 || !allocated[candidate] }
                if (reg != null) {
                    if (reg >= 0) {
                        allocated[reg] = true
                        operand.register = reg
                    }
                    if (choice == '+') operand.isReadWrite = true
                    assigned = true
                    break
                }
            }
            require(assigned) { "asm constraint $index ('${operand.alternatives}') could not be satisfied" }
        }
        operands.forEachIndexed { index, operand ->
            if (operand.tiedTo >= 0) {
                operand.register = operands[operand.tiedTo].register
                operand.isLongLong = operands[operand.tiedTo].isLongLong
            }
        }
        if (operands.any { it.isLocalPointer && it.register >= 0 })
            return (0..7).firstOrNull { !allocated[it] } ?: -1
        return -1
    }

    fun immediate(value: Int): Operand {
        var type = OP_IM32
        if (value == (value.toByte().toInt())) type = type or OP_IM8
        if (value == value.toByte().toInt()) type = type or OP_IM8S
        if (value == (value.toShort().toInt())) type = type or OP_IM16
        return Operand(type, expression = Expression(value))
    }

    /** Parses the common AT&T i386 operand spellings accepted by parse_operand. */
    fun parseOperand(source: String, evaluate: (String) -> Expression = { Expression(it.toInt()) }): Operand {
        var text = source.trim()
        var indirect = false
        if (text.startsWith('*')) { indirect = true; text = text.drop(1).trimStart() }
        if (text.startsWith('%')) {
            val name = text.drop(1).lowercase()
            val byteRegs = listOf("al", "cl", "dl", "bl", "ah", "ch", "dh", "bh")
            val wordRegs = listOf("ax", "cx", "dx", "bx", "sp", "bp", "si", "di")
            val dwordRegs = listOf("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi")
            val st = Regex("st(?:\\(([0-7])\\))?").matchEntire(name)
            val (type, register) = when {
                name in byteRegs -> OP_REG8 to byteRegs.indexOf(name)
                name in wordRegs -> OP_REG16 to wordRegs.indexOf(name)
                name in dwordRegs -> OP_REG32 to dwordRegs.indexOf(name)
                st != null -> OP_ST to (st.groupValues[1].ifEmpty { "0" }.toInt())
                else -> throw IllegalArgumentException("unknown register %$name")
            }
            var fullType = type
            if (name == "eax") fullType = fullType or OP_EAX
            if (name == "cl") fullType = fullType or OP_CL
            if (name == "dx") fullType = fullType or OP_DX
            return Operand(fullType or if (indirect) OP_INDIR else 0, register)
        }
        if (text.startsWith('$')) {
            val operand = parseExpression(text.drop(1), evaluate)
            val value = operand.expression.value
            if (operand.expression.symbol == null) {
                var type = OP_IM32
                if (value == value.toByte().toInt()) type = type or OP_IM8 or OP_IM8S
                if (value == value.toShort().toInt()) type = type or OP_IM16
                operand.type = type
            } else operand.type = OP_IM32
            if (indirect) operand.type = operand.type or OP_INDIR
            return operand
        }
        val memory = Regex("^(.*?)\\(([^)]*)\\)$").matchEntire(text)
        val displacement = memory?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: if (memory == null) text else "0"
        val expression = parseExpression(displacement, evaluate).expression
        if (memory == null) return Operand(OP_ADDR or if (indirect) OP_INDIR else 0, expression = expression)
        val pieces = memory.groupValues[2].split(',').map { it.trim() }
        val base = pieces.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { parseOperand(it, evaluate).register } ?: -1
        val index = pieces.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { parseOperand(it, evaluate).register } ?: -1
        val shift = pieces.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { registerShift(it.toInt()) } ?: 0
        return Operand(OP_EA or if (indirect) OP_INDIR else 0, base, index, shift, expression)
    }

    private fun parseExpression(text: String, evaluate: (String) -> Expression): Operand {
        val expression = evaluate(text.trim().ifEmpty { "0" })
        return Operand(expression = expression)
    }

    /** Emits an i386 ModRM operand and returns the current output offset. */
    fun modRm(regField: Int, operand: Operand, position: () -> Int): Int {
        val reg = regField and 7
        if (operand.type and (OP_REG8 or OP_REG16 or OP_REG32 or OP_MMX or OP_SSE) != 0) {
            emit(0xc0 or (reg shl 3) or (operand.register and 7))
        } else if (operand.register == -1 && operand.index == -1) {
            emit(0x05 or (reg shl 3))
            emitExpression32(operand.expression)
        } else {
            val base = if (operand.register == -1) 5 else operand.register and 7
            val hasIndex = operand.index != -1
            val mod = when {
                operand.register == -1 -> 0
                operand.expression.value == 0 && operand.expression.symbol == null && base != 5 -> 0
                operand.expression.symbol == null && operand.expression.value in -128..127 -> 0x40
                else -> 0x80
            }
            val rm = if (hasIndex) 4 else base
            emit(mod or (reg shl 3) or rm)
            if (rm == 4) emit((operand.shift shl 6) or ((if (operand.index == -1) 4 else operand.index) shl 3) or base)
            if (mod == 0x40) emit(operand.expression.value)
            else if (mod == 0x80 || operand.register == -1) emitExpression32(operand.expression)
        }
        return position()
    }

    /** Emits a branch displacement, resolving a same-section symbol locally. */
    fun displacement32(expression: Expression, position: Int, sameSectionAddress: (String) -> Int?): Int {
        val local = expression.symbol?.let(sameSectionAddress)
        if (local != null) {
            val value = expression.value + local - position - 4
            emit32(value)
            return value
        }
        emitRelocation(expression.symbol, expression.value, true)
        emit32(expression.value - 4)
        return expression.value - 4
    }

    /** Emits the assembler's condition-code suffix for a conditional branch. */
    fun conditionCode(tokenOffset: Int): Int {
        require(tokenOffset in conditionCodes.indices) { "unknown condition-code token offset $tokenOffset" }
        return conditionCodes[tokenOffset]
    }

    private fun emitExpression32(expression: Expression) {
        if (expression.pcRelative) {
            emitRelocation(expression.symbol, expression.value, true)
            emit32(expression.value - 4)
        } else {
            emitRelocation(expression.symbol, expression.value, false)
            emit32(expression.value)
        }
    }

    /** Hook for the surrounding assembler to record symbolic relocations. */
    var relocation: ((String?, Int, Boolean) -> Unit)? = null
    private fun emitRelocation(symbol: String?, addend: Int, pcRelative: Boolean) {
        if (symbol != null || pcRelative) relocation?.invoke(symbol, addend, pcRelative)
    }

    private fun emit32(value: Int) { repeat(4) { emit(value ushr (8 * it)) } }
}
