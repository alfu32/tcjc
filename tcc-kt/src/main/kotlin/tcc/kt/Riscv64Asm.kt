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
}
