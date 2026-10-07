package org.tinycc.backends.x86

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
import org.tinycc.core.ir.StackFrame
import org.tinycc.core.ir.StackFrameBuilder

enum class X86Mode(val bits: Int, val architecture: IrArchitecture) {
    I386(32, IrArchitecture.I386),
    X86_64(64, IrArchitecture.X86_64),
}

object X86Registers {
    fun bank(mode: X86Mode): IrRegisterBank = when (mode) {
        X86Mode.I386 -> IrRegisterBank(
            IrArchitecture.I386,
            listOf(
                register("eax", 0, 32, callerSaved = true), register("ebx", 1, 32, callerSaved = false),
                register("ecx", 2, 32, callerSaved = true), register("edx", 3, 32, callerSaved = true),
                register("esi", 4, 32, callerSaved = false), register("edi", 5, 32, callerSaved = false),
                register("ebp", 6, 32, callerSaved = false), register("esp", 7, 32, callerSaved = false),
                floatRegister("xmm0", 8, callerSaved = true), floatRegister("xmm1", 9, callerSaved = true),
                floatRegister("xmm2", 10, callerSaved = true), floatRegister("xmm3", 11, callerSaved = true),
            ),
        )
        X86Mode.X86_64 -> IrRegisterBank(
            IrArchitecture.X86_64,
            listOf(
                register("rax", 0, 64, callerSaved = true), register("rbx", 1, 64, callerSaved = false),
                register("rcx", 2, 64, callerSaved = true), register("rdx", 3, 64, callerSaved = true),
                register("rsi", 4, 64, callerSaved = true), register("rdi", 5, 64, callerSaved = true),
                register("rbp", 6, 64, callerSaved = false), register("rsp", 7, 64, callerSaved = false),
                register("r8", 8, 64, callerSaved = true), register("r9", 9, 64, callerSaved = true),
                register("r10", 10, 64, callerSaved = true), register("r11", 11, 64, callerSaved = true),
                register("r12", 12, 64, callerSaved = false), register("r13", 13, 64, callerSaved = false),
                register("r14", 14, 64, callerSaved = false), register("r15", 15, 64, callerSaved = false),
                floatRegister("xmm0", 16, callerSaved = true), floatRegister("xmm1", 17, callerSaved = true),
                floatRegister("xmm2", 18, callerSaved = true), floatRegister("xmm3", 19, callerSaved = true),
                floatRegister("xmm4", 20, callerSaved = true), floatRegister("xmm5", 21, callerSaved = true),
                floatRegister("xmm6", 22, callerSaved = true), floatRegister("xmm7", 23, callerSaved = true),
            ),
        )
    }

    fun allocatable(mode: X86Mode, registerClass: IrRegisterClass = IrRegisterClass.INTEGER): List<IrRegister> = when (registerClass) {
        IrRegisterClass.FLOAT, IrRegisterClass.VECTOR -> when (mode) {
            X86Mode.I386 -> listOf("xmm0", "xmm1", "xmm2", "xmm3").mapNotNull { bank(mode).find(it) }
            X86Mode.X86_64 -> (0..7).mapNotNull { bank(mode).find("xmm$it") }
        }
        else -> when (mode) {
            X86Mode.I386 -> listOf("eax", "ecx", "edx", "esi", "edi").mapNotNull { bank(mode).find(it) }
            X86Mode.X86_64 -> listOf("rax", "rcx", "rdx", "rsi", "rdi", "r8", "r9", "r10", "r11", "rbx")
                .mapNotNull { bank(mode).find(it) }
        }
    }

    fun calleeSaved(mode: X86Mode): List<IrRegister> = when (mode) {
        X86Mode.I386 -> listOf("ebx", "esi", "edi").mapNotNull { bank(mode).find(it) }
        X86Mode.X86_64 -> listOf("rbx", "r12", "r13", "r14", "r15").mapNotNull { bank(mode).find(it) }
    }

    fun returnRegister(mode: X86Mode, type: IrType? = null): IrRegister = bank(mode).find(
        if (type is IrType.Floating) "xmm0" else if (mode == X86Mode.I386) "eax" else "rax",
    )!!

