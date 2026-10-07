package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.x86.X86AssemblyEmitter
import org.tinycc.backends.x86.X86CodeGenerator
import org.tinycc.backends.x86.X86InstructionSelector
import org.tinycc.backends.x86.X86Location
import org.tinycc.backends.x86.X86Mode
import org.tinycc.backends.x86.X86Opcode
import org.tinycc.backends.x86.X86Registers
import org.tinycc.backends.x86.X86TargetOptions
import org.tinycc.core.ir.IrBasicBlock
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrCompareCondition
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrMemoryOrder
import org.tinycc.core.ir.IrParameter
import org.tinycc.core.ir.IrSymbol
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue
import org.tinycc.core.ir.IrTypes

class X86BackendTest {
    @Test
    fun selectsAllocatesAndEmitsX8664FunctionWithFrame() {
        val compiled = X86CodeGenerator(X86Mode.X86_64).compile(addFunction())
        val assembly = X86AssemblyEmitter().emit(compiled)

        assertTrue(compiled.allocation.locations.isNotEmpty())
        assertTrue(assembly.contains(".globl add"))
        assertTrue(assembly.contains("push rbp"))
        assertTrue(assembly.contains("add "))
        assertTrue(assembly.contains("pop rbp"))
        assertTrue(assembly.contains("ret"))
    }

    @Test
    fun exposesDistinctI386AndSysvAmd64AbiAssignments() {
        val cdecl = X86Registers.callingConvention(X86Mode.I386)
        val sysv = X86Registers.callingConvention(X86Mode.X86_64)

        assertEquals(0, cdecl.integerArgumentRegisters.size)
        assertEquals(listOf("rdi", "rsi", "rdx", "rcx", "r8", "r9"), sysv.integerArgumentRegisters.map { it.name })
        assertEquals("eax", X86Registers.returnRegister(X86Mode.I386).name)
        assertEquals("rax", X86Registers.returnRegister(X86Mode.X86_64).name)
    }

    @Test
    fun lowersBranchesAndCallsToExplicitMachineControlFlow() {
        val int = IrTypes.i32
        val type = IrType.Function(int, listOf(int))
        val condition = IrValue.Parameter(0, int, "condition")
        val function = IrFunction(
            IrSymbol("choose", type),
            listOf(IrParameter("condition", int)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(IrInstruction.Call(null, IrValue.SymbolAddress(IrSymbol("callee", type)), type, listOf(condition))),
                    IrTerminator.Branch(condition, "yes", "no"),
                ),
                IrBasicBlock("yes", emptyList(), IrTerminator.Return(IrValue.IntegerConstant(java.math.BigInteger.ONE, 32, true))),
                IrBasicBlock("no", emptyList(), IrTerminator.Return(IrValue.IntegerConstant(java.math.BigInteger.ZERO, 32, true))),
            ),
        )
        val machine = X86InstructionSelector(X86Mode.I386).select(function)
        val opcodes = machine.blocks.flatMap { it.instructions }.map { it.opcode }

