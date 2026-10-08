package tcc.kt

/** ARM code-generation constants and pure instruction helpers transcribed from arm-gen.c. */
object ArmGen {
    data class TargetConfiguration(val eabi: Boolean = false, val vfp: Boolean = false, val cpuVersion: Int? = null,
        val hardFloat: Boolean = false, val longDoubleSize: Int = 8, val longDoubleAlignment: Int = 4)

    fun initializeTarget(configuration: TargetConfiguration): TargetConfiguration {
        require(!configuration.eabi || configuration.vfp) {
            "Currently TinyCC only supports float computation with VFP instructions"
        }
        return configuration.copy(cpuVersion = configuration.cpuVersion ?: 5,
            longDoubleSize = if (configuration.vfp) 8 else configuration.longDoubleSize,
            longDoubleAlignment = if (configuration.eabi) 8 else 4)
    }

    fun availableRegisterCount(vfp: Boolean): Int = if (vfp) 13 else 9

    fun vfpCoprocessorTypeBit(type: ValueType): Int = if (type == ValueType.FLOAT) 0 else 0x100

    fun eabiRuntimeAliases(): Map<String, String> = mapOf(
        "__divdi3" to "__aeabi_ldivmod", "__moddi3" to "__aeabi_ldivmod",
        "__udivdi3" to "__aeabi_uldivmod", "__umoddi3" to "__aeabi_uldivmod",
    )

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
    const val TREG_FP = 11

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
    data class CallTarget(val value: Int = 0, val symbol: Symbol? = null, val register: Int = -1, val indirect: Boolean = false)

    data class AvailableVfpRegisters(
        val holes: IntArray = IntArray(3), var firstHole: Int = 0,
        var lastHole: Int = 0, var firstFree: Int = 0,
    )

    enum class ParameterType { STRUCT, FLOAT, DOUBLE, LONG_DOUBLE, LONG_LONG, OTHER }
    enum class ParameterClass { STACK, CORE_STRUCT, VFP, VFP_STRUCT, CORE }
    data class Parameter(val type: ParameterType, val size: Int, val alignment: Int, val homogeneousFloatAggregate: Boolean = false)
    data class ParameterPlan(val parameterIndex: Int, val parameterClass: ParameterClass, val start: Int, val end: Int)
    data class RegisterAssignment(val plans: List<ParameterPlan>, val stackBytes: Int, val coreRegisterTodo: Int)
    enum class ParameterCopyOperation { ALLOCATE_STACK, COPY_STRUCT, SAVE_VFP_SCRATCH, RESTORE_VFP_SCRATCH, POP_VFP_RANGE, PUSH_FLOAT, PUSH_LONG_HIGH, PUSH_LONG_LOW, PUSH_VALUE, ADD_STACK_PADDING, MOVE_VFP, MOVE_VFP_UPPER, MOVE_CORE, POP_CORE_STRUCT, SYNTHESIZE_CORE_VALUE }
    data class ParameterCopyAction(val operation: ParameterCopyOperation, val parameterIndex: Int = -1,
        val start: Int = 0, val end: Int = 0, val amount: Int = 0)
    data class ParameterCopyPlan(val actions: List<ParameterCopyAction>, val extraStackValues: Int)

    data class FunctionParameter(val size: Int, val alignment: Int, val type: ParameterType, val homogeneousFloatAggregate: Boolean = false)
    data class FunctionProloguePlan(val words: List<Int>, val parameterOffsets: List<Int>, val coreSaved: Int, val vfpSaved: Int,
        val hiddenStructReturn: Boolean, val stackAdjustmentPatchWord: Int, val structureReturnOffset: Int?)
    data class FunctionEpiloguePlan(val words: List<Int>, val stackAdjustment: Int, val patchInstruction: Int? = null)
    data class ConversionPlan(val words: List<Int> = emptyList(), val helper: String? = null,
        val integerResultHighRegister: Int? = null, val magicLiteralOffset: Int? = null)
    data class FloatingOperationPlan(val words: List<Int>, val comparison: Condition? = null, val consumedOperands: Int = 1)
    data class FpaOperationPlan(val word: Int, val comparison: Condition? = null)
    enum class FloatAbi { SOFT, HARD }
    data class FunctionCallPlan(val effectiveFloatAbi: FloatAbi, val argumentRegisters: RegisterAssignment,
        val stackBytesBeforeAlignment: Int, val alignmentPadding: Int, val stackBytesAfterAlignment: Int,
        val floatingReturnWords: List<Int>, val argumentCopies: ParameterCopyPlan, val valueStackPopCount: Int)

    /** Plans ARM function entry instructions and incoming parameter addresses. */
    fun functionPrologue(parameters: List<FunctionParameter>, structReturnInMemory: Boolean, variadic: Boolean, hardFloat: Boolean, eabi: Boolean): FunctionProloguePlan {
        var coreCount = if (structReturnInMemory) 1 else 0
        var vfpCount = 0
        val vfp = AvailableVfpRegisters()
        for (parameter in parameters) {
            if (coreCount >= 4 && vfpCount >= 16) break
            if (eabi && hardFloat && !variadic && (parameter.type in setOf(ParameterType.FLOAT, ParameterType.DOUBLE, ParameterType.LONG_DOUBLE) || parameter.homogeneousFloatAggregate)) {
                val first = assignVfpRegister(vfp, parameter.alignment, parameter.size)
                val lastSaved = first + (parameter.size + 3) / 4
                if (lastSaved > vfpCount) vfpCount = lastSaved
            } else if (coreCount < 4) coreCount += (parameter.size + 3) / 4
        }
        if (variadic) coreCount = 4
        coreCount = minOf(coreCount, 4)
        if (eabi) coreCount = (coreCount + 1) and -2
        if (vfpCount > 0) vfpCount = minOf((minOf(vfpCount, 16) + 1) and -2, 16)
        val words = mutableListOf(0xe1a0c00d.toInt())
        if (coreCount != 0) words += 0xe92d0000.toInt() or ((1 shl coreCount) - 1)
        if (vfpCount != 0) words += 0xed2d0a00.toInt() or vfpCount
        words.addAll(listOf(0xe92d5800.toInt(), 0xe1a0b00d.toInt()))
        val stackAdjustmentPatchWord = words.size
        words += 0xe1a00000.toInt()

        var core = if (structReturnInMemory) 1 else 0
        var stack = 0
        val addresses = mutableListOf<Int>()
        val argumentVfp = if (eabi && hardFloat) AvailableVfpRegisters() else null
        for (parameter in parameters) {
            val sizeWords = (parameter.size + 3) shr 2
            val alignment = (parameter.alignment + 3) and -4
            val floating = eabi && hardFloat && !variadic &&
                (parameter.type in setOf(ParameterType.FLOAT, ParameterType.DOUBLE, ParameterType.LONG_DOUBLE) || parameter.homogeneousFloatAggregate)
            val fpReg = if (floating) assignVfpRegister(argumentVfp!!, alignment, sizeWords shl 2) else -1
            val address: Int
            if (floating && fpReg >= 0) {
                address = fpReg * 4
            } else if (!floating && core < 4) {
                if (eabi) core = (core + (alignment - 1) / 4) and -(alignment / 4)
                address = (vfpCount + core) * 4
                core += sizeWords
                if (stack == 0 && core > 4) stack = core - 4
            } else {
                if (eabi) stack = (stack + (alignment - 1) / 4) and -(alignment / 4)
                address = (coreCount + vfpCount + stack) * 4
                stack += sizeWords
            }
            addresses += address + 12
        }
        val structureReturnOffset = if (structReturnInMemory) 12 + if (eabi && hardFloat) vfpCount * 4 else 0 else null
        return FunctionProloguePlan(words, addresses, coreCount, vfpCount, structReturnInMemory,
            stackAdjustmentPatchWord, structureReturnOffset)
    }

