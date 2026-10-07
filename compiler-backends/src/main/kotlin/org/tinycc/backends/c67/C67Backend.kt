package org.tinycc.backends.c67

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrMemoryOrder
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue

data class C67TargetOptions(
    val littleEndian: Boolean = true,
    val pointerBits: Int = 32,
    val allowUnalignedAccess: Boolean = false,
)

object C67Restrictions {
    fun validate(function: IrFunction, options: C67TargetOptions = C67TargetOptions()): List<String> {
        val errors = ArrayList<String>()
        if (options.pointerBits != 32) errors += "C67 pointers must be 32-bit"
        function.blocks.flatMap { it.instructions }.forEach { instruction ->
            when (instruction) {
                is IrInstruction.AtomicRmw, is IrInstruction.CompareExchange -> errors += "C67 atomic operations require target runtime support"
                is IrInstruction.GetElementPointer -> if (!options.allowUnalignedAccess) errors += "C67 requires aligned aggregate addresses"
                else -> Unit
            }
        }
        return errors
    }
}

sealed interface C67Operand {
    data class Register(val name: String) : C67Operand
    data class Immediate(val value: Long) : C67Operand
    data class Memory(val base: String, val offset: Long = 0) : C67Operand
    data class Label(val name: String) : C67Operand
    data class Symbol(val name: String) : C67Operand
}

enum class C67Opcode { MV, ADD, SUB, MPY, DIV, LDW, STW, LDF, STF, B, CALL, RET, CMP, BNE, NOP, MVK, MVKH }

data class C67Instruction(val opcode: C67Opcode, val operands: List<C67Operand> = emptyList(), val comment: String? = null)

data class C67Block(val name: String, val instructions: List<C67Instruction>)

data class C67Function(val name: String, val options: C67TargetOptions, val blocks: List<C67Block>)

class C67InstructionSelector(private val options: C67TargetOptions = C67TargetOptions()) {
    private val values = LinkedHashMap<String, String>()
    private var nextVirtual = 0

    fun select(function: IrFunction): C67Function {
        require(C67Restrictions.validate(function, options).isEmpty()) { C67Restrictions.validate(function, options).joinToString("; ") }
        values.clear()
        nextVirtual = 0
        val blocks = function.blocks.mapIndexed { index, block ->
            val output = ArrayList<C67Instruction>()
            if (index == 0) selectParameters(function, output)
            block.instructions.forEach { selectInstruction(it, output) }
            block.terminator?.let { selectTerminator(it, output) }
            C67Block(block.name, output)
        }
        return C67Function(function.symbol.name, options, blocks)
    }

    private fun selectParameters(function: IrFunction, output: MutableList<C67Instruction>) {
        function.parameters.forEachIndexed { index, parameter ->
            val source = if (index < 4) C67Operand.Register("a$index") else C67Operand.Memory("b15", (index - 4) * 4L)
            output += C67Instruction(C67Opcode.MV, listOf(C67Operand.Register(value(IrValue.Parameter(index, parameter.type))), source), "C67 ABI parameter $index")
        }
    }

    private fun selectInstruction(instruction: IrInstruction, output: MutableList<C67Instruction>) {
        when (instruction) {
            is IrInstruction.Alloca -> output += C67Instruction(C67Opcode.SUB, listOf(C67Operand.Register("b15"), immediate(instruction.count)))
            is IrInstruction.Load -> output += C67Instruction(if (instruction.loadedType is IrType.Floating) C67Opcode.LDF else C67Opcode.LDW, listOf(C67Operand.Register(value(instruction.result)), memory(instruction.address)))
            is IrInstruction.Store -> output += C67Instruction(if (instruction.value.type is IrType.Floating) C67Opcode.STF else C67Opcode.STW, listOf(memory(instruction.address), operand(instruction.value)))
            is IrInstruction.Binary -> {
                output += C67Instruction(C67Opcode.MV, listOf(C67Operand.Register(value(instruction.result)), operand(instruction.left)))
                output += C67Instruction(binaryOpcode(instruction.operation, instruction.left.type), listOf(C67Operand.Register(value(instruction.result)), operand(instruction.right)))
            }
            is IrInstruction.Compare -> {
                output += C67Instruction(C67Opcode.CMP, listOf(operand(instruction.left), operand(instruction.right)))
                output += C67Instruction(C67Opcode.MV, listOf(C67Operand.Register(value(instruction.result)), C67Operand.Immediate(0)), instruction.condition.name)
            }
            is IrInstruction.Cast -> output += C67Instruction(C67Opcode.MV, listOf(C67Operand.Register(value(instruction.result)), operand(instruction.value)))
            is IrInstruction.GetElementPointer -> output += C67Instruction(C67Opcode.ADD, listOf(C67Operand.Register(value(instruction.result)), operand(instruction.base)))
            is IrInstruction.Call -> {
                output += C67Instruction(C67Opcode.CALL, listOf(operand(instruction.callee)))
                instruction.result?.let { output += C67Instruction(C67Opcode.MV, listOf(C67Operand.Register(value(it)), C67Operand.Register("a0"))) }
            }
            is IrInstruction.AtomicRmw, is IrInstruction.CompareExchange -> error("C67 atomic operations are rejected by target restrictions")
        }
    }

