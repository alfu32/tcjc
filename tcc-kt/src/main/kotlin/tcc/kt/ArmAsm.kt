package tcc.kt

private fun parseArmInteger(source: String): Int {
    val text = source.trim().replace("_", "")
    val sign = if (text.startsWith('-')) -1 else 1
    val digits = text.removePrefix("-").removePrefix("+")
    val radix = when {
        digits.startsWith("0x", true) -> 16
        digits.startsWith("0b", true) -> 2
        digits.length > 1 && digits.startsWith('0') -> 8
        else -> 10
    }
    val number = digits.removePrefix("0x").removePrefix("0X").removePrefix("0b").removePrefix("0B")
    return sign * number.toLong(radix).toInt()
}

/** ARM assembler operand decoding and instruction encoding from arm-asm.c. */
class ArmAsm(
    private val output: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val expect: (String) -> Unit = { throw IllegalArgumentException("expected $it") },
    private val error: (String) -> Unit = { throw IllegalArgumentException(it) },
    private val warning: (String) -> Unit = {},
    private val expression: (String) -> Expression = { Expression(parseArmInteger(it)) },
) {
    enum class Kind { REG32, REGSET32, IMM8, IMM8N, IMM32, VREG32, VREG64 }
    data class Expression(val value: Int, val symbol: String? = null)
    data class Operand(
        var kind: Kind = Kind.IMM32, var register: Int = 0,
        var registerSet: Int = 0, var value: Expression = Expression(0),
    )

    fun g(value: Int) { if (!noCode()) output(value and 0xff) }
    fun genLe16(value: Int) { g(value); g(value ushr 8) }
    fun genLe32(value: Int) { g(value); g(value ushr 8); g(value ushr 16); g(value ushr 24) }
    fun genExpr32(value: Expression) { genLe32(value.value) }

    /** Reads ARM core, VFP, or register-list operands from AT&T-free ARM syntax. */
    fun parseOperand(source: String): Operand {
        val text = source.trim()
        if (text.startsWith('{') && text.endsWith('}')) {
            val names = text.substring(1, text.length - 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) error("empty register list is not supported")
            var set = 0
            var previous = -1
            names.forEach { name ->
                val register = coreRegister(name)
                if (register < 0) expect("register")
                if (previous >= 0 && register < previous)
                    warning("registers will be processed in ascending order by hardware--but are not specified in ascending order here")
                set = set or (1 shl register)
                previous = register
            }
            return Operand(Kind.REGSET32, registerSet = set)
        }
        coreRegister(text).takeIf { it >= 0 }?.let { return Operand(Kind.REG32, register = it) }
        Regex("s([0-9]|[12][0-9]|3[01])").matchEntire(text)?.let {
            return Operand(Kind.VREG32, register = it.groupValues[1].toInt())
        }
        Regex("d([0-9]|1[0-5])").matchEntire(text)?.let {
            return Operand(Kind.VREG64, register = it.groupValues[1].toInt())
        }
        val constant = if (text.startsWith('#') || text.startsWith('$')) text.drop(1) else text
        val result = try { expression(constant) } catch (_: RuntimeException) { expect("operand"); return Operand() }
        if (result.symbol != null) expect("operand")
        val number = result.value
        val kind = when {
            number < 0 && number >= -255 -> Kind.IMM8N
            number in 0..255 -> Kind.IMM8
            else -> Kind.IMM32
        }
        return Operand(kind, value = result)
    }

    private fun coreRegister(name: String): Int = when (name.lowercase()) {
        "sp" -> 13
        "lr" -> 14
        "pc" -> 15
        else -> Regex("r([0-9]|1[0-5])").matchEntire(name.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    fun conditionCode(token: Int, firstConditionToken: Int): Int {
        if (token < firstConditionToken) { expect("condition-enabled instruction"); return 0 }
        return (token - firstConditionToken) and 15
    }

    fun emitOpcode(token: Int, firstConditionToken: Int, opcode: Int) =
        genLe32((conditionCode(token, firstConditionToken) shl 28) or opcode)

    fun emitUnconditionalOpcode(opcode: Int) = genLe32(opcode)

    /** Encodes a coprocessor data-processing or register-transfer instruction word. */
    fun emitCoprocessorOpcode(
        highNibble: Int, coprocessor: Int, opcode: Int, destination: Int,
        nOperand: Int, mOperand: Int, opcode2: Int, interProcessorTransfer: Boolean,
    ) {
        var word = 0x0e000000
        if (interProcessorTransfer) word = word or (1 shl 4)
        word = word or (opcode shl 20) or (nOperand shl 16) or (destination shl 12)
        word = word or (coprocessor shl 8) or (opcode2 shl 5) or mOperand
        emitUnconditionalOpcode((highNibble shl 28) or word)
    }

    fun emitNullary(group: String, token: Int, firstConditionToken: Int) {
        when (group) {
            "nop" -> emitOpcode(token, firstConditionToken, 0xd shl 21)
            "wfe" -> emitOpcode(token, firstConditionToken, 0x0320f002)
            "wfi" -> emitOpcode(token, firstConditionToken, 0x0320f003)
            else -> expect("nullary instruction")
        }
    }

    fun emitUnary(group: String, token: Int, firstConditionToken: Int, operand: Operand) {
        if (group != "swi" && group != "svc") { expect("unary instruction"); return }
        if (operand.kind != Kind.IMM8) { expect("immediate 8-bit unsigned integer"); return }
        emitOpcode(token, firstConditionToken, (0xf shl 24) or operand.value.value)
    }
}
