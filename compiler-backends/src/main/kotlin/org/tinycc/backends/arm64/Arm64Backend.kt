package org.tinycc.backends.arm64

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

data class Arm64TargetOptions(
    val hardFloat: Boolean = true,
    val pic: Boolean = false,
    val threadLocalStorage: Boolean = true,
)

object Arm64Registers {
    fun bank(): IrRegisterBank = IrRegisterBank(
        IrArchitecture.ARM64,
        (0..30).map { index -> IrRegister("x$index", index, IrRegisterClass.INTEGER, 64, callerSaved = index < 19) } +
            listOf(IrRegister("sp", 31, IrRegisterClass.INTEGER, 64, callerSaved = false)) +
            (0..31).map { index -> IrRegister("v$index", 32 + index, IrRegisterClass.VECTOR, 128, callerSaved = index < 8) },
    )

    fun callingConvention(hardFloat: Boolean = true): CallingConventionDescriptor {
        val registers = bank()
        return CallingConventionDescriptor(
            name = "aapcs64",
            architecture = IrArchitecture.ARM64,
            pointerBits = 64,
            stackAlignment = 16,
            integerArgumentRegisters = (0..7).map { registers.find("x$it")!! },
            floatingArgumentRegisters = if (hardFloat) (0..7).map { registers.find("v$it")!! } else emptyList(),
            integerReturnRegisters = listOf(registers.find("x0")!!),
            floatingReturnRegisters = if (hardFloat) listOf(registers.find("v0")!!) else emptyList(),
            calleeSavedRegisters = (19..28).map { registers.find("x$it")!! },
        )
    }
}

sealed interface Arm64Operand {
    data class Register(val name: String) : Arm64Operand
    data class Immediate(val value: Long) : Arm64Operand
    data class Memory(val base: String, val offset: Long = 0) : Arm64Operand
    data class Label(val name: String) : Arm64Operand
    data class Symbol(val name: String, val relocation: Arm64Relocation = Arm64Relocation.DIRECT) : Arm64Operand
}

enum class Arm64Relocation { DIRECT, ADRP_PAGE, GOT_PAGE, GOT_LO12, TLS_DESC }

enum class Arm64Opcode {
    MOV, ADD, SUB, MUL, SDIV, CMP, LDR, STR, ADRP, ADD_LO12, LDR_GOT,
    FMOV_S, FMOV_D, FADD_S, FADD_D, FSUB_S, FSUB_D, FMUL_S, FMUL_D, FDIV_S, FDIV_D,
    B, B_EQ, B_NE, BL, RET, LDXR, STXR, DMB_ISH, CLREX, BRK,
}

data class Arm64Instruction(val opcode: Arm64Opcode, val operands: List<Arm64Operand> = emptyList(), val comment: String? = null)

data class Arm64Block(val name: String, val instructions: List<Arm64Instruction>)

data class Arm64Function(val name: String, val options: Arm64TargetOptions, val blocks: List<Arm64Block>)

class Arm64InstructionSelector(private val options: Arm64TargetOptions = Arm64TargetOptions()) {
    private val values = LinkedHashMap<String, String>()
    private var nextVirtual = 0

    fun select(function: IrFunction): Arm64Function {
        values.clear()
        nextVirtual = 0
        val blocks = function.blocks.mapIndexed { blockIndex, block ->
            val output = ArrayList<Arm64Instruction>()
            if (blockIndex == 0) selectParameters(function, output)
            block.instructions.forEach { selectInstruction(it, output) }
            block.terminator?.let { selectTerminator(it, output) }
            Arm64Block(block.name, output)
        }
        return Arm64Function(function.symbol.name, options, blocks)
    }

    private fun selectParameters(function: IrFunction, output: MutableList<Arm64Instruction>) {
        val planner = CallingConventionPlanner(Arm64Registers.callingConvention(options.hardFloat))
        planner.assignArguments(function.parameters.map { it.type }).forEach { assignment ->
            val destination = Arm64Operand.Register(value(IrValue.Parameter(assignment.index, assignment.type)))
            val source = when (val location = assignment.location) {
                is AbiLocation.Register -> Arm64Operand.Register(location.value.name)
                is AbiLocation.Stack -> Arm64Operand.Memory("sp", location.slot.offset)
            }
            output += Arm64Instruction(Arm64Opcode.MOV, listOf(destination, source), "AAPCS64 parameter ${assignment.index}")
        }
    }

