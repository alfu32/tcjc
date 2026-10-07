package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.arm.ArmArchitecture
import org.tinycc.backends.arm.ArmCrossCompiler
import org.tinycc.backends.arm.ArmExecutionAvailability
import org.tinycc.backends.arm.ArmPlatformTargets
import org.tinycc.core.ir.IrBasicBlock
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrSymbol
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue
import org.tinycc.core.ir.IrTypes

class ArmPlatformTest {
    @Test
    fun coversLinuxWindowsAndAppleCrossTargetProfiles() {
        val function = IrFunction(
            IrSymbol("platformMain", IrType.Function(IrTypes.i32, emptyList())),
            emptyList(),
            listOf(IrBasicBlock("entry", emptyList(), IrTerminator.Return(IrValue.IntegerConstant(java.math.BigInteger.ZERO, 32, true)))),
        )
        val artifacts = ArmCrossCompiler().let { compiler ->
            ArmPlatformTargets.all.map { compiler.compile(it, function) }
        }

        assertEquals(5, artifacts.size)
        assertTrue(artifacts.any { it.target.objectFormat.name == "ELF" && it.target.architecture == ArmArchitecture.ARM32 })
        assertTrue(artifacts.any { it.target.objectFormat.name == "PE_COFF" && it.target.targetOsIsWindows() })
        assertTrue(artifacts.any { it.target.objectFormat.name == "MACH_O" && it.target.targetOsIsApple() })
        artifacts.forEach { artifact ->
            assertTrue(artifact.assembly.contains(artifact.target.triple))
            assertTrue(artifact.assembly.contains("object-format ${artifact.target.objectFormat}"))
        }
    }

    @Test
    fun reportsExecutionAvailabilityWithoutAssumingAnEmulator() {
        val unavailable = ArmPlatformTargets.all.filterNot { ArmExecutionAvailability.supports(it) }
        assertFalse(unavailable.isEmpty() && System.getProperty("os.arch") !in setOf("aarch64", "arm64"))
    }

    private fun org.tinycc.backends.arm.ArmPlatformTarget.targetOsIsWindows() = operatingSystem.name == "WINDOWS"
    private fun org.tinycc.backends.arm.ArmPlatformTarget.targetOsIsApple() = operatingSystem.name == "APPLE"
}
