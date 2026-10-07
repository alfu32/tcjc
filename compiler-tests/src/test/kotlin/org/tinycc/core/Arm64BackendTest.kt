package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.arm64.Arm64AssemblyEmitter
import org.tinycc.backends.arm64.Arm64InstructionSelector
import org.tinycc.backends.arm64.Arm64Opcode
import org.tinycc.backends.arm64.Arm64Registers
import org.tinycc.backends.arm64.Arm64TargetOptions
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

class Arm64BackendTest {
    @Test
    fun exposesAapcs64IntegerAndVectorArguments() {
        val convention = Arm64Registers.callingConvention()
        assertEquals(listOf("x0", "x1", "x2", "x3"), convention.integerArgumentRegisters.take(4).map { it.name })
        assertEquals(listOf("v0", "v1", "v2", "v3"), convention.floatingArgumentRegisters.take(4).map { it.name })
        assertEquals("x0", convention.integerReturnRegisters.single().name)
        assertEquals("v0", convention.floatingReturnRegisters.single().name)
    }

    @Test
    fun emitsAarch64IntegerAndFloatingPointInstructions() {
        val function = addFunction()
        val assembly = Arm64AssemblyEmitter().emit(Arm64InstructionSelector().select(function))
        assertTrue(assembly.contains(".arch armv8-a"))
        assertTrue(assembly.contains("add"))

        val floating = floatingFunction()
        val selected = Arm64InstructionSelector().select(floating)
        val opcodes = selected.blocks.flatMap { it.instructions }.map { it.opcode }
        assertTrue(opcodes.contains(Arm64Opcode.FADD_D))
    }

    @Test
    fun lowersLlscAtomicsAndPicTlsRelocations() {
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
                    listOf(
                        IrInstruction.AtomicRmw(
                            result,
                            org.tinycc.core.ir.IrAtomicOperation.ADD,
                            IrValue.Parameter(0, pointer),
                            IrValue.IntegerConstant(java.math.BigInteger.ONE, 64, signed = true),
                        ),
                    ),
                    IrTerminator.Return(result),
                ),
            ),
        )
        val selected = Arm64InstructionSelector(Arm64TargetOptions(pic = true)).select(function)
        val opcodes = selected.blocks.flatMap { it.instructions }.map { it.opcode }
        val assembly = Arm64AssemblyEmitter().emit(selected)

        assertTrue(opcodes.contains(Arm64Opcode.LDXR))
        assertTrue(opcodes.contains(Arm64Opcode.STXR))
        assertTrue(opcodes.contains(Arm64Opcode.DMB_ISH))
        assertTrue(assembly.contains("dmb ish"))
    }

    private fun addFunction(): IrFunction {
        val int = IrTypes.i64
        val type = IrType.Function(int, listOf(int, int))
        val result = IrValue.Local(1, int, "result")
        return IrFunction(
            IrSymbol("add64", type),
            listOf(IrParameter("left", int), IrParameter("right", int)),
            listOf(IrBasicBlock("entry", listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, int), IrValue.Parameter(1, int))), IrTerminator.Return(result))),
        )
    }

    private fun floatingFunction(): IrFunction {
        val floating = IrTypes.f64
        val type = IrType.Function(floating, listOf(floating, floating))
        val result = IrValue.Local(1, floating, "result")
        return IrFunction(
            IrSymbol("addDouble", type),
            listOf(IrParameter("left", floating), IrParameter("right", floating)),
            listOf(IrBasicBlock("entry", listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, floating), IrValue.Parameter(1, floating))), IrTerminator.Return(result))),
        )
    }
}
