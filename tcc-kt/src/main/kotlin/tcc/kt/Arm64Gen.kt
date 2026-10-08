package tcc.kt

/** AArch64 code generation helpers mechanically translated from arm64-gen.c. */
class Arm64Gen(
    private val output: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val error: (String) -> Unit = { throw IllegalArgumentException(it) },
    private val relocation: (Symbol, Int, String, Long) -> Unit = { _, _, _, _ -> },
    private val position: () -> Int = { 0 },
    private val patchWord: (Int, Int) -> Int = { _, _ -> 0 },
) {
    data class Symbol(val name: String, val isStatic: Boolean = false, val isTls: Boolean = false)
    data class Relocation(val symbol: Symbol, val offset: Int, val type: String, val addend: Long = 0)
    enum class Type { BYTE, SHORT, INT, LONG_LONG, POINTER, FUNCTION, STRUCT, FLOAT, DOUBLE, LONG_DOUBLE, BOOL }
    enum class ValueLocation { CONSTANT, LOCAL, INDIRECT_LOCAL, COMPARE, JUMP, JUMP_INDIRECT, REGISTER }
    data class Value(
        val location: ValueLocation, val type: Type = Type.INT, val constant: Long = 0,
        val register: Int = -1, val symbol: Symbol? = null, val lvalue: Boolean = false,
        val unsigned: Boolean = false, val secondRegister: Int = -1,
    )
    data class BoundsPrologue(val sectionOffset: Long, val instructionOffset: Int, val addEpilogue: Boolean = false)
    data class BoundsRelocation(val wordIndex: Int, val symbol: Symbol, val type: String, val addend: Long = 0)
    data class BoundsEpilogue(val patchedPrologue: List<Int>, val body: List<Int>, val relocations: List<BoundsRelocation>, val terminator: Long?)
    data class AbiField(val type: AbiType, val offset: Int)
    data class AbiType(
        val type: Type, val size: Int, val alignment: Int = 8,
        val fields: List<AbiField> = emptyList(), val union: Boolean = false,
        val arrayCount: Int? = null, val elementType: AbiType? = null,
    )
    data class AbiAssignment(val stackBytes: Int, val locations: List<Int>)
    data class CallPlan(val stackBytes: Int, val argumentLocations: List<Int>, val structureTemporaryOffsets: Map<Int, Int>)
    data class FunctionFramePlan(
        val parameterLocations: List<Int>, val parameterOffsets: List<Int>, val savedIntegerPairs: Int,
        val savedVectorPairs: Int, val saveX8: Boolean, val variadicStackOffset: Int,
        val generalRegisterOffset: Int, val vectorRegisterOffset: Int, val setupSlots: Int = 6,
    )
    data class VaArgPlan(val size: Int, val alignment: Int, val homogeneousCount: Int, val indirect: Boolean, val registerClass: String)

    companion object {
        const val NB_REGS = 28
        const val TREG_R30 = 19
        const val TREG_F_BASE = 20
        const val RC_INT = 1
        const val RC_FLOAT = 2
        const val PTR_SIZE = 8
        const val LDOUBLE_SIZE = 16
        const val LDOUBLE_ALIGN = 16
        const val MAX_ALIGN = 16
        const val ARM64_MOVZ = 0x52800000
        const val ARM64_MOVN = 0x12800000
        const val ARM64_MOVK = 0xf2800000.toInt()
        const val ARM64_MOVZ64 = 0xd2800000.toInt()
        const val ARM64_MOVN64 = 0x92800000.toInt()
        const val ARM64_ADD_IMM = 0x11000000
        const val ARM64_ADD_REG = 0x0b000000
        const val ARM64_SUB_REG = 0x4b000000
        const val ARM64_ADRP = 0x90000000.toInt()
        const val ARM64_LDR_X = 0xf9400000.toInt()
        const val ARM64_LDR_B = 0x39400000
        const val ARM64_LDR_SCALAR = 0x3d400000
        const val ARM64_LDR_D_REG = 0xfc606800.toInt()
        const val ARM64_LDR_X_REG = 0xf8606800.toInt()
        const val ARM64_LDR_Q_REG = 0x3c606800
        const val ARM64_LDUR_Q = 0x3c400000
        const val ARM64_STR_Q_REG = 0x3c206800
        const val ARM64_B = 0x14000000
        const val ARM64_NOP = 0xd503201f.toInt()
        const val VT_CONST = 0x30
        const val VT_LLOCAL = 0x31
        const val VT_LOCAL = 0x32
        const val VT_CMP = 0x33
        const val VT_JMP = 0x34
        const val VT_JMPI = 0x35
        const val VT_VALMASK = 0x3f
        const val VT_LVAL = 0x100
        const val VT_SYM = 0x200
        val targetMachineDefinitions = listOf("__aarch64__", "__AARCH64EL__")
        fun machoTargetMachineDefinitions(): List<String> = listOf("__aarch64__", "__arm64__", "__AARCH64EL__")
        fun registerClasses(): IntArray = IntArray(NB_REGS) { reg -> when {
            reg <= 15 -> RC_INT or (1 shl (2 + reg))
            reg <= 18 -> 1 shl (2 + reg)
            reg == TREG_R30 -> 1 shl 21
            else -> RC_FLOAT or (1 shl (22 + reg - TREG_F_BASE))
        } }
        fun integerRegister(register: Int): Int { require(register in 0..TREG_R30); return if (register < TREG_R30) register else 30 }
        fun floatingRegister(register: Int): Int { require(register in TREG_F_BASE..TREG_F_BASE + 7); return register - TREG_F_BASE }
        fun typeSize(type: Type): Int = when (type) {
            Type.BYTE, Type.BOOL -> 0; Type.SHORT -> 1; Type.INT, Type.FLOAT -> 2
            Type.LONG_LONG, Type.POINTER, Type.FUNCTION, Type.STRUCT, Type.DOUBLE -> 3
            Type.LONG_DOUBLE -> 4
        }
    }

    fun o(word: Int) { if (!noCode()) output(word) }
    fun emitU32(word: Long) = o(word.toInt())

    /** Returns the ARM64 logical bitmask immediate encoding, or -1 for invalid masks. */
    fun encodeBitmaskImmediate(input: ULong): Int {
        var value = input
        val negative = value and 1uL != 0uL
        if (negative) value = value.inv()
        if (value == 0uL) return -1
        fun periodic(bits: Int) = (value shr bits) == (value and ((1uL shl (64 - bits)) - 1uL))
        val repetition = when {
            periodic(2) -> { value = value and 3uL; 2 }
            periodic(4) -> { value = value and 15uL; 4 }
            periodic(8) -> { value = value and 255uL; 8 }
            periodic(16) -> { value = value and 65535uL; 16 }
            periodic(32) -> { value = value and 0xffffffffuL; 32 }
            else -> 64
        }
        var position = 0
        for (bits in listOf(32, 16, 8, 4, 2, 1)) {
            val mask = (1uL shl bits) - 1uL
            if (value and mask == 0uL) { value = value shr bits; position += bits }
        }
        var length = 0
        for (bits in listOf(32, 16, 8, 4, 2, 1)) {
            val mask = (1uL shl bits) - 1uL
            if (value.inv() and mask == 0uL) { value = value shr bits; length += bits }
        }
        if (value != 0uL) return -1
        if (negative) { position = (position + length) and (repetition - 1); length = repetition - length }
        return (if (repetition == 64) 0x1000 else 0) or ((((repetition - 1) xor 31) shl 1) and 63) or
            (((repetition - position) and (repetition - 1)) shl 6) or (length - 1)
    }

    fun movi(register: Int, value: ULong): Int {
        val rd = register and 31
        val lowMask = 0xffffuL
        val x = value
        if (x and lowMask.inv() == 0uL) return ARM64_MOVZ or rd or (x.toInt() shl 5)
        if (x and (lowMask shl 16).inv() == 0uL) return ARM64_MOVZ or (1 shl 21) or rd or ((x shr 11).toInt() and 0x1fffe0)
        if (x and (lowMask shl 32).inv() == 0uL) return ARM64_MOVZ64 or (2 shl 21) or rd or ((x shr 27).toInt() and 0x1fffe0)
        if (x and (lowMask shl 48).inv() == 0uL) return ARM64_MOVZ64 or (3 shl 21) or rd or ((x shr 43).toInt() and 0x1fffe0)
        if (x and lowMask.inv() == lowMask shl 16) return ARM64_MOVN or rd or ((x.inv().toInt() shl 5) and 0x1fffe0)
        if (x and (lowMask shl 16).inv() == lowMask) return ARM64_MOVN or (1 shl 21) or rd or ((x.inv().toInt() ushr 11) and 0x1fffe0)
        if (x.inv() and lowMask == 0uL) return ARM64_MOVN64 or rd or ((x.inv().toInt() shl 5) and 0x1fffe0)
        if (x.inv() and (lowMask shl 16) == 0uL) return ARM64_MOVN64 or (1 shl 21) or rd or ((x.inv().toInt() ushr 11) and 0x1fffe0)
        if (x.inv() and (lowMask shl 32) == 0uL) return ARM64_MOVN64 or (2 shl 21) or rd or ((x.inv().toInt() ushr 27) and 0x1fffe0)
        if (x.inv() and (lowMask shl 48) == 0uL) return ARM64_MOVN64 or (3 shl 21) or rd or ((x.inv().toInt() ushr 43) and 0x1fffe0)
        if (x shr 32 == 0uL) {
            val encoded = encodeBitmaskImmediate(x or (x shl 32))
            if (encoded >= 0) return 0x320003e0 or rd or (encoded shl 10)
        }
        val encoded = encodeBitmaskImmediate(x)
        if (encoded >= 0) return 0xb20003e0.toInt() or rd or (encoded shl 10)
        return 0
    }

    fun moveImmediate(register: Int, value: ULong) {
        val single = movi(register, value)
        if (single != 0) { o(single); return }
        var zeros = 0
        var ones = 0
        var selected = value
        var base = ARM64_MOVZ64
        for (shift in 0..3) {
            val half = (value shr (shift * 16)) and 0xffffuL
            if (half == 0uL) zeros++
            if (half == 0xffffuL) ones++
        }
        if (ones > zeros) { selected = value.inv(); base = ARM64_MOVN64 }
        var first = -1
        for (i in 0..3) if ((selected shr (i * 16)) and 0xffffuL != 0uL) { first = i; break }
        if (first >= 0) o(base or (register and 31) or ((((selected shr (first * 16)).toInt()) and 0xffff) shl 5) or (first shl 21))
        for (i in (first + 1)..3) {
            val half = ((value shr (i * 16)).toInt()) and 0xffff
            if ((selected shr (i * 16)) and 0xffffuL != 0uL)
                o(ARM64_MOVK or 0x80000000.toInt() or (register and 31) or (half shl 5) or (i shl 21))
        }
    }

    /** Patches a linked branch chain; patchWord returns the next link stored at the current site. */
    fun patchBranchChain(first: Int, target: Int) {
        var site = first
        while (site != 0) {
            val next = patchWord(site, 0)
            val delta = target - site
            if (delta.toLong() + 0x8000000L !in 0L until 0x10000000L) error("branch out of range")
            patchWord(site, if (delta == 4) ARM64_NOP else ARM64_B or ((delta shr 2) and 0x3ffffff))
            site = next
        }
    }

    fun typeSize(type: Int): Int = when (type and 0x0f) {
        1, 11 -> 0
        2 -> 1
        3, 8 -> 2
        4, 5, 6, 7, 9 -> 3
        10 -> 4
        else -> error("invalid AArch64 type") as Int
    }

    fun stackOffset(register: Int, offset: ULong) {
        val subtract = offset shr 63 != 0uL
        val magnitude = if (subtract) 0uL - offset else offset
        if (magnitude < 4096uL) {
            o(ARM64_ADD_IMM or 0x80000000.toInt() or (31 shl 5) or (register and 31) or (magnitude.toInt() shl 10) or (if (subtract) (1 shl 30) else 0))
        } else {
            moveImmediate(30, magnitude)
            o(ARM64_ADD_REG or 0x80000000.toInt() or (30 shl 16) or (31 shl 5) or (register and 31) or (if (subtract) (1 shl 30) else 0))
        }
    }

    fun checkOffset(size: Int, offset: ULong, peTarget: Boolean = false): ULong {
        if (peTarget) return 0uL
        val scaledMask = 0xfffuL shl size
        if (offset and scaledMask.inv() == 0uL || offset < 256uL || 0uL - offset <= 256uL) return ULong.MAX_VALUE
        if (offset and scaledMask != 0uL) return scaledMask
        if (offset and 0x1ffuL != 0uL) return 0x1ffuL
        return 0uL
    }

    fun loadInteger(signed: Boolean, size: Int, destination: Int, base: Int, offset: ULong) {
        val scaledMask = 0xfffuL shl size
        val signBit = if (signed) 1 shl 23 else 0
        if (size >= 2) {
            o(ARM64_LDR_B or (destination and 31) or ((base and 31) shl 5) or (offset.toInt() shl (10 - size)) or signBit or (size shl 30))
        } else if (offset and scaledMask.inv() == 0uL) {
            o(ARM64_LDR_B or (destination and 31) or ((base and 31) shl 5) or (offset.toInt() shl (10 - size)) or signBit or (size shl 30))
        } else if (offset < 256uL || 0uL - offset <= 256uL) {
            o(0x38400000 or (destination and 31) or ((base and 31) shl 5) or ((offset.toInt() and 511) shl 12) or signBit or (size shl 30))
        } else {
            moveImmediate(30, offset)
            o(ARM64_LDR_X_REG or (destination and 31) or ((base and 31) shl 5) or (30 shl 16) or ((if (signed) 2 else 1) shl 22) or (size shl 30))
        }
    }

    fun loadVector(size: Int, destination: Int, base: Int, offset: ULong) {
        val scaledMask = 0xfffuL shl size
        if (offset and scaledMask.inv() == 0uL)
            o(ARM64_LDR_SCALAR or destination or (base shl 5) or (offset.toInt() shl (10 - size)) or ((size and 4) shl 21) or ((size and 3) shl 30))
        else if (offset < 256uL || 0uL - offset <= 256uL)
            o(0x3c400000 or destination or (base shl 5) or ((offset.toInt() and 511) shl 12) or ((size and 4) shl 21) or ((size and 3) shl 30))
        else {
            moveImmediate(30, offset)
            o(ARM64_LDR_Q_REG or destination or (base shl 5) or (30 shl 16) or (size shl 30) or ((size and 4) shl 21))
        }
    }

    fun loadStructure(register: Int, size: Int) {
        when (size) {
            0 -> Unit
            1 -> loadInteger(false, 0, register, register, 0uL)
            2 -> loadInteger(false, 1, register, register, 0uL)
            3 -> { loadInteger(false, 1, 30, register, 0uL); loadInteger(false, 0, register, register, 2uL); o(0x2a0043c0 or register or (register shl 16)) }
            4 -> loadInteger(false, 2, register, register, 0uL)
            5, 6, 7 -> {
                loadInteger(false, 2, 30, register, 0uL)
                loadInteger(false, if (size == 5) 0 else if (size == 6) 1 else 2, register, register, (if (size == 7) 3 else 4).toULong())
                if (size == 7) o(0x53087c00 or register or (register shl 5))
                o(0xaa0083c0.toInt() or register or (register shl 16))
            }
            8 -> loadInteger(false, 3, register, register, 0uL)
            in 9..15 -> {
                val upperSize = when (size) { 9 -> 0; 10 -> 1; 11, 12 -> 2; else -> 3 }
                val upperOff = when (size) { 9, 10 -> 8; 11 -> 7; 12 -> 8; 13 -> 5; 14 -> 6; else -> 7 }
                loadInteger(false, upperSize, register + 1, register, upperOff.toULong())
                if (size >= 13) o((when (size) { 13 -> 0xd358fc00L; 14 -> 0xd350fc00L; else -> 0xd348fc00L }).toInt() or (register + 1) or ((register + 1) shl 5))
                else if (size == 11) o(0x53087c00 or (register + 1) or ((register + 1) shl 5))
                loadInteger(false, 3, register, register, 0uL)
            }
            16 -> o(0xa9400000.toInt() or register or ((register + 1) shl 10) or (register shl 5))
            else -> error("unsupported structure size")
        }
    }

    fun storeInteger(size: Int, source: Int, base: Int, offset: ULong) {
        val scaledMask = 0xfffuL shl size
        if (offset and scaledMask.inv() == 0uL)
            o(0x39000000 or source or (base shl 5) or (offset.toInt() shl (10 - size)) or (size shl 30))
        else if (offset < 256uL || 0uL - offset <= 256uL)
            o(0x38000000 or source or (base shl 5) or ((offset.toInt() and 511) shl 12) or (size shl 30))
        else { moveImmediate(30, offset); o(0x38206800 or source or (base shl 5) or (30 shl 16) or (size shl 30)) }
    }

    fun storeVector(size: Int, source: Int, base: Int, offset: ULong) {
        val scaledMask = 0xfffuL shl size
        if (offset and scaledMask.inv() == 0uL)
            o(0x3d000000 or source or (base shl 5) or (offset.toInt() shl (10 - size)) or ((size and 4) shl 21) or ((size and 3) shl 30))
        else if (offset < 256uL || 0uL - offset <= 256uL)
            o(0x3c000000 or source or (base shl 5) or ((offset.toInt() and 511) shl 12) or ((size and 4) shl 21) or ((size and 3) shl 30))
        else { moveImmediate(30, offset); o(ARM64_STR_Q_REG or source or (base shl 5) or (30 shl 16) or (size shl 30) or ((size and 4) shl 21)) }
    }

    private fun addRelocation(symbol: Symbol, type: String, addend: Long) {
        relocation(symbol, position(), type, addend)
    }

    /** Emits the address sequence used for TLS, external, and local symbols. */
    fun loadSymbolAddress(register: Int, symbol: Symbol, addend: Long = 0, peTarget: Boolean = false) {
        val r = register and 31
        if (symbol.isTls) {
            if (peTarget) {
                loadSymbolAddress(30, Symbol("__tls_index"), 0, true)
                o(0xb94003de.toInt())
                o(0xf9402e40.toInt() or r)
                o(0x8b1e0c1e.toInt() or (r shl 5))
                o(0xf94003c0.toInt() or r)
            } else o(0xd53bd040.toInt() or r)
            addRelocation(symbol, "R_AARCH64_TLSLE_ADD_TPREL_HI12", addend)
            o(ARM64_ADD_IMM or 0x80000000.toInt() or (1 shl 22) or (r shl 5) or r)
            addRelocation(symbol, "R_AARCH64_TLSLE_ADD_TPREL_LO12", addend)
            o(ARM64_ADD_IMM or 0x80000000.toInt() or (r shl 5) or r)
        } else if (!symbol.isStatic && !peTarget) {
            addRelocation(symbol, "R_AARCH64_ADR_GOT_PAGE", 0)
            o(ARM64_ADRP or r)
            addRelocation(symbol, "R_AARCH64_LD64_GOT_LO12_NC", 0)
            o(ARM64_LDR_X or (r shl 5) or r)
            if (addend > 0xffffff) {
                moveImmediate(16, addend.toULong())
                o(ARM64_ADD_REG or 0x80000000.toInt() or (16 shl 16) or (r shl 5) or r)
            } else {
                val low = addend and 0xfff
                if (low != 0L) o(ARM64_ADD_IMM or 0x80000000.toInt() or (r shl 5) or r or (low.toInt() shl 10))
                val high = (addend shr 12) and 0xfff
                if (high != 0L) o(ARM64_ADD_IMM or 0x80400000.toInt() or (r shl 5) or r or (high.toInt() shl 10))
            }
        } else {
            addRelocation(symbol, "R_AARCH64_ADR_PREL_PG_HI21", addend)
            o(ARM64_ADRP or r)
            addRelocation(symbol, "R_AARCH64_ADD_ABS_LO12_NC", addend)
            o(ARM64_ADD_IMM or 0x80000000.toInt() or (r shl 5) or r)
        }
    }

    private fun isFloatRegister(register: Int): Boolean = register in TREG_F_BASE..TREG_F_BASE + 7
    private fun intReg(register: Int): Int = integerRegister(register)
    private fun floatReg(register: Int): Int = floatingRegister(register)

    /** Loads a TCC value into a target register. Jump and compare values use callbacks for compiler state. */
    fun loadValue(
        register: Int,
        value: Value,
        patchJumpChain: (Long) -> Unit = {},
        loadCompare: (Int, Value) -> Unit = { _, _ -> error("compare value requires a comparison loader") },
    ) {
        val size = typeSize(value.type)
        val signed = !value.unsigned
        val floating = isFloatRegister(register)
        val dst = if (floating) floatReg(register) else intReg(register)
        val baseReg = if (value.register >= 0) intReg(value.register) else 0
        val offset = value.constant.toInt().toLong().toULong()
        if (value.lvalue && value.location == ValueLocation.LOCAL) {
            if (floating) loadVector(size, floatReg(register), 29, offset) else loadInteger(signed, size, dst, 29, offset)
            return
        }
        if (value.lvalue && value.location == ValueLocation.CONSTANT) {
            moveImmediate(30, value.constant.toULong())
            if (floating) loadVector(size, floatReg(register), 30, 0uL) else loadInteger(signed, size, dst, 30, 0uL)
            return
        }
        if (value.lvalue && value.location == ValueLocation.REGISTER) {
            if (floating) loadVector(size, floatReg(register), baseReg, 0uL) else loadInteger(signed, size, dst, baseReg, 0uL)
            return
        }
        if (value.lvalue && value.location == ValueLocation.INDIRECT_LOCAL) {
            loadInteger(false, 3, 30, 29, offset)
            if (floating) loadVector(size, floatReg(register), 30, 0uL) else loadInteger(signed, size, dst, 30, 0uL)
            return
        }
        if (value.lvalue && value.symbol != null) {
            val mask = checkOffset(size, offset)
            loadSymbolAddress(30, value.symbol, (offset and mask.inv()).toLong())
            if (floating) loadVector(size, floatReg(register), 30, offset and mask) else loadInteger(signed, size, dst, 30, offset and mask)
            return
        }
        if (value.symbol != null && value.location == ValueLocation.CONSTANT) {
            loadSymbolAddress(dst, value.symbol, value.constant)
            return
        }
        when (value.location) {
            ValueLocation.CONSTANT -> moveImmediate(dst, if (size == 3) value.constant.toULong() else value.constant.toInt().toUInt().toULong())
            ValueLocation.LOCAL -> {
                val delta = -value.constant.toInt()
                if (delta < 0x1000) o(0xd10003a0.toInt() or dst or (delta shl 10))
                else { moveImmediate(30, delta.toLong().toULong()); o(0xcb0003a0.toInt() or dst or (30 shl 16)) }
            }
            ValueLocation.REGISTER -> {
                val srcIsFloat = isFloatRegister(value.register)
                if (floating && srcIsFloat) {
                    if (value.type == Type.LONG_DOUBLE) o(0x4ea01c00 or dst or (floatReg(value.register) shl 5))
                    else o(0x1e604000 or dst or (floatReg(value.register) shl 5))
                } else if (!floating && !srcIsFloat) o(0xaa0003e0.toInt() or dst or (intReg(value.register) shl 16))
                else error("cannot move between integer and floating register classes")
            }
            ValueLocation.JUMP, ValueLocation.JUMP_INDIRECT -> {
                val indirect = if (value.location == ValueLocation.JUMP_INDIRECT) 1 else 0
                moveImmediate(dst, indirect.toULong())
                o(ARM64_B or 2)
                patchJumpChain(value.constant)
                moveImmediate(dst, (indirect xor 1).toULong())
            }
            ValueLocation.COMPARE -> loadCompare(register, value)
            else -> error("unsupported AArch64 value location: ${value.location}")
        }
    }

    /** Stores a target register through an lvalue represented by the TCC value model. */
    fun storeValue(register: Int, value: Value) {
        val size = typeSize(value.type)
        val floating = isFloatRegister(register)
        val src = if (floating) floatReg(register) else intReg(register)
        val offset = value.constant.toInt().toLong().toULong()
        when {
            value.lvalue && value.location == ValueLocation.LOCAL -> if (floating) storeVector(size, src, 29, offset) else storeInteger(size, src, 29, offset)
            value.lvalue && value.location == ValueLocation.CONSTANT -> {
                moveImmediate(30, value.constant.toULong())
                if (floating) storeVector(size, src, 30, 0uL) else storeInteger(size, src, 30, 0uL)
            }
            value.lvalue && value.location == ValueLocation.REGISTER -> if (floating) storeVector(size, src, intReg(value.register), 0uL) else storeInteger(size, src, intReg(value.register), 0uL)
            value.lvalue && value.symbol != null -> {
                val mask = checkOffset(size, offset)
                loadSymbolAddress(30, value.symbol, (offset and mask.inv()).toLong())
                if (floating) storeVector(size, src, 30, offset and mask) else storeInteger(size, src, 30, offset and mask)
            }
            else -> error("unsupported AArch64 store value: $value")
        }
    }

    fun emitBranchOrCall(branch: Boolean, directSymbol: Symbol? = null, indirectTargetRegister: Int = 30) {
        if (directSymbol != null) {
            addRelocation(directSymbol, if (branch) "R_AARCH64_JUMP26" else "R_AARCH64_CALL26", 0)
            o(if (branch) ARM64_B else 0x94000000.toInt())
        } else {
            o((if (branch) 0xd61f0000L else 0xd63f0000L).toInt() or (intReg(indirectTargetRegister) shl 5))
        }
    }

    fun emitStaticCall(helper: Symbol) {
        addRelocation(helper, "R_AARCH64_CALL26", 0)
        o(0x94000000.toInt())
    }

    fun emitBoundsPrologue(sectionOffset: Long, instructionOffset: Int): BoundsPrologue {
        repeat(4) { o(ARM64_NOP) }
        return BoundsPrologue(sectionOffset, instructionOffset)
    }

    fun boundsEpilogue(state: BoundsPrologue, sectionOffset: Long, boundsSymbol: Symbol,
        newLocalHelper: Symbol, deleteLocalHelper: Symbol, addEpilogue: Boolean = state.addEpilogue): BoundsEpilogue {
        val modified = state.sectionOffset != sectionOffset
        if (!modified && !addEpilogue) return BoundsEpilogue(emptyList(), emptyList(), emptyList(), null)
        val prologue = mutableListOf<Int>()
        val body = mutableListOf<Int>()
        val relocations = mutableListOf<BoundsRelocation>()
        fun addAddress(words: MutableList<Int>) {
            val index = words.size
            words += ARM64_ADRP
            relocations += BoundsRelocation(index, boundsSymbol, "R_AARCH64_ADR_PREL_PG_HI21")
            words += ARM64_ADD_IMM or 0x80000000.toInt()
            relocations += BoundsRelocation(index + 1, boundsSymbol, "R_AARCH64_ADD_ABS_LO12_NC")
        }
        fun addCall(words: MutableList<Int>, symbol: Symbol) {
            relocations += BoundsRelocation(words.size, symbol, "R_AARCH64_CALL26")
            words += 0x94000000.toInt()
        }
        if (modified) { addAddress(prologue); addCall(prologue, newLocalHelper) }
        body += listOf(0xa9bf07e0.toInt(), 0x3c9f0fe0)
        addAddress(body)
        addCall(body, deleteLocalHelper)
        body += listOf(0x3cc107e0, 0xa8c107e0.toInt())
        return BoundsEpilogue(prologue, body, relocations, 0L)
    }

    fun fillNops(byteCount: Int) {
        require(byteCount % 4 == 0) { "code size must be aligned to four bytes" }
        repeat(byteCount.coerceAtLeast(0) / 4) { o(ARM64_NOP) }
    }

    fun generateJump(target: Int): Int {
        val site = position()
        o(ARM64_B or ((target - site shr 2) and 0x3ffffff))
        return site
    }

    fun generateJumpAddress(address: Int) { o(ARM64_B or ((address - position() shr 2) and 0x3ffffff)) }

    private fun isAbiFloat(type: Type): Boolean = type == Type.FLOAT || type == Type.DOUBLE

    private fun homogeneousFloatAux(type: AbiType, fsize: IntArray, count: Int): Int {
        if (isAbiFloat(type.type)) {
            if (count >= 4 || fsize[0] != 0 && fsize[0] != type.size) return -1
            fsize[0] = type.size
            return count + 1
        }
        if (type.type == Type.STRUCT) {
            if (!type.union) {
                var n = count
                for (field in type.fields) {
                    if (field.offset != (n - count) * fsize[0]) return -1
                    n = homogeneousFloatAux(field.type, fsize, n)
                    if (n < 0) return -1
                }
                return if (type.size == (n - count) * fsize[0]) n else -1
            }
            var n = count
            for (field in type.fields) {
                val branchSize = intArrayOf(fsize[0])
                val branch = homogeneousFloatAux(field.type, branchSize, count)
                if (branch < 0) return -1
                if (n == count || branch < n) { n = branch; fsize[0] = branchSize[0] }
            }
            return if (type.size == (n - count) * fsize[0]) n else -1
        }
        if (type.arrayCount != null && type.elementType != null) {
            if (type.arrayCount == 0) return count
            val elementCount = homogeneousFloatAux(type.elementType, fsize, count)
            if (elementCount < 0 || elementCount != count && type.arrayCount > 4) return -1
            val result = count + type.arrayCount * (elementCount - count)
            return if (result <= 4) result else -1
        }
        return -1
    }

    /** Returns the homogeneous float aggregate element count and writes element size to outSize[0]. */
    fun homogeneousFloatAggregate(type: AbiType, outSize: IntArray? = null): Int {
        if (type.type != Type.STRUCT) return 0
        val fsize = intArrayOf(0)
        val count = homogeneousFloatAux(type, fsize, 0)
        if (count !in 1..4) return 0
        if (outSize != null) outSize[0] = fsize[0]
        return count
    }

    /** Implements AArch64 PCS parameter placement; locations encode GPR, vector, or stack slots. */
    fun assignAbiArguments(types: List<AbiType>, variadicIndex: Int = 0, macho: Boolean = false, pe: Boolean = false): AbiAssignment {
        var nextInteger = 0
        var nextVector = 0
        var stack = 32
        val locations = MutableList(types.size) { -1 }
        for (i in types.indices) {
            val type = types[i]
            var hfa = homogeneousFloatAggregate(type)
            var size = if (type.type == Type.POINTER || type.type == Type.FUNCTION) 8 else type.size
            val alignment = if (type.type == Type.POINTER || type.type == Type.FUNCTION) 8 else type.alignment
            if (macho && variadicIndex > 0 && i == variadicIndex) { nextInteger = 8; nextVector = 8 }
            if (pe && variadicIndex > 0 && i >= variadicIndex) {
                hfa = 0
                if (isAbiFloat(type.type)) size = 8
            }
            if (hfa == 0 && size > 16) {
                if (nextInteger < 8) locations[i] = nextInteger++ * 2 + 1
                else { stack = (stack + 7) and -8; locations[i] = stack + 1; stack += 8 }
                continue
            }
            if (type.type == Type.STRUCT && hfa == 0) size = (size + 7) and -8
            if (isAbiFloat(type.type) && nextVector < 8) { locations[i] = 16 + (nextVector++ * 2); continue }
            if (hfa != 0 && nextVector + hfa <= 8) { locations[i] = 16 + nextVector * 2; nextVector += hfa; continue }
            if (hfa != 0) { nextVector = 8; size = (size + 7) and -8 }
            if (hfa != 0 || type.type == Type.LONG_DOUBLE) {
                stack = (stack + 7) and -8
                stack = (stack + alignment - 1) and -alignment
            }
            if (type.type == Type.FLOAT) size = 8
            if (hfa != 0 || isAbiFloat(type.type)) { locations[i] = stack; stack += size; continue }
            if (type.type != Type.STRUCT && size <= 8 && nextInteger < 8) { locations[i] = nextInteger++ * 2; continue }
            if (alignment == 16) nextInteger = (nextInteger + 1) and -2
            if (type.type != Type.STRUCT && size == 16 && nextInteger < 7) { locations[i] = nextInteger * 2; nextInteger += 2; continue }
            if (type.type == Type.STRUCT && size <= (8 - nextInteger) * 8) {
                locations[i] = nextInteger * 2
                nextInteger += (size + 7) shr 3
                continue
            }
            nextInteger = 8
            stack = (stack + 7) and -8
            stack = (stack + alignment - 1) and -alignment
            if (type.type == Type.STRUCT) { locations[i] = stack; stack += size; continue }
            if (size < 8) size = 8
            locations[i] = stack
            stack += size
        }
        return AbiAssignment(stack - 32, locations)
    }

    fun functionArgumentCount(argumentTypes: List<AbiType>): Int = argumentTypes.size

    /** Emits stack subtraction, including the Windows large allocation helper path. */
    fun subtractStackPointer(byteCount: ULong, peTarget: Boolean = false, checkStackHelper: Symbol = Symbol("__chkstk")) {
        if (byteCount == 0uL) return
        if (peTarget && byteCount >= 4096uL) {
            moveImmediate(15, byteCount shr 4)
            emitStaticCall(checkStackHelper)
            o(0xcb2f73ff.toInt())
            return
        }
        if (byteCount shr 24 == 0uL) {
            val low = byteCount and 0xfffuL
            val high = byteCount shr 12
            if (low != 0uL) o(0xd10003ff.toInt() or (low.toInt() shl 10))
            if (high != 0uL) o(0xd14003ff.toInt() or ((high.toInt() and 0xfff) shl 10))
        } else {
            moveImmediate(16, byteCount)
            o(0xcb3063ff.toInt())
        }
    }

    /** Plans argument placement and stack copies for a call after applying the PCS rules. */
    fun planCall(arguments: List<AbiType>, variadicIndex: Int = 0, macho: Boolean = false, pe: Boolean = false): CallPlan {
        val abi = assignAbiArguments(arguments, variadicIndex, macho, pe)
        var stack = abi.stackBytes
        val temporaries = linkedMapOf<Int, Int>()
        for (i in arguments.indices.reversed()) {
            if (abi.locations[i] and 1 != 0) {
                val type = arguments[i]
                stack = (stack + type.alignment - 1) and -type.alignment
                temporaries[i] = stack
                stack += type.size
            }
        }
        stack = (stack + 15) and -16
        require(stack < 0x1000000) { "stack size too big: $stack" }
        return CallPlan(stack, abi.locations, temporaries)
    }

    fun restoreStackPointer(byteCount: ULong) {
        val low = byteCount and 0xfffuL
        val high = byteCount shr 12
        if (low != 0uL) o(0x910003ff.toInt() or (low.toInt() shl 10))
        if (high != 0uL) o(0x914003ff.toInt() or (high.toInt() shl 10))
    }

    fun peParameterOffset(location: Int): Int = when {
        location < 16 -> 160 + location / 2 * 8
        location < 32 -> 16 + (location - 16) / 2 * 16
        else -> 224 + ((location - 32) shr 1 shl 1)
    }

    /** Computes the fixed 224-byte AArch64 entry frame and variadic save-area boundaries. */
    fun planFunctionFrame(parameterTypes: List<AbiType>, variadic: Boolean = false,
        macho: Boolean = false, pe: Boolean = false): FunctionFramePlan {
        val locations = assignAbiArguments(parameterTypes, if (variadic) parameterTypes.size else 0, macho, pe).locations
        var saveX8 = variadic && !macho
        var lastInteger = if (variadic && !macho) 4 else 0
        var lastVector = if (variadic && !macho) 4 else 0
        for (i in locations.indices) {
            val location = locations[i]
            if (location == 1) saveX8 = true
            if (location in 0..15) {
                val size = parameterTypes[i].size
                lastInteger = maxOf(lastInteger, location / 4 + 1 + (size - 1) / 8)
            } else if (location in 16..31) {
                val hfa = homogeneousFloatAggregate(parameterTypes[i])
                lastVector = maxOf(lastVector, location / 4 - 3 + if (hfa != 0) hfa - 1 else 0)
            }
        }
        lastInteger = lastInteger.coerceAtMost(4)
        lastVector = lastVector.coerceAtMost(4)
        val offsets = locations.map { location ->
            if (pe) peParameterOffset(location)
            else when { location < 16 -> 160 + location / 2 * 8; location < 32 -> 16 + (location - 16) / 2 * 16; else -> 224 + ((location - 32) shr 1 shl 1) }
        }
        val stack = if (pe && variadic && locations.isNotEmpty()) peParameterOffset(locations.last()) else assignAbiArguments(parameterTypes, if (variadic) parameterTypes.size else 0, macho, pe).stackBytes
        return FunctionFramePlan(locations, offsets, lastInteger, lastVector, saveX8, stack,
            if (variadic && !macho) -64 else 0, if (variadic && !macho) -128 else 0)
    }

    /** Emits the fixed frame save sequence described by planFunctionFrame. */
    fun emitFunctionPrologue(plan: FunctionFramePlan) {
        o(0xa9b27bfd.toInt())
        o(0x910003fd.toInt())
        for (i in 0 until plan.savedVectorPairs) o(0xad0087e0.toInt() + i * 0x10000 + (i shl 11) + (i shl 1))
        if (plan.saveX8) o(0xa90923e8.toInt())
        for (i in 0 until plan.savedIntegerPairs) o(0xa90a07e0.toInt() + i * 0x10000 + (i shl 11) + (i shl 1))
        repeat(plan.setupSlots) { o(ARM64_NOP) }
    }

    fun emitFunctionEpilogue() {
        o(0x910003bf.toInt())
        o(0xa8ce7bfd.toInt())
        o(0xd65f03c0.toInt())
    }

    /** Emits the AAPCS64 va_list initial fields; pointerRegister points at the va_list object. */
    fun emitVaStart(pointerRegister: Int, state: FunctionFramePlan, peTarget: Boolean = false, macho: Boolean = false) {
        val r = intReg(pointerRegister)
        if (peTarget) {
            if (state.variadicStackOffset != 0) {
                moveImmediate(30, state.variadicStackOffset.toULong())
                o(0x8b1e03be.toInt())
            } else o(0x910283be.toInt())
            o(0xf900001e.toInt() or (r shl 5))
            return
        }
        if (state.variadicStackOffset != 0) {
            moveImmediate(30, (state.variadicStackOffset + 224).toULong())
            o(0x8b1e03be.toInt())
        } else o(0x910383be.toInt())
        o(0xf900001e.toInt() or (r shl 5))
        if (!macho) {
            if (state.generalRegisterOffset != 0) {
                if (state.variadicStackOffset != 0) o(0x910383be.toInt())
                o(0xf900041e.toInt() or (r shl 5))
            }
            if (state.vectorRegisterOffset != 0) {
                o(0x910243be.toInt())
                o(0xf900081e.toInt() or (r shl 5))
            }
            moveImmediate(30, state.generalRegisterOffset.toLong().toULong())
            o(0xb900181e.toInt() or (r shl 5))
            moveImmediate(30, state.vectorRegisterOffset.toLong().toULong())
            o(0xb9001c1e.toInt() or (r shl 5))
        }
    }

    fun planVaArg(type: AbiType): VaArgPlan {
        val hfa = if (isAbiFloat(type.type)) 1 else homogeneousFloatAggregate(type)
        return VaArgPlan(type.size, type.alignment, hfa, type.size > 16,
            if (hfa != 0) "vector" else "general")
    }
}
