package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.c67.C67AssemblyEmitter
import org.tinycc.backends.c67.C67CoffEmitter
import org.tinycc.backends.c67.C67Function
import org.tinycc.backends.c67.C67InstructionSelector
import org.tinycc.backends.c67.C67Restrictions
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

class C67BackendTest {
    @Test
    fun enforcesC67RestrictionsAndEmitsCoffAssembly() {
        val function = addFunction()
        assertTrue(C67Restrictions.validate(function).isEmpty())
        val selected = C67InstructionSelector().select(function)
        val assembly = C67AssemblyEmitter().emit(selected)
        val objectFile = C67CoffEmitter.emit(selected)

        assertTrue(assembly.contains(".sect .text"))
        assertTrue(assembly.contains("add"))
        assertEquals(0x99, (objectFile.bytes[0].toInt() and 0xFF) or ((objectFile.bytes[1].toInt() and 0xFF) shl 8))
        assertTrue(objectFile.bytes.size > 20)
    }

    @Test
    fun rejectsUnsupportedC67Atomics() {
        val int = IrTypes.i32
        val pointer = IrType.Pointer(int)
        val function = IrFunction(
            IrSymbol("atomic", IrType.Function(int, listOf(pointer))),
            listOf(IrParameter("address", pointer)),
            listOf(
                IrBasicBlock(
                    "entry",
                    listOf(IrInstruction.AtomicRmw(IrValue.Local(1, int), org.tinycc.core.ir.IrAtomicOperation.ADD, IrValue.Parameter(0, pointer), IrValue.IntegerConstant(java.math.BigInteger.ONE, 32, true))),
                    IrTerminator.Return(IrValue.Local(1, int)),
                ),
            ),
        )
        assertTrue(C67Restrictions.validate(function).any { it.contains("atomic") })
        assertFailsWith<IllegalArgumentException> { C67InstructionSelector().select(function) }
    }

    private fun addFunction(): IrFunction {
        val int = IrTypes.i32
        val type = IrType.Function(int, listOf(int, int))
        val result = IrValue.Local(1, int, "result")
        return IrFunction(IrSymbol("add", type), listOf(IrParameter("left", int), IrParameter("right", int)), listOf(IrBasicBlock("entry", listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, IrValue.Parameter(0, int), IrValue.Parameter(1, int))), IrTerminator.Return(result))))
    }
}
