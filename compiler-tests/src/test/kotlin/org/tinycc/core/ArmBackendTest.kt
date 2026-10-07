package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.arm.ArmAssemblyEmitter
import org.tinycc.backends.arm.ArmInstructionSelector
import org.tinycc.backends.arm.ArmIsa
import org.tinycc.backends.arm.ArmOpcode
import org.tinycc.backends.arm.ArmRegisters
import org.tinycc.backends.arm.ArmTargetOptions
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

class ArmBackendTest {
    @Test
    fun exposesAapcsSoftFloatAndHardFloatArgumentRules() {
        val soft = ArmRegisters.callingConvention(hardFloat = false)
        val hard = ArmRegisters.callingConvention(hardFloat = true)

        assertEquals(listOf("r0", "r1", "r2", "r3"), soft.integerArgumentRegisters.map { it.name })
        assertTrue(soft.floatingArgumentRegisters.isEmpty())
        assertEquals(listOf("s0", "s1", "s2"), hard.floatingArgumentRegisters.take(3).map { it.name })
    }

    @Test
    fun emitsArmAndThumbUnifiedAssemblyForIntegerFunction() {
        val function = addFunction()
        val arm = ArmAssemblyEmitter().emit(ArmInstructionSelector(ArmTargetOptions(ArmIsa.ARM)).select(function))
        val thumb = ArmAssemblyEmitter().emit(ArmInstructionSelector(ArmTargetOptions(ArmIsa.THUMB2)).select(function))

        assertTrue(arm.contains(".arm"))
        assertTrue(arm.contains("add"))
        assertTrue(thumb.contains(".thumb"))
        assertTrue(thumb.contains("bx"))
    }

    @Test
    fun selectsVfpOperationsAndBarrierForHardFloatAtomics() {
        val floating = IrTypes.f32
        val type = IrType.Function(floating, listOf(floating, floating))
        val result = IrValue.Local(1, floating, "result")
        val function = IrFunction(
            IrSymbol("sum", type),
            listOf(IrParameter("left", floating), IrParameter("right", floating)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, floating), IrValue.Parameter(1, floating))),
                    IrTerminator.Return(result),
                ),
            ),
        )
        val selected = ArmInstructionSelector(ArmTargetOptions(hardFloat = true)).select(function)
        val opcodes = selected.blocks.flatMap { it.instructions }.map { it.opcode }

        assertTrue(opcodes.contains(ArmOpcode.VADD_F32))
        assertTrue(opcodes.contains(ArmOpcode.VMOV))
        assertTrue(ArmAssemblyEmitter().emit(selected).contains(".fpu vfpv3-d16"))
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
                    listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, int), IrValue.Parameter(1, int))),
                    IrTerminator.Return(result),
                ),
            ),
        )
    }
}