    /** Computes ARM function epilogue instructions and the deferred stack-frame adjustment patch. */
    fun functionEpilogue(localBytes: Int, isLeaf: Boolean, eabi: Boolean, patchPosition: Int, sequencePosition: Int,
        softFloatReturn: Boolean = false, doubleReturn: Boolean = false): FunctionEpiloguePlan {
        val words = mutableListOf<Int>()
        if (softFloatReturn) {
            if (doubleReturn) words.addAll(listOf(0xee100b10.toInt(), 0xee301b10.toInt()))
            else words += 0xee100a10.toInt()
        }
        words += 0xe89ba800.toInt()
        var difference = (-localBytes + 3) and -4
        if (eabi && !isLeaf) difference = ((difference + 11) and -8) - 4
        if (difference <= 0) return FunctionEpiloguePlan(words, difference)
        val adjustment = stuffConstant(0xe24bd000.toInt(), difference)
        if (adjustment != 0) return FunctionEpiloguePlan(words, difference, adjustment)
        words.addAll(listOf(0xe59fc004.toInt(), 0xe04bd00c.toInt(), 0xe1a0f00e.toInt(), difference))
        val patch = 0xe1000000.toInt() or encodeBranch(patchPosition, sequencePosition, true)
        return FunctionEpiloguePlan(words, difference, patch)
    }

    fun integerToFloat(source: ValueType, target: ValueType, unsigned: Boolean, sourceCoreRegister: Int,
        destinationFloatRegister: Int, vfp: Boolean, instructionOffset: Int = 0, lastMagicLiteralOffset: Int = 0,
        unsignedTempFloatRegister: Int = destinationFloatRegister, longDoubleSize: Int = 8): ConversionPlan {
        if (source in setOf(ValueType.BYTE, ValueType.SHORT, ValueType.INT)) {
            val sourceRegister = integerRegister(sourceCoreRegister)
            val destination = floatingRegister(destinationFloatRegister, vfp)
            if (vfp) {
                val targetDouble = if (target == ValueType.FLOAT) 0 else 0x100
                val signed = if (unsigned) 0 else 0x80
                return ConversionPlan(listOf(
                    0xee000a10.toInt() or (sourceRegister shl 12) or (destination shl 16),
                    0xeeb80a40.toInt() or (destination shl 12) or destination or signed or targetDouble,
                ))
            }
            val targetDouble = if (target == ValueType.FLOAT) 0 else 0x80
            val words = mutableListOf(0xee000110.toInt() or targetDouble or (destination shl 16) or (sourceRegister shl 12))
            var nextMagic = lastMagicLiteralOffset
            if (source == ValueType.INT && unsigned) {
                val temp = floatingRegister(unsignedTempFloatRegister, false)
                words += 0xe3500000.toInt() or (sourceRegister shl 12)
                var offset = if (lastMagicLiteralOffset != 0) (instructionOffset + 16 - lastMagicLiteralOffset) / 4 else 0
                if (offset > 255) offset = 0
                words += 0xbd1f0100.toInt() or (temp shl 12) or offset
                if (offset == 0) {
                    words += 0xea000000.toInt()
                    nextMagic = instructionOffset + 16
                    words += 0x4f800000
                }
                words += 0xbe000100.toInt() or targetDouble or (destination shl 16) or (destination shl 12) or temp
            }
            return ConversionPlan(words, magicLiteralOffset = nextMagic)
        }
        if (source == ValueType.LONG_LONG) {
            val helper = when (target) {
                ValueType.FLOAT -> if (unsigned) "__floatundisf" else "__floatdisf"
                ValueType.DOUBLE -> if (unsigned) "__floatundidf" else "__floatdidf"
                ValueType.LONG_DOUBLE -> if (longDoubleSize != 8) { if (unsigned) "__floatundixf" else "__floatdixf" } else null
                else -> null
            }
            return ConversionPlan(helper = helper)
        }
        return ConversionPlan()
    }

    fun floatToInteger(source: ValueType, target: ValueType, unsigned: Boolean, sourceFloatRegister: Int,
        destinationCoreRegister: Int, vfp: Boolean, longDoubleSize: Int = 8): ConversionPlan {
        val sourceRegister = floatingRegister(sourceFloatRegister, vfp)
        if (target == ValueType.INT && vfp) {
            val signedBit = if (unsigned) 0 else 0x10000
            val sourceDouble = if (source == ValueType.FLOAT) 0 else 0x100
            val destination = integerRegister(destinationCoreRegister)
            return ConversionPlan(listOf(
                0xeebc0ac0.toInt() or (sourceRegister shl 12) or sourceRegister or sourceDouble or signedBit,
                0xee100a10.toInt() or (sourceRegister shl 16) or (destination shl 12),
            ))
        }
        if (target == ValueType.INT && !unsigned) {
            val destination = integerRegister(destinationCoreRegister)
            return ConversionPlan(listOf(0xee100170.toInt() or (destination shl 12) or sourceRegister))
        }
        val helper = when (target) {
            ValueType.INT -> when (source) {
                ValueType.FLOAT -> "__fixunssfsi"
                ValueType.DOUBLE -> "__fixunsdfsi"
                ValueType.LONG_DOUBLE -> if (longDoubleSize != 8) "__fixunsxfsi" else null
                else -> null
            }
            ValueType.LONG_LONG -> when (source) {
                ValueType.FLOAT -> "__fixsfdi"
                ValueType.DOUBLE -> "__fixdfdi"
                ValueType.LONG_DOUBLE -> if (longDoubleSize != 8) "__fixxfdi" else null
                else -> null
            }
            else -> null
        }
        return ConversionPlan(helper = helper, integerResultHighRegister = if (target == ValueType.LONG_LONG) TREG_R1 else null)
    }

    fun convertFloatPrecision(source: ValueType, target: ValueType, register: Int, vfp: Boolean): ConversionPlan {
        if (!vfp || (source == ValueType.FLOAT) == (target == ValueType.FLOAT)) return ConversionPlan()
        val fpRegister = floatingRegister(register, true)
        val sourceDouble = if (source == ValueType.FLOAT) 0 else 0x100
        return ConversionPlan(listOf(0xeeb70ac0.toInt() or (fpRegister shl 12) or fpRegister or sourceDouble))
    }

