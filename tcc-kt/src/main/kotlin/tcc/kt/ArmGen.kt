package tcc.kt

/** ARM code-generation constants and pure instruction helpers transcribed from arm-gen.c. */
object ArmGen {
    const val RC_INT = 0x0001
    const val RC_FLOAT = 0x0002
    const val RC_R0 = 0x0004
    const val RC_R1 = 0x0008
    const val RC_R2 = 0x0010
    const val RC_R3 = 0x0020
    const val RC_R12 = 0x0040
    const val RC_F0 = 0x0080
    const val RC_F1 = 0x0100
    const val RC_F2 = 0x0200
    const val RC_F3 = 0x0400
    const val RC_F4 = 0x0800
    const val RC_F5 = 0x1000
    const val RC_F6 = 0x2000
    const val RC_F7 = 0x4000

    const val TREG_R0 = 0
    const val TREG_R1 = 1
    const val TREG_R2 = 2
    const val TREG_R3 = 3
    const val TREG_R12 = 4
    const val TREG_F0 = 5
    const val TREG_F1 = 6
    const val TREG_F2 = 7
    const val TREG_F3 = 8
    const val TREG_F4 = 9
    const val TREG_F5 = 10
    const val TREG_F6 = 11
    const val TREG_F7 = 12
    const val TREG_SP = 13
    const val TREG_LR = 14

    fun targetMachineDefinitions(eabi: Boolean): List<String> = buildList {
        addAll(listOf("__arm__", "__arm", "arm", "__arm_elf__", "__arm_elf", "arm_elf",
            "__ARM_ARCH_4__", "__ARMEL__", "__APCS_32__"))
        if (eabi) add("__ARM_EABI__")
    }

    fun registerClasses(vfp: Boolean): IntArray = buildList {
        addAll(listOf(RC_INT or RC_R0, RC_INT or RC_R1, RC_INT or RC_R2, RC_INT or RC_R3,
            RC_INT or RC_R12, RC_FLOAT or RC_F0, RC_FLOAT or RC_F1, RC_FLOAT or RC_F2, RC_FLOAT or RC_F3))
        if (vfp) addAll(listOf(RC_FLOAT or RC_F4, RC_FLOAT or RC_F5, RC_FLOAT or RC_F6, RC_FLOAT or RC_F7))
    }.toIntArray()

    fun twoToMask(a: Int, b: Int, classes: IntArray): Int {
        require(a in 0..14 && b in 0..14) { "compiler error! registers $a,$b is not valid" }
        return (classes[a] or classes[b]) and (RC_INT or RC_FLOAT).inv()
    }

    fun registerMask(register: Int, classes: IntArray): Int {
        require(register in 0..14) { "compiler error! register $register is not valid" }
        return classes[register] and (RC_INT or RC_FLOAT).inv()
    }

    /** Encodes an ARM rotated-byte immediate, or returns zero when no encoding exists. */
    fun stuffConstant(opcode: Int, constant: Int): Int {
        var op = opcode
        var value = constant
        var tryNegative = 0
        var negativeOpcode = 0
        var negativeValue = 0
        when (op and 0x01f00000) {
            0x00800000, 0x00400000 -> {
                tryNegative = 1
                negativeOpcode = op xor 0x00c00000
                negativeValue = -value
            }
            0x01a00000, 0x01e00000 -> {
                tryNegative = 1
                negativeOpcode = op xor 0x00400000
                negativeValue = value.inv()
            }
            0x00200000 -> if (value == -1) return (op and 0xf010f000.toInt()) or ((op ushr 16) and 15) or 0x01e00000
            0x00000000 -> if (value == -1) return (op and 0xf010f000.toInt()) or ((op ushr 16) and 15) or 0x01a00000
            0x01c00000 -> {
                tryNegative = 1
                negativeOpcode = op xor 0x01c00000
                negativeValue = value.inv()
            }
            0x01800000 -> if (value == -1) return (op and 0xfff0ffff.toInt()) or 0x01e00000
        }
        do {
            if (value in 0..255) return op or value
            for (rotation in 2 until 32 step 2) {
                val mask = (0xff ushr rotation) or (0xff shl (32 - rotation))
                if (value and mask.inv() == 0)
                    return op or (rotation shl 7) or (value shl rotation) or (value ushr (32 - rotation))
            }
            op = negativeOpcode
            value = negativeValue
        } while (tryNegative-- > 0)
        return 0
    }

