package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.x86.X86AssemblyEmitter
import org.tinycc.backends.x86.X86CodeGenerator
import org.tinycc.backends.x86.X86InstructionSelector
import org.tinycc.backends.x86.X86Mode
import org.tinycc.backends.x86.X86Registers
import org.tinycc.core.ir.IrBasicBlock
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
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
