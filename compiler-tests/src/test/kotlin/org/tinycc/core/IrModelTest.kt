package org.tinycc.core

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.ir.IrBasicBlock
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrGlobal
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrLinkage
import org.tinycc.core.ir.IrModule
import org.tinycc.core.ir.IrParameter
import org.tinycc.core.ir.IrRelocation
import org.tinycc.core.ir.IrRelocationKind
import org.tinycc.core.ir.IrSymbol
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue
import org.tinycc.core.ir.IrVerifier
import org.tinycc.core.ir.IrTypes

class IrModelTest {
    @Test
    fun verifiesTypedMemoryCallsAndBranches() {
        val int = IrTypes.i32
        val functionType = IrType.Function(int, listOf(int))
        val external = IrSymbol("helper", functionType, IrLinkage.EXTERNAL)
        val parameter = IrValue.Parameter(0, int, "value")
        val address = IrValue.Local(1, IrType.Pointer(int), "slot")
        val loaded = IrValue.Local(2, int, "loaded")
        val called = IrValue.Local(3, int, "called")
        val condition = IrValue.Local(4, IrTypes.i1, "condition")
        val entry = IrBasicBlock(
            name = "entry",
            instructions = listOf(
                IrInstruction.Alloca(address, int, IrValue.IntegerConstant(BigInteger.ONE, 32, signed = true)),
                IrInstruction.Store(parameter, address),
                IrInstruction.Load(loaded, address, int),
                IrInstruction.Call(called, IrValue.SymbolAddress(external), functionType, listOf(loaded)),
                IrInstruction.Compare(condition, org.tinycc.core.ir.IrCompareCondition.NOT_EQUAL, called, IrValue.IntegerConstant(BigInteger.ZERO, 32, signed = true)),
            ),
            terminator = IrTerminator.Branch(condition, "yes", "no"),
        )
        val yes = IrBasicBlock("yes", emptyList(), IrTerminator.Return(called))
        val no = IrBasicBlock("no", emptyList(), IrTerminator.Return(IrValue.IntegerConstant(BigInteger.ZERO, 32, signed = true)))
        val function = IrFunction(
            symbol = IrSymbol("main", functionType),
            parameters = listOf(IrParameter("value", int)),
            blocks = listOf(entry, yes, no),
        )

        assertTrue(IrVerifier().verify(IrModule("typed", functions = listOf(function))).isEmpty())
    }

    @Test
    fun retainsSymbolsRelocationsAndDebugReadyModuleShape() {
        val int = IrTypes.i32
        val symbol = IrSymbol("counter", int, IrLinkage.INTERNAL, ".data")
        val relocation = IrRelocation(".text", 4, 8, IrRelocationKind.ABSOLUTE, symbol, addend = 2)
        val module = IrModule(
            name = "globals",
            globals = listOf(IrGlobal(symbol, IrValue.IntegerConstant(BigInteger.TEN, 32, signed = true))),
            relocations = listOf(relocation),
        )

        assertEquals(emptyList(), IrVerifier().verify(module))
        assertEquals(".data", module.globals.single().symbol.section)
        assertEquals(2, module.relocations.single().addend)
    }

    @Test
    fun rejectsMissingTerminatorsBadBranchesAndReturnTypes() {
        val functionType = IrType.Function(IrTypes.i32, emptyList())
        val function = IrFunction(
            symbol = IrSymbol("broken", functionType),
            parameters = emptyList(),
            blocks = listOf(
                IrBasicBlock(
                    "entry",
                    instructions = emptyList(),
                    terminator = IrTerminator.Branch(IrValue.IntegerConstant(BigInteger.ONE, 32, signed = true), "missing", "missing"),
                ),
                IrBasicBlock("missing", emptyList(), terminator = null),
            ),
        )

        val errors = IrVerifier().verify(IrModule("broken", functions = listOf(function)))
        assertTrue(errors.any { it.message.contains("branch condition must be i1") })
        assertTrue(errors.any { it.message.contains("has no terminator") })
    }
}
