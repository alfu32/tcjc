package org.tinycc.backends.arm

import org.tinycc.core.ir.AbiLocation
import org.tinycc.core.ir.CallingConventionDescriptor
import org.tinycc.core.ir.CallingConventionPlanner
import org.tinycc.core.ir.IrArchitecture
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrRegister
import org.tinycc.core.ir.IrRegisterBank
import org.tinycc.core.ir.IrRegisterClass
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue

enum class ArmIsa { ARM, THUMB2 }

data class ArmTargetOptions(
    val isa: ArmIsa = ArmIsa.ARM,
    val hardFloat: Boolean = false,
    val pic: Boolean = false,
    val thumbInterworking: Boolean = true,
)

data class ArmRegister(
    val name: String,
    val number: Int,
    val registerClass: IrRegisterClass,
    val bits: Int,
)

object ArmRegisters {
    fun bank(): IrRegisterBank = IrRegisterBank(
        IrArchitecture.ARM,
        (0..15).map { index -> IrRegister("r$index", index, IrRegisterClass.INTEGER, 32, callerSaved = index < 4) } +
            (0..15).map { index -> IrRegister("s$index", 16 + index, IrRegisterClass.FLOAT, 32, callerSaved = true) },
    )

    fun callingConvention(hardFloat: Boolean): CallingConventionDescriptor {
        val bank = bank()
        return CallingConventionDescriptor(
            name = if (hardFloat) "aapcs-vfp" else "aapcs-softfp",
            architecture = IrArchitecture.ARM,
            pointerBits = 32,
            stackAlignment = 8,
            integerArgumentRegisters = (0..3).map { bank.find("r$it")!! },
            floatingArgumentRegisters = if (hardFloat) (0..15).map { bank.find("s$it")!! } else emptyList(),
            integerReturnRegisters = listOf(bank.find("r0")!!),
            floatingReturnRegisters = if (hardFloat) listOf(bank.find("s0")!!) else emptyList(),
            calleeSavedRegisters = (4..11).map { bank.find("r$it")!! },
        )
    }
}

data class ArmVirtualRegister(val id: Int, val bits: Int, val registerClass: IrRegisterClass)

sealed interface ArmOperand {
    data class Register(val name: String) : ArmOperand
    data class Immediate(val value: Long) : ArmOperand
    data class Memory(val base: String, val offset: Long = 0) : ArmOperand
    data class Label(val name: String) : ArmOperand
    data class Symbol(val name: String) : ArmOperand
    data class Condition(val name: String) : ArmOperand
}

enum class ArmOpcode {
    MOV, ADD, SUB, MUL, SDIV, CMP, LDR, STR, B, BEQ, BNE, BL, BX,
    PUSH, POP, VMOV, VADD_F32, VSUB_F32, VMUL_F32, VDIV_F32, VCMP_F32, VMRS,
    DMB_ISH, CLREX, UDF,
}

data class ArmInstruction(val opcode: ArmOpcode, val operands: List<ArmOperand> = emptyList(), val comment: String? = null)

data class ArmBlock(val name: String, val instructions: List<ArmInstruction>)

data class ArmFunction(
    val name: String,
    val options: ArmTargetOptions,
    val blocks: List<ArmBlock>,
)

class ArmInstructionSelector(private val options: ArmTargetOptions = ArmTargetOptions()) {
    private val values = LinkedHashMap<String, String>()
    private var nextVirtual = 0

    fun select(function: IrFunction): ArmFunction {
        values.clear()
        nextVirtual = 0
        val blocks = function.blocks.mapIndexed { blockIndex, block ->
            val output = ArrayList<ArmInstruction>()
            if (blockIndex == 0) selectParameters(function, output)
            block.instructions.forEach { selectInstruction(it, output) }
            block.terminator?.let { selectTerminator(it, output) }
            ArmBlock(block.name, output)
        }
        return ArmFunction(function.symbol.name, options, blocks)
    }

    private fun selectParameters(function: IrFunction, output: MutableList<ArmInstruction>) {
        val convention = CallingConventionPlanner(ArmRegisters.callingConvention(options.hardFloat))
        convention.assignArguments(function.parameters.map { it.type }).forEach { assignment ->
            val destination = value(IrValue.Parameter(assignment.index, assignment.type, function.parameters[assignment.index].name))
            val source = when (val location = assignment.location) {
                is AbiLocation.Register -> ArmOperand.Register(location.value.name)
                is AbiLocation.Stack -> ArmOperand.Memory("sp", location.slot.offset)
            }
            output += ArmInstruction(ArmOpcode.MOV, listOf(ArmOperand.Register(destination), source), "AAPCS parameter ${assignment.index}")
        }
    }