    fun callingConvention(mode: X86Mode): CallingConventionDescriptor {
        val registers = bank(mode)
        return if (mode == X86Mode.I386) {
            CallingConventionDescriptor(
                "cdecl32", IrArchitecture.I386, 32, 4,
                integerReturnRegisters = listOf(registers.find("eax")!!),
                calleeSavedRegisters = calleeSaved(mode),
            )
        } else {
            CallingConventionDescriptor(
                "sysv-amd64", IrArchitecture.X86_64, 64, 16,
                integerArgumentRegisters = listOf("rdi", "rsi", "rdx", "rcx", "r8", "r9").map { registers.find(it)!! },
                floatingArgumentRegisters = (0..7).map { registers.find("xmm$it")!! },
                integerReturnRegisters = listOf(registers.find("rax")!!),
                floatingReturnRegisters = listOf(registers.find("xmm0")!!),
                calleeSavedRegisters = calleeSaved(mode),
            )
        }
    }

    private fun register(name: String, number: Int, bits: Int, callerSaved: Boolean) = IrRegister(name, number, IrRegisterClass.INTEGER, bits, callerSaved)
    private fun floatRegister(name: String, number: Int, callerSaved: Boolean) = IrRegister(name, number, IrRegisterClass.FLOAT, 128, callerSaved)
}

data class X86VirtualRegister(
    val id: Int,
    val bits: Int,
    val registerClass: IrRegisterClass = IrRegisterClass.INTEGER,
)

enum class X86RelocationSyntax { DIRECT, GOTPCREL, PLT32, TLSGD, TPOFF }

data class X86TargetOptions(
    val mode: X86Mode,
    val pic: Boolean = false,
    val pie: Boolean = false,
    val sse2: Boolean = true,
    val threadLocalStorage: Boolean = true,
) {
    init {
        require(!pie || pic) { "PIE requires PIC addressing" }
    }
}

sealed interface X86RegisterRef {
    data class Virtual(val value: X86VirtualRegister) : X86RegisterRef
    data class Physical(val value: IrRegister) : X86RegisterRef
}

sealed interface X86Operand {
    data class Register(val value: X86RegisterRef) : X86Operand
    data class Immediate(val value: Long) : X86Operand
    data class Memory(
        val base: X86RegisterRef? = null,
        val displacement: Long = 0,
        val symbol: String? = null,
        val relocation: X86RelocationSyntax = X86RelocationSyntax.DIRECT,
    ) : X86Operand
    data class Symbol(val name: String, val relocation: X86RelocationSyntax = X86RelocationSyntax.DIRECT) : X86Operand
    data class Label(val name: String) : X86Operand
    data class Condition(val name: String) : X86Operand
    data class StackSlotRef(val slot: Int) : X86Operand
}

enum class X86Opcode {
    MOV, LEA, ADD, SUB, IMUL, IDIV, AND, OR, XOR, SHL, SHR,
    MOVSS, MOVSD, ADDSS, ADDSD, SUBSS, SUBSD, MULSS, MULSD, DIVSS, DIVSD,
    CMP, UCOMISS, UCOMISD, SETCC, CALL, JMP, JNE, PUSH, POP, SUB_STACK, ADD_STACK,
    XCHG, LOCK_XADD, LOCK_ADD, LOCK_SUB, LOCK_AND, LOCK_OR, LOCK_XOR, CMPXCHG, MFENCE,
    ALLOCA, UD2, RET,
}

data class X86Instruction(
    val opcode: X86Opcode,
    val operands: List<X86Operand> = emptyList(),
    val comment: String? = null,
)

data class X86MachineBlock(val name: String, val instructions: List<X86Instruction>)

data class X86MachineFunction(
    val name: String,
    val mode: X86Mode,
    val blocks: List<X86MachineBlock>,
    val virtualRegisters: Set<X86VirtualRegister>,
)

