package org.tinycc.backends.riscv

import org.tinycc.core.ir.AbiLocation
import org.tinycc.core.ir.CallingConventionDescriptor
import org.tinycc.core.ir.CallingConventionPlanner
import org.tinycc.core.ir.IrArchitecture
import org.tinycc.core.ir.IrAtomicOperation
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrRegister
import org.tinycc.core.ir.IrRegisterBank
import org.tinycc.core.ir.IrRegisterClass
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue

enum class RiscVVariant(val bits: Int) { RV32I(32), RV64GC(64) }

data class RiscVTargetOptions(
    val variant: RiscVVariant = RiscVVariant.RV64GC,
    val floatingPoint: Boolean = true,
    val pic: Boolean = false,
)

object RiscVRegisters {
    private val integerNames = listOf(
        "zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7",
        "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6",
    )

    fun bank(): IrRegisterBank = IrRegisterBank(
        IrArchitecture.RISCV64,
        integerNames.mapIndexed { index, name -> IrRegister(name, index, IrRegisterClass.INTEGER, 64, callerSaved = index in 1..7 || index in 10..17 || index >= 28) } +
            (0..31).map { index -> IrRegister("f$index", 32 + index, IrRegisterClass.FLOAT, 64, callerSaved = index < 8) },
    )

    fun callingConvention(options: RiscVTargetOptions): CallingConventionDescriptor {
        val registers = bank()
        return CallingConventionDescriptor(
            name = if (options.variant == RiscVVariant.RV32I) "ilp32" else "lp64d",
            architecture = IrArchitecture.RISCV64,
            pointerBits = options.variant.bits,
            stackAlignment = 16,
            integerArgumentRegisters = (0..7).map { registers.find("a$it")!! },
            floatingArgumentRegisters = if (options.floatingPoint) (0..7).map { registers.find("f${10 + it}")!! } else emptyList(),
            integerReturnRegisters = listOf(registers.find("a0")!!),
            floatingReturnRegisters = if (options.floatingPoint) listOf(registers.find("f10")!!) else emptyList(),
            calleeSavedRegisters = (8..9).map { registers.find("s${it - 8}")!! } + (18..27).map { registers.find("s${it - 16}")!! },
        )
    }
}

sealed interface RiscVOperand {
    data class Register(val name: String) : RiscVOperand
    data class Immediate(val value: Long) : RiscVOperand
    data class Memory(val base: String, val offset: Long = 0) : RiscVOperand
    data class Label(val name: String) : RiscVOperand
    data class Symbol(val name: String) : RiscVOperand
}

enum class RiscVOpcode {
    LI, MV, ADD, SUB, MUL, DIV, REM, LW, LD, SW, SD, ADDI, AUIPC, LA,
    FLW, FLD, FSW, FSD, FADD_S, FADD_D, FSUB_S, FSUB_D, FMUL_S, FMUL_D, FDIV_S, FDIV_D,
    BEQ, BNE, J, JAL, JALR, RET, AMOSWAP_W, AMOADD_W, AMOADD_D, LR_W, SC_W, LR_D, SC_D, FENCE, ECALL, EBREAK,
}

data class RiscVInstruction(val opcode: RiscVOpcode, val operands: List<RiscVOperand> = emptyList(), val comment: String? = null)

data class RiscVBlock(val name: String, val instructions: List<RiscVInstruction>)

data class RiscVFunction(val name: String, val options: RiscVTargetOptions, val blocks: List<RiscVBlock>)

class RiscVInstructionSelector(private val options: RiscVTargetOptions = RiscVTargetOptions()) {
    private val values = LinkedHashMap<String, String>()
    private var nextVirtual = 0

    fun select(function: IrFunction): RiscVFunction {
        values.clear()
        nextVirtual = 0
        val blocks = function.blocks.mapIndexed { index, block ->
            val output = ArrayList<RiscVInstruction>()
            if (index == 0) selectParameters(function, output)
            block.instructions.forEach { selectInstruction(it, output) }
            block.terminator?.let { selectTerminator(it, output) }
            RiscVBlock(block.name, output)
        }
        return RiscVFunction(function.symbol.name, options, blocks)
    }

    private fun selectParameters(function: IrFunction, output: MutableList<RiscVInstruction>) {
        CallingConventionPlanner(RiscVRegisters.callingConvention(options)).assignArguments(function.parameters.map { it.type }).forEach { assignment ->
            val destination = RiscVOperand.Register(value(IrValue.Parameter(assignment.index, assignment.type)))
            val source = when (val location = assignment.location) {
                is AbiLocation.Register -> RiscVOperand.Register(location.value.name)
                is AbiLocation.Stack -> RiscVOperand.Memory("sp", location.slot.offset)
            }
            output += RiscVInstruction(RiscVOpcode.MV, listOf(destination, source), "RISC-V ABI parameter ${assignment.index}")
        }
    }

