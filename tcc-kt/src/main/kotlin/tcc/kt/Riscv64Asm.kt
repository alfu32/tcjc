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
}