class X86InstructionSelector(
    private val mode: X86Mode,
    private val options: X86TargetOptions = X86TargetOptions(mode),
) {
    private val valueRegisters = LinkedHashMap<String, X86VirtualRegister>()
    private val virtuals = LinkedHashSet<X86VirtualRegister>()
    private var nextVirtual = 0

    fun select(function: IrFunction): X86MachineFunction {
        valueRegisters.clear()
        virtuals.clear()
        nextVirtual = 0
        val blocks = function.blocks.mapIndexed { blockIndex, block ->
            val instructions = ArrayList<X86Instruction>()
            if (blockIndex == 0) selectParameters(function, instructions)
            block.instructions.forEach { selectInstruction(it, instructions) }
            block.terminator?.let { selectTerminator(it, function, instructions) }
            X86MachineBlock(block.name, instructions)
        }
        return X86MachineFunction(function.symbol.name, mode, blocks, virtuals.toSet())
    }

    private fun selectParameters(function: IrFunction, output: MutableList<X86Instruction>) {
        val convention = X86Registers.callingConvention(mode)
        val assignments = CallingConventionPlanner(convention).assignArguments(function.parameters.map { it.type })
        assignments.forEach { assignment ->
            val parameter = IrValue.Parameter(assignment.index, assignment.type, function.parameters[assignment.index].name)
            val destination = value(parameter)
            val source = when (val location = assignment.location) {
                is AbiLocation.Register -> X86Operand.Register(X86RegisterRef.Physical(location.value))
                is AbiLocation.Stack -> X86Operand.Memory(
                    base = X86RegisterRef.Physical(X86Registers.bank(mode).find(frameRegisterName())!!),
                    displacement = (if (mode == X86Mode.I386) 8 else 16) + location.slot.offset,
                )
            }
            output += X86Instruction(X86Opcode.MOV, listOf(destination, source), "ABI parameter ${assignment.index}")
        }
    }

    private fun frameRegisterName(): String = if (mode == X86Mode.I386) "ebp" else "rbp"

    private fun selectInstruction(instruction: IrInstruction, output: MutableList<X86Instruction>) {
        when (instruction) {
            is IrInstruction.Alloca -> output += X86Instruction(X86Opcode.ALLOCA, listOf(register(instruction.result), immediate(instruction.count)))
            is IrInstruction.Load -> output += X86Instruction(X86Opcode.MOV, listOf(register(instruction.result), memory(instruction.address)))
            is IrInstruction.Store -> output += X86Instruction(X86Opcode.MOV, listOf(memory(instruction.address), value(instruction.value)))
            is IrInstruction.Binary -> {
                output += X86Instruction(moveOpcode(instruction.left.type), listOf(register(instruction.result), value(instruction.left)))
                output += X86Instruction(binaryOpcode(instruction.operation, instruction.left.type), listOf(register(instruction.result), value(instruction.right)))
            }
            is IrInstruction.Compare -> {
                output += X86Instruction(compareOpcode(instruction.left.type), listOf(value(instruction.left), value(instruction.right)))
                output += X86Instruction(X86Opcode.SETCC, listOf(register(instruction.result), X86Operand.Condition(instruction.condition.name.lowercase())))
            }
            is IrInstruction.Cast -> output += X86Instruction(X86Opcode.MOV, listOf(register(instruction.result), value(instruction.value)))
            is IrInstruction.GetElementPointer -> output += X86Instruction(X86Opcode.LEA, listOf(register(instruction.result), memory(instruction.base)))
            is IrInstruction.Call -> {
                output += X86Instruction(X86Opcode.CALL, listOf(callValue(instruction.callee)))
                instruction.result?.let {
                    output += X86Instruction(X86Opcode.MOV, listOf(register(it), X86Operand.Register(X86RegisterRef.Physical(X86Registers.returnRegister(mode, instruction.functionType.returnType)))))
                }
            }
            is IrInstruction.AtomicRmw -> {
                output += X86Instruction(X86Opcode.MOV, listOf(register(instruction.result), value(instruction.value)))
                output += X86Instruction(atomicOpcode(instruction.operation), listOf(memory(instruction.address), register(instruction.result)))
                if (instruction.memoryOrder == org.tinycc.core.ir.IrMemoryOrder.SEQ_CST) output += X86Instruction(X86Opcode.MFENCE)
            }
            is IrInstruction.CompareExchange -> {
                output += X86Instruction(X86Opcode.MOV, listOf(register(instruction.result), value(instruction.expected)))
                output += X86Instruction(X86Opcode.CMPXCHG, listOf(memory(instruction.address), value(instruction.replacement)))
                if (instruction.memoryOrder == org.tinycc.core.ir.IrMemoryOrder.SEQ_CST) output += X86Instruction(X86Opcode.MFENCE)
            }
        }
    }

    private fun selectTerminator(terminator: IrTerminator, function: IrFunction, output: MutableList<X86Instruction>) {
        when (terminator) {
            is IrTerminator.Jump -> output += X86Instruction(X86Opcode.JMP, listOf(X86Operand.Label(terminator.target)))
            is IrTerminator.Branch -> {
                output += X86Instruction(X86Opcode.CMP, listOf(value(terminator.condition), X86Operand.Immediate(0)))
                output += X86Instruction(X86Opcode.JNE, listOf(X86Operand.Label(terminator.trueTarget)))
                output += X86Instruction(X86Opcode.JMP, listOf(X86Operand.Label(terminator.falseTarget)))
            }
            is IrTerminator.Switch -> output += X86Instruction(X86Opcode.JMP, listOf(X86Operand.Label(terminator.defaultTarget)), "switch cases lowered by target expansion")
            is IrTerminator.Return -> {
                terminator.value?.let { output += X86Instruction(X86Opcode.MOV, listOf(X86Operand.Register(X86RegisterRef.Physical(X86Registers.returnRegister(mode, it.type))), value(it))) }
                output += X86Instruction(X86Opcode.RET)
            }
            is IrTerminator.Unreachable -> output += X86Instruction(X86Opcode.UD2)
        }
    }

    private fun register(value: IrValue.Local): X86Operand.Register = X86Operand.Register(virtual(valueKey(value), value.type))

    private fun value(value: IrValue): X86Operand = when (value) {
        is IrValue.Local -> register(value)
        is IrValue.Parameter -> X86Operand.Register(virtual(valueKey(value), value.type))
        is IrValue.IntegerConstant -> X86Operand.Immediate(value.value.longValueExact())
        is IrValue.FloatingConstant -> X86Operand.Immediate(value.value?.toRawBits() ?: 0L)
        is IrValue.NullPointer -> X86Operand.Immediate(0)
        is IrValue.Undef -> X86Operand.Immediate(0)
        is IrValue.SymbolAddress -> X86Operand.Symbol(value.symbol.name, addressRelocation(value.symbol))
    }

    private fun callValue(value: IrValue): X86Operand = when (value) {
        is IrValue.SymbolAddress -> X86Operand.Symbol(value.symbol.name, callRelocation(value.symbol))
        else -> value(value)
    }

    private fun immediate(value: IrValue): X86Operand = when (value) {
        is IrValue.IntegerConstant -> X86Operand.Immediate(value.value.longValueExact())
        else -> value(value)
    }

    private fun memory(value: IrValue): X86Operand.Memory = when (val operand = value(value)) {
        is X86Operand.Register -> X86Operand.Memory(base = operand.value)
        is X86Operand.Symbol -> X86Operand.Memory(symbol = operand.name, relocation = operand.relocation)
        else -> X86Operand.Memory()
    }

    private fun addressRelocation(symbol: org.tinycc.core.ir.IrSymbol): X86RelocationSyntax = when {
        symbol.threadLocal && options.threadLocalStorage -> X86RelocationSyntax.TPOFF
        options.pic || options.pie -> X86RelocationSyntax.GOTPCREL
        else -> X86RelocationSyntax.DIRECT
    }

    private fun callRelocation(symbol: org.tinycc.core.ir.IrSymbol): X86RelocationSyntax = when {
        symbol.threadLocal && options.threadLocalStorage -> X86RelocationSyntax.TLSGD
        options.pic || options.pie -> X86RelocationSyntax.PLT32
        else -> X86RelocationSyntax.DIRECT
    }

    private fun virtual(key: String, type: IrType): X86RegisterRef.Virtual {
        return valueRegisters.getOrPut(key) {
            X86VirtualRegister(nextVirtual++, type.bits(mode), type.registerClass())
                .also { virtuals += it }
        }.let(X86RegisterRef::Virtual)
    }

    private fun valueKey(value: IrValue): String = when (value) {
        is IrValue.Local -> "local:${value.id}"
        is IrValue.Parameter -> "parameter:${value.index}"
        else -> error("only local and parameter values can have registers")
    }

    private fun binaryOpcode(operation: IrBinaryOp, type: IrType): X86Opcode = if (type is IrType.Floating) when (operation) {
        IrBinaryOp.ADD -> if (type.bits == 32) X86Opcode.ADDSS else X86Opcode.ADDSD
        IrBinaryOp.SUBTRACT -> if (type.bits == 32) X86Opcode.SUBSS else X86Opcode.SUBSD
        IrBinaryOp.MULTIPLY -> if (type.bits == 32) X86Opcode.MULSS else X86Opcode.MULSD
        IrBinaryOp.DIVIDE -> if (type.bits == 32) X86Opcode.DIVSS else X86Opcode.DIVSD
        else -> error("unsupported floating-point operation $operation")
    } else when (operation) {
        IrBinaryOp.ADD -> X86Opcode.ADD
        IrBinaryOp.SUBTRACT -> X86Opcode.SUB
        IrBinaryOp.MULTIPLY -> X86Opcode.IMUL
        IrBinaryOp.DIVIDE -> X86Opcode.IDIV
        IrBinaryOp.REMAINDER -> X86Opcode.IDIV
        IrBinaryOp.SHIFT_LEFT -> X86Opcode.SHL
        IrBinaryOp.SHIFT_RIGHT -> X86Opcode.SHR
        IrBinaryOp.BITWISE_AND -> X86Opcode.AND
        IrBinaryOp.BITWISE_OR -> X86Opcode.OR
        IrBinaryOp.BITWISE_XOR -> X86Opcode.XOR
    }

    private fun moveOpcode(type: IrType): X86Opcode = if (type is IrType.Floating) {
        if (type.bits == 32) X86Opcode.MOVSS else X86Opcode.MOVSD
    } else X86Opcode.MOV

    private fun compareOpcode(type: IrType): X86Opcode = if (type is IrType.Floating) {
        if (type.bits == 32) X86Opcode.UCOMISS else X86Opcode.UCOMISD
    } else X86Opcode.CMP

    private fun atomicOpcode(operation: org.tinycc.core.ir.IrAtomicOperation): X86Opcode = when (operation) {
        org.tinycc.core.ir.IrAtomicOperation.EXCHANGE -> X86Opcode.XCHG
        org.tinycc.core.ir.IrAtomicOperation.ADD -> X86Opcode.LOCK_XADD
        org.tinycc.core.ir.IrAtomicOperation.SUBTRACT -> X86Opcode.LOCK_SUB
        org.tinycc.core.ir.IrAtomicOperation.AND -> X86Opcode.LOCK_AND
        org.tinycc.core.ir.IrAtomicOperation.OR -> X86Opcode.LOCK_OR
        org.tinycc.core.ir.IrAtomicOperation.XOR -> X86Opcode.LOCK_XOR
    }
}