    private fun selectInstruction(instruction: IrInstruction, output: MutableList<RiscVInstruction>) {
        when (instruction) {
            is IrInstruction.Alloca -> output += RiscVInstruction(RiscVOpcode.ADDI, listOf(RiscVOperand.Register("sp"), RiscVOperand.Register("sp"), immediate(instruction.count)))
            is IrInstruction.Load -> output += RiscVInstruction(loadOpcode(instruction.loadedType), listOf(RiscVOperand.Register(value(instruction.result)), memory(instruction.address)))
            is IrInstruction.Store -> output += RiscVInstruction(storeOpcode(instruction.value.type), listOf(operand(instruction.value), memory(instruction.address)))
            is IrInstruction.Binary -> {
                output += RiscVInstruction(moveOpcode(instruction.left.type), listOf(RiscVOperand.Register(value(instruction.result)), operand(instruction.left)))
                output += RiscVInstruction(binaryOpcode(instruction.operation, instruction.left.type), listOf(RiscVOperand.Register(value(instruction.result)), operand(instruction.right)))
            }
            is IrInstruction.Compare -> output += RiscVInstruction(RiscVOpcode.SUB, listOf(RiscVOperand.Register(value(instruction.result)), operand(instruction.left), operand(instruction.right)), instruction.condition.name)
            is IrInstruction.Cast -> output += RiscVInstruction(moveOpcode(instruction.targetType), listOf(RiscVOperand.Register(value(instruction.result)), operand(instruction.value)))
            is IrInstruction.GetElementPointer -> output += RiscVInstruction(RiscVOpcode.ADD, listOf(RiscVOperand.Register(value(instruction.result)), operand(instruction.base)))
            is IrInstruction.Call -> {
                output += RiscVInstruction(RiscVOpcode.JAL, listOf(RiscVOperand.Register("ra"), callOperand(instruction.callee)))
                instruction.result?.let { output += RiscVInstruction(moveOpcode(it.type), listOf(RiscVOperand.Register(value(it)), RiscVOperand.Register(returnRegister(it.type)))) }
            }
            is IrInstruction.AtomicRmw -> {
                output += RiscVInstruction(atomicOpcode(instruction.operation, instruction.value.type), listOf(RiscVOperand.Register(value(instruction.result)), memory(instruction.address)))
                output += RiscVInstruction(RiscVOpcode.FENCE, comment = instruction.memoryOrder.name)
            }
            is IrInstruction.CompareExchange -> {
                output += RiscVInstruction(RiscVOpcode.LR_D, listOf(RiscVOperand.Register(value(instruction.result)), memory(instruction.address)))
                output += RiscVInstruction(RiscVOpcode.SC_D, listOf(RiscVOperand.Register("t0"), operand(instruction.replacement), memory(instruction.address)))
                output += RiscVInstruction(RiscVOpcode.FENCE, comment = instruction.memoryOrder.name)
            }
        }
    }

    private fun selectTerminator(terminator: IrTerminator, output: MutableList<RiscVInstruction>) {
        when (terminator) {
            is IrTerminator.Jump -> output += RiscVInstruction(RiscVOpcode.J, listOf(RiscVOperand.Label(terminator.target)))
            is IrTerminator.Branch -> {
                output += RiscVInstruction(RiscVOpcode.BNE, listOf(operand(terminator.condition), RiscVOperand.Immediate(0), RiscVOperand.Label(terminator.trueTarget)))
                output += RiscVInstruction(RiscVOpcode.J, listOf(RiscVOperand.Label(terminator.falseTarget)))
            }
            is IrTerminator.Switch -> output += RiscVInstruction(RiscVOpcode.J, listOf(RiscVOperand.Label(terminator.defaultTarget)), "switch expansion")
            is IrTerminator.Return -> {
                terminator.value?.let { output += RiscVInstruction(moveOpcode(it.type), listOf(RiscVOperand.Register(returnRegister(it.type)), operand(it))) }
                output += RiscVInstruction(RiscVOpcode.RET)
            }
            is IrTerminator.Unreachable -> output += RiscVInstruction(RiscVOpcode.EBREAK)
        }
    }

    private fun operand(value: IrValue): RiscVOperand = when (value) {
        is IrValue.Local -> RiscVOperand.Register(this.value(value))
        is IrValue.Parameter -> RiscVOperand.Register(this.value(value))
        is IrValue.IntegerConstant -> RiscVOperand.Immediate(value.value.longValueExact())
        is IrValue.FloatingConstant -> RiscVOperand.Immediate(value.value?.toRawBits() ?: 0)
        is IrValue.NullPointer, is IrValue.Undef -> RiscVOperand.Immediate(0)
        is IrValue.SymbolAddress -> RiscVOperand.Symbol(value.symbol.name)
    }

    private fun immediate(value: IrValue): RiscVOperand = operand(value)

