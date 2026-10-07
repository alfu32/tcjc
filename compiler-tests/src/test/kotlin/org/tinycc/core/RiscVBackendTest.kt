package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.riscv.RiscVAssemblyEmitter
import org.tinycc.backends.riscv.RiscVInstructionSelector
import org.tinycc.backends.riscv.RiscVOpcode
import org.tinycc.backends.riscv.RiscVRegisters
import org.tinycc.backends.riscv.RiscVTargetOptions
import org.tinycc.backends.riscv.RiscVVariant
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

class RiscVBackendTest {
    @Test
    fun exposesRv32AndRv64CallingConventions() {
        val rv32 = RiscVRegisters.callingConvention(RiscVTargetOptions(RiscVVariant.RV32I, floatingPoint = false))
        val rv64 = RiscVRegisters.callingConvention(RiscVTargetOptions())

        assertEquals(32, rv32.pointerBits)
        assertEquals(listOf("a0", "a1", "a2", "a3"), rv32.integerArgumentRegisters.take(4).map { it.name })
        assertEquals(64, rv64.pointerBits)
        assertEquals("f10", rv64.floatingReturnRegisters.single().name)
    }

    @Test
    fun emitsIntegerFloatingAndAtomicRiscVInstructions() {
        val selected = RiscVInstructionSelector().select(addFunction())
        val assembly = RiscVAssemblyEmitter().emit(selected)
        assertTrue(assembly.contains(".attribute arch, \"rv64imafdc\""))
        assertTrue(assembly.contains("add"))

        val floating = RiscVInstructionSelector().select(floatFunction())
        assertTrue(floating.blocks.flatMap { it.instructions }.any { it.opcode == RiscVOpcode.FADD_D })
        assertTrue(assembly.contains("ret"))
    }

    @Test
    fun lowersAtomicAddToAmoAndFence() {
        val int = IrTypes.i64
        val pointer = IrType.Pointer(int)
        val type = IrType.Function(int, listOf(pointer))
        val result = IrValue.Local(1, int, "old")
        val function = IrFunction(
            IrSymbol("atomic", type),
            listOf(IrParameter("address", pointer)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(IrInstruction.AtomicRmw(result, org.tinycc.core.ir.IrAtomicOperation.ADD, IrValue.Parameter(0, pointer), IrValue.IntegerConstant(java.math.BigInteger.ONE, 64, true))),
                    IrTerminator.Return(result),
                ),
            ),
        )
        val opcodes = RiscVInstructionSelector().select(function).blocks.flatMap { it.instructions }.map { it.opcode }
        assertTrue(opcodes.contains(RiscVOpcode.AMOADD_D))
        assertTrue(opcodes.contains(RiscVOpcode.FENCE))
    }

    private fun addFunction(): IrFunction {
        val int = IrTypes.i64
        val type = IrType.Function(int, listOf(int, int))
        val result = IrValue.Local(1, int, "result")
        return IrFunction(IrSymbol("add", type), listOf(IrParameter("left", int), IrParameter("right", int)), listOf(IrBasicBlock("entry", listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, int), IrValue.Parameter(1, int))), IrTerminator.Return(result))))
    }

    private fun floatFunction(): IrFunction {
        val floating = IrTypes.f64
        val type = IrType.Function(floating, listOf(floating, floating))
        val result = IrValue.Local(1, floating, "result")
        return IrFunction(IrSymbol("addDouble", type), listOf(IrParameter("left", floating), IrParameter("right", floating)), listOf(IrBasicBlock("entry", listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, floating), IrValue.Parameter(1, floating))), IrTerminator.Return(result))))
    }
}