sealed interface X86Location {
    data class Register(val value: IrRegister) : X86Location
    data class Spill(val slot: Int) : X86Location
}

data class X86Allocation(
    val locations: Map<X86VirtualRegister, X86Location>,
    val spillSlots: List<org.tinycc.core.ir.StackSlot>,
)

class X86LinearScanAllocator(private val mode: X86Mode) {
    fun allocate(function: X86MachineFunction): X86Allocation {
        val intervals = intervals(function)
        val active = ArrayList<ActiveInterval>()
        val locations = LinkedHashMap<X86VirtualRegister, X86Location>()
        val frame = StackFrameBuilder(if (mode == X86Mode.I386) 4 else 16)
        intervals.forEach { interval ->
            active.removeAll { it.end < interval.start }
            val used = active.mapNotNull { (locations[it.register] as? X86Location.Register)?.value }.toSet()
            val available = X86Registers.allocatable(mode, interval.register.registerClass)
            val register = available.firstOrNull { it !in used && it.bits >= interval.register.bits }
            if (register != null) {
                locations[interval.register] = X86Location.Register(register)
                active += ActiveInterval(interval.register, interval.end)
            } else {
                val slot = frame.allocate((interval.register.bits + 7) / 8, if (mode == X86Mode.I386) 4 else 8)
                locations[interval.register] = X86Location.Spill(slot.id)
            }
        }
        return X86Allocation(locations, frame.finish().slots)
    }

