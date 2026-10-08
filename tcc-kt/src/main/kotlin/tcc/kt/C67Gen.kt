package tcc.kt

/** TMS320C67xx backend helpers mechanically translated from c67-gen.c. */
class C67Gen(
    private val outputWord: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val assembler: (String, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    private val relocate: (Int, Int, String) -> Unit = { _, _, _ -> },
    private val readWord: (Int) -> Int = { 0 },
    private val writeWord: (Int, Int) -> Unit = { _, _ -> },
    private val position: () -> Int = { 0 },
) {
    companion object {
        const val NB_REGS = 24
        const val RC_INT = 0x0001
        const val RC_FLOAT = 0x0002
        const val RC_EAX = 0x0004
        const val RC_ST0 = 0x0008
        const val RC_ECX = 0x0010
        const val RC_EDX = 0x0020
        const val RC_INT_BSIDE = 0x00000040
        const val C67_A0 = 105
        const val C67_SP = 106
        const val C67_B3 = 107
        const val C67_FP = 108
        const val C67_B2 = 109
        const val C67_CREG_ZERO = -1
        const val PTR_SIZE = 4
        const val LDOUBLE_SIZE = 12
        const val LDOUBLE_ALIGN = 4
        const val MAX_ALIGN = 8
        const val NO_CALL_ARGS_PASSED_ON_STACK = 10
        val targetMachineDefinitions = listOf("__C67__")
        val registerNames = listOf("A2", "A3", "B0", "B1", "A4", "A5", "B4", "B5", "A6", "A7", "B6", "B7", "A8", "A9", "B8", "B9", "A10", "A11", "B10", "B11", "A12", "A13", "B12", "B13")
        val registerClasses = intArrayOf(
            RC_INT or RC_FLOAT or RC_EAX, RC_INT or RC_ECX, RC_INT or RC_INT_BSIDE or RC_FLOAT or RC_EDX, RC_INT or RC_INT_BSIDE or RC_ST0,
            0x100, 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000, 0x8000,
            0x10000, 0x20000, 0x40000, 0x80000, 0x00100000, 0x00200000, 0x00400000, 0x00800000,
            0x00100000, 0x00200000, 0x00400000, 0x00800000,
        )
    }

    var numberOfCurrentFunctionArguments: Int = 0
    val translateStackToRegister: IntArray = IntArray(NO_CALL_ARGS_PASSED_ON_STACK)
    val parameterLocationsOnStack: IntArray = IntArray(NO_CALL_ARGS_PASSED_ON_STACK)
    var totalBytesPushedOnStack: Int = 0

    fun emit(word: Int) { if (!noCode()) outputWord(word) }

    /** Patches TCC's linked C67 call chain and attaches low/high relocations. */
    fun patchSymbolAddressChain(head: Int, address: Int, symbolForAddress: (Int) -> Int) {
        var site = head
        while (site != 0) {
            val lowInstruction = readWord(site)
            val highInstruction = readWord(site + 4)
            val next = ((lowInstruction ushr 7) and 0xffff) or (((highInstruction ushr 7) and 0xffff) shl 16)
            val symbol = symbolForAddress(address)
            relocate(symbol, site, "R_C60LO16")
            relocate(symbol, site + 4, "R_C60HI16")
            writeWord(site, lowInstruction and (0xffff shl 7).inv())
            writeWord(site + 4, highInstruction and (0xffff shl 7).inv())
            site = next
        }
    }

    fun convertRegisterToClass(register: Int): Int {
        require(register in 4..23)
        return 0x100 shl (register - 4)
    }

    fun mapRegisterNumber(register: Int): Int = when (register) {
        0 -> 2
        1 -> 3
        2 -> 0
        3 -> 1
        in 4..23 -> (((register and -4) shr 1) or (register and 1)) + 2
        C67_A0, C67_B2, C67_B3, C67_CREG_ZERO -> when (register) { C67_B2 -> 2; C67_B3 -> 3; else -> 0 }
        C67_SP, C67_FP -> 15
        else -> throw IllegalArgumentException("invalid C67 register $register")
    }

    fun mapConditionRegister(register: Int): Int = when (register) {
        0 -> 5
        2 -> 1
        3 -> 2
        C67_B2 -> 3
        C67_CREG_ZERO -> 0
        else -> throw IllegalArgumentException("invalid C67 condition register $register")
    }

    fun mapRegisterSide(register: Int): Int = when (register) {
        0, 1, C67_A0, C67_FP -> 0
        2, 3, C67_B2, C67_B3, C67_SP -> 1
        in 4..23 -> (register and 2) shr 1
        else -> throw IllegalArgumentException("invalid C67 register side $register")
    }

    fun mapSUnit(mnemonic: String): Int = when {
        ".S1" in mnemonic -> 0
        ".S2" in mnemonic -> 1
        else -> throw IllegalArgumentException("invalid C67 S unit: $mnemonic")
    }

    fun mapDUnit(mnemonic: String): Int = when {
        ".D1" in mnemonic -> 0
        ".D2" in mnemonic -> 1
        else -> throw IllegalArgumentException("invalid C67 D unit: $mnemonic")
    }

    fun asm(mnemonic: String, a: Int = 0, b: Int = 0, c: Int = 0) = assemble(mnemonic, a, b, c)

    private fun arithmetic(opcode: Int, fixed: Int, a: Int, b: Int, destination: Int,
        opcodeShift: Int = 5, hasFirstSource: Boolean = true, secondSourceBias: Int = 0, parallel: Boolean = false) {
        val side = mapRegisterSide(destination)
        if (hasFirstSource) require(mapRegisterSide(a) == side) { "C67 source and destination must share a register side" }
        val crossPath = if (secondSourceBias != 0) 0 else mapRegisterSide(b) xor side
        val src1 = if (hasFirstSource) mapRegisterNumber(a) else 0
        val src2 = mapRegisterNumber(b) + secondSourceBias
        val word = (mapRegisterNumber(destination) shl 23) or (src2 shl 18) or (src1 shl 13) or
            (crossPath shl 12) or (opcode shl opcodeShift) or (fixed shl 2) or (side shl 1) or (if (parallel) 1 else 0)
        emit(word)
    }

    private fun compare(opcode: Int, floating: Boolean, a: Int, b: Int, destination: Int) {
        val side = mapRegisterSide(a)
        require(mapRegisterSide(destination) == side) { "C67 comparison result must be on source1 side" }
        val crossPath = mapRegisterSide(a) xor mapRegisterSide(b)
        val word = (mapRegisterNumber(destination) shl 23) or (mapRegisterNumber(b) shl 18) or
            (mapRegisterNumber(a) shl 13) or (crossPath shl 12) or
            if (floating) (opcode shl 6) or (8 shl 2) or (side shl 1) else (opcode shl 5) or (6 shl 2) or (side shl 1)
        emit(word)
    }

    private fun memory(load: Boolean, dataRegister: Int, baseRegister: Int, offsetRegister: Int,
        mode: Int, sizeCode: Int, doubleWord: Boolean = false, baseSideOverride: Int? = null,
        offsetIsConstant: Boolean = false) {
        val dataNumber = mapRegisterNumber(dataRegister)
        val baseNumber = mapRegisterNumber(baseRegister)
        val dataSide = mapRegisterSide(dataRegister)
        val baseSide = baseSideOverride ?: mapRegisterSide(baseRegister)
        val offsetNumber = if (offsetIsConstant) offsetRegister else mapRegisterNumber(offsetRegister)
        val word = (dataNumber shl 23) or (baseNumber shl 18) or (offsetNumber shl 13) or
            (mode shl 9) or ((if (doubleWord) 1 else 0) shl 8) or (baseSide shl 7) or
            (sizeCode shl 4) or (1 shl 2) or (dataSide shl 1)
        emit(word)
    }

    /** Encodes the arithmetic and control instruction families used by C67_asm. */
    fun assemble(mnemonic: String, a: Int, b: Int, c: Int) {
        when {
            mnemonic.startsWith("MVKL") -> emit((mapRegisterNumber(b) shl 23) or ((a and 0xffff) shl 7) or (0x0a shl 2) or (mapRegisterSide(b) shl 1))
            mnemonic.startsWith("MVKH") -> emit((mapRegisterNumber(b) shl 23) or (((a ushr 16) and 0xffff) shl 7) or (0x1a shl 2) or (mapRegisterSide(b) shl 1))
            mnemonic.startsWith("STW.D SP POST DEC") -> memory(false, a, C67_SP, 2, 10, 7, baseSideOverride = 1, offsetIsConstant = true)
            mnemonic.startsWith("STB.D *+SP[A0]") -> memory(false, a, C67_FP, C67_A0, 5, 3, baseSideOverride = 0)
            mnemonic.startsWith("STH.D *+SP[A0]") -> memory(false, a, C67_FP, C67_A0, 5, 5, baseSideOverride = 0)
            mnemonic.startsWith("STW.D *+SP[A0]") -> memory(false, a, C67_FP, C67_A0, 5, 7, baseSideOverride = 0)
            mnemonic.startsWith("STW.D +*") -> { require(c in 0 until 32); memory(false, a, b, c, 1, 7, offsetIsConstant = true) }
            mnemonic.startsWith("STW.D *") -> memory(false, a, b, 0, 1, 7)
            mnemonic.startsWith("STH.D *") -> memory(false, a, b, 0, 1, 5)
            mnemonic.startsWith("STB.D *") -> memory(false, a, b, 0, 1, 3)
            mnemonic.startsWith("LDW.D SP PRE INC") -> memory(true, a, C67_SP, 2, 9, 6, baseSideOverride = 1, offsetIsConstant = true)
            mnemonic.startsWith("LDDW.D SP PRE INC") -> memory(true, a, C67_SP, 1, 9, 6, doubleWord = true, baseSideOverride = 1, offsetIsConstant = true)
            mnemonic.startsWith("LDW.D *+SP[A0]") -> memory(true, a, C67_FP, C67_A0, 5, 6, baseSideOverride = 0)
            mnemonic.startsWith("LDDW.D *+SP[A0]") -> memory(true, a, C67_FP, C67_A0, 5, 6, doubleWord = true, baseSideOverride = 0)
            mnemonic.startsWith("LDH.D *+SP[A0]") -> memory(true, a, C67_FP, C67_A0, 5, 4, baseSideOverride = 0)
            mnemonic.startsWith("LDB.D *+SP[A0]") -> memory(true, a, C67_FP, C67_A0, 5, 2, baseSideOverride = 0)
            mnemonic.startsWith("LDHU.D *+SP[A0]") -> memory(true, a, C67_FP, C67_A0, 5, 0, baseSideOverride = 0)
            mnemonic.startsWith("LDBU.D *+SP[A0]") -> memory(true, a, C67_FP, C67_A0, 5, 1, baseSideOverride = 0)
            mnemonic.startsWith("LDW.D +*") -> memory(true, b, a, 1, 1, 6, offsetIsConstant = true)
            mnemonic.startsWith("LDW.D *") -> memory(true, b, a, 0, 1, 6)
            mnemonic.startsWith("LDDW.D *") -> memory(true, b, a, 0, 1, 6, doubleWord = true)
            mnemonic.startsWith("LDH.D *") -> memory(true, b, a, 0, 1, 4)
            mnemonic.startsWith("LDB.D *") -> memory(true, b, a, 0, 1, 2)
            mnemonic.startsWith("LDHU.D *") -> memory(true, b, a, 0, 1, 0)
            mnemonic.startsWith("LDBU.D *") -> memory(true, b, a, 0, 1, 1)
            mnemonic.startsWith("B DISP") -> emit((a shl 7) or (4 shl 2))
            mnemonic.startsWith("B.") -> {
                val cross = mapRegisterSide(c) xor 1
                emit((mapConditionRegister(b) shl 29) or (a shl 28) or (mapRegisterNumber(c) shl 18) or (cross shl 12) or (0xd shl 6) or (8 shl 2) or 2)
            }
            mnemonic.startsWith("NOP") -> emit((a - 1) shl 13)
            mnemonic.startsWith("||ADDK") -> emit((mapRegisterNumber(b) shl 23) or ((a and 0xffff) shl 7) or (0x14 shl 2) or (mapRegisterSide(b) shl 1) or 1)
            mnemonic.startsWith("ADDK") -> emit((mapRegisterNumber(b) shl 23) or ((a and 0xffff) shl 7) or (0x14 shl 2) or (mapRegisterSide(b) shl 1))
            mnemonic.startsWith("CMPLTSP") -> compare(0x3a, true, a, b, c)
            mnemonic.startsWith("CMPGTSP") -> compare(0x39, true, a, b, c)
            mnemonic.startsWith("CMPEQSP") -> compare(0x38, true, a, b, c)
            mnemonic.startsWith("CMPLTDP") -> compare(0x2a, true, a, b, c)
            mnemonic.startsWith("CMPGTDP") -> compare(0x29, true, a, b, c)
            mnemonic.startsWith("CMPEQDP") -> compare(0x28, true, a, b, c)
            mnemonic.startsWith("CMPLTU") -> compare(0x5f, false, a, b, c)
            mnemonic.startsWith("CMPGTU") -> compare(0x4f, false, a, b, c)
            mnemonic.startsWith("CMPLT") -> compare(0x57, false, a, b, c)
            mnemonic.startsWith("CMPGT") -> compare(0x47, false, a, b, c)
            mnemonic.startsWith("CMPEQ") -> compare(0x53, false, a, b, c)
            mnemonic.startsWith("MV.L") -> arithmetic(0x2, 6, 0, b, c, hasFirstSource = false)
            mnemonic.startsWith("SPTRUNC.L") -> arithmetic(0xb, 6, 0, b, c, hasFirstSource = false)
            mnemonic.startsWith("DPTRUNC.L") -> arithmetic(0x1, 6, 0, b, c, hasFirstSource = false, secondSourceBias = 1)
            mnemonic.startsWith("INTSPU.L") -> arithmetic(0x49, 6, 0, b, c, hasFirstSource = false)
            mnemonic.startsWith("INTSP.L") -> arithmetic(0x4a, 6, 0, b, c, hasFirstSource = false)
            mnemonic.startsWith("INTDPU.L") -> arithmetic(0x3b, 6, 0, b, c, hasFirstSource = false, secondSourceBias = 1)
            mnemonic.startsWith("INTDP.L") -> arithmetic(0x39, 6, 0, b, c, hasFirstSource = false)
            mnemonic.startsWith("SPDP.L") -> arithmetic(0x2, 8, 0, b, c, opcodeShift = 6, hasFirstSource = false)
            mnemonic.startsWith("DPSP.L") -> { require(mapRegisterSide(b) == mapRegisterSide(c)); arithmetic(0x9, 6, 0, b, c, hasFirstSource = false, secondSourceBias = 1) }
            mnemonic.startsWith("ADD.L") -> arithmetic(0x3, 6, a, b, c)
            mnemonic.startsWith("SUB.L") -> arithmetic(0x7, 6, a, b, c)
            mnemonic.startsWith("OR.L") -> arithmetic(0x7f, 6, a, b, c)
            mnemonic.startsWith("AND.L") -> arithmetic(0x7b, 6, a, b, c)
            mnemonic.startsWith("XOR.L") -> arithmetic(0x6f, 6, a, b, c)
            mnemonic.startsWith("ADDSP.L") -> arithmetic(0x10, 6, a, b, c)
            mnemonic.startsWith("SUBSP.L") -> arithmetic(0x11, 6, a, b, c)
            mnemonic.startsWith("ADDDP.L") -> arithmetic(0x18, 6, a, b, c)
            mnemonic.startsWith("SUBDP.L") -> arithmetic(0x19, 6, a, b, c)
            mnemonic.startsWith("MPYSP.M") -> arithmetic(0x1c, 0, a, b, c, opcodeShift = 7)
            mnemonic.startsWith("MPYDP.M") -> arithmetic(0x0e, 0, a, b, c, opcodeShift = 7)
            mnemonic.startsWith("MPYI.M") -> arithmetic(0x4, 0, a, b, c, opcodeShift = 7)
            mnemonic.startsWith("SHR.S") -> arithmetic(0x37, 8, a, b, c, opcodeShift = 6)
            mnemonic.startsWith("SHRU.S") -> arithmetic(0x27, 8, a, b, c, opcodeShift = 6)
            mnemonic.startsWith("SHL.S") -> arithmetic(0x33, 8, a, b, c, opcodeShift = 6)
            else -> throw IllegalArgumentException("unsupported C67 instruction: $mnemonic")
        }
    }
}
