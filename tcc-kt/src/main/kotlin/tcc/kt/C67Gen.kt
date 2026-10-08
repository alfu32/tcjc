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
    enum class ValueLocation { CONSTANT, LOCAL, INDIRECT_LOCAL, COMPARE, JUMP, JUMP_INDIRECT, REGISTER }
    enum class ValueType { BYTE, SHORT, INT, LONG_LONG, POINTER, FUNCTION, STRUCT, FLOAT, DOUBLE, LONG_DOUBLE, BOOL }
    data class Value(
        val location: ValueLocation, val type: ValueType = ValueType.INT, val constant: Int = 0,
        val register: Int = -1, val symbol: Int? = null, val lvalue: Boolean = false,
        val unsigned: Boolean = false,
    )
    data class CallTarget(val symbol: Int? = null, val targetRegister: Int = -1, val addend: Int = 0)
    data class FunctionFrame(val argumentSizes: List<Int>, val parameterOffsets: List<Int>, val pushedArgumentBytes: Int,
        val stackAdjustmentOffset: Int, val returnSubtraction: Int, val structReturnOffset: Int?)
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
    var invertTest: Boolean = false
    var compareRegister: Int = 0
    val translateStackToRegister: IntArray = IntArray(NO_CALL_ARGS_PASSED_ON_STACK)
    val parameterLocationsOnStack: IntArray = IntArray(NO_CALL_ARGS_PASSED_ON_STACK)
    var totalBytesPushedOnStack: Int = 0
    private var functionStackAdjustmentOffset: Int = 0
    private var functionReturnSubtraction: Int = 0

    fun structureReturnResultAlignment(): Int = 1
    fun structureReturnRegisterCount(): Int = 0

    /** Emits direct or register based C67 branches and the delayed return address for calls. */
    fun callOrJump(isJump: Boolean, target: CallTarget, returnAddressSymbol: Int? = null) {
        if (target.symbol != null) {
            relocate(target.symbol, position(), "R_C60LO16")
            relocate(target.symbol, position() + 4, "R_C60HI16")
            moveLow(C67_A0, target.addend)
            moveHigh(C67_A0, target.addend)
            conditionalBranch(false, C67_CREG_ZERO, C67_A0)
        } else {
            require(target.targetRegister >= 0) { "indirect C67 call requires a target register" }
            conditionalBranch(false, C67_CREG_ZERO, target.targetRegister)
        }
        if (isJump) nop(5) else {
            val returnSym = returnAddressSymbol ?: throw IllegalArgumentException("call requires return address symbol")
            relocate(returnSym, position(), "R_C60LO16")
            relocate(returnSym, position() + 4, "R_C60HI16")
            moveLow(C67_B3, 0)
            moveHigh(C67_B3, 0)
            nop(3)
        }
    }

    fun emitJump(chainValue: Int): Int {
        if (noCode()) return chainValue
        val site = position()
        moveLow(C67_A0, chainValue); moveHigh(C67_A0, chainValue)
        conditionalBranch(false, C67_CREG_ZERO, C67_A0)
        nop(5)
        return site
    }

    fun emitJumpAddress(address: Int, symbolForAddress: (Int) -> Int) {
        val sym = symbolForAddress(address)
        relocate(sym, position(), "R_C60LO16")
        relocate(sym, position() + 4, "R_C60HI16")
        emitJump(0)
    }

    fun emitConditionalJump(invert: Boolean, chainValue: Int): Int {
        if (noCode()) return chainValue
        val site = position()
        moveLow(C67_A0, chainValue); moveHigh(C67_A0, chainValue)
        if (compareRegister != 0 && compareRegister != 2 && compareRegister != 3 && compareRegister != C67_B2) {
            move(compareRegister, C67_B2)
            compareRegister = C67_B2
        }
        conditionalBranch(invert xor invertTest, compareRegister, C67_A0)
        nop(5)
        return site
    }

    fun appendJumpChain(head: Int, tail: Int): Int {
        if (head == 0) return tail
        var site = head
        while (site != 0) {
            val low = readWord(site); val high = readWord(site + 4)
            val next = ((low ushr 7) and 0xffff) or (((high ushr 7) and 0xffff) shl 16)
            if (next == 0) {
                writeWord(site, low or ((tail and 0xffff) shl 7))
                writeWord(site + 4, high or (((tail ushr 16) and 0xffff) shl 7))
                break
            }
            site = next
        }
        return head
    }

    fun fillNops(byteCount: Int) {
        require(byteCount % 4 == 0) { "alignment of code section not multiple of 4" }
        repeat(byteCount.coerceAtLeast(0) / 4) { nop(4) }
    }

    private fun memorySize(type: ValueType): Int = when (type) {
        ValueType.BYTE, ValueType.BOOL -> 1
        ValueType.SHORT -> 2
        ValueType.DOUBLE, ValueType.LONG_LONG -> 8
        ValueType.LONG_DOUBLE -> throw IllegalArgumentException("long double not supported by C67")
        else -> 4
    }

    private fun loadPointerValue(size: Int, unsigned: Boolean, base: Int, destination: Int) {
        when (size) {
            1 -> if (unsigned) loadUnsignedBytePointer(base, destination) else loadBytePointer(base, destination)
            2 -> if (unsigned) loadUnsignedHalfPointer(base, destination) else loadHalfPointer(base, destination)
            4 -> loadWordPointer(base, destination)
            8 -> loadDoubleWordPointer(base, destination)
        }
        nop(4)
    }

    private fun loadStackValue(size: Int, unsigned: Boolean, destination: Int, offset: Int) {
        val index = (offset / size) + 8 / size
        moveLow(C67_A0, index)
        moveHigh(C67_A0, index)
        when (size) {
            1 -> if (unsigned) loadUnsignedByteStackA0(destination) else loadByteStackA0(destination)
            2 -> if (unsigned) loadUnsignedHalfStackA0(destination) else loadHalfStackA0(destination)
            4 -> loadWordStackA0(destination)
            8 -> loadDoubleWordStackA0(destination)
        }
        nop(4)
    }

    private fun translatedFormalOffset(offset: Int): Int {
        if (offset <= 0) return offset
        var stackPosition = 8
        for (index in 0 until NO_CALL_ARGS_PASSED_ON_STACK) {
            if (offset == stackPosition) return parameterLocationsOnStack[index] - 8
            stackPosition += translateStackToRegister[index]
        }
        return offset
    }

    fun loadValue(destination: Int, value: Value, patchJumpChain: (Int) -> Unit = {}) {
        var location = value.location
        var register = value.register
        var offset = value.constant
        val size = memorySize(value.type)
        val unsigned = value.unsigned
        if (value.lvalue) {
            if (location == ValueLocation.INDIRECT_LOCAL) {
                loadValue(destination, value.copy(location = ValueLocation.LOCAL, lvalue = true))
                location = ValueLocation.REGISTER
                register = destination
            } else if (value.type == ValueType.LONG_DOUBLE) throw IllegalArgumentException("long double not supported")
            if (location == ValueLocation.LOCAL) offset = translatedFormalOffset(offset)
            if (location == ValueLocation.REGISTER) {
                loadPointerValue(size, unsigned, register, destination)
                return
            }
            if (value.symbol != null) {
                relocate(value.symbol, position(), "R_C60LO16")
                relocate(value.symbol, position() + 4, "R_C60HI16")
                moveLow(C67_A0, offset); moveHigh(C67_A0, offset)
                loadPointerValue(size, unsigned, C67_A0, destination)
                return
            }
            loadStackValue(size, unsigned, destination, offset)
            return
        }
        when (location) {
            ValueLocation.CONSTANT -> {
                if (value.symbol != null) {
                    relocate(value.symbol, position(), "R_C60LO16")
                    relocate(value.symbol, position() + 4, "R_C60HI16")
                }
                moveLow(destination, offset); moveHigh(destination, offset)
            }
            ValueLocation.LOCAL -> { moveLow(destination, offset + 8); moveHigh(destination, offset + 8); add(C67_FP, destination, destination) }
            ValueLocation.COMPARE -> move(compareRegister, destination)
            ValueLocation.JUMP, ValueLocation.JUMP_INDIRECT -> {
                val jumpValue = if (location == ValueLocation.JUMP_INDIRECT) 1 else 0
                branchDisplacement(4); moveLow(destination, jumpValue); nop(4); patchJumpChain(offset); moveLow(destination, jumpValue xor 1)
            }
            ValueLocation.REGISTER -> if (register != destination) {
                move(register, destination)
                if (value.type == ValueType.DOUBLE) move(register + 1, destination + 1)
            }
            else -> throw IllegalArgumentException("unsupported C67 value location: $location")
        }
    }

    fun storeValue(source: Int, value: Value) {
        require(value.type != ValueType.LONG_DOUBLE) { "long double not supported" }
        val size = memorySize(value.type)
        val location = value.location
        val offset = value.constant
        val base = value.register
        when {
            location == ValueLocation.CONSTANT -> {
                if (value.symbol != null) {
                    relocate(value.symbol, position(), "R_C60LO16")
                    relocate(value.symbol, position() + 4, "R_C60HI16")
                }
                moveLow(C67_A0, offset); moveHigh(C67_A0, offset)
                storePointerValue(size, source, C67_A0)
            }
            location == ValueLocation.LOCAL -> {
                val adjusted = translatedFormalOffset(offset)
                val element = if (size == 8) 4 else size
                val index = (adjusted / element) + 8 / element
                moveLow(C67_A0, index); moveHigh(C67_A0, index)
                storeStackValue(size, source)
            }
            else -> storePointerValue(size, source, base)
        }
    }

    private fun storePointerValue(size: Int, source: Int, base: Int) {
        when (size) {
            1 -> storeBytePointer(source, base)
            2 -> storeHalfPointer(source, base)
            4, 8 -> storeWordPointer(source, base)
        }
        if (size == 8) storeWordPreIncrement(source + 1, base, 1)
    }

    private fun storeStackValue(size: Int, source: Int) {
        when (size) {
            1 -> storeByteStackA0(source)
            2 -> storeHalfStackA0(source)
            4, 8 -> storeWordStackA0(source)
        }
        if (size == 8) { addConstant(1, C67_A0); storeWordStackA0(source + 1) }
    }

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

    fun moveLow(register: Int, constant: Int) = asm("MVKL.", constant, register)
    fun moveHigh(register: Int, constant: Int) = asm("MVKH.", constant, register)
    fun storeByteStackA0(register: Int) = asm("STB.D *+SP[A0]", register)
    fun storeHalfStackA0(register: Int) = asm("STH.D *+SP[A0]", register)
    fun storeWordStackA0(register: Int) = asm("STW.D *+SP[A0]", register)
    fun storeBytePointer(source: Int, base: Int) = asm("STB.D *", source, base)
    fun storeHalfPointer(source: Int, base: Int) = asm("STH.D *", source, base)
    fun storeWordPointer(source: Int, base: Int) = asm("STW.D *", source, base)
    fun storeWordPreIncrement(source: Int, base: Int, increment: Int) = asm("STW.D +*", source, base, increment)
    fun push(register: Int) = asm("STW.D SP POST DEC", register)
    fun loadWordStackA0(register: Int) = asm("LDW.D *+SP[A0]", register)
    fun loadDoubleWordStackA0(register: Int) = asm("LDDW.D *+SP[A0]", register)
    fun loadHalfStackA0(register: Int) = asm("LDH.D *+SP[A0]", register)
    fun loadByteStackA0(register: Int) = asm("LDB.D *+SP[A0]", register)
    fun loadUnsignedHalfStackA0(register: Int) = asm("LDHU.D *+SP[A0]", register)
    fun loadUnsignedByteStackA0(register: Int) = asm("LDBU.D *+SP[A0]", register)
    fun loadWordPointer(base: Int, destination: Int) = asm("LDW.D *", base, destination)
    fun loadDoubleWordPointer(base: Int, destination: Int) = asm("LDDW.D *", base, destination)
    fun loadHalfPointer(base: Int, destination: Int) = asm("LDH.D *", base, destination)
    fun loadBytePointer(base: Int, destination: Int) = asm("LDB.D *", base, destination)
    fun loadUnsignedHalfPointer(base: Int, destination: Int) = asm("LDHU.D *", base, destination)
    fun loadUnsignedBytePointer(base: Int, destination: Int) = asm("LDBU.D *", base, destination)
    fun loadWordPreIncrement(base: Int, destination: Int) = asm("LDW.D +*", base, destination)
    fun pop(register: Int) = asm("LDW.D SP PRE INC", register)
    fun popDoubleWord(register: Int) = asm("LDDW.D SP PRE INC", register)

    fun compareLess(left: Int, right: Int, destination: Int) = asm("CMPLT.L1", left, right, destination)
    fun compareGreater(left: Int, right: Int, destination: Int) = asm("CMPGT.L1", left, right, destination)
    fun compareEqual(left: Int, right: Int, destination: Int) = asm("CMPEQ.L1", left, right, destination)
    fun compareLessUnsigned(left: Int, right: Int, destination: Int) = asm("CMPLTU.L1", left, right, destination)
    fun compareGreaterUnsigned(left: Int, right: Int, destination: Int) = asm("CMPGTU.L1", left, right, destination)
    fun compareLessFloat(left: Int, right: Int, destination: Int, double: Boolean = false) = asm(if (double) "CMPLTDP.S1" else "CMPLTSP.S1", left, right, destination)
    fun compareGreaterFloat(left: Int, right: Int, destination: Int, double: Boolean = false) = asm(if (double) "CMPGTDP.S1" else "CMPGTSP.S1", left, right, destination)
    fun compareEqualFloat(left: Int, right: Int, destination: Int, double: Boolean = false) = asm(if (double) "CMPEQDP.S1" else "CMPEQSP.S1", left, right, destination)
    fun conditionalBranch(invert: Boolean, conditionRegister: Int, targetRegister: Int) = asm("B.S2", if (invert) 1 else 0, conditionRegister, targetRegister)
    fun branchDisplacement(wordOffset: Int) = asm("B DISP", wordOffset + ((position() and 31) shr 2))
    fun nop(cycles: Int) = asm("NOP", cycles)

    fun addConstant(value: Int, register: Int, parallel: Boolean = false) {
        require(kotlin.math.abs(value) < 32767)
        asm(if (parallel) "||ADDK" else "ADDK", value, register)
    }

    fun adjustAddConstant(instruction: Int, value: Int): Int {
        require(kotlin.math.abs(value) < 32767)
        return (instruction and (0xffff shl 7).inv()) or ((value and 0xffff) shl 7)
    }

    fun move(destination: Int, source: Int) = asm("MV.L", 0, source, destination)
    fun truncateDoubleToFloat(destination: Int, source: Int) = asm("DPTRUNC.L", 0, source, destination)
    fun truncateFloatToDouble(destination: Int, source: Int) = asm("SPTRUNC.L", 0, source, destination)
    fun convertIntToFloat(destination: Int, source: Int, unsigned: Boolean = false, double: Boolean = false) =
        asm(when { double && unsigned -> "INTDPU.L"; double -> "INTDP.L"; unsigned -> "INTSPU.L"; else -> "INTSP.L" }, 0, source, destination)
    fun convertFloatToDouble(destination: Int, source: Int) = asm("SPDP.L", 0, source, destination)
    fun convertDoubleToFloat(destination: Int, source: Int) = asm("DPSP.L", 0, source, destination)
    fun add(destination: Int, left: Int, right: Int) = asm("ADD.L", left, right, destination)
    fun subtract(destination: Int, left: Int, right: Int) = asm("SUB.L", left, right, destination)
    fun and(destination: Int, left: Int, right: Int) = asm("AND.L", left, right, destination)
    fun or(destination: Int, left: Int, right: Int) = asm("OR.L", left, right, destination)
    fun xor(destination: Int, left: Int, right: Int) = asm("XOR.L", left, right, destination)
    fun addFloat(destination: Int, left: Int, right: Int, double: Boolean = false) = asm(if (double) "ADDDP.L" else "ADDSP.L", left, right, destination)
    fun subtractFloat(destination: Int, left: Int, right: Int, double: Boolean = false) = asm(if (double) "SUBDP.L" else "SUBSP.L", left, right, destination)
    fun multiplyFloat(destination: Int, left: Int, right: Int, double: Boolean = false) = asm(if (double) "MPYDP.M" else "MPYSP.M", left, right, destination)
    fun multiplyInteger(destination: Int, left: Int, right: Int) = asm("MPYI.M", left, right, destination)
    fun shiftLeft(destination: Int, value: Int, count: Int) = asm("SHL.S", value, count, destination)
    fun shiftRightUnsigned(destination: Int, value: Int, count: Int) = asm("SHRU.S", value, count, destination)
    fun shiftRight(destination: Int, value: Int, count: Int) = asm("SHR.S", value, count, destination)
}