    /** Encodes the VFP arithmetic, unary, and compare instructions selected by gen_opf. */
    fun vfpFloatingOperation(operation: String, type: ValueType, destination: Int, left: Int, right: Int,
        leftIsZero: Boolean = false, rightIsZero: Boolean = false, condition: Condition? = null): FloatingOperationPlan {
        val single = type == ValueType.FLOAT
        val precision = if (single) 0 else 0x100
        var lhs = floatingRegister(left, true)
        var rhs = floatingRegister(right, true)
        val dest by lazy { floatingRegister(destination, true) }
        var opcode = 0xee000a00.toInt() or precision
        when (operation) {
            "+" -> {
                if (leftIsZero) { val swap = lhs; lhs = rhs; rhs = swap }
                if (rightIsZero) return FloatingOperationPlan(emptyList())
                opcode = opcode or 0x300000
            }
            "-" -> {
                opcode = opcode or 0x300040
                if (rightIsZero) return FloatingOperationPlan(emptyList())
                if (leftIsZero) {
                    opcode = opcode or 0x810000
                    val swap = lhs; lhs = rhs; rhs = swap
                }
            }
            "*" -> opcode = opcode or 0x200000
            "/" -> opcode = opcode or 0x800000
            "compare" -> {
                var cmp = condition ?: throw IllegalArgumentException("floating compare requires a condition")
                if (leftIsZero) {
                    val swap = lhs; lhs = rhs; rhs = swap
                    cmp = when (cmp) { Condition.LT -> Condition.GT; Condition.GE -> Condition.ULE; Condition.LE -> Condition.GE; Condition.GT -> Condition.ULT; else -> cmp }
                }
                opcode = opcode or 0xb40040
                if (cmp !in setOf(Condition.EQ, Condition.NE)) opcode = opcode or 0x80
                val compareWord = if (rightIsZero) opcode or 0x10000 or (lhs shl 12)
                    else opcode or rhs or (lhs shl 12)
                val result = when (cmp) {
                    Condition.LE -> Condition.ULE; Condition.LT -> Condition.ULT
                    Condition.UGE -> Condition.GE; Condition.UGT -> Condition.GT
                    else -> cmp
                }
                return FloatingOperationPlan(listOf(compareWord, 0xeef1fa10.toInt()), result)
            }
            else -> throw IllegalArgumentException("unknown floating operation $operation")
        }
        val word = if (operation == "-" && leftIsZero) opcode or (dest shl 12) or lhs
            else opcode or (dest shl 12) or (lhs shl 16) or rhs
        return FloatingOperationPlan(listOf(word), consumedOperands = if (operation in setOf("abs", "sqrt") || operation == "-" && leftIsZero) 1 else 2)
    }

    /** Encodes FPA arithmetic and comparison operations, including immediate constants. */
    fun fpaFloatingOperation(operation: String, type: ValueType, destination: Int, left: Int, right: Int,
        leftImmediate: Int = 0, rightImmediate: Int = 0, longDoubleSize: Int = 8,
        condition: Condition? = null): FpaOperationPlan {
        var first = floatingRegister(left, false)
        var second = floatingRegister(right, false)
        val target by lazy { floatingRegister(destination, false) }
        var lhsConstant = leftImmediate
        var rhsConstant = rightImmediate
        var opcode = 0xee000100.toInt()
        if (type == ValueType.DOUBLE) opcode = opcode or 0x80
        else if (type == ValueType.LONG_DOUBLE && longDoubleSize != 8) opcode = opcode or 0x80000
        var compareResult: Condition? = null
        var operand: Int
        when (operation) {
            "+" -> {
                if (rhsConstant == 0) {
                    val swap = first; first = second; second = swap
                    rhsConstant = lhsConstant
                }
                if (rhsConstant > 0xf) opcode = opcode or 0x200000
                operand = if (rhsConstant != 0) rhsConstant and 15 else second
            }
            "-" -> when {
                rhsConstant != 0 -> {
                    if (rhsConstant <= 0xf) opcode = opcode or 0x200000
                    operand = rhsConstant and 15
                    val swap = first; first = second; second = swap
                }
                lhsConstant in 1..15 -> {
                    opcode = opcode or 0x300000
                    operand = lhsConstant
                    first = second
                }
                else -> { opcode = opcode or 0x200000; operand = second }
            }
            "*" -> {
                if (rhsConstant == 0 || rhsConstant > 0xf) {
                    val swap = first; first = second; second = swap
                    rhsConstant = lhsConstant
                }
                if (rhsConstant in 1..15) operand = rhsConstant
                else operand = second
                opcode = opcode or 0x100000
            }
            "/" -> when {
                rhsConstant in 1..15 -> { opcode = opcode or 0x400000; operand = rhsConstant; val swap = first; first = second; second = swap }
                lhsConstant in 1..15 -> { opcode = opcode or 0x500000; operand = lhsConstant; first = second }
                else -> { opcode = opcode or 0x400000; operand = second }
            }
            "compare" -> {
                var cmp = condition ?: throw IllegalArgumentException("floating compare requires a condition")
                opcode = opcode or 0xd0f110
                if (cmp in setOf(Condition.ULT, Condition.UGE, Condition.ULE, Condition.UGT))
                    throw IllegalArgumentException("unsigned comparison on floats?")
                if (cmp == Condition.LT) cmp = Condition.NEGATIVE
                if (cmp == Condition.LE) cmp = Condition.ULE
                if (cmp == Condition.EQ || cmp == Condition.NE) opcode = opcode and 0xffbfffff.toInt()
                if (lhsConstant != 0 && rhsConstant == 0) {
                    rhsConstant = lhsConstant
                    val swap = first; first = second; second = swap
                    cmp = when (cmp) {
                        Condition.NEGATIVE -> Condition.GT; Condition.GE -> Condition.ULE
                        Condition.ULE -> Condition.GE; Condition.GT -> Condition.NEGATIVE
                        else -> cmp
                    }
                }
                if (rhsConstant > 0xf) opcode = opcode or 0x200000
                operand = if (rhsConstant != 0) rhsConstant and 15 else second
                compareResult = cmp
            }
            else -> throw IllegalArgumentException("unknown floating operation $operation")
        }
        val destinationField = if (operation == "compare") 15 else target
        return FpaOperationPlan(opcode or (first shl 16) or (destinationField shl 12) or operand, compareResult)
    }

    /** Assigns argument values to stack, core registers, and VFP registers according to AAPCS. */
    fun assignParameterRegisters(parameters: List<Parameter>, hardFloat: Boolean): RegisterAssignment {
        var nextCore = 0
        var nextStack = 0
        var coreTodo = 0
        val plans = mutableListOf<ParameterPlan>()
        val vfpRegisters = AvailableVfpRegisters()
        for (index in parameters.indices.reversed()) {
            val parameter = parameters[index]
            val size = (parameter.size + 3) and -4
            val alignment = (parameter.alignment + 3) and -4
            var startVfp = 0
            var assigned = false
            if (parameter.type in setOf(ParameterType.STRUCT, ParameterType.FLOAT, ParameterType.DOUBLE, ParameterType.LONG_DOUBLE)) {
                if (hardFloat && (parameter.type in setOf(ParameterType.FLOAT, ParameterType.DOUBLE, ParameterType.LONG_DOUBLE) || parameter.homogeneousFloatAggregate)) {
                    startVfp = assignVfpRegister(vfpRegisters, alignment, size)
                    if (startVfp >= 0) {
                        val cls = if (parameter.homogeneousFloatAggregate) ParameterClass.VFP_STRUCT else ParameterClass.VFP
                        plans.add(0, ParameterPlan(index, cls, startVfp, startVfp + ((size - 1) shr 2)))
                        continue
                    }
                }
                nextCore = (nextCore + (alignment - 1) / 4) and ((alignment / 4) - 1).inv()
                if (nextCore + size / 4 <= 4 || (nextCore < 4 && startVfp != -1)) {
                    var reg = nextCore
                    while (reg < 4 && reg < nextCore + size / 4) {
                        coreTodo = coreTodo or (1 shl reg)
                        reg++
                    }
                    plans.add(0, ParameterPlan(index, ParameterClass.CORE_STRUCT, nextCore, reg))
                    nextCore += size / 4
                    if (nextCore > 4) nextStack = (nextCore - 4) * 4
                    assigned = true
                } else nextCore = 4
            } else if (nextCore < 4) {
                val isLong = parameter.type == ParameterType.LONG_LONG
                if (isLong) {
                    nextCore = (nextCore + 1) and -2
                    if (nextCore == 4) {
                        // Fall through to stack allocation below.
                    } else {
                        plans.add(0, ParameterPlan(index, ParameterClass.CORE, nextCore, nextCore + 1))
                        nextCore += 2
                        continue
                    }
                } else {
                    plans.add(0, ParameterPlan(index, ParameterClass.CORE, nextCore, nextCore))
                    nextCore++
                    continue
                }
            }
            if (!assigned) {
                nextStack = (nextStack + alignment - 1) and (alignment - 1).inv()
                plans.add(0, ParameterPlan(index, ParameterClass.STACK, nextStack, nextStack + size))
                nextStack += size
            }
        }
        return RegisterAssignment(plans, nextStack, coreTodo)
    }