    private fun intervals(function: X86MachineFunction): List<Interval> {
        val first = HashMap<X86VirtualRegister, Int>()
        val last = HashMap<X86VirtualRegister, Int>()
        var position = 0
        function.blocks.forEach { block ->
            block.instructions.forEach { instruction ->
                registerRefs(instruction).forEach { register ->
                    first.putIfAbsent(register, position)
                    last[register] = position
                }
                position++
            }
        }
        return function.virtualRegisters.map { Interval(it, first[it] ?: 0, last[it] ?: 0) }.sortedBy { it.start }
    }

    private fun registerRefs(instruction: X86Instruction): List<X86VirtualRegister> = instruction.operands.flatMap { operand ->
        when (operand) {
            is X86Operand.Register -> (operand.value as? X86RegisterRef.Virtual)?.value?.let(::listOf) ?: emptyList()
            is X86Operand.Memory -> (operand.base as? X86RegisterRef.Virtual)?.value?.let(::listOf) ?: emptyList()
            else -> emptyList()
        }
    }

    private data class Interval(val register: X86VirtualRegister, val start: Int, val end: Int)
    private data class ActiveInterval(val register: X86VirtualRegister, val end: Int)
}

data class X86FramePlan(
    val stackFrame: StackFrame,
    val savedRegisters: List<IrRegister>,
)

