package tcc.kt

/** CIL code generation helpers mechanically translated from il-gen.c. */
class IlGen(
    private val writeByte: (Int) -> Unit = {},
    private val currentOffset: () -> Int = { 0 },
    private val stdout: Appendable = StringBuilder(),
) {
    enum class TypeKind { VOID, BOOL, BYTE, SHORT, ENUM, INT, LONG, LONG_LONG, FLOAT, DOUBLE, LONG_DOUBLE, STRUCT, FUNCTION, POINTER }
    data class TypeDesc(
        val kind: TypeKind, val unsigned: Boolean = false, val pointedType: TypeDesc? = null,
        val returnType: TypeDesc? = null, val parameters: List<TypeDesc> = emptyList(),
    )
    enum class ValueKind { CONSTANT, LOCAL, COMPARE, JUMP, JUMP_INDIRECT, REGISTER }
    data class Value(
        val kind: ValueKind, val type: TypeDesc, val constant: Int = 0,
        val lvalue: Boolean = false, val symbol: Int? = null,
    )
    data class FunctionContext(var callingConvention: Int = 0)
    data class FunctionParameter(val name: String, val type: TypeDesc)
    data class FunctionSignature(
        val returnType: TypeDesc, val parameters: List<FunctionParameter>, val name: String,
        val callingConvention: Int = 0, val variadic: Boolean = false, val returnsStructure: Boolean = false,
    )

    companion object {
        const val NB_REGS = 3
        const val RC_ST = 0x0001
        const val RC_ST0 = 0x0002
        const val RC_ST1 = 0x0004
        const val RC_INT = RC_ST
        const val RC_FLOAT = RC_ST
        const val RC_IRET = RC_ST0
        const val RC_LRET = RC_ST0
        const val RC_FRET = RC_ST0
        const val REG_ST0 = 0
        const val REG_ST1 = 1
        const val REG_ST2 = 2
        const val PTR_SIZE = 4
        const val LDOUBLE_SIZE = 8
        const val LDOUBLE_ALIGN = 8
        const val ARG_BASE = 0x70000000
        const val IL_OP_PREFIX = 0xfe
        val registerClasses = intArrayOf(RC_ST or RC_ST0, RC_ST or RC_ST1, RC_ST)
        val returnRegisters = mapOf("int" to REG_ST0, "long" to REG_ST0, "float" to REG_ST0)
        val targetMachineDefinitions = emptyList<String>()
    }

    private var initialized = false
    private var pendingComparison: String? = null
    private fun putByte(value: Int) = writeByte(value and 0xff)
    private fun putLe32(value: Int) {
        putByte(value); putByte(value ushr 8); putByte(value ushr 16); putByte(value ushr 24)
    }

    fun initializeOutput() {
        if (initialized) return
        initialized = true
        stdout.append(".assembly extern mscorlib\n{\n.ver 1:0:2411:0\n}\n\n")
    }

    fun outputOpcodeByte(opcode: Int) {
        if (opcode and 0x100 != 0) putByte(IL_OP_PREFIX)
        putByte(opcode and 0xff)
    }

    fun outputOpcode(opcode: IlOpcode) {
        outputOpcodeByte(opcode.code)
        stdout.append(" ").append(opcode.mnemonic).append('\n')
    }

    fun outputOpcodeByteOperand(opcode: IlOpcode, operand: Int) {
        outputOpcodeByte(opcode.code); putByte(operand)
        stdout.append(" ").append(opcode.mnemonic).append(' ').append(operand.toString()).append('\n')
    }

    fun outputOpcodeIntOperand(opcode: IlOpcode, operand: Int) {
        outputOpcodeByte(opcode.code); putLe32(operand)
        stdout.append(" ").append(opcode.mnemonic).append(" 0x").append(operand.toUInt().toString(16)).append('\n')
    }

    fun formatType(type: TypeDesc, variable: String? = null): String {
        val unsignedPrefix = if (type.unsigned) "unsigned " else ""
        val base = when (type.kind) {
            TypeKind.VOID -> "void"
            TypeKind.BOOL -> "bool"
            TypeKind.BYTE -> "int8"
            TypeKind.SHORT -> "int16"
            TypeKind.ENUM, TypeKind.INT, TypeKind.LONG -> "int32"
            TypeKind.LONG_LONG -> "int64"
            TypeKind.FLOAT -> "float32"
            TypeKind.DOUBLE, TypeKind.LONG_DOUBLE -> "float64"
            TypeKind.STRUCT -> throw UnsupportedOperationException("structures not handled yet")
            TypeKind.FUNCTION -> {
                val result = formatType(type.returnType ?: TypeDesc(TypeKind.VOID), variable)
                return result + "(" + type.parameters.joinToString(", ") { formatType(it) } + ")"
            }
            TypeKind.POINTER -> {
                val target = type.pointedType ?: TypeDesc(TypeKind.VOID)
                return formatType(target, "*" + (variable ?: ""))
            }
        }
        return unsignedPrefix + base + (variable?.let { " $it" } ?: "")
    }

    fun patchRelocation(relocationOffset: Int, value: Int) { /* Source function is intentionally empty. */ }
    fun patchSymbolAddressChain(head: Int, address: Int) { /* Source function is intentionally empty. */ }

    fun outputJump(opcode: IlOpcode, label: Int): Int {
        outputOpcodeByte(opcode.code)
        putLe32(0)
        val resolved = if (label == 0) currentOffset() else label
        stdout.append(" ").append(opcode.mnemonic).append(" L").append(resolved.toString()).append('\n')
        return resolved
    }

    fun defineLabel(label: Int) { stdout.append("L").append(label.toString()).append(":\n") }

    fun loadValue(value: Value) {
        val slot = value.constant
        if (value.lvalue) {
            when (value.kind) {
                ValueKind.LOCAL -> if (slot >= ARG_BASE) emitIndexLoad(slot - ARG_BASE, true) else emitIndexLoad(slot, false)
                ValueKind.CONSTANT -> outputOpcodeIntOperand(IlOpcode.LDSFLD, 0) // Global field metadata is absent in the C source.
                else -> outputOpcode(selectIndirectLoad(value.type))
            }
            return
        }
        when (value.kind) {
            ValueKind.CONSTANT -> if (value.symbol != null) outputOpcodeIntOperand(IlOpcode.LDC_I4, slot)
                else if (slot in -1..8) outputOpcode(IlOpcode.fromCode(IlOpcode.LDC_I4_M1.code + slot + 1))
                else outputOpcodeIntOperand(IlOpcode.LDC_I4, slot)
            ValueKind.LOCAL -> {
                if (slot >= ARG_BASE) {
                    val index = slot - ARG_BASE
                    if (index <= 0xff) outputOpcodeByteOperand(IlOpcode.LDARGA_S, index) else outputOpcodeIntOperand(IlOpcode.LDARGA, index)
                } else if (slot <= 0xff) outputOpcodeByteOperand(IlOpcode.LDLOCA_S, slot)
                else outputOpcodeIntOperand(IlOpcode.LDLOCA, slot)
            }
            ValueKind.COMPARE, ValueKind.JUMP, ValueKind.JUMP_INDIRECT, ValueKind.REGISTER -> Unit // Evaluation stack already contains the value.
        }
    }

    private fun emitIndexLoad(index: Int, argument: Boolean) {
        if (argument && index in 0..3) outputOpcode(IlOpcode.fromCode(IlOpcode.LDARG_0.code + index))
        else if (!argument && index in 0..3) outputOpcode(IlOpcode.fromCode(IlOpcode.LDLOC_0.code + index))
        else if (index in 0..255) outputOpcodeByteOperand(if (argument) IlOpcode.LDARG_S else IlOpcode.LDLOC_S, index)
        else outputOpcodeIntOperand(if (argument) IlOpcode.LDARG else IlOpcode.LDLOC, index)
    }

    private fun selectIndirectLoad(type: TypeDesc): IlOpcode = when (type.kind) {
        TypeKind.FLOAT -> IlOpcode.LDIND_R4
        TypeKind.DOUBLE, TypeKind.LONG_DOUBLE -> IlOpcode.LDIND_R8
        TypeKind.BYTE -> if (type.unsigned) IlOpcode.LDIND_U1 else IlOpcode.LDIND_I1
        TypeKind.SHORT -> if (type.unsigned) IlOpcode.LDIND_U2 else IlOpcode.LDIND_I2
        TypeKind.LONG_LONG -> IlOpcode.LDIND_I8
        TypeKind.POINTER, TypeKind.FUNCTION -> IlOpcode.LDIND_REF
        else -> IlOpcode.LDIND_I4
    }

    fun storeValue(value: Value) {
        when (value.kind) {
            ValueKind.LOCAL -> {
                val slot = value.constant
                if (slot >= ARG_BASE) {
                    val index = slot - ARG_BASE
                    if (index <= 0xff) outputOpcodeByteOperand(IlOpcode.STARG_S, index) else outputOpcodeIntOperand(IlOpcode.STARG, index)
                } else if (slot in 0..3) outputOpcode(IlOpcode.fromCode(IlOpcode.STLOC_0.code + slot))
                else if (slot <= 0xff) outputOpcodeByteOperand(IlOpcode.STLOC_S, slot)
                else outputOpcodeIntOperand(IlOpcode.STLOC, slot)
            }
            ValueKind.CONSTANT -> outputOpcodeIntOperand(IlOpcode.STSFLD, 0)
            else -> outputOpcode(selectIndirectStore(value.type))
        }
    }

    private fun selectIndirectStore(type: TypeDesc): IlOpcode = when (type.kind) {
        TypeKind.FLOAT -> IlOpcode.STIND_R4
        TypeKind.DOUBLE, TypeKind.LONG_DOUBLE -> IlOpcode.STIND_R8
        TypeKind.BYTE, TypeKind.BOOL -> IlOpcode.STIND_I1
        TypeKind.SHORT -> IlOpcode.STIND_I2
        TypeKind.LONG_LONG -> IlOpcode.STIND_I8
        TypeKind.POINTER, TypeKind.FUNCTION -> IlOpcode.STIND_REF
        else -> IlOpcode.STIND_I4
    }

    fun startFunctionCall(context: FunctionContext, callingConvention: Int) { context.callingConvention = callingConvention }

    fun pushFunctionParameter(type: TypeDesc, loadValueToStack: () -> Unit = {}, popValueStack: () -> Unit = {}) {
        if (type.kind == TypeKind.STRUCT) throw UnsupportedOperationException("structures passed as value not handled yet")
        loadValueToStack()
        popValueStack()
    }

    fun emitFunctionCall(context: FunctionContext, functionType: TypeDesc, direct: Boolean, loadIndirectFunction: () -> Unit = {}) {
        if (direct) stdout.append(" call ").append(formatType(functionType, "xxx")).append('\n')
        else {
            loadIndirectFunction()
            stdout.append(" calli ").append(formatType(functionType)).append('\n')
        }
    }

    fun emitFunctionPrologue(signature: FunctionSignature, defineParameter: (FunctionParameter, Int) -> Unit = { _, _ -> }) {
        initializeOutput()
        stdout.append(".method static ").append(formatType(signature.returnType, signature.name)).append('(')
        stdout.append(signature.parameters.joinToString(", ") { formatType(it.type, it.name) }).append(") il managed\n{\n")
        stdout.append(" .maxstack ").append(NB_REGS.toString()).append('\n')
        stdout.append(" .locals (int32, int32, int32, int32, int32, int32, int32, int32)\n")
        if (signature.name == "main") stdout.append(" .entrypoint\n")
        var argument = 0
        if (signature.returnsStructure) argument++
        for (parameter in signature.parameters) {
            defineParameter(parameter, argument)
            if (parameter.name.isNotEmpty()) stdout.append(" // arg").append(argument.toString()).append(' ').append(formatType(parameter.type, parameter.name)).append('\n')
            argument++
        }
    }

    fun emitFunctionEpilogue() { outputOpcode(IlOpcode.RET); stdout.append("}\n\n") }
    fun generateJump(label: Int): Int = outputJump(IlOpcode.BR, label)
    fun generateJumpAddress(address: Int) { outputOpcodeIntOperand(IlOpcode.BR, address) }

    fun generateTest(invert: Boolean, label: Int, value: Value, comparison: String? = null,
        appendJumpChain: (Int, Int) -> Int = { _, tail -> tail }): Int {
        var target = label
        if (value.kind == ValueKind.COMPARE) {
            val op = comparison ?: pendingComparison ?: throw IllegalArgumentException("comparison operator is missing")
            val branch = when (op to invert) {
                "==" to false, "!=" to true -> IlOpcode.BEQ
                "!=" to false, "==" to true -> IlOpcode.BNE_UN
                "<" to false, ">=" to true -> IlOpcode.BLT
                "<=" to false, ">" to true -> IlOpcode.BLE
                ">" to false, "<=" to true -> IlOpcode.BGT
                ">=" to false, "<" to true -> IlOpcode.BGE
                "u<" to false, "u>=" to true -> IlOpcode.BLT_UN
                "u<=" to false, "u>" to true -> IlOpcode.BLE_UN
                "u>" to false, "u<=" to true -> IlOpcode.BGT_UN
                "u>=" to false, "u<" to true -> IlOpcode.BGE_UN
                else -> throw IllegalArgumentException("unsupported IL comparison $op")
            }
            target = outputJump(branch, label)
            pendingComparison = null
        } else if (value.kind == ValueKind.JUMP || value.kind == ValueKind.JUMP_INDIRECT) {
            if ((if (value.kind == ValueKind.JUMP_INDIRECT) 1 else 0) == if (invert) 1 else 0)
                target = appendJumpChain(value.constant, label)
            else { target = generateJump(label); defineLabel(value.constant) }
        } else target = generateJump(label)
        return target
    }

    fun generateIntegerOperation(operation: String): Boolean {
        val opcode = when (operation) {
            "+" -> IlOpcode.ADD; "-" -> IlOpcode.SUB; "*" -> IlOpcode.MUL
            "/" -> IlOpcode.DIV; "udiv" -> IlOpcode.DIV_UN; "%" -> IlOpcode.REM; "umod" -> IlOpcode.REM_UN
            "&" -> IlOpcode.AND; "|" -> IlOpcode.OR; "^" -> IlOpcode.XOR
            "shl" -> IlOpcode.SHL; "shr" -> IlOpcode.SHR_UN; "sar" -> IlOpcode.SHR
            "==" -> IlOpcode.CEQ; ">" -> IlOpcode.CGT; "u>" -> IlOpcode.CGT_UN; "<" -> IlOpcode.CLT; "u<" -> IlOpcode.CLT_UN
            else -> return false
        }
        if (opcode in setOf(IlOpcode.CEQ, IlOpcode.CGT, IlOpcode.CGT_UN, IlOpcode.CLT, IlOpcode.CLT_UN)) {
            pendingComparison = operation
            return true
        }
        outputOpcode(opcode)
        return false
    }

    fun generateFloatingOperation(operation: String): Boolean = generateIntegerOperation(operation)

    fun convertIntegerToFloat(destination: TypeKind) { outputOpcode(if (destination == TypeKind.FLOAT) IlOpcode.CONV_R4 else IlOpcode.CONV_R8) }
    fun convertFloatToInteger(destination: TypeKind, unsigned: Boolean = false) {
        outputOpcode(when (destination) {
            TypeKind.LONG_LONG -> if (unsigned) IlOpcode.CONV_U8 else IlOpcode.CONV_I8
            TypeKind.INT, TypeKind.LONG -> if (unsigned) IlOpcode.CONV_U4 else IlOpcode.CONV_I4
            else -> if (unsigned) IlOpcode.CONV_U4 else IlOpcode.CONV_I4
        })
    }
    fun convertFloatingPrecision(destination: TypeKind) { outputOpcode(if (destination == TypeKind.FLOAT) IlOpcode.CONV_R4 else IlOpcode.CONV_R8) }
}