    /** Reproduces copy_params class ordering and emits compiler-independent copy actions. */
    fun copyParameterPlan(parameters: List<Parameter>, assignment: RegisterAssignment,
        coreValuesNeedingSecondPass: Set<Int> = emptySet()): ParameterCopyPlan {
        val actions = mutableListOf<ParameterCopyAction>()
        val classOrder = listOf(ParameterClass.STACK, ParameterClass.CORE_STRUCT, ParameterClass.VFP,
            ParameterClass.VFP_STRUCT, ParameterClass.CORE)
        var passes = 0
        do {
            classOrder.forEach { cls ->
                val classPlans = assignment.plans.filter { it.parameterClass == cls }
                classPlans.forEachIndexed { index, plan ->
                    if (passes > 0 && (cls != ParameterClass.CORE || plan.parameterIndex !in coreValuesNeedingSecondPass)) return@forEachIndexed
                    val parameter = parameters[plan.parameterIndex]
                    if (cls in setOf(ParameterClass.STACK, ParameterClass.CORE_STRUCT, ParameterClass.VFP_STRUCT)) {
                        if (parameter.type == ParameterType.STRUCT) {
                            val padding = if (cls == ParameterClass.STACK && index + 1 < classPlans.size)
                                plan.start - classPlans[index + 1].end else 0
                            val structureSize = (parameter.size + 3) and -4
                            actions += ParameterCopyAction(ParameterCopyOperation.ALLOCATE_STACK, plan.parameterIndex, amount = -structureSize - padding)
                            actions += ParameterCopyAction(ParameterCopyOperation.SAVE_VFP_SCRATCH, plan.parameterIndex)
                            actions += ParameterCopyAction(ParameterCopyOperation.COPY_STRUCT, plan.parameterIndex, amount = padding)
                            actions += ParameterCopyAction(ParameterCopyOperation.RESTORE_VFP_SCRATCH, plan.parameterIndex)
                            if (cls == ParameterClass.VFP_STRUCT)
                                actions += ParameterCopyAction(ParameterCopyOperation.POP_VFP_RANGE, plan.parameterIndex, plan.start, plan.end)
                        } else if (parameter.type in setOf(ParameterType.FLOAT, ParameterType.DOUBLE, ParameterType.LONG_DOUBLE)) {
                            actions += ParameterCopyAction(ParameterCopyOperation.PUSH_FLOAT, plan.parameterIndex, amount = parameter.size)
                            if (cls == ParameterClass.STACK && index + 1 < classPlans.size)
                                actions += ParameterCopyAction(ParameterCopyOperation.ADD_STACK_PADDING, plan.parameterIndex,
                                    amount = plan.start - classPlans[index + 1].end)
                        } else if (parameter.type == ParameterType.LONG_LONG) {
                            actions += ParameterCopyAction(ParameterCopyOperation.PUSH_LONG_HIGH, plan.parameterIndex)
                            actions += ParameterCopyAction(ParameterCopyOperation.PUSH_LONG_LOW, plan.parameterIndex)
                        } else {
                            actions += ParameterCopyAction(ParameterCopyOperation.PUSH_VALUE, plan.parameterIndex)
                            if (cls == ParameterClass.STACK && index + 1 < classPlans.size)
                                actions += ParameterCopyAction(ParameterCopyOperation.ADD_STACK_PADDING, plan.parameterIndex,
                                    amount = plan.start - classPlans[index + 1].end)
                        }
                    } else if (cls == ParameterClass.VFP) {
                        actions += ParameterCopyAction(ParameterCopyOperation.MOVE_VFP, plan.parameterIndex, plan.start, plan.end)
                        if (plan.start and 1 != 0) actions += ParameterCopyAction(ParameterCopyOperation.MOVE_VFP_UPPER, plan.parameterIndex, plan.start - 1, plan.start)
                    } else if (cls == ParameterClass.CORE) {
                        if (parameter.type == ParameterType.LONG_LONG) actions += ParameterCopyAction(ParameterCopyOperation.MOVE_CORE, plan.parameterIndex, plan.end, plan.end)
                        actions += ParameterCopyAction(ParameterCopyOperation.MOVE_CORE, plan.parameterIndex, plan.start, plan.start)
                    }
                }
            }
        } while (++passes < 2)
        var extraValues = 0
        if (assignment.coreRegisterTodo != 0) {
            actions += ParameterCopyAction(ParameterCopyOperation.POP_CORE_STRUCT, amount = assignment.coreRegisterTodo)
            assignment.plans.filter { it.parameterClass == ParameterClass.CORE_STRUCT }.forEach { plan ->
                var register = plan.start + 1
                while (register <= plan.end) {
                    if (assignment.coreRegisterTodo and (1 shl register) != 0) {
                        actions += ParameterCopyAction(ParameterCopyOperation.SYNTHESIZE_CORE_VALUE, plan.parameterIndex, start = register)
                        extraValues++
                    }
                    register++
                }
            }
        }
        return ParameterCopyPlan(actions, extraValues)
    }

    /** Plans ABI selection, argument placement, stack alignment, and soft-float return moves for a call. */
    fun functionCall(parameters: List<Parameter>, requestedAbi: FloatAbi, variadic: Boolean,
        helperUsesCoreFloatRegisters: Boolean, eabi: Boolean, vfp: Boolean, returnsFloat: Boolean, returnsDouble: Boolean): FunctionCallPlan {
        val effectiveAbi = if (requestedAbi == FloatAbi.HARD && (variadic || helperUsesCoreFloatRegisters)) FloatAbi.SOFT else requestedAbi
        val assignment = assignParameterRegisters(parameters, effectiveAbi == FloatAbi.HARD)
        val padding = if (eabi && assignment.stackBytes and 7 != 0) 4 else 0
        val alignedSize = if (padding == 0) assignment.stackBytes else (assignment.stackBytes + 7) and -8
        val returnWords = if (eabi && vfp && effectiveAbi == FloatAbi.SOFT && returnsFloat) {
            if (returnsDouble) listOf(0xee000b10.toInt(), 0xee201b10.toInt()) else listOf(0xee000a10.toInt())
        } else emptyList()
        val copies = copyParameterPlan(parameters, assignment)
        return FunctionCallPlan(effectiveAbi, assignment, assignment.stackBytes, padding, alignedSize,
            returnWords, copies, parameters.size + copies.extraStackValues + 1)
    }