class X86FramePlanner(private val mode: X86Mode) {
    fun plan(allocation: X86Allocation): X86FramePlan {
        val saved = allocation.locations.values.mapNotNull { (it as? X86Location.Register)?.value }
            .filter { it in X86Registers.calleeSaved(mode) }
            .distinctBy { it.name }
        val alignment = if (mode == X86Mode.I386) 4 else 16
        val frame = StackFrame(allocation.spillSlots, allocation.spillSlots.maxOfOrNull { it.offset + it.size } ?: 0, alignment)
        return X86FramePlan(frame, saved)
    }
}

data class X86CompiledFunction(
    val function: X86MachineFunction,
    val allocation: X86Allocation,
    val frame: X86FramePlan,
)

class X86CodeGenerator(
    private val mode: X86Mode,
    private val options: X86TargetOptions = X86TargetOptions(mode),
) {
    fun compile(function: IrFunction): X86CompiledFunction {
        val machine = X86InstructionSelector(mode, options).select(function)
        val allocation = X86LinearScanAllocator(mode).allocate(machine)
        return X86CompiledFunction(machine, allocation, X86FramePlanner(mode).plan(allocation))
    }
}

class X86AssemblyEmitter {
    fun emit(compiled: X86CompiledFunction): String = buildString {
        val function = compiled.function
        appendLine(".text")
        appendLine(".globl ${function.name}")
        appendLine("${function.name}:")
        appendLine("  push ${frameRegister(function.mode)}")
        appendLine("  mov ${frameRegister(function.mode)}, ${stackRegister(function.mode)}")
        compiled.frame.savedRegisters.forEach { appendLine("  push ${it.name}") }
        if (compiled.frame.stackFrame.size > 0) appendLine("  sub ${stackRegister(function.mode)}, ${compiled.frame.stackFrame.size}")
        function.blocks.forEach { block ->
            appendLine("${function.name}.${block.name}:")
            block.instructions.forEach { instruction ->
                if (instruction.opcode == X86Opcode.RET) {
                    if (compiled.frame.stackFrame.size > 0) appendLine("  add ${stackRegister(function.mode)}, ${compiled.frame.stackFrame.size}")
                    compiled.frame.savedRegisters.asReversed().forEach { appendLine("  pop ${it.name}") }
                    appendLine("  pop ${frameRegister(function.mode)}")
                }
                appendLine("  ${format(instruction, compiled)}")
            }
        }
    }

