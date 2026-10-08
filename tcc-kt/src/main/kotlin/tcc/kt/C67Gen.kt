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

    fun asm(mnemonic: String, a: Int = 0, b: Int = 0, c: Int = 0) = assembler(mnemonic, a, b, c)
}
