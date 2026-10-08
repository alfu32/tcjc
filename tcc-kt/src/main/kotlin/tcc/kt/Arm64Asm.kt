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

    fun emitInstruction(word: Int) { if (!noCode()) output(word) }
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

    fun emitBranch(offset: Int, link: Boolean = false) = emitInstruction((if (link) 0x94000000.toInt() else 0x14000000) or ((offset shr 2) and 0x03ffffff))
    fun emitBranchRegister(register: Int, link: Boolean = false) = emitInstruction((if (link) 0xd63f0000.toInt() else 0xd61f0000.toInt()) or ((register and 31) shl 5))
    fun emitReturn(register: Int = 30) = emitInstruction(0xd65f0000.toInt() or ((register and 31) shl 5))
    fun emitConditionalBranch(condition: Int, offset: Int) = emitInstruction(0x54000000 or (((offset shr 2) and 0x7ffff) shl 5) or (condition and 15))
    fun emitCompareBranch(register: Int, offset: Int, is64Bit: Boolean, nonZero: Boolean = false) =
        emitInstruction((if (nonZero) 0x35000000 else 0x34000000) or (if (is64Bit) 0x80000000.toInt() else 0) or (((offset shr 2) and 0x7ffff) shl 5) or (register and 31))
    fun emitMoveRegister(destination: Int, source: Int, is64Bit: Boolean) =
        emitInstruction((if (is64Bit) 0xaa0003e0.toInt() else 0x2a0003e0) or ((source and 31) shl 16) or (destination and 31))
}