    /** Allocates a VFP argument range using the AAPCS hole and alignment rules. */
    fun assignVfpRegister(registers: AvailableVfpRegisters, alignment: Int, size: Int): Int {
        if (registers.firstFree == -1) return -1
        var first = registers.firstFree
        if (alignment shr 3 != 0) {
            if (first and 1 != 0) {
                registers.holes[registers.lastHole++] = first
                first++
            }
        } else if (size == 4 && registers.firstHole != registers.lastHole) {
            return registers.holes[registers.firstHole++]
        }
        if (first + size / 4 <= 16) {
            registers.firstFree = first + size / 4
            return first
        }
        registers.firstFree = -1
        return -1
    }

    enum class AggregateMemberType { FLOAT, DOUBLE, OTHER }

    fun isHomogeneousFloatAggregate(isStruct: Boolean, memberTypes: List<AggregateMemberType>): Boolean {
        if (!isStruct || memberTypes.isEmpty()) return false
        val first = memberTypes.first()
        return first != AggregateMemberType.OTHER && memberTypes.size <= 4 && memberTypes.all { it == first }
    }

    data class StructReturn(val registerCount: Int, val alignment: Int? = null, val registerSize: Int? = null, val type: String? = null)

    data class IntegerOperationPlan(
        val dataProcessingOpcode: Int? = null, val shiftOpcode: Int? = null,
        val helper: String? = null, val resultRegister: Int = TREG_R0,
        val multiply: Boolean = false, val unsigned: Boolean = false,
    )
    data class IntegerEmission(val words: List<Int>, val destinationRegister: Int?, val comparison: Condition? = null)

    /** Selects the ARM instruction or runtime helper used by gen_opi. */
    fun integerOperationPlan(operation: String, eabi: Boolean): IntegerOperationPlan = when (operation) {
        "+" -> IntegerOperationPlan(dataProcessingOpcode = 8)
        "addc1" -> IntegerOperationPlan(dataProcessingOpcode = 9)
        "-" -> IntegerOperationPlan(dataProcessingOpcode = 4)
        "subc1" -> IntegerOperationPlan(dataProcessingOpcode = 5)
        "addc2" -> IntegerOperationPlan(dataProcessingOpcode = 10)
        "subc2" -> IntegerOperationPlan(dataProcessingOpcode = 12)
        "&" -> IntegerOperationPlan(dataProcessingOpcode = 0)
        "^" -> IntegerOperationPlan(dataProcessingOpcode = 2)
        "|" -> IntegerOperationPlan(dataProcessingOpcode = 0x18)
        "*" -> IntegerOperationPlan(multiply = true)
        "shl" -> IntegerOperationPlan(shiftOpcode = 0)
        "shr" -> IntegerOperationPlan(shiftOpcode = 1, unsigned = true)
        "sar" -> IntegerOperationPlan(shiftOpcode = 2)
        "/", "pdiv" -> IntegerOperationPlan(helper = "__divsi3")
        "udiv" -> IntegerOperationPlan(helper = "__udivsi3", unsigned = true)
        "%" -> if (eabi) IntegerOperationPlan(helper = "__aeabi_idivmod", resultRegister = TREG_R1)
            else IntegerOperationPlan(helper = "__modsi3")
        "umod" -> if (eabi) IntegerOperationPlan(helper = "__aeabi_uidivmod", resultRegister = TREG_R1, unsigned = true)
            else IntegerOperationPlan(helper = "__umodsi3", unsigned = true)
        "umull" -> IntegerOperationPlan(multiply = true, unsigned = true)
        else -> IntegerOperationPlan(dataProcessingOpcode = 0x15)
    }

    /** Encodes gen_opi's ARM data-processing and multiply instruction paths. */
    fun integerDataProcessing(operation: String, leftRegister: Int, rightRegister: Int, destinationRegister: Int,
        leftImmediate: Int? = null, rightImmediate: Int? = null): IntegerEmission {
        val comparison = when (operation) {
            "ult" -> Condition.ULT; "uge" -> Condition.UGE; "eq" -> Condition.EQ; "ne" -> Condition.NE
            "ule" -> Condition.ULE; "ugt" -> Condition.UGT; "negative" -> Condition.NEGATIVE
            "nonnegative" -> Condition.NON_NEGATIVE; "lt" -> Condition.LT; "ge" -> Condition.GE
            "le" -> Condition.LE; "gt" -> Condition.GT; else -> null
        }
        var opcode = when (operation) {
            "+" -> 8; "addc1" -> 9; "-" -> 4; "subc1" -> 5; "addc2" -> 10; "subc2" -> 12
            "&" -> 0; "^" -> 2; "|" -> 0x18; "cmp" -> 0x15
            else -> if (comparison != null) 0x15 else throw IllegalArgumentException("gen_opi $operation unimplemented")
        }
        var lhs = integerRegister(leftRegister)
        var rhs = integerRegister(rightRegister)
        var immediate = rightImmediate
        if (leftImmediate != null && operation in setOf("-", "subc1", "subc2")) {
            val swap = lhs; lhs = rhs; rhs = swap
            immediate = leftImmediate
            opcode = opcode or 2
        }
        val base = 0xe0000000.toInt() or (opcode shl 20)
        if (immediate != null) {
            val encoded = stuffConstant(base or 0x02000000 or (lhs shl 16), immediate)
            if (encoded != 0) {
                if (comparison != null || operation == "cmp") return IntegerEmission(listOf(encoded), null, comparison)
                return IntegerEmission(listOf(encoded or (integerRegister(destinationRegister) shl 12)), destinationRegister)
            }
        }
        if (comparison != null || operation == "cmp") return IntegerEmission(listOf(base or (lhs shl 16) or rhs), null, comparison)
        return IntegerEmission(listOf(base or (lhs shl 16) or (integerRegister(destinationRegister) shl 12) or rhs), destinationRegister)
    }

    fun integerMultiply(left: Int, right: Int): Int {
        val rd = integerRegister(left)
        val rm = integerRegister(right)
        return 0xe0000090.toInt() or (rd shl 16) or (rd shl 8) or rm
    }

    fun integerShift(shift: Int, source: Int, destination: Int, amount: Int? = null, amountRegister: Int? = null): Int {
        require(shift in 0..3) { "unknown ARM shift opcode $shift" }
        val sourceRegister = integerRegister(source)
        val destinationRegister = integerRegister(destination)
        val opcode = 0xe1a00000.toInt() or (shift shl 5)
        return if (amount != null) opcode or sourceRegister or ((amount and 31) shl 7) or (destinationRegister shl 12)
        else opcode or sourceRegister or (destinationRegister shl 12) or (integerRegister(amountRegister ?: error("register shift requires a shift register")) shl 8) or 0x10
    }

    fun integerLongMultiply(left: Int, right: Int, destinationLow: Int, destinationHigh: Int,
        signed: Boolean = false, accumulate: Boolean = false, setFlags: Boolean = false): Int {
        var opcode = 0x00800090
        if (signed) opcode = opcode or 0x00400000
        if (accumulate) opcode = opcode or 0x00200000
        if (setFlags) opcode = opcode or 0x00100000
        return opcode or (integerRegister(destinationHigh) shl 16) or (integerRegister(destinationLow) shl 12) or
            (integerRegister(left) shl 8) or integerRegister(right)
    }

