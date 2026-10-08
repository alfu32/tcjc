package tcc.kt

/** RISC-V assembler byte emission and operand representation from riscv64-asm.c. */
class Riscv64Asm(
    private val output: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val expect: (String) -> Unit = { throw IllegalArgumentException("expected $it") },
    private val error: (String) -> Unit = { throw IllegalArgumentException(it) },
) {
    enum class OperandType { REGISTER, IMMEDIATE_12_SIGNED, IMMEDIATE_32 }
    data class Expression(val value: Long, val symbol: String? = null)
    data class Operand(var type: Int = 0, var register: Int = 0, var registerSet: Int = 0, var expression: Expression = Expression(0))
    data class AsmValue(
        val constant: Long = 0, val symbol: String? = null, val register: Int = -1,
        val local: Boolean = false, val lvalue: Boolean = false, val floating: Boolean = false,
    )
    data class AssemblyHooks(
        val isExternalOrStatic: (String) -> Boolean = { false }, val isStaticSymbol: (String) -> Boolean = { false },
        val relocateSymbol: (String, String) -> Unit = { _, _ -> },
    )
    enum class InlineValueKind { CONSTANT, LOCAL, LOCAL_LVALUE, REGISTER, OTHER }
    data class InlineOperand(
        val constraint: String, var valueKind: InlineValueKind = InlineValueKind.OTHER,
        var fixedRegister: Int = -1, var register: Int = -1, var inputIndex: Int = -1,
        var referenceIndex: Int = -1, var priority: Int = 0, var isMemory: Boolean = false,
        var isReadWrite: Boolean = false, var isLongLong: Boolean = false,
    )
    data class ConstraintResult(val outputRegister: Int, val allocationMasks: IntArray, val sortedOperands: List<Int>)
    data class InlineCodeHooks(
        val load: (Int, InlineOperand) -> Unit = { _, _ -> }, val store: (Int, InlineOperand) -> Unit = { _, _ -> },
        val loadAddress: (Int, InlineOperand) -> Unit = { _, _ -> },
    )

    companion object {
        const val REGISTER_COUNT = 64
        const val MAX_ASM_OPERANDS = 30
        const val REG_FLOAT_MASK = 0x20
        const val OPT_REG = 0
        const val OPT_IM12S = 1
        const val OPT_IM32 = 2
        const val OP_REG = 1 shl OPT_REG
        const val OP_IM12S = 1 shl OPT_IM12S
        const val OP_IM32 = 1 shl OPT_IM32
        const val REG_OUT_MASK = 1
        const val REG_IN_MASK = 2

        fun isFloatRegister(register: Int): Boolean = register and REG_FLOAT_MASK != 0
        fun registerValue(register: Int): Int = register and (REG_FLOAT_MASK - 1)
        fun encodeCompressedRs1(register: Int): Int = registerValue(register) shl 7
        fun encodeCompressedRs2(register: Int): Int = registerValue(register) shl 2
        fun encodeRd(register: Int): Int = registerValue(register) shl 7
        fun encodeRs1(register: Int): Int = registerValue(register) shl 15
        fun encodeRs2(register: Int): Int = registerValue(register) shl 20
        fun nthBit(value: Int, bit: Int): Int = (value ushr bit) and 1
    }

    fun emitByte(value: Int) {
        if (!noCode()) output(value and 0xff)
    }

    fun emitLittleEndian16(value: Int) {
        emitByte(value)
        emitByte(value ushr 8)
    }

    fun emitLittleEndian32(value: Int) {
        if (!noCode()) {
            output(value and 0xff)
            output((value ushr 8) and 0xff)
            output((value ushr 16) and 0xff)
            output((value ushr 24) and 0xff)
        }
    }

    fun emitExpression32(expression: Expression) = emitLittleEndian32(expression.value.toInt())
    fun emitOpcode(opcode: Int) = emitLittleEndian32(opcode)

    fun emitNullaryOpcode(name: String): Int? {
        val opcode = when (name) {
            "fence.i" -> (3 shl 2) or 3 or (1 shl 12)
            "ecall" -> (0x1c shl 2) or 3
            "ebreak" -> (0x1c shl 2) or 3 or (1 shl 20)
            "nop" -> 0x13
            "wfi" -> (0x1c shl 2) or 3 or (0x105 shl 20)
            "ret" -> 0x67 or encodeRs1(1)
            "c.ebreak" -> 2 or (9 shl 12)
            "c.nop" -> 1
            else -> { expect("nullary instruction"); return null }
        }
        if (name.startsWith("c.")) emitLittleEndian16(opcode) else emitOpcode(opcode)
        return opcode
    }

    fun parseFenceOperand(name: String): Int? {
        if (name == "iorw") return 0xf
        var result = 0
        for (character in name) result = result or when (character) {
            'i' -> 8; 'o' -> 4; 'r' -> 2; 'w' -> 1; else -> return null
        }
        return result
    }

    fun emitFence(predecessor: Int = 0xf, successor: Int = 0xf): Boolean {
        if (predecessor !in 0..15 || successor !in 0..15) { error("Expected valid fence predecessor and successor operands"); return false }
        emitOpcode((3 shl 2) or 3 or (successor shl 20) or (predecessor shl 24))
        return true
    }

    fun emitUnaryOpcode(name: String, operand: Operand, relocateCall: (String, Int) -> Unit = { _, _ -> }) : Boolean {
        if (name in setOf("rdcycle", "rdcycleh", "rdtime", "rdtimeh", "rdinstret", "rdinstreth", "frflags", "frrm", "frcsr")) {
            if (!requireRegister(operand, "destination operand")) return false
            val csr = when (name) {
                "rdcycle" -> 0xc00; "rdcycleh" -> 0xc80; "rdtime" -> 0xc01; "rdtimeh" -> 0xc81
                "rdinstret" -> 0xc02; "rdinstreth" -> 0xc82; "frflags" -> 1; "frrm" -> 2; else -> 3
            }
            emitOpcode((0x1c shl 2) or 3 or (2 shl 12) or (csr shl 20) or encodeRd(operand.register))
            return true
        }
        if (name == "jr") return emitI(0x67, Operand(OP_REG, register = 0), operand, Operand(OP_IM12S))
        if (name == "call" || name == "tail" || name == "jump") {
            val symbol = operand.expression.symbol
            if (symbol == null) { error("Expected call target symbol"); return false }
            val temporary = when (name) { "call" -> 1; "tail" -> 6; else -> 5 }
            relocateCall(symbol, 18) // R_RISCV_CALL
            emitOpcode(3 or (5 shl 2) or encodeRd(temporary)) // auipc temporary, 0
            emitOpcode(0x67 or encodeRs1(temporary)) // jalr zero, 0(temporary)
            return true
        }
        if (name in setOf("c.j", "c.jal", "c.jr", "c.jalr")) {
            error("compressed unary encoder required for '$name'")
            return false
        }
        expect("unary instruction")
        return false
    }

    fun parseRegister(name: String): Int? {
        val text = name.trim().lowercase()
        if (text == "zero") return 0
        if (text == "ra") return 1
        if (text == "sp") return 2
        val aliases = mapOf(
            "gp" to 3, "tp" to 4, "t0" to 5, "t1" to 6, "t2" to 7,
            "s0" to 8, "fp" to 8, "s1" to 9, "a0" to 10, "a1" to 11,
            "a2" to 12, "a3" to 13, "a4" to 14, "a5" to 15, "a6" to 16,
            "a7" to 17, "s2" to 18, "s3" to 19, "s4" to 20, "s5" to 21,
            "s6" to 22, "s7" to 23, "s8" to 24, "s9" to 25, "s10" to 26,
            "s11" to 27, "t3" to 28, "t4" to 29, "t5" to 30, "t6" to 31,
        )
        aliases[text]?.let { return it }
        val match = Regex("([xf])([0-9]+)").matchEntire(text) ?: return null
        val register = match.groupValues[2].toIntOrNull() ?: return null
        return if (register in 0..31) register or if (match.groupValues[1] == "f") REG_FLOAT_MASK else 0 else null
    }

    fun parseRegisterOrError(name: String): Int {
        val register = parseRegister(name)
        if (register == null) error("invalid register '$name'")
        return register ?: 0
    }

    fun parseRegisterVariable(name: String): Int = parseRegister(name) ?: -1

    fun parseCsrVariable(name: String): Int = when (name.lowercase()) {
        "cycle" -> 0xc00; "fcsr" -> 3; "fflags" -> 1; "frm" -> 2; "instret" -> 0xc02
        "time" -> 0xc01; "cycleh" -> 0xc80; "instreth" -> 0xc82; "timeh" -> 0xc81; else -> -1
    }

    fun markClobber(clobbers: BooleanArray, name: String): Boolean {
        if (name in setOf("memory", "cc", "flags")) return true
        val register = parseRegisterVariable(name)
        if (register < 0 || register >= clobbers.size) { error("invalid clobber register '$name'"); return false }
        clobbers[register] = true
        return true
    }

    fun skipConstraintModifiers(constraint: String): String = constraint.dropWhile { it == '=' || it == '&' || it == '+' || it == '%' }

    fun constraintPriority(constraint: String): Int {
        var priority = 0
        for (character in constraint) {
            val rank = when (character) {
                'A', 'S', 'f', 'r', 'p' -> 3
                'I', 'i', 'm', 'g' -> 4
                'v' -> { error("unimp: constraint '$character'"); return -1 }
                else -> { error("unknown constraint '$character'"); return -1 }
            }
            priority = maxOf(priority, rank)
        }
        return priority
    }

    fun computeConstraints(operands: MutableList<InlineOperand>, outputCount: Int, clobbered: BooleanArray,
        findReference: (String) -> Int? = { it.removePrefix("[").removeSuffix("]").toIntOrNull() }): ConstraintResult? {
        if (operands.size > MAX_ASM_OPERANDS || clobbered.size < REGISTER_COUNT || outputCount !in 0..operands.size) {
            error("invalid inline assembly operand state"); return null
        }
        operands.forEach { it.inputIndex = -1; it.referenceIndex = -1; it.register = -1; it.isMemory = false; it.isReadWrite = false }
        operands.forEachIndexed { index, operand ->
            val constraint = skipConstraintModifiers(operand.constraint)
            if (constraint.firstOrNull()?.isDigit() == true || constraint.startsWith('[')) {
                val reference = findReference(constraint)
                if (reference == null || reference >= index || index < outputCount) { error("invalid reference in constraint $index ('$constraint')"); return null }
                if (operands[reference].inputIndex >= 0) { error("cannot reference twice the same operand"); return null }
                operand.referenceIndex = reference
                operands[reference].inputIndex = index
                operand.priority = 5
            } else if (operand.valueKind == InlineValueKind.LOCAL && operand.fixedRegister >= 0) {
                operand.priority = 1
                operand.register = operand.fixedRegister
            } else {
                operand.priority = constraintPriority(constraint)
                if (operand.priority < 0) return null
            }
        }
        val sorted = operands.indices.sortedBy { operands[it].priority }
        val masks = IntArray(REGISTER_COUNT) { if (clobbered[it]) REG_IN_MASK or REG_OUT_MASK else 0 }
        for (index in sorted) {
            val operand = operands[index]
            if (operand.referenceIndex >= 0) continue
            var registerMask = when {
                operand.inputIndex >= 0 -> REG_IN_MASK or REG_OUT_MASK
                index < outputCount -> REG_OUT_MASK
                else -> REG_IN_MASK
            }
            var selected = operand.register
            val chars = operand.constraint.iterator()
            var allocated = false
            while (chars.hasNext()) {
                when (val character = chars.nextChar()) {
                    '=', '%' -> Unit
                    '+', '&' -> {
                        if (index >= outputCount) { error("'$character' modifier can only be applied to outputs"); return null }
                        if (character == '+') operand.isReadWrite = true
                        registerMask = REG_IN_MASK or REG_OUT_MASK
                    }
                    'r', 'p', 'f' -> {
                        val range = if (character == 'f') 42..50 else 10..18
                        if (selected < 0) selected = range.firstOrNull { masks[it] == 0 } ?: -1
                        if (selected < 0) continue
                        if (selected !in masks.indices || masks[selected] and registerMask != 0) {
                            error("asm register is already allocated"); return null
                        }
                        operand.isLongLong = false
                        operand.register = selected
                        masks[selected] = masks[selected] or registerMask
                        allocated = true
                        break
                    }
                    'I', 'i' -> if (operand.valueKind != InlineValueKind.CONSTANT) continue else { allocated = true; break }
                    'm', 'g' -> {
                        if (index < outputCount || character == 'm') {
                            if (operand.valueKind == InlineValueKind.LOCAL_LVALUE) {
                                selected = (10..18).firstOrNull { masks[it] and REG_IN_MASK == 0 } ?: -1
                                if (selected < 0) continue
                                masks[selected] = masks[selected] or REG_IN_MASK
                                operand.register = selected
                                operand.isMemory = true
                            }
                        }
                        allocated = true
                        break
                    }
                    else -> { error("asm constraint $index ('${operand.constraint}') could not be satisfied"); return null }
                }
            }
            if (!allocated) { error("asm constraint $index ('${operand.constraint}') could not be satisfied"); return null }
            if (operand.inputIndex >= 0) {
                operands[operand.inputIndex].register = operand.register
                operands[operand.inputIndex].isLongLong = operand.isLongLong
            }
        }
        var outputRegister = -1
        for (operand in operands) {
            if (operand.register >= 0 && operand.valueKind == InlineValueKind.LOCAL_LVALUE && !operand.isMemory) {
                val candidates = if (isFloatRegister(operand.register)) 42..50 else 10..18
                outputRegister = candidates.firstOrNull { masks[it] and REG_OUT_MASK == 0 } ?: -1
                if (outputRegister < 0) { error("could not find free output register for reloading"); return null }
                break
            }
        }
        return ConstraintResult(outputRegister, masks, sorted)
    }

    fun tccIntegerRegister(allocatedRegister: Int): Int = registerValue(allocatedRegister) - 10
    fun tccFloatingRegister(allocatedRegister: Int): Int = registerValue(allocatedRegister) - 10 + 8

    fun generateInlineAsm(operands: List<InlineOperand>, outputCount: Int, isOutput: Boolean,
        clobbered: BooleanArray, outputRegister: Int, hooks: InlineCodeHooks = InlineCodeHooks()) {
        val savedRegisters = intArrayOf(8, 9, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 40, 41, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59)
        val used = BooleanArray(REGISTER_COUNT)
        for (register in 0 until minOf(clobbered.size, REGISTER_COUNT)) used[register] = clobbered[register]
        operands.forEach { if (it.register in 0 until REGISTER_COUNT) used[it.register] = true }
        if (!isOutput) {
            for (register in savedRegisters) if (used[register]) {
                emitOpcode(0x13 or encodeRd(2) or encodeRs1(2) or (-8 shl 20)) // addi sp, sp, -8
                val save = if (isFloatRegister(register)) 0x3027 else 0x3023
                emitOpcode(save or encodeRs2(register) or encodeRs1(2))
            }
            operands.forEachIndexed { index, operand ->
                if (operand.register < 0) return@forEachIndexed
                if (operand.valueKind == InlineValueKind.LOCAL_LVALUE && operand.isMemory) {
                    hooks.loadAddress(tccIntegerRegister(operand.register), operand)
                } else if (index >= outputCount || operand.isReadWrite) {
                    val register = if (isFloatRegister(operand.register)) tccFloatingRegister(operand.register) else tccIntegerRegister(operand.register)
                    hooks.load(register, operand)
                }
            }
        } else {
            for ((index, operand) in operands.take(outputCount).withIndex()) {
                if (operand.register < 0) continue
                if (operand.valueKind == InlineValueKind.LOCAL_LVALUE && !operand.isMemory) {
                    hooks.loadAddress(tccIntegerRegister(outputRegister), operand)
                    val register = if (isFloatRegister(operand.register)) tccFloatingRegister(operand.register) else tccIntegerRegister(operand.register)
                    hooks.store(register, operand)
                } else {
                    val register = if (isFloatRegister(operand.register)) tccFloatingRegister(operand.register) else tccIntegerRegister(operand.register)
                    hooks.store(register, operand)
                }
            }
            for (register in savedRegisters.reversed()) if (used[register]) {
                val restore = if (isFloatRegister(register)) 0x3007 else 0x3003
                emitOpcode(restore or encodeRd(register) or encodeRs1(2))
                emitOpcode(0x13 or encodeRd(2) or encodeRs1(2) or (8 shl 20)) // addi sp, sp, 8
            }
        }
    }

    fun substituteAssemblyOperand(value: AsmValue, modifier: Char = '\u0000', leadingUnderscore: Boolean = false): String {
        if (value.symbol != null || value.local) {
            if (value.symbol == null) return value.constant.toString()
            val prefix = if (leadingUnderscore) "_" else ""
            if (value.constant == 0L) return prefix + value.symbol
            val adjusted = if (modifier == 'n') -value.constant else value.constant
            return "$prefix${value.symbol}+${adjusted}"
        }
        if (value.register >= 0) {
            val number = registerValue(value.register)
            return (if (isFloatRegister(value.register) || value.floating) "f" else "x") + number
        }
        val adjusted = if (modifier == 'n') -value.constant else value.constant
        return if (modifier == 'z' && adjusted == 0L) "zero" else adjusted.toString()
    }

    fun parseExpression(text: String): Expression {
        val value = text.trim().replace("_", "")
        val sign = if (value.startsWith('-')) -1L else 1L
        val digits = value.removePrefix("-").removePrefix("+")
        val radix = when { digits.startsWith("0x", true) -> 16; digits.startsWith("0b", true) -> 2; else -> 10 }
        val body = when (radix) { 16 -> digits.drop(2); 2 -> digits.drop(2); else -> digits }
        return body.toLongOrNull(radix)?.let { Expression(sign * it) }
            ?: if (Regex("[A-Za-z_.$][A-Za-z0-9_.$]*").matches(value)) Expression(0, value)
            else { expect("constant expression"); Expression(0) }
    }

    fun parseImmediate(text: String): Operand {
        val expression = parseExpression(text.trim().removePrefix("#"))
        val type = if (expression.value in -0x1000..0x0fff) OP_IM12S else OP_IM32
        return Operand(type = type, expression = expression)
    }

    fun parseOperand(source: String, csrValue: (String) -> Int? = { null },
        relocateSymbol: (String, Boolean) -> Unit = { _, _ -> }, isExternalOrStatic: (String) -> Boolean = { false }): Operand {
        val text = source.trim()
        parseRegister(text)?.let { return Operand(type = OP_REG, register = it) }
        val expressionText = text.removePrefix("$")
        val csr = csrValue(expressionText)
        val expression = if (csr != null) Expression(csr.toLong()) else parseExpression(expressionText)
        var operand = Operand(type = if (expression.value in -0x1000..0x0fff && expression.symbol == null) OP_IM12S else OP_IM32,
            expression = expression)
        if (expression.symbol != null && isExternalOrStatic(expression.symbol)) {
            relocateSymbol(expression.symbol, false)
            operand = operand.copy(type = OP_IM12S, expression = Expression(0, expression.symbol))
        } else if (expression.symbol != null) {
            expect("operand")
        }
        return operand
    }

    fun parseBranchOffset(source: String, isExternalOrStatic: (String) -> Boolean = { false }): Operand {
        val expression = parseExpression(source)
        val type = if (expression.symbol == null && expression.value in -0x1000..0x0fff) OP_IM12S else OP_IM32
        if (expression.symbol != null && !isExternalOrStatic(expression.symbol)) expect("operand")
        return Operand(type, expression = if (expression.symbol != null) Expression(0, expression.symbol) else expression)
    }

    fun parseJumpOffset(source: String, isExternalOrStatic: (String) -> Boolean = { false },
        relocateSymbol: (String, Boolean) -> Unit = { _, _ -> }): Operand {
        val expression = parseExpression(source)
        if (expression.symbol != null) {
            if (!isExternalOrStatic(expression.symbol)) expect("operand") else relocateSymbol(expression.symbol, true)
            return Operand(OP_IM12S, expression = Expression(0, expression.symbol))
        }
        return Operand(if (expression.value in -0x1000..0x0fff) OP_IM12S else OP_IM32, expression = expression)
    }

    fun parseOperands(source: String, count: Int, csrValue: (String) -> Int? = { null }): List<Operand> {
        val parts = splitOperands(source)
        if (parts.size != count) expect("$count operands")
        return parts.take(count).map { parseOperand(it, csrValue) }
    }

    /** Parses `X, imm(Y)` into destination, base register, and offset operands. */
    fun parseMemoryAccessOperands(source: String, parse: (String) -> Operand = { parseOperand(it) }): List<Operand> {
        val parts = splitOperands(source)
        if (parts.size != 2) { expect("memory access operands"); return listOf(Operand(), Operand(), Operand()) }
        val destination = parse(parts[0])
        val address = parts[1].trim()
        val open = address.indexOf('(')
        if (address.startsWith('(') && address.endsWith(')')) {
            val base = parse(address.substring(1, address.length - 1))
            return listOf(destination, base, Operand(OP_IM12S))
        }
        if (open >= 0 && address.endsWith(')')) {
            val immediate = parse(address.substring(0, open).ifBlank { "0" })
            val base = parse(address.substring(open + 1, address.length - 1))
            return listOf(destination, base, immediate)
        }
        val base = parse(address)
        return listOf(destination, base, Operand(OP_IM12S))
    }

    private fun splitOperands(source: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        source.forEachIndexed { index, char ->
            when (char) { '(' -> depth++; ')' -> depth-- ; ',' -> if (depth == 0) { parts += source.substring(start, index).trim(); start = index + 1 } }
        }
        if (source.isNotBlank()) parts += source.substring(start).trim()
        return parts
    }

    private fun requireRegister(operand: Operand, description: String): Boolean {
        if (operand.type == OP_REG) return true
        error("Expected $description to be a register")
        return false
    }

    fun emitR(opcode: Int, rd: Operand, rs1: Operand, rs2: Operand): Boolean {
        if (!requireRegister(rd, "destination operand") || !requireRegister(rs1, "first source operand") ||
            !requireRegister(rs2, "second source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register))
        return true
    }

    fun emitFloating(opcode: Int, rd: Operand, rs1: Operand, rs2: Operand): Boolean {
        if (!requireFloatRegister(rd, "destination operand") || !requireFloatRegister(rs1, "first source operand") ||
            !requireFloatRegister(rs2, "second source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register))
        return true
    }

    fun emitFloatingUnary(opcode: Int, rd: Operand, rs: Operand): Boolean {
        if (!requireFloatRegister(rd, "destination operand") || !requireFloatRegister(rs, "source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs.register))
        return true
    }

    fun emitFloatingQuaternary(opcode: Int, rd: Operand, rs1: Operand, rs2: Operand, rs3: Operand): Boolean {
        if (!requireFloatRegister(rd, "destination operand") || !requireFloatRegister(rs1, "first source operand") ||
            !requireFloatRegister(rs2, "second source operand") || !requireFloatRegister(rs3, "third source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register) or (registerValue(rs3.register) shl 27))
        return true
    }

    private fun requireFloatRegister(operand: Operand, description: String): Boolean {
        if (operand.type == OP_REG && isFloatRegister(operand.register)) return true
        error("Expected $description to be a floating-point register")
        return false
    }

    fun emitI(opcode: Int, rd: Operand, rs1: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rd, "destination operand") || !requireRegister(rs1, "first source operand")) return false
        if (immediate.type != OP_IM12S) { error("Expected second source operand to be an immediate value between 0 and 8191"); return false }
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or (immediate.expression.value.toInt() shl 20))
        return true
    }

    fun emitU(opcode: Int, rd: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rd, "destination operand")) return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected source operand to be an immediate value"); return false }
        if (immediate.expression.value >= 0x100000) { error("Expected source operand immediate between 0 and 0xfffff"); return false }
        emitOpcode(opcode or encodeRd(rd.register) or (immediate.expression.value.toInt() shl 12))
        return true
    }

    fun emitJ(opcode: Int, rd: Operand, offset: Operand): Boolean {
        if (!requireRegister(rd, "destination operand")) return false
        if (offset.type != OP_IM12S && offset.type != OP_IM32) { error("Expected jump offset immediate"); return false }
        val immediate = offset.expression.value.toInt()
        if (immediate > (1 shl 20) - 1 || immediate <= -((1 shl 20) - 1)) { error("Expected jump offset in range"); return false }
        if (immediate and 1 != 0) { error("Expected an even jump offset"); return false }
        val encoded = (((immediate ushr 20) and 1) shl 31) or (((immediate ushr 1) and 0x3ff) shl 21) or
            (((immediate ushr 11) and 1) shl 20) or (((immediate ushr 12) and 0xff) shl 12)
        emitOpcode(opcode or encodeRd(rd.register) or encoded)
        return true
    }

    fun emitAtomic(opcode: Int, rd: Operand, rs2: Operand, rs1: Operand, acquire: Boolean, release: Boolean): Boolean {
        if (!requireRegister(rd, "first destination operand") || !requireRegister(rs2, "second source operand") ||
            !requireRegister(rs1, "third source operand")) return false
        emitOpcode(opcode or encodeRs1(rs1.register) or encodeRs2(rs2.register) or encodeRd(rd.register) or
            ((if (acquire) 1 else 0) shl 26) or ((if (release) 1 else 0) shl 25))
        return true
    }

    fun emitAtomicInstruction(name: String, rd: Operand, source: Operand?, base: Operand): Boolean {
        val acquire = name.endsWith("_aq") || name.endsWith("_aqrl")
        val release = name.endsWith("_rl") || name.endsWith("_aqrl")
        val operation = name.removeSuffix("_aqrl").removeSuffix("_aq").removeSuffix("_rl")
        val width = when { operation.endsWith("_w") -> 2; operation.endsWith("_d") -> 3; else -> { expect("atomic word or doubleword instruction"); return false } }
        val mnemonic = operation.removeSuffix("_w").removeSuffix("_d")
        val function = when (mnemonic) {
            "lr" -> 0x0c; "sc" -> 0x18; "amoadd" -> 0x00; "amoswap" -> 0x01
            "amoand" -> 0x0c; "amoor" -> 0x08; "amoxor" -> 0x04; "amomax" -> 0x14
            "amomaxu" -> 0x1c; "amomin" -> 0x10; "amominu" -> 0x18
            else -> { expect("atomic instruction"); return false }
        }
        val rs2 = if (mnemonic == "lr") Operand(OP_REG) else source ?: run { expect("atomic source register"); return false }
        return emitAtomic(0x2f or (width shl 12) or (function shl 27), rd, rs2, base, acquire, release)
    }

    /** String-backed instruction dispatcher corresponding to asm_opcode and its operand handlers. */
    private fun parseAssemblyOperand(source: String, hooks: AssemblyHooks): Operand =
        parseOperand(source, ::parseCsrVariable,
            relocateSymbol = { symbol, _ -> hooks.relocateSymbol(symbol, if (hooks.isStaticSymbol(symbol)) "PCREL_HI20" else "GOT_HI20") },
            isExternalOrStatic = hooks.isExternalOrStatic)

    fun assembleInstruction(mnemonic: String, operandText: String = "", hooks: AssemblyHooks = AssemblyHooks()): Boolean {
        val name = mnemonic.lowercase()
        if (name in setOf("fence.i", "ecall", "ebreak", "nop", "wfi", "ret", "c.ebreak", "c.nop")) {
            emitNullaryOpcode(name); return true
        }
        if (name == "fence") {
            val parts = splitOperands(operandText)
            if (parts.isEmpty()) return emitFence()
            if (parts.size != 2) { expect("fence predecessor and successor"); return false }
            val before = parseFenceOperand(parts[0]); val after = parseFenceOperand(parts[1])
            if (before == null || after == null) { error("Expected valid fence predecessor and successor operands"); return false }
            return emitFence(before, after)
        }
        if (name in setOf("j", "jal")) {
            val parts = splitOperands(operandText)
            val rd: Operand
            val target: String
            if (name == "j") { if (parts.size != 1) { expect("jump target"); return false }; rd = Operand(OP_REG); target = parts[0] }
            else when (parts.size) {
                1 -> { rd = Operand(OP_REG, register = 1); target = parts[0] }
                2 -> { rd = parseAssemblyOperand(parts[0], hooks); target = parts[1] }
                else -> { expect("jump target"); return false }
            }
            val offset = parseJumpOffset(target, hooks.isExternalOrStatic) { symbol, _ -> hooks.relocateSymbol(symbol, "JAL") }
            return emitJ(0x6f, rd, offset)
        }
        if (name == "jalr") {
            val parts = splitOperands(operandText)
            if (parts.size == 1) return emitI(0x67, Operand(OP_REG, register = 1), parseAssemblyOperand(parts[0], hooks), Operand(OP_IM12S))
            if (parts.size != 2) { expect("jalr operands"); return false }
            val parsed = parseMemoryAccessOperands(operandText) { parseAssemblyOperand(it, hooks) }
            return emitI(0x67, parsed[0], parsed[1], parsed[2])
        }
        if (name in setOf("jr", "call", "tail", "jump", "rdcycle", "rdcycleh", "rdtime", "rdtimeh", "rdinstret", "rdinstreth", "frflags", "frrm", "frcsr")) {
            val operand = if (name == "call" || name == "tail" || name == "jump") Operand(OP_IM32, expression = parseExpression(operandText)) else parseAssemblyOperand(operandText, hooks)
            return emitUnaryOpcode(name, operand) { symbol, _ -> hooks.relocateSymbol(symbol, "CALL") }
        }
        if (name in setOf("lb", "lh", "lw", "ld", "lbu", "lhu", "lwu", "fld", "sb", "sh", "sw", "sd", "fsd")) {
            val ops = parseMemoryAccessOperands(operandText) { parseAssemblyOperand(it, hooks) }
            return emitMemoryInstruction(name, ops, hooks.isStaticSymbol) { hooks.relocateSymbol(it, "PCREL_HI20") }
        }
        if (name.startsWith("amo") || name.startsWith("lr_") || name.startsWith("sc_")) {
            val parts = splitOperands(operandText)
            if (parts.size != 3 || !parts[2].startsWith('(') || !parts[2].endsWith(')')) { expect("atomic memory operands"); return false }
            val rd = parseAssemblyOperand(parts[0], hooks); val source = if (name.startsWith("lr_")) null else parseAssemblyOperand(parts[1], hooks)
            val base = parseAssemblyOperand(parts[2].substring(1, parts[2].length - 1), hooks)
            return emitAtomicInstruction(name, rd, source, base)
        }
        if (name.startsWith("c.")) return assembleCompressed(name, operandText, hooks)
        if (name.startsWith("fcvt") || name.startsWith("fclass")) {
            val parts = splitOperands(operandText)
            if (parts.size !in 2..3) { expect("floating conversion operands"); return false }
            val ops = parts.take(2).map { parseAssemblyOperand(it, hooks) }
            return emitFloatingInstruction(name, ops, parseRoundingMode(parts.getOrNull(2)))
        }
        if (name in setOf("beq", "bne", "blt", "bge", "bltu", "bgeu", "bgt", "ble", "bgtu", "bleu", "beqz", "bnez", "blez", "bgez", "bltz", "bgtz")) {
            val parts = splitOperands(operandText)
            if (parts.size !in 2..3) { expect("branch operands"); return false }
            val parsed = parts.dropLast(1).map { parseAssemblyOperand(it, hooks) } +
                parseBranchOffset(parts.last(), hooks.isExternalOrStatic)
            return emitBranchInstruction(name, parsed) { opcode, rs1, rs2, symbol ->
                emitOpcode(opcode or encodeRs1(rs1) or encodeRs2(rs2) or (1 shl 8))
                hooks.relocateSymbol(symbol, "CALL")
                emitOpcode(0x17 or encodeRd(5))
                emitOpcode(0x67 or encodeRs1(5))
            }
        }
        val parts = splitOperands(operandText)
        val parsed = parts.map { parseAssemblyOperand(it, hooks) }
        if (name in setOf("lui", "auipc")) return emitBinaryInstruction(name, parsed.getOrElse(0) { Operand() }, parsed.getOrElse(1) { Operand() })
        if (name in setOf("mv", "not", "neg", "negw", "sext.w", "seqz", "snez", "sltz", "sgtz", "la", "lla", "li",
                "fabs.s", "fabs.d", "fneg.s", "fneg.d", "fmv.s", "fmv.d", "csrr", "csrw", "csrs", "csrc", "csrwi", "csrsi", "csrci", "fsrm", "fscsr"))
            return emitPseudoBinary(name, parsed)
        if (name == "fmadd.s" || name == "fmadd.d" || name == "fmadd_s" || name == "fmadd_d") return emitFusedMultiplyAdd(name, parsed)
        if (name.startsWith("f") && (name.startsWith("fcvt") || name.startsWith("fclass") || name.startsWith("fsqrt") ||
                    name.startsWith("fadd") || name.startsWith("fsub") || name.startsWith("fmul") || name.startsWith("fdiv") ||
                    name.startsWith("fsgnj") || name.startsWith("fmin") || name.startsWith("fmax") || name.startsWith("feq") || name.startsWith("flt") || name.startsWith("fle")))
            return emitFloatingInstruction(name, parsed)
        return emitTernaryInstruction(name, parsed)
    }

    private fun assembleCompressed(name: String, text: String, hooks: AssemblyHooks): Boolean {
        val parts = splitOperands(text)
        if (name in setOf("c.j", "c.jal")) {
            val offset = parseJumpOffset(parts.singleOrNull().orEmpty(), hooks.isExternalOrStatic) { symbol, _ -> hooks.relocateSymbol(symbol, "RVC_JUMP") }
            return emitCompressedCj(if (name == "c.j") 1 or (5 shl 13) else 1 or (1 shl 13), offset)
        }
        if (name == "c.jr" || name == "c.jalr") {
            val reg = parseOperand(parts.singleOrNull().orEmpty())
            return emitCompressedCr(2 or ((if (name == "c.jr") 8 else 9) shl 12), reg, Operand(OP_REG))
        }
        if (name in setOf("c.add", "c.mv", "c.addw", "c.and", "c.or", "c.sub", "c.subw", "c.xor")) {
            val ops = parseOperands(text, 2)
            val opcode = when (name) {
                "c.add" -> 2 or (9 shl 12); "c.mv" -> 2 or (8 shl 12)
                else -> 1 or (3 shl 10) or (4 shl 13) or when (name) {
                    "c.addw" -> (1 shl 5) or (1 shl 12); "c.and" -> 3 shl 5; "c.or" -> 2 shl 5
                    "c.sub" -> 0; "c.subw" -> 1 shl 12; else -> 1 shl 5
                }
            }
            return if (name == "c.add" || name == "c.mv") emitCompressedCr(opcode, ops[0], ops[1]) else emitCompressedCa(opcode, ops[0], ops[1])
        }
        if (name in setOf("c.fld", "c.flw", "c.ld", "c.lw")) {
            val ops = parseMemoryAccessOperands(text) { parseAssemblyOperand(it, hooks) }
            val opcode = when (name) { "c.fld" -> 1 shl 13; "c.flw" -> 3 shl 13; "c.ld" -> 3 shl 13; else -> 2 shl 13 }
            return emitCompressedCl(name, opcode, ops[0], ops[1], ops[2])
        }
        if (name in setOf("c.fsd", "c.fsw", "c.sd", "c.sw")) {
            val ops = parseMemoryAccessOperands(text) { parseAssemblyOperand(it, hooks) }
            val opcode = when (name) { "c.fsd" -> 5 shl 13; "c.fsw" -> 7 shl 13; "c.sd" -> 7 shl 13; else -> 6 shl 13 }
            return emitCompressedCs(name, opcode, ops[0], ops[1], ops[2])
        }
        if (name in setOf("c.swsp", "c.sdsp", "c.fswsp", "c.fsdsp")) {
            val ops = splitOperands(text).map { parseAssemblyOperand(it, hooks) }
            val opcode = when (name) { "c.swsp" -> 2 or (6 shl 13); "c.sdsp" -> 2 or (7 shl 13); "c.fswsp" -> 2 or (7 shl 13); else -> 2 or (5 shl 13) }
            return emitCompressedCss(name, opcode, ops[0], ops[1])
        }
        if (name in setOf("c.beqz", "c.bnez", "c.andi", "c.srai", "c.srli")) {
            val parts = splitOperands(text)
            if (parts.size != 2) { expect("two compressed branch operands"); return false }
            val ops = if (name == "c.beqz" || name == "c.bnez") listOf(parseAssemblyOperand(parts[0], hooks), parseBranchOffset(parts[1], hooks.isExternalOrStatic))
                else parts.map { parseAssemblyOperand(it, hooks) }
            val opcode = when (name) { "c.beqz" -> 1 or (6 shl 13); "c.bnez" -> 1 or (7 shl 13); "c.andi" -> 1 or (2 shl 10) or (4 shl 13); "c.srai" -> 1 or (1 shl 10) or (4 shl 13); else -> 1 or (4 shl 13) }
            return emitCompressedCb(name, opcode, ops[0], ops[1])
        }
        if (name in setOf("c.addi16sp", "c.addi4spn", "c.addi", "c.addiw", "c.fldsp", "c.flwsp", "c.ldsp", "c.li", "c.lui", "c.lwsp", "c.slli")) {
            val ops = splitOperands(text).map { parseAssemblyOperand(it, hooks) }
            val opcode = when (name) {
                "c.addi16sp" -> 1 or (3 shl 13); "c.addi4spn" -> 0; "c.addi" -> 1
                "c.addiw" -> 1 or (1 shl 13); "c.fldsp" -> 2 or (1 shl 13); "c.flwsp", "c.ldsp" -> 2 or (3 shl 13)
                "c.li" -> 1 or (2 shl 13); "c.lui" -> 1 or (3 shl 13); "c.lwsp" -> 2 or (2 shl 13); else -> 2
            }
            return if (name == "c.addi4spn") emitCompressedCiw(opcode, ops[0], ops[1]) else emitCompressedCi(name, opcode, ops[0], ops[1])
        }
        expect("compressed instruction")
        return false
    }

    fun emitFusedMultiplyAdd(name: String, operands: List<Operand>): Boolean {
        if (operands.size != 4) { expect("four fused multiply-add operands"); return false }
        val format = when {
            name.endsWith("_s") || name.endsWith(".s") -> 0
            name.endsWith("_d") || name.endsWith(".d") -> 1
            else -> { expect("single or double fused multiply-add"); return false }
        }
        if (name != "fmadd_s" && name != "fmadd_d" && name != "fmadd.s" && name != "fmadd.d") {
            expect("fused multiply-add instruction"); return false
        }
        return emitFloatingQuaternary(0x43 or (format shl 25) or (7 shl 12), operands[0], operands[1], operands[2], operands[3])
    }

    fun emitCsrTernary(name: String, operands: List<Operand>): Boolean {
        if (operands.size != 3) { expect("three CSR operands"); return false }
        val rd = operands[0]
        val csr = operands[1].expression.value.toInt()
        val source = operands[2]
        val function = when (name) { "csrrw" -> 1; "csrrs" -> 2; "csrrc" -> 3; "csrrwi" -> 5; "csrrsi" -> 6; "csrrci" -> 7; else -> { expect("CSR instruction"); return false } }
        val immediate = name.endsWith('i')
        val sourceField = if (immediate) source.expression.value.toInt() else source.register
        emitOpcode(0x73 or (function shl 12) or (csr shl 20) or encodeRd(rd.register) or ((sourceField and 31) shl 15))
        return true
    }

    fun emitCsrUnary(name: String, operands: List<Operand>): Boolean {
        if (operands.size != 2) { expect("two CSR operands"); return false }
        val first = operands[0]
        val second = operands[1]
        val opcode = when (name) {
            "csrr" -> 0x73 or (2 shl 12) or encodeRd(first.register) or (second.expression.value.toInt() shl 20)
            "csrw" -> 0x73 or (1 shl 12) or (first.expression.value.toInt() shl 20) or encodeRs1(second.register)
            "csrs" -> 0x73 or (2 shl 12) or (first.expression.value.toInt() shl 20) or encodeRs1(second.register)
            "csrc" -> 0x73 or (3 shl 12) or (first.expression.value.toInt() shl 20) or encodeRs1(second.register)
            "fsrm" -> 0x73 or (1 shl 12) or (2 shl 20) or encodeRd(first.register) or encodeRs1(second.register)
            "fscsr" -> 0x73 or (1 shl 12) or (3 shl 20) or encodeRd(first.register) or encodeRs1(second.register)
            "csrwi", "csrsi", "csrci" -> {
                val funct = when (name) { "csrwi" -> 5; "csrsi" -> 6; else -> 7 }
                0x73 or (funct shl 12) or (first.expression.value.toInt() shl 20) or ((second.expression.value.toInt() and 31) shl 15)
            }
            else -> { expect("CSR pseudo instruction"); return false }
        }
        emitOpcode(opcode)
        return true
    }

    fun emitS(opcode: Int, rs1: Operand, rs2: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rs1, "first source operand") || !requireRegister(rs2, "second source operand")) return false
        if (immediate.type != OP_IM12S) { error("Expected immediate value between 0 and 8191"); return false }
        val value = immediate.expression.value.toInt()
        emitOpcode(opcode or encodeRs1(rs1.register) or encodeRs2(rs2.register) or ((value and 0x1f) shl 7) or ((value ushr 5) shl 25))
        return true
    }

    fun emitB(opcode: Int, rs1: Operand, rs2: Operand, immediate: Operand,
        expandFarBranch: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> error("far branch relocation required") }): Boolean {
        if (!requireRegister(rs1, "first source operand") || !requireRegister(rs2, "destination operand")) return false
        val symbol = immediate.expression.symbol
        if (immediate.type == OP_IM32 && symbol != null) {
            val inverseFunction = ((opcode ushr 12) and 7) xor 1
            val inverseOpcode = (opcode and (7 shl 12).inv()) or (inverseFunction shl 12)
            expandFarBranch(inverseOpcode, rs1.register, rs2.register, symbol)
            return true
        }
        if (immediate.type != OP_IM12S) { error("Expected branch immediate value between 0 and 8191"); return false }
        val offset = immediate.expression.value.toInt()
        emitOpcode(opcode or encodeRs1(rs1.register) or encodeRs2(rs2.register) or (((offset ushr 1) and 0xf) shl 8) or
            (((offset ushr 5) and 0x1f) shl 25) or (((offset ushr 11) and 1) shl 7) or (((offset ushr 12) and 1) shl 31))
        return true
    }

    private fun compactRegister(operand: Operand, role: String): Int? {
        if (!requireRegister(operand, role)) return null
        val register = registerValue(operand.register) - 8
        if (register !in 0..7 || isFloatRegister(operand.register)) { error("Expected $role to use a valid C-extension register"); return null }
        return register
    }

    fun emitCompressedCa(opcode: Int, rd: Operand, rs2: Operand): Boolean {
        val dst = compactRegister(rd, "destination operand") ?: return false
        val src = compactRegister(rs2, "source operand") ?: return false
        emitLittleEndian16(opcode or encodeCompressedRs2(src) or encodeCompressedRs1(dst))
        return true
    }

    fun emitCompressedCb(name: String, opcode: Int, rs1: Operand, immediate: Operand): Boolean {
        val source = compactRegister(rs1, "source operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset and 1 != 0) { error("Expected an even immediate value"); return false }
        val encoded = if (name == "c.beqz" || name == "c.bnez") {
            ((nthBit(offset, 5) or (((offset ushr 1) and 3) shl 1) or (((offset ushr 6) and 3) shl 3)) shl 2) or
                ((((offset ushr 3) and 3) or nthBit(offset, 8)) shl 10)
        } else ((offset and 0x1f) shl 2) or (nthBit(offset, 5) shl 12)
        emitLittleEndian16(opcode or encodeCompressedRs1(source) or encoded)
        return true
    }

    fun emitCompressedCi(name: String, opcode: Int, rd: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rd, "destination operand")) return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val value = immediate.expression.value.toInt()
        val encoded = when (name) {
            "c.addi", "c.addiw", "c.li", "c.slli" -> ((value and 0x1f) shl 2) or encodeRd(rd.register) or (nthBit(value, 5) shl 12)
            "c.addi16sp" -> nthBit(value, 5) shl 2 or (((value ushr 7) and 3) shl 3) or (nthBit(value, 6) shl 5) or
                (nthBit(value, 4) shl 6) or encodeRd(rd.register) or (nthBit(value, 9) shl 12)
            "c.lui" -> (((value ushr 12) and 0x1f) shl 2) or encodeRd(rd.register) or (nthBit(value, 17) shl 12)
            "c.fldsp", "c.ldsp" -> (((value ushr 6) and 7) shl 2) or (((value ushr 3) and 2) shl 5) or encodeRd(rd.register) or (nthBit(value, 5) shl 12)
            "c.flwsp", "c.lwsp" -> (((value ushr 6) and 3) shl 2) or (((value ushr 2) and 7) shl 4) or encodeRd(rd.register) or (nthBit(value, 5) shl 12)
            "c.nop" -> 0
            else -> { expect("known compressed instruction"); return false }
        }
        emitLittleEndian16(opcode or encoded)
        return true
    }

    fun emitCompressedCiw(opcode: Int, rd: Operand, immediate: Operand): Boolean {
        val dst = compactRegister(rd, "destination operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val value = immediate.expression.value.toInt()
        if (value > 0x3fc) { error("Expected immediate value between 0 and 0x3ff"); return false }
        if (value and 3 != 0) { error("Expected non-zero immediate divisible by 4"); return false }
        val field = nthBit(value, 3) or (nthBit(value, 2) shl 1) or (((value ushr 6) and 0xf) shl 2) or (((value ushr 4) and 3) shl 6)
        emitLittleEndian16(opcode or encodeCompressedRs2(dst) or (field shl 5))
        return true
    }

    fun emitCompressedCj(opcode: Int, immediate: Operand): Boolean {
        if (immediate.type != OP_IM12S) { error("Expected 12-bit immediate value"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset and 1 != 0) { error("Expected an even immediate value"); return false }
        val encoded = (nthBit(offset, 5) shl 2) or (((offset ushr 1) and 7) shl 3) or (nthBit(offset, 7) shl 6) or
            (nthBit(offset, 6) shl 7) or (nthBit(offset, 10) shl 8) or (((offset ushr 8) and 3) shl 9) or
            (nthBit(offset, 4) shl 11) or (nthBit(offset, 11) shl 12)
        emitLittleEndian16(opcode or encoded)
        return true
    }

    private fun compressedRegisterNumber(operand: Operand, role: String): Int? {
        if (!requireRegister(operand, role)) return null
        val register = registerValue(operand.register) - 8
        if (register !in 0..7) { error("Expected $role to use a valid C-extension register"); return null }
        return register
    }

    fun emitCompressedCl(name: String, opcode: Int, rd: Operand, rs1: Operand, immediate: Operand): Boolean {
        val dst = compressedRegisterNumber(rd, "destination operand") ?: return false
        val base = compressedRegisterNumber(rs1, "source operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset > 0xff) { error("Expected immediate value between 0 and 0xff"); return false }
        if (offset and 3 != 0) { error("Expected immediate divisible by 4"); return false }
        val encoded = when (name) {
            "c.flw", "c.lw" -> (nthBit(offset, 6) shl 5) or (nthBit(offset, 2) shl 6) or (((offset ushr 3) and 7) shl 10)
            "c.fld", "c.ld" -> (((offset ushr 6) and 3) shl 5) or (((offset ushr 3) and 7) shl 10)
            else -> { expect("known compressed load"); return false }
        }
        emitLittleEndian16(opcode or encodeCompressedRs2(dst) or encodeCompressedRs1(base) or encoded)
        return true
    }

    fun emitCompressedCr(opcode: Int, rd: Operand, rs2: Operand): Boolean {
        if (!requireRegister(rd, "destination operand") || !requireRegister(rs2, "source operand")) return false
        emitLittleEndian16(opcode or encodeCompressedRs1(rd.register) or encodeCompressedRs2(rs2.register))
        return true
    }

    fun emitCompressedCs(name: String, opcode: Int, rs2: Operand, rs1: Operand, immediate: Operand): Boolean {
        val base = compressedRegisterNumber(rs1, "base operand") ?: return false
        val source = compressedRegisterNumber(rs2, "source operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset > 0xff) { error("Expected immediate value between 0 and 0xff"); return false }
        if (offset and 3 != 0) { error("Expected immediate divisible by 4"); return false }
        val encoded = when (name) {
            "c.fsw", "c.sw" -> (nthBit(offset, 6) shl 5) or (nthBit(offset, 2) shl 6) or (((offset ushr 3) and 7) shl 10)
            "c.fsd", "c.sd" -> (((offset ushr 6) and 3) shl 5) or (((offset ushr 3) and 7) shl 10)
            else -> { expect("known compressed store"); return false }
        }
        emitLittleEndian16(opcode or encodeCompressedRs2(base) or encodeCompressedRs1(source) or encoded)
        return true
    }

    fun emitCompressedCss(name: String, opcode: Int, rs2: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rs2, "destination operand")) return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset > 0xff) { error("Expected immediate value between 0 and 0xff"); return false }
        if (offset and 3 != 0) { error("Expected immediate divisible by 4"); return false }
        val encoded = when (name) {
            "c.fswsp", "c.swsp" -> (((offset ushr 6) and 3) shl 7) or (((offset ushr 2) and 0xf) shl 9)
            "c.fsdsp", "c.sdsp" -> (((offset ushr 6) and 7) shl 7) or (((offset ushr 3) and 7) shl 10)
            else -> { expect("known compressed stack store"); return false }
        }
        emitLittleEndian16(opcode or (registerValue(rs2.register) shl 2) or encoded)
        return true
    }

    fun emitTernaryInstruction(name: String, operands: List<Operand>): Boolean {
        if (operands.size != 3) { expect("three operands"); return false }
        val (rd, rs1, rs2) = operands
        val rOpcodes = mapOf(
            "sll" to (0x33 or (1 shl 12)), "srl" to (0x33 or (5 shl 12)), "sra" to (0x33 or (5 shl 12) or (0x20 shl 25)),
            "sllw" to (0x3b or (1 shl 12)), "srlw" to (0x3b or (5 shl 12)), "sraw" to (0x3b or (5 shl 12)),
            "add" to 0x33, "sub" to (0x33 or (0x20 shl 25)), "addw" to 0x3b,
            "subw" to (0x3b or (0x20 shl 25)), "xor" to (0x33 or (4 shl 12)),
            "or" to (0x33 or (6 shl 12)), "and" to (0x33 or (7 shl 12)),
            "slt" to (0x33 or (2 shl 12)), "sltu" to (0x33 or (3 shl 12)),
            "mul" to (0x33 or (1 shl 25)), "mulh" to (0x33 or (1 shl 12) or (1 shl 25)),
            "mulhsu" to (0x33 or (2 shl 12) or (1 shl 25)), "mulhu" to (0x33 or (3 shl 12) or (1 shl 25)),
            "mulw" to (0x3b or (1 shl 25)), "div" to (0x33 or (4 shl 12) or (1 shl 25)),
            "divu" to (0x33 or (5 shl 12) or (1 shl 25)), "divw" to (0x3b or (4 shl 12) or (1 shl 25)),
            "divuw" to (0x3b or (5 shl 12) or (1 shl 25)), "rem" to (0x33 or (6 shl 12) or (1 shl 25)),
            "remu" to (0x33 or (7 shl 12) or (1 shl 25)), "remw" to (0x3b or (6 shl 12) or (1 shl 25)),
            "remuw" to (0x3b or (7 shl 12) or (1 shl 25)),
        )
        rOpcodes[name]?.let { return emitR(it, rd, rs1, rs2) }
        val iOpcodes = mapOf(
            "slli" to (0x13 or (1 shl 12)), "srli" to (0x13 or (5 shl 12)), "srai" to (0x13 or (5 shl 12) or (16 shl 26)),
            "slliw" to (0x1b or (1 shl 12)), "srliw" to (0x1b or (5 shl 12)), "sraiw" to (0x1b or (5 shl 12)),
            "addi" to 0x13, "addiw" to 0x1b, "xori" to (0x13 or (4 shl 12)), "ori" to (0x13 or (6 shl 12)),
            "andi" to (0x13 or (7 shl 12)), "slti" to (0x13 or (2 shl 12)), "sltiu" to (0x13 or (3 shl 12)),
        )
        iOpcodes[name]?.let { return emitI(it, rd, rs1, rs2) }
        expect("ternary instruction")
        return false
    }

    fun parseRoundingMode(name: String?): Int = when (name) {
        null -> 7; "rne" -> 0; "rtz" -> 1; "rdn" -> 2; "rup" -> 3; "rmm" -> 4
        else -> { expect("rounding mode"); 7 }
    }

    fun emitFloatingInstruction(name: String, operands: List<Operand>, roundingMode: Int = 7): Boolean {
        if (operands.size != 2 && operands.size != 3) { expect("two or three floating point operands"); return false }
        val rd = operands[0]
        val rs1 = operands[1]
        val format = if (name.endsWith("_d") || name.endsWith(".d")) 1 else 0
        val simple = mapOf("fadd" to (0 to 7), "fsub" to (1 to 7), "fmul" to (2 to 7), "fdiv" to (3 to 7),
            "fsgnj" to (4 to 0), "fmin" to (5 to 0), "fmax" to (5 to 1))
        val operation = name.removeSuffix("_s").removeSuffix("_d").removeSuffix(".s").removeSuffix(".d")
        if (operation == "fsqrt") {
            if (operands.size != 2) { expect("two floating point operands"); return false }
            return emitFloatingUnary(0x53 or (11 shl 27) or (format shl 25) or (7 shl 12), rd, rs1)
        }
        if (operation in setOf("fneg", "fmv", "fabs")) {
            val function3 = when (operation) { "fneg" -> 1; "fmv" -> 0; else -> 2 }
            val function5 = if (operation == "fabs") 4 else 4
            val opcode = 0x53 or (function5 shl 27) or (format shl 25) or (function3 shl 12)
            return emitFloating(opcode, rd, rs1, rs1)
        }
        val comparison = mapOf("feq" to 2, "flt" to 1, "fle" to 0)
        if (operation in comparison && operands.size == 3) {
            val rs2 = operands[2]
            val opcode = 0x53 or (0x14 shl 27) or (format shl 25) or (comparison.getValue(operation) shl 12)
            emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register))
            return true
        }
        val encoding = simple[operation]
        if (encoding != null && operands.size == 3) {
            val opcode = 0x53 or (encoding.first shl 27) or (format shl 25) or (encoding.second shl 12)
            return emitFloating(opcode, rd, rs1, operands[2])
        }
        val convert = conversionEncoding(name.replace('_', '.')) ?: run { expect("floating point instruction"); return false }
        val rm = if (convert.third < 0) roundingMode else convert.third
        val opcode = 0x53 or (convert.first shl 25) or (rm shl 12) or (convert.second shl 20)
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register))
        return true
    }

    private fun conversionEncoding(name: String): Triple<Int, Int, Int>? {
        val encodings = mapOf(
            "fcvt.w.s" to Triple(0x60, 0, -1), "fcvt.wu.s" to Triple(0x60, 1, -1),
            "fcvt.l.s" to Triple(0x60, 2, -1), "fcvt.lu.s" to Triple(0x60, 3, -1),
            "fcvt.w.d" to Triple(0x61, 0, -1), "fcvt.wu.d" to Triple(0x61, 1, -1),
            "fcvt.l.d" to Triple(0x61, 2, -1), "fcvt.lu.d" to Triple(0x61, 3, -1),
            "fcvt.s.w" to Triple(0x68, 0, 7), "fcvt.s.wu" to Triple(0x68, 1, 7),
            "fcvt.s.l" to Triple(0x68, 2, 7), "fcvt.s.lu" to Triple(0x68, 3, 7),
            "fcvt.d.w" to Triple(0x69, 0, 7), "fcvt.d.wu" to Triple(0x69, 1, 7),
            "fcvt.d.l" to Triple(0x69, 2, 7), "fcvt.d.lu" to Triple(0x69, 3, 7),
            "fcvt.s.d" to Triple(0x20, 1, 7), "fcvt.d.s" to Triple(0x21, 0, 7),
            "fclass.s" to Triple(0x70, 0, 1), "fclass.d" to Triple(0x71, 0, 1),
        )
        return encodings[name]
    }

    fun emitBinaryInstruction(name: String, rd: Operand, source: Operand): Boolean = when (name) {
        "lui" -> emitU(0x37, rd, source)
        "auipc" -> emitU(0x17, rd, source)
        "c.add" -> emitCompressedCr(2 or (9 shl 12), rd, source)
        "c.mv" -> emitCompressedCr(2 or (8 shl 12), rd, source)
        "c.addi" -> emitCompressedCi(name, 1, rd, source)
        "c.addiw" -> emitCompressedCi(name, 1 or (1 shl 13), rd, source)
        "c.addi16sp" -> emitCompressedCi(name, 1 or (3 shl 13), rd, source)
        "c.fldsp" -> emitCompressedCi(name, 2 or (1 shl 13), rd, source)
        "c.flwsp", "c.ldsp" -> emitCompressedCi(name, 2 or (3 shl 13), rd, source)
        "c.li" -> emitCompressedCi(name, 1 or (2 shl 13), rd, source)
        "c.lui" -> emitCompressedCi(name, 1 or (3 shl 13), rd, source)
        "c.lwsp" -> emitCompressedCi(name, 2 or (2 shl 13), rd, source)
        "c.slli" -> emitCompressedCi(name, 2, rd, source)
        "c.addi4spn" -> emitCompressedCiw(0, rd, source)
        else -> { expect("binary instruction"); false }
    }

    fun emitPseudoBinary(name: String, operands: List<Operand>, relocateAddress: (String) -> Unit = {}): Boolean {
        if (operands.size != 2) { expect("two pseudo instruction operands"); return false }
        val (rd, source) = operands
        val zero = Operand(OP_REG)
        val immediateZero = Operand(OP_IM12S)
        when (name) {
            "li" -> {
                if (source.type != OP_IM32 && source.type != OP_IM12S) { error("Expected immediate source operand"); return false }
                val low = source.expression.value.toInt()
                var high = (source.expression.value shr 32).toInt()
                if (low < 0) high++
                var immediate = ((high + 0x800) and -0x1000) shr 12
                if (!emitU(0x37, rd, Operand(OP_IM12S, expression = Expression(immediate.toLong())))) return false
                immediate = (high shl 20) shr 20
                if (!emitI(0x13, rd, rd, Operand(OP_IM12S, expression = Expression(immediate.toLong())))) return false
                if (!emitI(0x1013, rd, rd, Operand(OP_IM12S, expression = Expression(12)))) return false
                immediate = (low + (1 shl 19)) shr 20
                if (!emitI(0x13, rd, rd, Operand(OP_IM12S, expression = Expression(immediate.toLong())))) return false
                if (!emitI(0x1013, rd, rd, Operand(OP_IM12S, expression = Expression(12)))) return false
                val lowTwenty = (low shl 12) shr 12
                if (!emitI(0x13, rd, rd, Operand(OP_IM12S, expression = Expression((lowTwenty shr 8).toLong())))) return false
                if (!emitI(0x1013, rd, rd, Operand(OP_IM12S, expression = Expression(8)))) return false
                val lowEight = lowTwenty and 0xff
                return emitI(0x13, rd, rd, Operand(OP_IM12S, expression = Expression(((lowEight shl 20) shr 20).toLong())))
            }
            "mv" -> return emitI(0x13, rd, source, immediateZero)
            "not" -> return emitI(0x4013, rd, source, Operand(OP_IM12S, expression = Expression(-1)))
            "neg" -> return emitR(0x40000033, rd, zero, source)
            "negw" -> return emitR(0x4000003b, rd, zero, source)
            "sext.w" -> return emitI(0x1b, rd, source, immediateZero)
            "seqz" -> return emitI(0x3013, rd, source, Operand(OP_IM12S, expression = Expression(1)))
            "snez" -> return emitR(0x3033, rd, zero, source)
            "sltz" -> return emitR(0x2033, rd, source, zero)
            "sgtz" -> return emitR(0x2033, rd, zero, source)
            "fabs.s", "fabs.d", "fneg.s", "fneg.d", "fmv.s", "fmv.d" -> {
                val format = if (name.endsWith(".d")) 1 else 0
                val funct3 = when { name.startsWith("fneg") -> 1; name.startsWith("fabs") -> 2; else -> 0 }
                return emitFloating(0x53 or (4 shl 27) or (format shl 25) or (funct3 shl 12), rd, source, source)
            }
            "csrr", "csrw", "csrs", "csrc", "csrwi", "csrsi", "csrci", "fsrm", "fscsr" -> return emitCsrUnary(name, operands)
            "la", "lla" -> {
                val symbol = source.expression.symbol
                if (symbol == null) { error("Expected address symbol"); return false }
                relocateAddress(symbol)
                if (!emitU(0x17, rd, immediateZero)) return false
                return emitI(if (name == "la") 0x2003 else 0x13, rd, rd, immediateZero)
            }
            else -> { expect("binary pseudo instruction"); return false }
        }
    }

    fun emitMemoryInstruction(name: String, operands: List<Operand>, isStaticSymbol: (String) -> Boolean = { false },
        relocateAddress: (String) -> Unit = {}): Boolean {
        if (operands.size != 3) { expect("memory access operands"); return false }
        val (destination, parsedBase, parsedOffset) = operands
        var base = parsedBase
        var offset = parsedOffset
        val symbol = base.expression.symbol
        if (base.type == OP_IM32 && symbol != null && isStaticSymbol(symbol)) {
            relocateAddress(symbol)
            base = destination
            offset = Operand(OP_IM12S)
            if (!emitU(0x17, destination, Operand(OP_IM12S))) return false
        }
        val loads = mapOf("lb" to 0x03, "lh" to 0x1003, "lw" to 0x2003, "ld" to 0x3003,
            "lbu" to 0x4003, "lhu" to 0x5003, "lwu" to 0x6003, "fld" to 0x3007)
        loads[name]?.let { return emitI(it, destination, base, offset) }
        val stores = mapOf("sb" to 0x23, "sh" to 0x1023, "sw" to 0x2023, "sd" to 0x3023, "fsd" to 0x3027)
        stores[name]?.let { return emitS(it, base, destination, offset) }
        expect("memory access instruction")
        return false
    }

    fun emitBranchInstruction(name: String, operands: List<Operand>,
        expandFarBranch: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> error("far branch relocation required") }): Boolean {
        val zero = Operand(OP_REG, register = 0)
        val offset: Operand
        var first: Operand
        var second: Operand
        val comparison = when (name) {
            "beq", "beqz" -> 0; "bne", "bnez" -> 1; "blt", "bgt", "bltz", "bgtz" -> 4
            "bge", "ble", "blez", "bgez" -> 5; "bltu", "bgtu" -> 6; "bgeu", "bleu" -> 7
            else -> { expect("branch instruction"); return false }
        }
        if (name in setOf("beqz", "bnez", "blez", "bgez", "bltz", "bgtz")) {
            if (operands.size != 2) { expect("two branch operands"); return false }
            first = operands[0]; offset = operands[1]
            second = if (name == "bgez" || name == "bgtz") first.also { first = zero } else zero
        } else {
            if (operands.size != 3) { expect("three branch operands"); return false }
            first = operands[0]; second = operands[1]; offset = operands[2]
            if (name in setOf("bgt", "ble", "bgtu", "bleu")) { val swap = first; first = second; second = swap }
        }
        return emitB(0x63 or (comparison shl 12), first, second, offset, expandFarBranch)
    }
}
