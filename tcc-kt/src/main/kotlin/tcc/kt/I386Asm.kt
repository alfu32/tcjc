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

    fun immediate(value: Int): Operand {
        var type = OP_IM32
        if (value == (value.toByte().toInt())) type = type or OP_IM8
        if (value == value.toByte().toInt()) type = type or OP_IM8S
        if (value == (value.toShort().toInt())) type = type or OP_IM16
        return Operand(type, expression = Expression(value))
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
