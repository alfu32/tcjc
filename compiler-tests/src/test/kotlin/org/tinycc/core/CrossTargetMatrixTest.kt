package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.CrossTargetFixtureCompiler
import org.tinycc.backends.CrossTargetProfiles
import org.tinycc.core.ir.IrBasicBlock
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrSymbol
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue
import org.tinycc.core.ir.IrTypes

class CrossTargetMatrixTest {
    @Test
    fun compilesRiscVAndC67FixturesWithoutExternalTools() {
        val function = IrFunction(
            IrSymbol("fixture", IrType.Function(IrTypes.i32, emptyList())),
            emptyList(),
            listOf(IrBasicBlock("entry", emptyList(), IrTerminator.Return(IrValue.IntegerConstant(java.math.BigInteger.ZERO, 32, true)))),
        )
        val compiler = CrossTargetFixtureCompiler()
        val riscv = compiler.compile(CrossTargetProfiles.riscv64Linux, function)
        val c67 = compiler.compile(CrossTargetProfiles.c67, function)

        assertTrue(riscv.assembly.contains("rv64"))
        assertTrue(c67.assembly.contains("C67 COFF"))
        assertTrue(c67.objectBytes.size > 20)
        assertEquals(riscv.assembly, compiler.compile(CrossTargetProfiles.riscv64Linux, function).assembly)
    }

    @Test
    fun publishesRequirementsForEveryUnavailableProfile() {
        CrossTargetProfiles.all.filterNot { it.name == "x86_64-linux" }.forEach { profile ->
            assertTrue(profile.requirements.isNotEmpty() || profile.execution.name == "HOST")
        }
    }
}