    fun floatsInCoreRegisters(helperSymbol: String?, vfp: Boolean): Boolean {
        val symbols = mutableSetOf("__floatundisf", "__floatundidf", "__fixunssfdi", "__fixunsdfdi",
            "__floatdisf", "__floatdidf", "__fixsfdi", "__fixdfdi")
        if (!vfp) symbols += "__fixunsxfdi"
        return helperSymbol in symbols
    }

    /** Returns the FPA immediate-constant encoding, or zero when a literal is required. */
    fun fpaFloatConstant(value: Double, finite: Boolean = value.isFinite()): Int {
        if (!finite) return 0
        var encoding = if (value < 0.0) 0x18 else 0x08
        val magnitude = kotlin.math.abs(value)
        if (magnitude == 0.0) return encoding
        val immediate = when (magnitude) {
            1.0 -> 1; 2.0 -> 2; 3.0 -> 3; 4.0 -> 4; 5.0 -> 5
            0.5 -> 6; 10.0 -> 7
            else -> return 0
        }
        encoding = encoding or immediate
        return encoding
    }

    data class CoverageIncrement(val instructions: List<Int>, val relocationWordIndex: Int)

    fun coverageIncrement(addressRegister: Int, valueRegister: Int): CoverageIncrement {
        val address = integerRegister(addressRegister)
        val value = integerRegister(valueRegister)
        return CoverageIncrement(listOf(
            0xe59f0000.toInt() or (address shl 12), 0xea000000.toInt(), -12,
            0xe080000f.toInt() or (address shl 16) or (address shl 12),
            0xe5900000.toInt() or (address shl 16) or (value shl 12),
            0xe2900001.toInt() or (value shl 16) or (value shl 12),
            0xe5800000.toInt() or (address shl 16) or (value shl 12),
            0xe2800004.toInt() or (address shl 16) or (address shl 12),
            0xe5900000.toInt() or (address shl 16) or (value shl 12),
            0xe2a00000.toInt() or (value shl 16) or (value shl 12),
            0xe5800000.toInt() or (address shl 16) or (value shl 12),
        ), 2)
    }

    data class VlaAllocationPlan(val alignment: Int, val instructions: List<Int>, val boundsCheckEnabled: Boolean)

    enum class ValueType { BYTE, BOOL, SHORT, INT, LONG_LONG, FLOAT, DOUBLE, LONG_DOUBLE }
    enum class ValueLocation { CONSTANT, LOCAL, LOCAL_LVALUE, INDIRECT_LOCAL_LVALUE, CONSTANT_LVALUE, REGISTER_LVALUE, REGISTER, COMPARE, JUMP, JUMP_INDIRECT }
    data class CodeValue(
        val location: ValueLocation, val type: ValueType = ValueType.INT, val value: Int = 0,
        val register: Int = -1, val symbol: Symbol? = null, val unsigned: Boolean = false,
        val condition: Condition? = null, val tls: Boolean = false,
    )
    data class MemoryInstruction(val baseRegister: Int, val offset: Int, val negativeOffset: Boolean, val words: List<Int>)

    /** Encodes a scalar load from an ARM base register plus/minus an offset. */
    fun memoryLoad(destination: Int, base: Int, offset: Int, type: ValueType, unsigned: Boolean,
        vfp: Boolean, doubleLongDouble: Boolean = true): MemoryInstruction {
        val dest = if (type in setOf(ValueType.FLOAT, ValueType.DOUBLE, ValueType.LONG_DOUBLE)) floatingRegister(destination, vfp) else integerRegister(destination)
        var addressBase = base
        var magnitude = kotlin.math.abs(offset)
        var negative = offset < 0
        val words = mutableListOf<Int>()
        if (type in setOf(ValueType.FLOAT, ValueType.DOUBLE, ValueType.LONG_DOUBLE)) {
            val address = calculateAddress(addressBase, magnitude, if (negative) 1 else 0, 1020, 2)
            addressBase = address.base; magnitude = address.offset; negative = address.sign != 0; words += address.words
            var opcode = if (vfp) 0xed100a00.toInt() else 0xed100100.toInt()
            if (!negative) opcode = opcode or 0x800000
            if (vfp) {
                if (type != ValueType.FLOAT) opcode = opcode or 0x100
            } else {
                if (type == ValueType.DOUBLE) opcode = opcode or 0x8000
                else if (type == ValueType.LONG_DOUBLE && !doubleLongDouble) opcode = opcode or 0x400000
            }
            val fpRegister = if (vfp) dest else dest
            words += opcode or (fpRegister shl 12) or (magnitude shr 2) or (addressBase shl 16)
        } else if (type == ValueType.BYTE && !unsigned || type == ValueType.SHORT) {
            val address = calculateAddress(addressBase, magnitude, if (negative) 1 else 0, 255, 0)
            addressBase = address.base; magnitude = address.offset; negative = address.sign != 0; words += address.words
            var opcode = 0xe1500090.toInt()
            if (type == ValueType.SHORT) opcode = opcode or 0x20
            if (!unsigned) opcode = opcode or 0x40
            if (!negative) opcode = opcode or 0x800000
            words += opcode or (dest shl 12) or (addressBase shl 16) or ((magnitude and 0xf0) shl 4) or (magnitude and 15)
        } else {
            val address = calculateAddress(addressBase, magnitude, if (negative) 1 else 0, 4095, 0)
            addressBase = address.base; magnitude = address.offset; negative = address.sign != 0; words += address.words
            var opcode = 0xe5100000.toInt()
            if (!negative) opcode = opcode or 0x800000
            if (type == ValueType.BYTE || type == ValueType.BOOL) opcode = opcode or 0x400000
            words += opcode or (dest shl 12) or magnitude or (addressBase shl 16)
        }
        return MemoryInstruction(addressBase, magnitude, negative, words)
    }

    /** Encodes a scalar store to an ARM base register plus/minus an offset. */
    fun memoryStore(source: Int, base: Int, offset: Int, type: ValueType, vfp: Boolean,
        doubleLongDouble: Boolean = true): MemoryInstruction {
        val sourceRegister = if (type in setOf(ValueType.FLOAT, ValueType.DOUBLE, ValueType.LONG_DOUBLE)) floatingRegister(source, vfp) else integerRegister(source)
        var addressBase = base
        var magnitude = kotlin.math.abs(offset)
        var negative = offset < 0
        val words = mutableListOf<Int>()
        if (type in setOf(ValueType.FLOAT, ValueType.DOUBLE, ValueType.LONG_DOUBLE)) {
            val address = calculateAddress(addressBase, magnitude, if (negative) 1 else 0, 1020, 2)
            addressBase = address.base; magnitude = address.offset; negative = address.sign != 0; words += address.words
            var opcode = if (vfp) 0xed000a00.toInt() else 0xed000100.toInt()
            if (!negative) opcode = opcode or 0x800000
            if (vfp) {
                if (type != ValueType.FLOAT) opcode = opcode or 0x100
            } else {
                if (type == ValueType.DOUBLE) opcode = opcode or 0x8000
                else if (type == ValueType.LONG_DOUBLE && !doubleLongDouble) opcode = opcode or 0x400000
            }
            words += opcode or (sourceRegister shl 12) or (magnitude shr 2) or (addressBase shl 16)
        } else if (type == ValueType.SHORT) {
            val address = calculateAddress(addressBase, magnitude, if (negative) 1 else 0, 255, 0)
            addressBase = address.base; magnitude = address.offset; negative = address.sign != 0; words += address.words
            var opcode = 0xe14000b0.toInt()
            if (!negative) opcode = opcode or 0x800000
            words += opcode or (sourceRegister shl 12) or (addressBase shl 16) or ((magnitude and 0xf0) shl 4) or (magnitude and 15)
        } else {
            val address = calculateAddress(addressBase, magnitude, if (negative) 1 else 0, 4095, 0)
            addressBase = address.base; magnitude = address.offset; negative = address.sign != 0; words += address.words
            var opcode = 0xe5000000.toInt()
            if (!negative) opcode = opcode or 0x800000
            if (type == ValueType.BYTE || type == ValueType.BOOL) opcode = opcode or 0x400000
            words += opcode or (sourceRegister shl 12) or magnitude or (addressBase shl 16)
        }
        return MemoryInstruction(addressBase, magnitude, negative, words)
    }

