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
        const val OPC_REG = 0x04
        const val OPC_MODRM = 0x08
        const val OPC_GROUP_SHIFT = 13
        const val OPC_FWAIT = 0x10
        const val OPC_SHIFT = 0x20
        const val OPC_ARITH = 0x30
        const val OPC_FARITH = 0x40
        const val OPC_TEST = 0x50
        const val OPC_0F01 = 0x60
        const val OPC_0F = 0x100

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
        val operandTypes: List<Int>, val mnemonic: String = "",
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

    fun selectMnemonic(mnemonic: String, operands: List<Operand>): Instruction? =
        selectInstruction(I386AsmInstructionTable.entries.filter { it.mnemonic == mnemonic }, 0, operands)

    /** Selects a mnemonic template and emits its i386 opcode and operands. */
    fun assemble(
        mnemonic: String, operands: List<Operand>, operandSize16: Boolean = false,
        segmentPrefix: Int = 0, addressSize16: Boolean = false,
        emitExpression: (Expression, Boolean) -> Unit = { e, _ -> emit32(e.value) },
    ): Boolean {
        val instruction = selectMnemonic(mnemonic, operands) ?: return false
        if (mnemonic == "int" && operands.size == 1 && operands[0].expression.symbol == null && operands[0].expression.value == 3) {
            emit(0xcc)
            return true
        }
        var opcode = emitPrefixes(instruction, operandSize16, segmentPrefix, addressSize16)
        if (operands.size == 1 && operands[0].type and OP_SEG != 0 && (opcode == 0x06 || opcode == 0x07)) {
            val segment = operands[0].register
            opcode = if (segment >= 4) 0x0fa0 + (opcode - 0x06) + ((segment - 4) shl 3) else opcode + (segment shl 3)
            if (opcode ushr 8 != 0) emit(opcode ushr 8)
            emit(opcode)
            return true
        }
        val group = groupForMnemonic(instruction, mnemonic)
        emitInstruction(instruction, operands, opcodeForMnemonic(instruction, mnemonic, opcode), groupOverride = group, emitExpression = emitExpression)
        return true
    }

    private fun opcodeForMnemonic(instruction: Instruction, mnemonic: String, baseOpcode: Int): Int {
        val kind = instruction.instructionType and 0x70
        val root = mnemonic.dropLastWhile { it in "bwl" }
        val group = when (kind) {
            0x30 -> mapOf("add" to 0, "or" to 1, "adc" to 2, "sbb" to 3, "and" to 4, "sub" to 5, "xor" to 6, "cmp" to 7)[root]
            0x40 -> groupForMnemonic(instruction, mnemonic)
            else -> null
        }
        if (group != null && kind != OPC_SHIFT) return baseOpcode + (group shl 3)
        if (kind == OPC_0F01) return baseOpcode or 0x0f0100
        if (kind == 0x50) {
            val condition = conditionNames.indexOf(root.removePrefix("cmov").removePrefix("set").removePrefix("j"))
            if (condition >= 0) return baseOpcode + condition
        }
        val width = when (mnemonic.lastOrNull()) { 'b' -> 0; 'w' -> 1; 'l' -> 2; else -> 0 }
        return if (instruction.instructionType and 1 != 0 && width > 0) baseOpcode + 1 else baseOpcode
    }

    private fun groupForMnemonic(instruction: Instruction, mnemonic: String): Int? {
        val root = mnemonic.dropLastWhile { it in "bwl" }
        return when (instruction.instructionType and 0x70) {
            OPC_ARITH -> mapOf("add" to 0, "or" to 1, "adc" to 2, "sbb" to 3, "and" to 4, "sub" to 5, "xor" to 6, "cmp" to 7)[root]
            OPC_SHIFT -> mapOf("rol" to 0, "ror" to 1, "rcl" to 2, "rcr" to 3, "shl" to 4, "sal" to 4, "shr" to 5, "sar" to 7)[root]
            OPC_FARITH -> mapOf("fadd" to 0, "fmul" to 1, "fcom" to 2, "fcomp" to 3, "fsub" to 4, "fsubr" to 5, "fdiv" to 6, "fdivr" to 7)[root.removeSuffix("p")]
            OPC_TEST -> {
                val cc = root.removePrefix("cmov").removePrefix("set").removePrefix("j")
                conditionNames.indexOf(cc).takeIf { it >= 0 }
            }
            else -> null
        }
    }

    private val conditionNames = listOf(
        "o", "no", "b", "c", "nae", "nb", "nc", "ae", "e", "z", "ne", "nz",
        "be", "na", "nbe", "a", "s", "ns", "p", "pe", "np", "po", "l", "nge",
        "nl", "ge", "le", "ng", "nle", "g",
    )

    private fun expandOperandType(type: Int): Int = when (type and 0x1f) {
        in 0..19 -> 1 shl (type and 0x1f)
        20 -> OP_IM8 or OP_IM8S or OP_IM16 or OP_IM32
        21 -> OP_REG8 or OP_REG16 or OP_REG32
        22 -> OP_REG16 or OP_REG32
        23 -> OP_IM16 or OP_IM32
        24 -> OP_MMX or OP_SSE
        25, 26 -> OP_ADDR
        else -> type
    } or (if (type and 0x80 != 0) OP_EA else 0)

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
        val alternatives: String, val id: String = "", val isConstant: Boolean = false,
        var isMemory: Boolean = false, val isLocalPointer: Boolean = false,
        var tiedTo: Int = -1, var register: Int = -1,
        var isReadWrite: Boolean = false, var isLongLong: Boolean = false,
    )

    data class InlineValue(
        val register: Int = -1, val constant: Int = 0, val symbol: String? = null,
        val isConstant: Boolean = false, val isLValue: Boolean = false,
        val isLocal: Boolean = false, val kind: String = "int",
    )

    /** Renders an extended-asm operand using the target register spelling. */
    fun substituteOperand(value: InlineValue, modifier: Char = '\u0000', leadingUnderscore: Boolean = false): String {
        if (value.isConstant) {
            val out = StringBuilder()
            if (!value.isLValue && modifier !in setOf('c', 'n', 'P')) out.append('$')
            value.symbol?.let { symbol ->
                if (leadingUnderscore) out.append('_')
                out.append(symbol)
                if (value.constant != 0) out.append('+')
            }
            val number = if (modifier == 'n') -value.constant else value.constant
            if (value.symbol == null || value.constant != 0) out.append(number)
            return out.toString()
        }
        if (value.isLocal) return "${value.constant}(%ebp)"
        require(value.register in 0..7) { "invalid i386 inline-asm register ${value.register}" }
        if (value.isLValue) return "(%${registerName(value.register, 4)})"
        var size = when (value.kind) { "byte", "bool" -> 1; "short" -> 2; else -> 4 }
        if (size == 1 && value.register >= 4) size = 4
        when (modifier) {
            'b' -> { require(value.register < 4) { "cannot use byte register" }; size = 1 }
            'h' -> { require(value.register < 4) { "cannot use byte register" }; size = -1 }
            'w' -> size = 2
            'k' -> size = 4
        }
        return "%${registerName(value.register, size)}"
    }

    private fun registerName(register: Int, size: Int): String {
        val names = when (size) {
            -1 -> listOf("ah", "ch", "dh", "bh", "ah", "ch", "dh", "bh")
            1 -> listOf("al", "cl", "dl", "bl", "ah", "ch", "dh", "bh")
            2 -> listOf("ax", "cx", "dx", "bx", "sp", "bp", "si", "di")
            else -> listOf("eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi")
        }
        return names[register]
    }

    /** Performs the i386 register and tied-operand allocation phase. */
    fun allocateConstraints(
        operands: MutableList<ConstraintOperand>, outputCount: Int,
        clobbers: BooleanArray,
    ): Int {
        val allocated = BooleanArray(8)
        clobbers.indices.take(8).forEach { allocated[it] = clobbers[it] }
        allocated[4] = true // esp
        allocated[5] = true // ebp
        val referenced = mutableSetOf<Int>()
        val priorities = operands.mapIndexed { operandIndex, operand ->
            val constraint = skipConstraintModifiers(operand.alternatives)
            val ref = when {
                constraint.startsWith('[') && ']' in constraint -> operands.indexOfFirst { it.id == constraint.substringAfter('[').substringBefore(']') }
                else -> Regex("^\\d+").find(constraint)?.value?.toIntOrNull() ?: -1
            }
            if (ref >= 0) {
                require(ref < operandIndex && operandIndex >= outputCount) { "invalid tied operand reference" }
                require(referenced.add(ref)) { "cannot reference twice the same operand" }
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
                    'A' -> if (!allocated[0] && !allocated[2]) listOf(0) else emptyList()
                    'a' -> listOf(0); 'b' -> listOf(3); 'c' -> listOf(1); 'd' -> listOf(2)
                    'S' -> listOf(6); 'D' -> listOf(7)
                    'q' -> listOf(0, 3, 1, 2)
                    'r', 'R', 'p' -> (0..7).toList()
                    'e', 'i' -> if (operand.isConstant) listOf(-1) else emptyList()
                    'I', 'N', 'M' -> if (operand.isConstant) listOf(-1) else emptyList()
                    'm' -> when {
                        operand.isMemory -> listOf(-1)
                        operand.isLocalPointer && (isOutput || choice == 'm') -> (0..7).toList()
                        else -> emptyList()
                    }
                    'g' -> when {
                        operand.isConstant || operand.isMemory -> listOf(-1)
                        operand.isLocalPointer && isOutput -> (0..7).toList()
                        else -> (0..7).toList()
                    }
                    '=', '&', '+' , '%' -> emptyList()
                    else -> emptyList()
                }
                val reg = candidates.firstOrNull { candidate -> candidate < 0 || !allocated[candidate] }
                if (reg != null) {
                    if (reg >= 0) {
                        allocated[reg] = true
                        operand.register = reg
                        if (choice == 'A') {
                            allocated[2] = true
                            operand.isLongLong = true
                        }
                        if (operand.isLocalPointer && (choice == 'm' || (choice == 'g' && isOutput))) operand.isMemory = true
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
            val segments = listOf("es", "cs", "ss", "ds", "fs", "gs")
            val st = Regex("st(?:\\(([0-7])\\))?").matchEntire(name)
            val (type, register) = when {
                name in byteRegs -> OP_REG8 to byteRegs.indexOf(name)
                name in wordRegs -> OP_REG16 to wordRegs.indexOf(name)
                name in dwordRegs -> OP_REG32 to dwordRegs.indexOf(name)
                name in segments -> OP_SEG to segments.indexOf(name)
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

    /** Accepts an optional-percent spelling of an i386 integer register variable. */
    fun parseRegisterVariable(identifier: String): Int? = try {
        val operand = parseOperand(if (identifier.startsWith('%')) identifier else "%$identifier")
        if (operand.type and (OP_REG8 or OP_REG16 or OP_REG32) != 0) operand.register else null
    } catch (_: IllegalArgumentException) {
        null
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

    /** Chooses the short branch form for a resolvable in-section target, else the near form. */
    fun branch(opcode: Int, expression: Expression, position: Int, sameSectionAddress: (String) -> Int?): Boolean {
        val target = expression.symbol?.let(sameSectionAddress)
        val shortDelta = if (target != null) expression.value + target - position - 2 else Int.MAX_VALUE
        if (shortDelta in -128..127) {
            emit(opcode)
            emit(shortDelta)
            return true
        }
        when (opcode) {
            0xeb -> emit(0xe9)
            in 0x70..0x7f -> { emit(0x0f); emit(opcode + 0x10) }
            else -> throw IllegalArgumentException("invalid short branch opcode 0x${opcode.toString(16)}")
        }
        if (target != null) emit32(expression.value + target - position - 5)
        else {
            emitRelocation(expression.symbol, expression.value, true)
            emit32(expression.value - 4)
        }
        return false
    }

    /** Emits the assembler's condition-code suffix for a conditional branch. */
    fun conditionCode(tokenOffset: Int): Int {
        require(tokenOffset in conditionCodes.indices) { "unknown condition-code token offset $tokenOffset" }
        return conditionCodes[tokenOffset]
    }

    /** Emits a selected i386 template's opcode, ModRM byte, and immediate operands. */
    fun emitInstruction(
        instruction: Instruction, operands: List<Operand>, opcode: Int,
        suffixOpcodeBits: Int = 0, groupOverride: Int? = null,
        emitExpression: (Expression, Boolean) -> Unit = { e, _ -> emit32(e.value) },
    ) {
        var op = opcode + suffixOpcodeBits
        var modRmIndex = -1
        if (instruction.instructionType and OPC_MODRM != 0) {
            modRmIndex = operands.indices.firstOrNull { operands[it].type and OP_EA != 0 }
                ?: operands.indices.firstOrNull { operands[it].type and (OP_REG8 or OP_REG16 or OP_REG32 or OP_MMX or OP_SSE or OP_INDIR) != 0 }
                ?: if (operands.isEmpty()) -2 else throw IllegalArgumentException("instruction has no ModRM operand")
        }
        if (instruction.instructionType and OPC_REG != 0) {
            val registerOperand = operands.firstOrNull { it.type and (OP_REG8 or OP_REG16 or OP_REG32 or OP_ST) != 0 }
                ?: throw IllegalArgumentException("register opcode has no register operand")
            op += registerOperand.register
        }
        if (op ushr 16 != 0) emit(op ushr 16)
        if ((op ushr 8) and 0xff != 0) emit(op ushr 8)
        emit(op)
        if (modRmIndex == -2) {
            val group = groupOverride ?: ((instruction.instructionType ushr OPC_GROUP_SHIFT) and 7)
            val syntheticRegister = if (instruction.mnemonic == "endbr32") 3 else 0
            emit(0xc0 or (group shl 3) or syntheticRegister)
        } else if (modRmIndex >= 0) {
            val otherRegister = operands.indices.firstOrNull { index ->
                index != modRmIndex && operands[index].type and (OP_REG8 or OP_REG16 or OP_REG32 or OP_MMX or OP_SSE or OP_CR or OP_TR or OP_DB or OP_SEG) != 0
            }
            val group = groupOverride ?: ((instruction.instructionType ushr OPC_GROUP_SHIFT) and 7)
            val field = otherRegister?.let { operands[it].register } ?: group
            modRm(field, operands[modRmIndex]) { -1 }
        }
        operands.forEachIndexed { index, operand ->
            if (index == modRmIndex) return@forEachIndexed
            val operandType = instruction.operandTypes[index] and 0x1f
            if (operandType in 10..13 || operandType == 25 || operandType == 26 || operand.type and (OP_IM8 or OP_IM8S or OP_IM16 or OP_IM32 or OP_ADDR) != 0) {
                val value = operand.expression
                when {
                    operand.type and (OP_IM8 or OP_IM8S) != 0 -> {
                        if (value.symbol != null) throw IllegalArgumentException("cannot relocate an 8 bit immediate")
                        emit(value.value)
                    }
                    operand.type and OP_IM16 != 0 -> {
                        if (value.symbol != null) throw IllegalArgumentException("cannot relocate a 16 bit immediate")
                        emit(value.value); emit(value.value ushr 8)
                    }
                    else -> emitExpression(value, operandType == 25 || operandType == 26)
                }
            }
        }
    }

    /** Emits i386 instruction prefixes and extracts the final opcode word. */
    fun emitPrefixes(instruction: Instruction, operandSize16: Boolean = false, segmentPrefix: Int = 0, addressSize16: Boolean = false): Int {
        if (instruction.instructionType and OPC_FWAIT != 0) emit(0x9b)
        if (segmentPrefix != 0) emit(segmentPrefix)
        if (addressSize16) emit(0x67)
        if (operandSize16) emit(0x66)
        var opcode = instruction.opcode
        val prefix = (opcode ushr 8) and 0xff
        when (prefix) {
            0, 0xd4, 0xd5, in 0xd8..0xdf -> Unit
            0x66, 0x67, 0xf2, 0xf3 -> { emit(prefix); opcode = opcode and 0xff }
            else -> throw IllegalArgumentException("bad i386 opcode prefix 0x${prefix.toString(16)}")
        }
        if (instruction.instructionType and OPC_0F != 0)
            opcode = ((opcode and 0xffff00) shl 8) or 0x0f00 or (opcode and 0xff)
        return opcode
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