    /** Expands larger add/sub constants into a sequence of ARM data-processing words. */
    fun stuffConstantHarder(opcode: Int, value: Int): List<Int> {
        val direct = stuffConstant(opcode, value)
        if (direct != 0) return listOf(direct)
        val masks = IntArray(16)
        masks[0] = 0xff
        val secondOpcode = (opcode and 0xfff0ffff.toInt()) or ((opcode and 0xf000) shl 4)
        for (i in 1 until masks.size) masks[i] = (masks[i - 1] ushr 2) or (masks[i - 1] shl 30)
        for (i in 0 until 12) {
            for (j in (if (i < 4) i + 12 else 15) downTo i + 4) {
                if (value and (masks[i] or masks[j]) == value)
                    return listOf(stuffConstant(opcode, value and masks[i]), stuffConstant(secondOpcode, value and masks[j]))
            }
        }
        val negativeOpcode = opcode xor 0x00c00000
        val negativeSecondOpcode = secondOpcode xor 0x00c00000
        val negativeValue = -value
        for (i in 0 until 12) {
            for (j in (if (i < 4) i + 12 else 15) downTo i + 4) {
                if (negativeValue and (masks[i] or masks[j]) == negativeValue)
                    return listOf(stuffConstant(negativeOpcode, negativeValue and masks[i]), stuffConstant(negativeSecondOpcode, negativeValue and masks[j]))
            }
        }
        for (i in 0 until 8) {
            for (j in i + 4 until 12) {
                for (k in (if (i < 4) i + 12 else 15) downTo j + 4) {
                    if (value and (masks[i] or masks[j] or masks[k]) == value)
                        return listOf(stuffConstant(opcode, value and masks[i]), stuffConstant(secondOpcode, value and masks[j]), stuffConstant(secondOpcode, value and masks[k]))
                }
            }
        }
        for (i in 0 until 8) {
            for (j in i + 4 until 12) {
                for (k in (if (i < 4) i + 12 else 15) downTo j + 4) {
                    if (negativeValue and (masks[i] or masks[j] or masks[k]) == negativeValue)
                        return listOf(stuffConstant(negativeOpcode, negativeValue and masks[i]), stuffConstant(negativeSecondOpcode, negativeValue and masks[j]), stuffConstant(negativeSecondOpcode, negativeValue and masks[k]))
                }
            }
        }
        return listOf(stuffConstant(opcode, value and masks[0]), stuffConstant(secondOpcode, value and masks[4]),
            stuffConstant(secondOpcode, value and masks[8]), stuffConstant(secondOpcode, value and masks[12]))
    }

    data class AddressCalculation(val base: Int, val offset: Int, val sign: Int, val words: List<Int>)

    /** Materializes an out-of-range or unaligned address offset through LR. */
    fun calculateAddress(base: Int, offset: Int, sign: Int, maxOffset: Int, shift: Int): AddressCalculation {
        var newBase = base
        var newOffset = offset
        var newSign = sign
        val words = mutableListOf<Int>()
        if (newOffset > maxOffset || newOffset and ((1 shl shift) - 1) != 0) {
            var opcode = if (newSign != 0) 0xe240e000.toInt() else 0xe280e000.toInt()
            opcode = opcode or (newBase shl 16)
            newBase = 14
            var encoded = stuffConstant(opcode, newOffset and maxOffset.inv())
            if (encoded != 0) {
                words += encoded
                newOffset = newOffset and maxOffset
                return AddressCalculation(newBase, newOffset, newSign, words)
            }
            encoded = stuffConstant(opcode, (newOffset + maxOffset) and maxOffset.inv())
            if (encoded != 0) {
                words += encoded
                newSign = if (newSign == 0) 1 else 0
                newOffset = ((newOffset + maxOffset) and maxOffset.inv()) - newOffset
                return AddressCalculation(newBase, newOffset, newSign, words)
            }
            words += stuffConstantHarder(opcode, newOffset and maxOffset.inv())
            newOffset = newOffset and maxOffset
        }
        return AddressCalculation(newBase, newOffset, newSign, words)
    }

    fun integerRegister(register: Int): Int = when {
        register == TREG_R12 -> 12
        register in TREG_R0..TREG_R3 -> register - TREG_R0
        register in TREG_SP..TREG_LR -> register + (13 - TREG_SP)
        else -> throw IllegalArgumentException("compiler error! register $register is no int register")
    }

    fun floatingRegister(register: Int, vfp: Boolean): Int {
        val limit = if (vfp) TREG_F7 else TREG_F3
        require(register in TREG_F0..limit) {
            "compiler error! register $register is no ${if (vfp) "vfp" else "fpa"} register"
        }
        return register - TREG_F0
    }

    data class Symbol(val name: String, val isStatic: Boolean = false)
    data class ConstantValue(val value: Int, val symbol: Symbol? = null)