    /** Loads an ARM compiler value into a core or VFP register. */
    fun loadRegister(value: CodeValue, destination: Int, vfp: Boolean, cpuVersion: Int = 5, pic: Boolean = false,
        output: (Int) -> Unit, currentPosition: () -> Int = { 0 }, relocate: (Symbol, Int, String) -> Unit = { _, _, _ -> },
        patchJumpChain: (Int) -> Unit = {}): List<Int> {
        val words = mutableListOf<Int>()
        fun emit(word: Int) { words += word; output(word) }
        fun loadConstant(constant: Int, symbol: Symbol?, register: Int) = emitLoadValue(
            ConstantValue(constant, symbol), register, cpuVersion, pic, ::emit, currentPosition, relocate)
        fun destinationCore() = integerRegister(destination)
        when (value.location) {
            ValueLocation.CONSTANT -> {
                val opcode = stuffConstant(0xe3a00000.toInt() or (destinationCore() shl 12), value.value)
                if (value.symbol != null || opcode == 0) loadConstant(value.value, value.symbol, destination)
                else emit(opcode)
            }
            ValueLocation.LOCAL -> {
                val opcode = stuffConstant(0xe28b0000.toInt() or (destinationCore() shl 12), value.value)
                if (value.symbol != null || opcode == 0) {
                    loadConstant(value.value, value.symbol, destination)
                    emit(0xe08b0000.toInt() or (destinationCore() shl 12) or destinationCore())
                } else emit(opcode)
            }
            ValueLocation.LOCAL_LVALUE, ValueLocation.INDIRECT_LOCAL_LVALUE, ValueLocation.CONSTANT_LVALUE, ValueLocation.REGISTER_LVALUE -> {
                val addressBase: Int
                val offset: Int
                if (value.tls && value.symbol != null) {
                    emit(0xee1d0fe0.toInt())
                    relocate(value.symbol, currentPosition(), "R_ARM_TLS_LE32")
                    emit(0xe510e000.toInt() or (destinationCore() shl 12))
                    return words
                }
                when (value.location) {
                    ValueLocation.LOCAL_LVALUE -> { addressBase = 11; offset = value.value }
                    ValueLocation.INDIRECT_LOCAL_LVALUE -> {
                        memoryLoad(TREG_LR, TREG_FP, value.value, ValueType.INT, unsigned = false, vfp = false).words.forEach(::emit)
                        addressBase = 14; offset = 0
                    }
                    ValueLocation.CONSTANT_LVALUE -> {
                        loadConstant(value.value, value.symbol, TREG_LR)
                        addressBase = 14; offset = 0
                    }
                    else -> { addressBase = integerRegister(value.register); offset = 0 }
                }
                val access = memoryLoad(destination, addressBase, offset, value.type, value.unsigned, vfp)
                access.words.forEach(::emit)
            }
            ValueLocation.REGISTER -> {
                if (value.type in setOf(ValueType.FLOAT, ValueType.DOUBLE, ValueType.LONG_DOUBLE)) {
                    val source = floatingRegister(value.register, vfp)
                    val target = floatingRegister(destination, vfp)
                    emit(if (vfp) 0xeeb00a40.toInt() or (target shl 12) or source or if (value.type == ValueType.FLOAT) 0 else 0x100
                        else 0xee008180.toInt() or (target shl 12) or source)
                } else emit(0xe1a00000.toInt() or (destinationCore() shl 12) or integerRegister(value.register))
            }
            ValueLocation.COMPARE -> {
                val condition = value.condition ?: error("missing ARM compare condition")
                emit(mapCondition(condition) or 0x03a00001 or (destinationCore() shl 12))
                emit(mapCondition(negateCondition(condition)) or 0x03a00000 or (destinationCore() shl 12))
            }
            ValueLocation.JUMP, ValueLocation.JUMP_INDIRECT -> {
                val truth = if (value.location == ValueLocation.JUMP) 1 else 0
                emit(0xe3a00000.toInt() or (destinationCore() shl 12) or truth)
                emit(0xea000000.toInt())
                patchJumpChain(value.value)
                emit(0xe3a00000.toInt() or (destinationCore() shl 12) or (truth xor 1))
            }
        }
        return words
    }

    /** Stores a core or VFP register into an ARM compiler lvalue. */
    fun storeRegister(source: Int, destination: CodeValue, vfp: Boolean, output: (Int) -> Unit,
        currentPosition: () -> Int = { 0 }, relocate: (Symbol, Int, String) -> Unit = { _, _, _ -> }) : List<Int> {
        val words = mutableListOf<Int>()
        fun emit(word: Int) { words += word; output(word) }
        val sourceCore = integerRegister(source)
        if (destination.tls && destination.symbol != null) {
            emit(0xee1d0fe0.toInt())
            relocate(destination.symbol, currentPosition(), "R_ARM_TLS_LE32")
            emit(0xe500e000.toInt() or (sourceCore shl 12))
            return words
        }
        val base: Int
        val offset: Int
        when (destination.location) {
            ValueLocation.LOCAL, ValueLocation.LOCAL_LVALUE -> { base = 11; offset = destination.value }
            ValueLocation.CONSTANT_LVALUE -> {
                loadRegister(CodeValue(ValueLocation.CONSTANT, ValueType.INT, destination.value, symbol = destination.symbol), TREG_LR,
                    vfp, output = ::emit, currentPosition = currentPosition, relocate = relocate)
                base = 14; offset = 0
            }
            ValueLocation.REGISTER_LVALUE -> { base = integerRegister(destination.register); offset = 0 }
            else -> throw IllegalArgumentException("store unimplemented")
        }
        memoryStore(source, base, offset, destination.type, vfp).words.forEach(::emit)
        return words
    }