    private fun selectInstruction(instruction: IrInstruction, output: MutableList<Arm64Instruction>) {
        when (instruction) {
            is IrInstruction.Alloca -> output += Arm64Instruction(Arm64Opcode.SUB, listOf(Arm64Operand.Register("sp"), immediate(instruction.count)))
            is IrInstruction.Load -> output += Arm64Instruction(Arm64Opcode.LDR, listOf(Arm64Operand.Register(value(instruction.result)), memory(instruction.address)))
            is IrInstruction.Store -> output += Arm64Instruction(Arm64Opcode.STR, listOf(operand(instruction.value), memory(instruction.address)))
            is IrInstruction.Binary -> {
                output += Arm64Instruction(moveOpcode(instruction.left.type), listOf(Arm64Operand.Register(value(instruction.result)), operand(instruction.left)))
                output += Arm64Instruction(binaryOpcode(instruction.operation, instruction.left.type), listOf(Arm64Operand.Register(value(instruction.result)), operand(instruction.right)))
            }
            is IrInstruction.Compare -> output += Arm64Instruction(Arm64Opcode.CMP, listOf(operand(instruction.left), operand(instruction.right)), instruction.condition.name)
            is IrInstruction.Cast -> output += Arm64Instruction(Arm64Opcode.MOV, listOf(Arm64Operand.Register(value(instruction.result)), operand(instruction.value)))
            is IrInstruction.GetElementPointer -> output += Arm64Instruction(Arm64Opcode.ADD, listOf(Arm64Operand.Register(value(instruction.result)), operand(instruction.base)))
            is IrInstruction.Call -> {
                output += Arm64Instruction(Arm64Opcode.BL, listOf(callOperand(instruction.callee)))
                instruction.result?.let { output += Arm64Instruction(moveOpcode(it.type), listOf(Arm64Operand.Register(value(it)), Arm64Operand.Register(returnRegister(it.type)))) }
            }
            is IrInstruction.AtomicRmw -> {
                output += Arm64Instruction(Arm64Opcode.LDXR, listOf(Arm64Operand.Register(value(instruction.result)), memory(instruction.address)))
                output += Arm64Instruction(atomicOpcode(instruction.operation), listOf(Arm64Operand.Register(value(instruction.result)), operand(instruction.value)))
                output += Arm64Instruction(Arm64Opcode.STXR, listOf(Arm64Operand.Register("w9"), Arm64Operand.Register(value(instruction.result)), memory(instruction.address)))
                output += Arm64Instruction(Arm64Opcode.DMB_ISH, comment = instruction.memoryOrder.name)
            }
            is IrInstruction.CompareExchange -> {
                output += Arm64Instruction(Arm64Opcode.LDXR, listOf(Arm64Operand.Register(value(instruction.result)), memory(instruction.address)))
                output += Arm64Instruction(Arm64Opcode.STXR, listOf(Arm64Operand.Register("w9"), operand(instruction.replacement), memory(instruction.address)))
                output += Arm64Instruction(Arm64Opcode.DMB_ISH, comment = instruction.memoryOrder.name)
            }
        }
    }

    private fun selectTerminator(terminator: IrTerminator, output: MutableList<Arm64Instruction>) {
        when (terminator) {
            is IrTerminator.Jump -> output += Arm64Instruction(Arm64Opcode.B, listOf(Arm64Operand.Label(terminator.target)))
            is IrTerminator.Branch -> {
                output += Arm64Instruction(Arm64Opcode.CMP, listOf(operand(terminator.condition), Arm64Operand.Immediate(0)))
                output += Arm64Instruction(Arm64Opcode.B_NE, listOf(Arm64Operand.Label(terminator.trueTarget)))
                output += Arm64Instruction(Arm64Opcode.B, listOf(Arm64Operand.Label(terminator.falseTarget)))
            }
            is IrTerminator.Switch -> output += Arm64Instruction(Arm64Opcode.B, listOf(Arm64Operand.Label(terminator.defaultTarget)), "switch expansion")
            is IrTerminator.Return -> {
                terminator.value?.let { output += Arm64Instruction(moveOpcode(it.type), listOf(Arm64Operand.Register(returnRegister(it.type)), operand(it))) }
                output += Arm64Instruction(Arm64Opcode.RET)
            }
            is IrTerminator.Unreachable -> output += Arm64Instruction(Arm64Opcode.BRK, listOf(Arm64Operand.Immediate(0)))
        }
    }

    private fun operand(value: IrValue): Arm64Operand = when (value) {
        is IrValue.Local -> Arm64Operand.Register(this.value(value))
        is IrValue.Parameter -> Arm64Operand.Register(this.value(value))
        is IrValue.IntegerConstant -> Arm64Operand.Immediate(value.value.longValueExact())
        is IrValue.FloatingConstant -> Arm64Operand.Immediate(value.value?.toRawBits() ?: 0)
        is IrValue.NullPointer, is IrValue.Undef -> Arm64Operand.Immediate(0)
        is IrValue.SymbolAddress -> Arm64Operand.Symbol(value.symbol.name, symbolRelocation(value.symbol))
    }

    private fun immediate(value: IrValue): Arm64Operand = operand(value)

    private fun memory(value: IrValue): Arm64Operand.Memory = when (val operand = operand(value)) {
        is Arm64Operand.Register -> Arm64Operand.Memory(operand.name)
        is Arm64Operand.Symbol -> Arm64Operand.Memory(operand.name)
        else -> Arm64Operand.Memory("x0")
    }

    private fun callOperand(value: IrValue): Arm64Operand = when (val operand = operand(value)) {
        is Arm64Operand.Symbol -> operand.copy(relocation = if (options.pic) Arm64Relocation.DIRECT else Arm64Relocation.DIRECT)
        else -> operand
    }