    /** Emits the literal-pool and relocation sequence used to load a C value into an ARM register. */
    fun emitLoadValue(
        value: ConstantValue, register: Int, cpuVersion: Int, pic: Boolean,
        output: (Int) -> Unit, currentPosition: () -> Int,
        relocate: (Symbol, Int, String) -> Unit,
    ) {
        val armRegister = integerRegister(register)
        if (cpuVersion >= 7 && value.symbol == null) {
            val constant = value.value
            output(0xe3000000.toInt() or (armRegister shl 12) or (constant and 0xfff) or ((constant shl 4) and 0xf0000))
            if (constant and 0xffff0000.toInt() != 0)
                output(0xe3400000.toInt() or (armRegister shl 12) or ((constant ushr 16) and 0xfff) or ((constant ushr 12) and 0xf0000))
            return
        }
        output(0xe59f0000.toInt() or (armRegister shl 12))
        output(0xea000000.toInt())
        val symbol = value.symbol
        if (!pic) {
            if (symbol != null) relocate(symbol, currentPosition(), "R_ARM_ABS32")
            output(value.value)
            return
        }
        if (symbol == null) {
            output(value.value)
        } else if (symbol.isStatic) {
            relocate(symbol, currentPosition(), "R_ARM_REL32")
            output(value.value - 12)
            output(0xe080000f.toInt() or (armRegister shl 12) or (armRegister shl 16))
        } else {
            relocate(symbol, currentPosition(), "R_ARM_GOT_PREL")
            output(-12)
            output(0xe080000f.toInt() or (armRegister shl 12) or (armRegister shl 16))
            output(0xe5900000.toInt() or (armRegister shl 12) or (armRegister shl 16))
            if (value.value != 0) {
                stuffConstantHarder(0xe2800000.toInt() or (armRegister shl 12) or (armRegister shl 16), value.value).forEach(output)
            }
        }
    }

    /** Encodes a PC-relative ARM branch displacement. */
    fun encodeBranch(position: Int, address: Int, fail: Boolean, error: (String) -> Unit = { throw IllegalArgumentException(it) }): Int {
        val displacement = address - position - 8
        val words = displacement / 4
        if (words >= 0x1000000 || words < -0x1000000) {
            if (fail) error("FIXME: function bigger than 32MB")
            return 0
        }
        return 0x0a000000 or (words and 0xffffff)
    }

    fun decodeBranch(position: Int, instruction: Int): Int {
        var displacement = instruction and 0x00ffffff
        if (displacement and 0x00800000 != 0) displacement -= 0x01000000
        return displacement * 4 + position + 8
    }

    enum class Condition { ULT, UGE, EQ, NE, ULE, UGT, NEGATIVE, NON_NEGATIVE, LT, GE, LE, GT }

    fun mapCondition(condition: Condition): Int = when (condition) {
        Condition.ULT -> 0x30000000; Condition.UGE -> 0x20000000; Condition.EQ -> 0x00000000; Condition.NE -> 0x10000000
        Condition.ULE -> 0x90000000.toInt(); Condition.UGT -> 0x80000000.toInt(); Condition.NEGATIVE -> 0x40000000; Condition.NON_NEGATIVE -> 0x50000000
        Condition.LT -> 0xb0000000.toInt(); Condition.GE -> 0xa0000000.toInt(); Condition.LE -> 0xd0000000.toInt(); Condition.GT -> 0xc0000000.toInt()
        else -> throw IllegalArgumentException("unexpected condition code")
    }

    fun negateCondition(condition: Condition): Condition = when (condition) {
        Condition.ULT -> Condition.UGE; Condition.UGE -> Condition.ULT
        Condition.EQ -> Condition.NE; Condition.NE -> Condition.EQ
        Condition.ULE -> Condition.UGT; Condition.UGT -> Condition.ULE
        Condition.NEGATIVE -> Condition.NON_NEGATIVE; Condition.NON_NEGATIVE -> Condition.NEGATIVE
        Condition.LT -> Condition.GE; Condition.GE -> Condition.LT
        Condition.LE -> Condition.GT; Condition.GT -> Condition.LE
    }

    /** Patches a linked list of unresolved B instructions to the final address. */
    fun patchBranchChain(code: ByteArray, head: Int, address: Int) {
        var patch = head
        while (patch != 0) {
            val instruction = readLe32(code, patch)
            val next = decodeBranch(patch, instruction)
            if (address == patch + 4) writeLe32(code, patch, 0xe1a00000.toInt())
            else writeLe32(code, patch, (instruction and -0x1000000) or encodeBranch(patch, address, true))
            patch = next
        }
    }

    private fun readLe32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)

    private fun writeLe32(bytes: ByteArray, at: Int, value: Int) {
        repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() }
    }
}