    private fun memory(value: IrValue): RiscVOperand.Memory = when (val operand = operand(value)) {
        is RiscVOperand.Register -> RiscVOperand.Memory(operand.name)
        is RiscVOperand.Symbol -> RiscVOperand.Memory(operand.name)
        else -> RiscVOperand.Memory("zero")
    }

    private fun callOperand(value: IrValue): RiscVOperand = operand(value)
    private fun value(value: IrValue): String = values.getOrPut(key(value)) { "v${nextVirtual++}" }
    private fun key(value: IrValue): String = when (value) {
        is IrValue.Local -> "l${value.id}"
        is IrValue.Parameter -> "p${value.index}"
        else -> error("only SSA values have RISC-V virtual registers")
    }

    private fun returnRegister(type: IrType): String = if (options.floatingPoint && type is IrType.Floating) "f10" else "a0"
    private fun moveOpcode(type: IrType): RiscVOpcode = when {
        options.floatingPoint && type is IrType.Floating && type.bits == 32 -> RiscVOpcode.FADD_S
        options.floatingPoint && type is IrType.Floating -> RiscVOpcode.FADD_D
        else -> RiscVOpcode.MV
    }
    private fun loadOpcode(type: IrType): RiscVOpcode = when (type) {
        is IrType.Floating -> if (type.bits == 32) RiscVOpcode.FLW else RiscVOpcode.FLD
        else -> if (options.variant == RiscVVariant.RV32I) RiscVOpcode.LW else RiscVOpcode.LD
    }
    private fun storeOpcode(type: IrType): RiscVOpcode = when (type) {
        is IrType.Floating -> if (type.bits == 32) RiscVOpcode.FSW else RiscVOpcode.FSD
        else -> if (options.variant == RiscVVariant.RV32I) RiscVOpcode.SW else RiscVOpcode.SD
    }
    private fun binaryOpcode(operation: IrBinaryOp, type: IrType): RiscVOpcode = if (options.floatingPoint && type is IrType.Floating) {
        when (operation) {
            IrBinaryOp.ADD -> if (type.bits == 32) RiscVOpcode.FADD_S else RiscVOpcode.FADD_D
            IrBinaryOp.SUBTRACT -> if (type.bits == 32) RiscVOpcode.FSUB_S else RiscVOpcode.FSUB_D
            IrBinaryOp.MULTIPLY -> if (type.bits == 32) RiscVOpcode.FMUL_S else RiscVOpcode.FMUL_D
            IrBinaryOp.DIVIDE -> if (type.bits == 32) RiscVOpcode.FDIV_S else RiscVOpcode.FDIV_D
            else -> error("unsupported RISC-V floating operation $operation")
        }
    } else when (operation) {
        IrBinaryOp.ADD -> RiscVOpcode.ADD
        IrBinaryOp.SUBTRACT -> RiscVOpcode.SUB
        IrBinaryOp.MULTIPLY -> RiscVOpcode.MUL
        IrBinaryOp.DIVIDE -> RiscVOpcode.DIV
        IrBinaryOp.REMAINDER -> RiscVOpcode.REM
        else -> RiscVOpcode.ADD
    }
    private fun atomicOpcode(operation: IrAtomicOperation, type: IrType): RiscVOpcode = when (operation) {
        IrAtomicOperation.EXCHANGE -> if (type is IrType.Integer && type.bits <= 32) RiscVOpcode.AMOSWAP_W else RiscVOpcode.AMOSWAP_W
        IrAtomicOperation.ADD -> if (type is IrType.Integer && type.bits <= 32) RiscVOpcode.AMOADD_W else RiscVOpcode.AMOADD_D
        else -> RiscVOpcode.AMOADD_W
    }
}

class RiscVAssemblyEmitter {
    fun emit(function: RiscVFunction): String = buildString {
        appendLine(".option rvc")
        appendLine(".attribute arch, \"rv${function.options.variant.bits}imafdc\"")
        appendLine(".text")
        appendLine(".globl ${function.name}")
        appendLine("${function.name}:")
        function.blocks.forEach { block ->
            appendLine("${function.name}.${block.name}:")
            block.instructions.forEach { instruction ->
                val operands = instruction.operands.joinToString(", ") { format(it) }
                val comment = instruction.comment?.let { " # $it" }.orEmpty()
                appendLine("  ${instruction.opcode.name.lowercase()}${if (operands.isEmpty()) "" else " $operands"}$comment")
            }
        }
    }

    private fun format(operand: RiscVOperand): String = when (operand) {
        is RiscVOperand.Register -> operand.name
        is RiscVOperand.Immediate -> operand.value.toString()
        is RiscVOperand.Memory -> "${operand.offset}(${operand.base})"
        is RiscVOperand.Label -> operand.name
        is RiscVOperand.Symbol -> if (operand.name.isBlank()) "0" else operand.name
    }
}