    private fun format(instruction: X86Instruction, compiled: X86CompiledFunction): String {
        val operands = instruction.operands.joinToString(", ") { format(it, compiled) }
        val mnemonic = mnemonic(instruction.opcode)
        return listOfNotNull(mnemonic, operands.takeIf { it.isNotEmpty() }, instruction.comment?.let { "# $it" }).joinToString(" ")
    }

    private fun format(operand: X86Operand, compiled: X86CompiledFunction): String = when (operand) {
        is X86Operand.Register -> format(operand.value, compiled)
        is X86Operand.Immediate -> operand.value.toString()
        is X86Operand.Memory -> {
            val base = operand.base?.let { format(it, compiled) }
            val displacement = if (operand.displacement == 0L) "" else operand.displacement.toString()
            val symbol = operand.symbol?.let { formatSymbol(it, operand.relocation, compiled.function.mode) }
            if (base == null && operand.relocation != X86RelocationSyntax.DIRECT && symbol != null) {
                symbol
            } else {
                "[${listOfNotNull(base, symbol, displacement).joinToString(" + ")}]"
            }
        }
        is X86Operand.Symbol -> formatSymbol(operand.name, operand.relocation, compiled.function.mode)
        is X86Operand.Label -> operand.name
        is X86Operand.Condition -> operand.name
        is X86Operand.StackSlotRef -> "[stack+${operand.slot}]"
    }

    private fun format(register: X86RegisterRef, compiled: X86CompiledFunction): String = when (register) {
        is X86RegisterRef.Physical -> register.value.name
        is X86RegisterRef.Virtual -> when (val location = compiled.allocation.locations[register.value]) {
            is X86Location.Register -> location.value.name
            is X86Location.Spill -> "[spill${location.slot}]"
            null -> "[unallocated${register.value.id}]"
        }
    }

    private fun mnemonic(opcode: X86Opcode): String = when (opcode) {
        X86Opcode.LOCK_XADD -> "lock xadd"
        X86Opcode.LOCK_ADD -> "lock add"
        X86Opcode.LOCK_SUB -> "lock sub"
        X86Opcode.LOCK_AND -> "lock and"
        X86Opcode.LOCK_OR -> "lock or"
        X86Opcode.LOCK_XOR -> "lock xor"
        else -> opcode.name.lowercase()
    }

    private fun formatSymbol(name: String, relocation: X86RelocationSyntax, mode: X86Mode): String = when (relocation) {
        X86RelocationSyntax.DIRECT -> name
        X86RelocationSyntax.GOTPCREL -> "$name@GOTPCREL(%rip)"
        X86RelocationSyntax.PLT32 -> "$name@PLT"
        X86RelocationSyntax.TLSGD -> if (mode == X86Mode.X86_64) "$name@TLSGD(%rip)" else "$name@TLSGD"
        X86RelocationSyntax.TPOFF -> if (mode == X86Mode.X86_64) "%fs:$name@TPOFF" else "$name@TPOFF"
    }

    private fun frameRegister(mode: X86Mode): String = if (mode == X86Mode.I386) "ebp" else "rbp"
    private fun stackRegister(mode: X86Mode): String = if (mode == X86Mode.I386) "esp" else "rsp"
}

private fun IrType.bits(mode: X86Mode): Int = when (this) {
    IrType.Void -> 0
    is IrType.Integer -> bits.coerceAtLeast(8)
    is IrType.Floating -> bits
    is IrType.Pointer, is IrType.Function -> mode.bits
    is IrType.Aggregate -> mode.bits
}

private fun IrType.registerClass(): IrRegisterClass = when (this) {
    is IrType.Floating -> IrRegisterClass.FLOAT
    else -> IrRegisterClass.INTEGER
}