    private fun symbolRelocation(symbol: org.tinycc.core.ir.IrSymbol): Arm64Relocation = when {
        symbol.threadLocal && options.threadLocalStorage -> Arm64Relocation.TLS_DESC
        options.pic -> Arm64Relocation.GOT_PAGE
        else -> Arm64Relocation.DIRECT
    }

    private fun value(value: IrValue): String = values.getOrPut(key(value)) { "v${nextVirtual++}" }

    private fun key(value: IrValue): String = when (value) {
        is IrValue.Local -> "l${value.id}"
        is IrValue.Parameter -> "p${value.index}"
        else -> error("only SSA values have AArch64 virtual registers")
    }

    private fun returnRegister(type: IrType): String = if (options.hardFloat && type is IrType.Floating) "v0" else "x0"
    private fun moveOpcode(type: IrType): Arm64Opcode = if (options.hardFloat && type is IrType.Floating) {
        if (type.bits == 32) Arm64Opcode.FMOV_S else Arm64Opcode.FMOV_D
    } else Arm64Opcode.MOV
    private fun binaryOpcode(operation: IrBinaryOp, type: IrType): Arm64Opcode = if (options.hardFloat && type is IrType.Floating) {
        when (operation) {
            IrBinaryOp.ADD -> if (type.bits == 32) Arm64Opcode.FADD_S else Arm64Opcode.FADD_D
            IrBinaryOp.SUBTRACT -> if (type.bits == 32) Arm64Opcode.FSUB_S else Arm64Opcode.FSUB_D
            IrBinaryOp.MULTIPLY -> if (type.bits == 32) Arm64Opcode.FMUL_S else Arm64Opcode.FMUL_D
            IrBinaryOp.DIVIDE -> if (type.bits == 32) Arm64Opcode.FDIV_S else Arm64Opcode.FDIV_D
            else -> error("unsupported AArch64 floating operation $operation")
        }
    } else when (operation) {
        IrBinaryOp.ADD -> Arm64Opcode.ADD
        IrBinaryOp.SUBTRACT -> Arm64Opcode.SUB
        IrBinaryOp.MULTIPLY -> Arm64Opcode.MUL
        IrBinaryOp.DIVIDE, IrBinaryOp.REMAINDER -> Arm64Opcode.SDIV
        else -> Arm64Opcode.ADD
    }

    private fun atomicOpcode(operation: IrAtomicOperation): Arm64Opcode = when (operation) {
        IrAtomicOperation.EXCHANGE, IrAtomicOperation.ADD -> Arm64Opcode.ADD
        IrAtomicOperation.SUBTRACT -> Arm64Opcode.SUB
        else -> Arm64Opcode.ADD
    }
}

class Arm64AssemblyEmitter {
    fun emit(function: Arm64Function): String = buildString {
        appendLine(".arch armv8-a")
        appendLine(".text")
        appendLine(".global ${function.name}")
        appendLine(".type ${function.name}, %function")
        appendLine("${function.name}:")
        function.blocks.forEach { block ->
            appendLine("${function.name}.${block.name}:")
            block.instructions.forEach { instruction ->
                val operands = instruction.operands.joinToString(", ") { format(it) }
                val comment = instruction.comment?.let { " // $it" }.orEmpty()
                appendLine("  ${mnemonic(instruction.opcode)}${if (operands.isEmpty()) "" else " $operands"}$comment")
            }
        }
        appendLine(".size ${function.name}, .-${function.name}")
    }

    private fun mnemonic(opcode: Arm64Opcode): String = when (opcode) {
        Arm64Opcode.FMOV_S -> "fmov s"
        Arm64Opcode.FMOV_D -> "fmov d"
        Arm64Opcode.FADD_S -> "fadd s"
        Arm64Opcode.FADD_D -> "fadd d"
        Arm64Opcode.FSUB_S -> "fsub s"
        Arm64Opcode.FSUB_D -> "fsub d"
        Arm64Opcode.FMUL_S -> "fmul s"
        Arm64Opcode.FMUL_D -> "fmul d"
        Arm64Opcode.FDIV_S -> "fdiv s"
        Arm64Opcode.FDIV_D -> "fdiv d"
        Arm64Opcode.DMB_ISH -> "dmb ish"
        else -> opcode.name.lowercase()
    }

    private fun format(operand: Arm64Operand): String = when (operand) {
        is Arm64Operand.Register -> operand.name
        is Arm64Operand.Immediate -> "#${operand.value}"
        is Arm64Operand.Memory -> "[${operand.base}${if (operand.offset == 0L) "" else ", #${operand.offset}"}]"
        is Arm64Operand.Label -> operand.name
        is Arm64Operand.Symbol -> when (operand.relocation) {
            Arm64Relocation.DIRECT -> operand.name
            Arm64Relocation.ADRP_PAGE -> ":pg_hi21:${operand.name}"
            Arm64Relocation.GOT_PAGE -> ":got:${operand.name}:PAGE:"
            Arm64Relocation.GOT_LO12 -> ":got_lo12:${operand.name}:"
            Arm64Relocation.TLS_DESC -> ":tlsdesc:${operand.name}:"
        }
    }
}