    private fun selectInstruction(instruction: IrInstruction, output: MutableList<ArmInstruction>) {
        when (instruction) {
            is IrInstruction.Alloca -> output += ArmInstruction(ArmOpcode.SUB, listOf(ArmOperand.Register("sp"), immediate(instruction.count)))
            is IrInstruction.Load -> output += ArmInstruction(loadOpcode(instruction.loadedType), listOf(ArmOperand.Register(value(instruction.result)), memory(instruction.address)))
            is IrInstruction.Store -> output += ArmInstruction(storeOpcode(instruction.value.type), listOf(memory(instruction.address), operand(instruction.value)))
            is IrInstruction.Binary -> {
                output += ArmInstruction(moveOpcode(instruction.left.type), listOf(ArmOperand.Register(value(instruction.result)), operand(instruction.left)))
                output += ArmInstruction(binaryOpcode(instruction.operation, instruction.left.type), listOf(ArmOperand.Register(value(instruction.result)), operand(instruction.right)))
            }
            is IrInstruction.Compare -> {
                output += ArmInstruction(if (instruction.left.type is IrType.Floating) ArmOpcode.VCMP_F32 else ArmOpcode.CMP, listOf(operand(instruction.left), operand(instruction.right)))
                if (instruction.left.type is IrType.Floating) output += ArmInstruction(ArmOpcode.VMRS, listOf(ArmOperand.Register("apsr_nzcv")))
                output += ArmInstruction(ArmOpcode.MOV, listOf(ArmOperand.Register(value(instruction.result)), ArmOperand.Immediate(0)), instruction.condition.name)
            }
            is IrInstruction.Cast -> output += ArmInstruction(moveOpcode(instruction.targetType), listOf(ArmOperand.Register(value(instruction.result)), operand(instruction.value)))
            is IrInstruction.GetElementPointer -> output += ArmInstruction(ArmOpcode.ADD, listOf(ArmOperand.Register(value(instruction.result)), operand(instruction.base)))
            is IrInstruction.Call -> {
                output += ArmInstruction(ArmOpcode.BL, listOf(callOperand(instruction.callee)))
                instruction.result?.let { output += ArmInstruction(moveOpcode(it.type), listOf(ArmOperand.Register(value(it)), ArmOperand.Register(returnRegister(it.type)))) }
            }
            is IrInstruction.AtomicRmw -> {
                output += ArmInstruction(ArmOpcode.CLREX)
                output += ArmInstruction(ArmOpcode.DMB_ISH, comment = instruction.memoryOrder.name)
                output += ArmInstruction(ArmOpcode.ADD, listOf(ArmOperand.Register(value(instruction.result)), operand(instruction.value)))
            }
            is IrInstruction.CompareExchange -> {
                output += ArmInstruction(ArmOpcode.CLREX)
                output += ArmInstruction(ArmOpcode.DMB_ISH, comment = instruction.memoryOrder.name)
                output += ArmInstruction(ArmOpcode.MOV, listOf(ArmOperand.Register(value(instruction.result)), operand(instruction.expected)))
            }
        }
    }

    private fun selectTerminator(terminator: IrTerminator, output: MutableList<ArmInstruction>) {
        when (terminator) {
            is IrTerminator.Jump -> output += ArmInstruction(ArmOpcode.B, listOf(ArmOperand.Label(terminator.target)))
            is IrTerminator.Branch -> {
                output += ArmInstruction(ArmOpcode.CMP, listOf(operand(terminator.condition), ArmOperand.Immediate(0)))
                output += ArmInstruction(ArmOpcode.BNE, listOf(ArmOperand.Label(terminator.trueTarget)))
                output += ArmInstruction(ArmOpcode.B, listOf(ArmOperand.Label(terminator.falseTarget)))
            }
            is IrTerminator.Switch -> output += ArmInstruction(ArmOpcode.B, listOf(ArmOperand.Label(terminator.defaultTarget)), "switch expansion")
            is IrTerminator.Return -> {
                terminator.value?.let { output += ArmInstruction(moveOpcode(it.type), listOf(ArmOperand.Register(returnRegister(it.type)), operand(it))) }
                output += ArmInstruction(ArmOpcode.BX, listOf(ArmOperand.Register("lr")))
            }
            is IrTerminator.Unreachable -> output += ArmInstruction(ArmOpcode.UDF, listOf(ArmOperand.Immediate(0)))
        }
    }

    private fun operand(value: IrValue): ArmOperand = when (value) {
        is IrValue.Local -> ArmOperand.Register(this.value(value))
        is IrValue.Parameter -> ArmOperand.Register(this.value(value))
        is IrValue.IntegerConstant -> ArmOperand.Immediate(value.value.longValueExact())
        is IrValue.FloatingConstant -> ArmOperand.Immediate(value.value?.toRawBits() ?: 0)
        is IrValue.NullPointer, is IrValue.Undef -> ArmOperand.Immediate(0)
        is IrValue.SymbolAddress -> ArmOperand.Symbol(value.symbol.name)
    }