        assertTrue(opcodes.contains(org.tinycc.backends.x86.X86Opcode.CALL))
        assertTrue(opcodes.contains(org.tinycc.backends.x86.X86Opcode.JNE))
        assertTrue(opcodes.contains(org.tinycc.backends.x86.X86Opcode.JMP))
    }

    @Test
    fun zeroExtendsIntegerComparisonResultsAfterSetcc() {
        val int = IrTypes.i32
        val bool = IrTypes.i1
        val result = IrValue.Local(1, bool, "less")
        val function = IrFunction(
            IrSymbol("isLess", IrType.Function(bool, listOf(int, int))),
            listOf(IrParameter("left", int), IrParameter("right", int)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(
                        IrInstruction.Compare(
                            result,
                            IrCompareCondition.SIGNED_LESS,
                            IrValue.Parameter(0, int, "left"),
                            IrValue.Parameter(1, int, "right"),
                        ),
                    ),
                    IrTerminator.Return(result),
                ),
            ),
        )
        val compiled = X86CodeGenerator(X86Mode.X86_64).compile(function)
        val instructions = compiled.function.blocks.single().instructions
        assertTrue(instructions.any { it.opcode == X86Opcode.SETCC })
        assertTrue(instructions.any { it.opcode == X86Opcode.MOVZX })
        val assembly = X86AssemblyEmitter().emit(compiled)
        assertTrue(assembly.contains("setl "))
        assertTrue(assembly.contains("movzx "))
    }

    @Test
    fun guardsFloatingComparisonsAgainstUnorderedNanFlags() {
        val floating = IrTypes.f64
        val bool = IrTypes.i1
        val guarded = listOf(
            IrCompareCondition.EQUAL,
            IrCompareCondition.FLOAT_ORDERED_LESS,
            IrCompareCondition.FLOAT_ORDERED_LESS_EQUAL,
        )
        val conditions = guarded + listOf(
            IrCompareCondition.NOT_EQUAL,
            IrCompareCondition.FLOAT_ORDERED_GREATER,
            IrCompareCondition.FLOAT_ORDERED_GREATER_EQUAL,
        )
        conditions.forEachIndexed { index, condition ->
            val result = IrValue.Local(1, bool, "comparison")
            val function = IrFunction(
                IrSymbol("floatCompare$index", IrType.Function(bool, listOf(floating, floating))),
                listOf(IrParameter("left", floating), IrParameter("right", floating)),
                listOf(
                    IrBasicBlock(
                        "entry",
                        listOf(
                            IrInstruction.Compare(
                                result,
                                condition,
                                IrValue.Parameter(0, floating, "left"),
                                IrValue.Parameter(1, floating, "right"),
                            ),
                        ),
                        IrTerminator.Return(result),
                    ),
                ),
            )
            val compiled = X86CodeGenerator(X86Mode.X86_64).compile(function)
            val instructions = compiled.function.blocks.single().instructions
            assertTrue(instructions.any { it.opcode == X86Opcode.UCOMISD })
            assertTrue(instructions.count { it.opcode == X86Opcode.SETCC } == if (condition in guarded || condition == IrCompareCondition.NOT_EQUAL) 2 else 1)
            if (condition in guarded) assertTrue(instructions.any { it.opcode == X86Opcode.AND })
            if (condition == IrCompareCondition.NOT_EQUAL) assertTrue(instructions.any { it.opcode == X86Opcode.OR })
            val assembly = X86AssemblyEmitter().emit(compiled)
            assertTrue(assembly.contains("setp ") || assembly.contains("setnp ") || condition !in guarded && condition != IrCompareCondition.NOT_EQUAL)
        }
    }

    @Test
    fun selectsSseFloatingPointOperationsIntoXmmRegisters() {
        val floating = IrTypes.f64
        val type = IrType.Function(floating, listOf(floating, floating))
        val result = IrValue.Local(1, floating, "result")
        val function = IrFunction(
            IrSymbol("sumDouble", type),
            listOf(IrParameter("left", floating), IrParameter("right", floating)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, floating), IrValue.Parameter(1, floating))),
                    IrTerminator.Return(result),
                ),
            ),
        )
        val compiled = X86CodeGenerator(X86Mode.X86_64).compile(function)
        val opcodes = compiled.function.blocks.flatMap { it.instructions }.map { it.opcode }

        assertTrue(opcodes.contains(X86Opcode.MOVSD))
        assertTrue(opcodes.contains(X86Opcode.ADDSD))
        assertTrue(compiled.allocation.locations.values.any { it is X86Location.Register && it.value.name.startsWith("xmm") })
    }

    @Test
    fun lowersAtomicRmwAndCompareExchangeWithOrderingFence() {
        val int = IrTypes.i32
        val pointer = IrType.Pointer(int)
        val type = IrType.Function(int, listOf(pointer))
        val result = IrValue.Local(1, int, "old")
        val function = IrFunction(
            IrSymbol("atomicAdd", type),
            listOf(IrParameter("address", pointer)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(
                        IrInstruction.AtomicRmw(
                            result,
                            org.tinycc.core.ir.IrAtomicOperation.ADD,
                            IrValue.Parameter(0, pointer),
                            IrValue.IntegerConstant(java.math.BigInteger.ONE, 32, signed = true),
                            IrMemoryOrder.SEQ_CST,
                        ),
                    ),
                    IrTerminator.Return(result),
                ),
            ),
        )
        val opcodes = X86InstructionSelector(X86Mode.X86_64).select(function).blocks.flatMap { it.instructions }.map { it.opcode }

        assertTrue(opcodes.contains(X86Opcode.LOCK_XADD))
        assertTrue(opcodes.contains(X86Opcode.MFENCE))
    }

    @Test
    fun emitsPicPltAndThreadLocalRelocationSyntax() {
        val void = IrType.Void
        val calleeType = IrType.Function(void, emptyList())
        val tls = IrSymbol("tlsValue", IrTypes.i32, threadLocal = true)
        val function = IrFunction(
            IrSymbol("getTls", IrType.Function(IrTypes.i32, emptyList())),
            emptyList(),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(
                        IrInstruction.Call(null, IrValue.SymbolAddress(IrSymbol("external", calleeType)), calleeType, emptyList()),
                        IrInstruction.Load(IrValue.Local(1, IrTypes.i32), IrValue.SymbolAddress(tls), IrTypes.i32),
                    ),
                    IrTerminator.Return(IrValue.Local(1, IrTypes.i32)),
                ),
            ),
        )
        val compiled = X86CodeGenerator(
            X86Mode.X86_64,
            X86TargetOptions(X86Mode.X86_64, pic = true, pie = true),
        ).compile(function)
        val assembly = X86AssemblyEmitter().emit(compiled)

        assertTrue(assembly.contains("external@PLT"))
        assertTrue(assembly.contains("tlsValue@TPOFF"))
    }

    private fun addFunction(): IrFunction {
        val int = IrTypes.i32
        val type = IrType.Function(int, listOf(int, int))
        val result = IrValue.Local(1, int, "result")
        return IrFunction(
            IrSymbol("add", type),
            listOf(IrParameter("left", int), IrParameter("right", int)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(
                        IrInstruction.Binary(
                            result,
                            IrBinaryOp.ADD,
                            IrValue.Parameter(0, int, "left"),
                            IrValue.Parameter(1, int, "right"),
                        ),
                    ),
                    IrTerminator.Return(result),
                ),
            ),
        )
    }
}