    fun vlaAllocation(register: Int, alignment: Int, eabi: Boolean, boundsCheck: Boolean): VlaAllocationPlan {
        var aligned = alignment
        val minimum = if (eabi) 8 else 4
        if (aligned < minimum) aligned = minimum
        require(aligned and (aligned - 1) == 0) { "alignment is not a power of 2: $aligned" }
        val armRegister = integerRegister(register)
        val instructions = mutableListOf<Int>()
        if (boundsCheck) instructions += 0xe2800001.toInt() or (armRegister shl 16) or (armRegister shl 12)
        instructions += 0xe04d0000.toInt() or (armRegister shl 12) or armRegister
        instructions += stuffConstant(0xe3c0d000.toInt() or (armRegister shl 16), aligned - 1)
        return VlaAllocationPlan(aligned, instructions, boundsCheck)
    }

    fun saveVlaStackPointer(frameOffset: Int): List<Int> =
        memoryStore(TREG_SP, TREG_FP, frameOffset, ValueType.INT, vfp = false).words

    fun restoreVlaStackPointer(frameOffset: Int): List<Int> =
        memoryLoad(TREG_SP, TREG_FP, frameOffset, ValueType.INT, unsigned = false, vfp = false).words

    fun computedGoto(target: CallTarget, position: () -> Int, output: (Int) -> Unit,
        relocate: (Symbol, Int, String) -> Unit, allocateIntegerRegister: () -> Int = { TREG_R0 }) {
        emitCallOrJump(target, jump = true, position, output, relocate, allocateIntegerRegister)
    }

    /** Applies ARM EABI structure and homogeneous-float aggregate return rules. */
    fun structureReturn(size: Int, hardFloat: Boolean, variadic: Boolean, isFloat: Boolean, homogeneousFloatAggregate: Boolean, eabi: Boolean): StructReturn {
        if (!eabi) return StructReturn(0)
        if (hardFloat && !variadic && (isFloat || homogeneousFloatAggregate))
            return StructReturn((size + 7) shr 3, 8, 8, "double")
        if (size in 1..4) return StructReturn(1, 4, 4, "int")
        return StructReturn(0)
    }

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

    /** Emits the ARM direct, literal-pool, or register-indirect call/jump sequence. */
    fun emitCallOrJump(
        target: CallTarget, jump: Boolean, position: () -> Int, output: (Int) -> Unit,
        relocate: (Symbol, Int, String) -> Unit, allocateIntegerRegister: () -> Int = { TREG_R0 },
        loadValue: (ConstantValue, Int) -> Unit = { _, _ -> }, clearBoundsFlag: () -> Unit = {},
    ) {
        if (!target.indirect && target.register < 0) {
            val symbol = target.symbol
            if (symbol != null) {
                val at = position()
                val branch = encodeBranch(at, at + target.value, false)
                if (branch != 0) {
                    relocate(symbol, at, "R_ARM_PC24")
                    output(branch or if (jump) 0xe0000000.toInt() else 0xe1000000.toInt())
                } else {
                    val register = TREG_LR
                    loadValue(ConstantValue(target.value, symbol), register)
                    output(if (jump) 0xe1a0f000.toInt() or integerRegister(register) else 0xe12fff30.toInt() or integerRegister(register))
                }
            } else {
                if (!jump) output(0xe28fe004.toInt())
                output(0xe51ff004.toInt())
                output(target.value)
            }
            return
        }
        clearBoundsFlag()
        val register = if (target.register >= 0) target.register else allocateIntegerRegister()
        if (!jump) output(0xe1a0e00f.toInt())
        output(0xe1a0f000.toInt() or integerRegister(register))
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

    fun fillNops(bytes: Int, output: (Int) -> Unit) {
        require(bytes and 3 == 0) { "alignment of code section not multiple of 4" }
        repeat(bytes.coerceAtLeast(0) / 4) { output(0xe1a00000.toInt()) }
    }

    fun adjustStackPointer(byteCount: Int, output: (Int) -> Unit) {
        stuffConstantHarder(0xe28dd000.toInt(), byteCount).forEach(output)
    }

    data class BoundsPrologue(val sectionOffset: Long, val instructionOffset: Int, val addEpilog: Boolean = false)
    data class BoundsRelocation(val wordIndex: Int, val symbol: Symbol, val type: String, val addend: Int = 0)
    data class BoundsEpilogue(val patchOffset: Int?, val prologueWords: List<Int>, val words: List<Int>, val relocations: List<BoundsRelocation>, val terminator: Long?)

    /** Reserves the five ARM instructions patched by bounds-check epilogue generation. */
    fun emitBoundsPrologue(sectionOffset: Long, instructionOffset: Int, output: (Int) -> Unit): BoundsPrologue {
        repeat(5) { output(0xe1a00000.toInt()) }
        return BoundsPrologue(sectionOffset, instructionOffset)
    }

    /** Plans bounds table termination, deferred local registration, and function cleanup. */
    fun boundsEpilogue(state: BoundsPrologue, sectionOffset: Long, boundsSymbol: Symbol,
        newLocalHelper: Symbol, deleteLocalHelper: Symbol): BoundsEpilogue {
        val modified = state.sectionOffset != sectionOffset
        if (!modified && !state.addEpilog) return BoundsEpilogue(null, emptyList(), emptyList(), emptyList(), null)
        val prologue = mutableListOf<Int>()
        val words = mutableListOf<Int>()
        val relocations = mutableListOf<BoundsRelocation>()
        fun call(helper: Symbol) {
            relocations += BoundsRelocation(words.size, helper, "R_ARM_PC24")
            words += 0xebfffffe.toInt()
        }
        if (modified) {
            prologue += listOf(0xe59f0000.toInt(), 0xea000000.toInt())
            relocations += BoundsRelocation(2, boundsSymbol, "R_ARM_REL32")
            prologue += listOf(-12, 0xe080000f.toInt())
            prologue += 0xebfffffe.toInt()
            relocations += BoundsRelocation(4, newLocalHelper, "R_ARM_PC24")
        }
        words.addAll(listOf(0xe92d0003.toInt(), 0xed2d0b04.toInt(), 0xe59f0000.toInt(), 0xea000000.toInt()))
        relocations += BoundsRelocation(words.size, boundsSymbol, "R_ARM_REL32")
        words.addAll(listOf(-12, 0xe080000f.toInt()))
        call(deleteLocalHelper)
        words.addAll(listOf(0xecbd0b04.toInt(), 0xe8bd0003.toInt()))
        return BoundsEpilogue(if (modified) state.instructionOffset else null, prologue, words, relocations, 0L)
    }

    fun generateJump(position: Int, target: Int, noCode: Boolean = false, output: (Int) -> Unit = {}): Int {
        if (noCode) return target
        output(jumpWord(position, target))
        return position
    }

    fun jumpWord(position: Int, target: Int): Int = 0xe0000000.toInt() or encodeBranch(position, target, true)

    fun conditionalJumpWord(position: Int, target: Int, condition: Condition): Int =
        mapCondition(condition) or encodeBranch(position, target, true)

    /** Appends one unresolved branch to the end of an existing branch chain. */
    fun appendJump(code: ByteArray, chain: Int, target: Int): Int {
        if (chain == 0) return target
        var patch = chain
        var next: Int
        do {
            next = decodeBranch(patch, readLe32(code, patch))
            if (next != 0) patch = next
        } while (next != 0)
        val current = readLe32(code, patch)
        writeLe32(code, patch, (current and -0x1000000) or encodeBranch(patch, target, true))
        return chain
    }

    private fun readLe32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)

    private fun writeLe32(bytes: ByteArray, at: Int, value: Int) {
        repeat(4) { bytes[at + it] = (value ushr (it * 8)).toByte() }
    }
}