    private fun immediate(value: IrValue): ArmOperand = operand(value)

    private fun memory(value: IrValue): ArmOperand = when (val operand = operand(value)) {
        is ArmOperand.Register -> ArmOperand.Memory(operand.name)
        is ArmOperand.Symbol -> ArmOperand.Memory(operand.name)
        else -> ArmOperand.Memory("r0")
    }

    private fun callOperand(value: IrValue): ArmOperand = when (val operand = operand(value)) {
        is ArmOperand.Symbol -> operand
        else -> operand
    }

    private fun value(value: IrValue): String = values.getOrPut(key(value)) { "v${nextVirtual++}" }

    private fun key(value: IrValue): String = when (value) {
        is IrValue.Local -> "l${value.id}"
        is IrValue.Parameter -> "p${value.index}"
        else -> error("only SSA values have ARM virtual registers")
    }

    private fun returnRegister(type: IrType): String = if (options.hardFloat && type is IrType.Floating) "s0" else "r0"

    private fun moveOpcode(type: IrType): ArmOpcode = if (options.hardFloat && type is IrType.Floating) ArmOpcode.VMOV else ArmOpcode.MOV
    private fun loadOpcode(type: IrType): ArmOpcode = moveOpcode(type).let { if (it == ArmOpcode.VMOV) ArmOpcode.VMOV else ArmOpcode.LDR }
    private fun storeOpcode(type: IrType): ArmOpcode = moveOpcode(type).let { if (it == ArmOpcode.VMOV) ArmOpcode.VMOV else ArmOpcode.STR }

    private fun binaryOpcode(operation: IrBinaryOp, type: IrType): ArmOpcode = if (options.hardFloat && type is IrType.Floating) {
        when (operation) {
            IrBinaryOp.ADD -> ArmOpcode.VADD_F32
            IrBinaryOp.SUBTRACT -> ArmOpcode.VSUB_F32
            IrBinaryOp.MULTIPLY -> ArmOpcode.VMUL_F32
            IrBinaryOp.DIVIDE -> ArmOpcode.VDIV_F32
            else -> error("unsupported ARM floating operation $operation")
        }
    } else when (operation) {
        IrBinaryOp.ADD -> ArmOpcode.ADD
        IrBinaryOp.SUBTRACT -> ArmOpcode.SUB
        IrBinaryOp.MULTIPLY -> ArmOpcode.MUL
        IrBinaryOp.DIVIDE, IrBinaryOp.REMAINDER -> ArmOpcode.SDIV
        else -> ArmOpcode.ADD
    }
}

class ArmAssemblyEmitter {
    fun emit(function: ArmFunction): String = buildString {
        appendLine(".syntax unified")
        if (function.options.isa == ArmIsa.THUMB2) appendLine(".thumb") else appendLine(".arm")
        if (function.options.hardFloat) appendLine(".fpu vfpv3-d16")
        appendLine(".text")
        appendLine(".global ${function.name}")
        appendLine("${function.name}:")
        appendLine("  push {r4, r5, r6, r7, lr}")
        function.blocks.forEach { block ->
            appendLine("${function.name}.${block.name}:")
            block.instructions.forEach { instruction ->
                val operands = instruction.operands.joinToString(", ") { format(it) }
                val comment = instruction.comment?.let { " @ $it" }.orEmpty()
                appendLine("  ${mnemonic(instruction.opcode)}${if (operands.isEmpty()) "" else " $operands"}$comment")
            }
        }
        appendLine("  pop {r4, r5, r6, r7, pc}")
    }

    private fun mnemonic(opcode: ArmOpcode): String = when (opcode) {
        ArmOpcode.VADD_F32 -> "vadd.f32"
        ArmOpcode.VSUB_F32 -> "vsub.f32"
        ArmOpcode.VMUL_F32 -> "vmul.f32"
        ArmOpcode.VDIV_F32 -> "vdiv.f32"
        ArmOpcode.VCMP_F32 -> "vcmp.f32"
        ArmOpcode.DMB_ISH -> "dmb ish"
        else -> opcode.name.lowercase()
    }

    private fun format(operand: ArmOperand): String = when (operand) {
        is ArmOperand.Register -> operand.name
        is ArmOperand.Immediate -> "#${operand.value}"
        is ArmOperand.Memory -> "[${operand.base}${if (operand.offset == 0L) "" else ", #${operand.offset}"}]"
        is ArmOperand.Label -> operand.name
        is ArmOperand.Symbol -> "${operand.name}(PLT)"
        is ArmOperand.Condition -> operand.name
    }
}
