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

    companion object {
        const val REGISTER_COUNT = 64
        const val REG_FLOAT_MASK = 0x20
        const val OPT_REG = 0
        const val OPT_IM12S = 1
        const val OPT_IM32 = 2
        const val OP_REG = 1 shl OPT_REG
        const val OP_IM12S = 1 shl OPT_IM12S
        const val OP_IM32 = 1 shl OPT_IM32

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
        if (name == "call" || name == "tail") {
            val symbol = operand.expression.symbol
            if (symbol == null) { error("Expected call target symbol"); return false }
            val temporary = if (name == "call") 1 else 6
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
            operand = operand.copy(type = OP_IM12S, expression = Expression(0))
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
    fun parseMemoryAccessOperands(source: String): List<Operand> {
        val parts = splitOperands(source)
        if (parts.size != 2) { expect("memory access operands"); return listOf(Operand(), Operand(), Operand()) }
        val destination = parseOperand(parts[0])
        val address = parts[1].trim()
        val open = address.indexOf('(')
        if (address.startsWith('(') && address.endsWith(')')) {
            val base = parseOperand(address.substring(1, address.length - 1))
            return listOf(destination, base, Operand(OP_IM12S))
        }
        if (open >= 0 && address.endsWith(')')) {
            val immediate = parseOperand(address.substring(0, open).ifBlank { "0" })
            val base = parseOperand(address.substring(open + 1, address.length - 1))
            return listOf(destination, base, immediate)
        }
        val base = parseOperand(address)
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
}