    private fun selectTerminator(terminator: IrTerminator, output: MutableList<C67Instruction>) {
        when (terminator) {
            is IrTerminator.Jump -> output += C67Instruction(C67Opcode.B, listOf(C67Operand.Label(terminator.target)))
            is IrTerminator.Branch -> {
                output += C67Instruction(C67Opcode.BNE, listOf(operand(terminator.condition), C67Operand.Immediate(0), C67Operand.Label(terminator.trueTarget)))
                output += C67Instruction(C67Opcode.B, listOf(C67Operand.Label(terminator.falseTarget)))
            }
            is IrTerminator.Switch -> output += C67Instruction(C67Opcode.B, listOf(C67Operand.Label(terminator.defaultTarget)), "switch expansion")
            is IrTerminator.Return -> {
                terminator.value?.let { output += C67Instruction(C67Opcode.MV, listOf(C67Operand.Register("a0"), operand(it))) }
                output += C67Instruction(C67Opcode.RET)
            }
            is IrTerminator.Unreachable -> output += C67Instruction(C67Opcode.NOP, comment = "unreachable")
        }
    }

    private fun operand(value: IrValue): C67Operand = when (value) {
        is IrValue.Local -> C67Operand.Register(this.value(value))
        is IrValue.Parameter -> C67Operand.Register(this.value(value))
        is IrValue.IntegerConstant -> C67Operand.Immediate(value.value.longValueExact())
        is IrValue.FloatingConstant -> C67Operand.Immediate(value.value?.toRawBits() ?: 0)
        is IrValue.NullPointer, is IrValue.Undef -> C67Operand.Immediate(0)
        is IrValue.SymbolAddress -> C67Operand.Symbol(value.symbol.name)
    }

    private fun immediate(value: IrValue): C67Operand = operand(value)
    private fun memory(value: IrValue): C67Operand.Memory = when (val operand = operand(value)) {
        is C67Operand.Register -> C67Operand.Memory(operand.name)
        is C67Operand.Symbol -> C67Operand.Memory(operand.name)
        else -> C67Operand.Memory("b15")
    }
    private fun value(value: IrValue): String = values.getOrPut(key(value)) { "v${nextVirtual++}" }
    private fun key(value: IrValue): String = when (value) {
        is IrValue.Local -> "l${value.id}"
        is IrValue.Parameter -> "p${value.index}"
        else -> error("only SSA values have C67 virtual registers")
    }
    private fun binaryOpcode(operation: IrBinaryOp, type: IrType): C67Opcode = when (operation) {
        IrBinaryOp.ADD -> C67Opcode.ADD
        IrBinaryOp.SUBTRACT -> C67Opcode.SUB
        IrBinaryOp.MULTIPLY -> C67Opcode.MPY
        IrBinaryOp.DIVIDE, IrBinaryOp.REMAINDER -> C67Opcode.DIV
        else -> C67Opcode.ADD
    }
}

class C67AssemblyEmitter {
    fun emit(function: C67Function): String = buildString {
        appendLine("; C67 COFF assembly")
        appendLine(".sect .text")
        appendLine(".global ${function.name}")
        appendLine("${function.name}:")
        function.blocks.forEach { block ->
            appendLine("${function.name}.${block.name}:")
            block.instructions.forEach { instruction ->
                val operands = instruction.operands.joinToString(", ") { format(it) }
                appendLine("  ${instruction.opcode.name.lowercase()}${if (operands.isEmpty()) "" else " $operands"}${instruction.comment?.let { " ; $it" }.orEmpty()}")
            }
        }
    }

    private fun format(operand: C67Operand): String = when (operand) {
        is C67Operand.Register -> operand.name
        is C67Operand.Immediate -> operand.value.toString()
        is C67Operand.Memory -> "*${operand.base}${if (operand.offset == 0L) "" else "+${operand.offset}"}"
        is C67Operand.Label -> operand.name
        is C67Operand.Symbol -> operand.name
    }
}

data class C67CoffObject(val bytes: ByteArray, val machine: Int = 0x0099)

object C67CoffEmitter {
    fun emit(function: C67Function): C67CoffObject {
        val text = C67AssemblyEmitter().emit(function).encodeToByteArray()
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0099.toShort())
            .putShort(1)
            .putInt(0)
            .putInt(0)
            .putShort(0)
            .putShort(0)
            .array()
        return C67CoffObject(header + text)
    }
}
