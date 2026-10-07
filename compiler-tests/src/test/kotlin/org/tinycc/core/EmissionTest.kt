package org.tinycc.core

import java.math.BigInteger
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.ir.ArtifactDifferential
import org.tinycc.core.ir.CanonicalAssemblyEmitter
import org.tinycc.core.ir.DeterministicObjectEmitter
import org.tinycc.core.ir.IrArchitecture
import org.tinycc.core.ir.IrBasicBlock
import org.tinycc.core.ir.IrBinaryOp
import org.tinycc.core.ir.IrFunction
import org.tinycc.core.ir.IrInstruction
import org.tinycc.core.ir.IrModule
import org.tinycc.core.ir.IrObjectFormat
import org.tinycc.core.ir.IrParameter
import org.tinycc.core.ir.IrSymbol
import org.tinycc.core.ir.IrTerminator
import org.tinycc.core.ir.IrType
import org.tinycc.core.ir.IrValue
import org.tinycc.core.ir.IrTypes
import org.tinycc.core.ir.ObjectTarget

class EmissionTest {
    @Test
    fun emitsStableAssemblyAndObjectManifests() {
        val module = sampleModule()
        val assembly = CanonicalAssemblyEmitter().emit(module)
        assertTrue(assembly.contains(".function @add"))
        assertTrue(assembly.contains("add i32s"))
        val emitter = DeterministicObjectEmitter()
        val target = ObjectTarget(IrObjectFormat.ELF, IrArchitecture.X86_64, 64, littleEndian = true)
        val first = emitter.emit(module, target)
        val second = emitter.emit(module.copy(functions = module.functions.reversed()), target)
        assertEquals(first.assemblyDigest, second.assemblyDigest)
        assertTrue(ArtifactDifferential.compare(first.bytes, second.bytes).matches)
    }

    @Test
    fun reportsFirstDifferenceInDifferentialArtifacts() {
        val report = ArtifactDifferential.compareText("alpha\nbeta\n", "alpha\nzeta\n")
        assertFalse(report.matches)
        assertNotNull(report.firstDifference)
        assertEquals(report.expectedSize, report.actualSize)
    }

    @Test
    fun comparesObjectTargetMetadataWithCapturedTinyCcHeader() {
        val captured = TestPaths.repositoryPath("baseline", "object", "ex1.headers").toFile().readText()
        val target = ObjectTarget(
            format = if (captured.contains("ELF 64-bit")) IrObjectFormat.ELF else IrObjectFormat.RAW,
            architecture = if (captured.contains("x86-64")) IrArchitecture.X86_64 else IrArchitecture.I386,
            bits = if (captured.contains("class: ELF64")) 64 else 32,
            littleEndian = captured.contains("little endian"),
        )
        val emitted = DeterministicObjectEmitter().emit(sampleModule(), target)

        assertEquals(IrObjectFormat.ELF, emitted.target.format)
        assertEquals(IrArchitecture.X86_64, emitted.target.architecture)
        assertEquals(64, emitted.target.bits)
        assertTrue(emitted.bytes.decodeToString().contains("format=ELF"))
    }

    private fun sampleModule(): IrModule {
        val int = IrTypes.i32
        val type = IrType.Function(int, listOf(int, int))
        val left = IrValue.Parameter(0, int, "left")
        val right = IrValue.Parameter(1, int, "right")
        val result = IrValue.Local(1, int, "result")
        return IrModule(
            name = "golden-add",
            functions = listOf(
                IrFunction(
                    symbol = IrSymbol("add", type),
                    parameters = listOf(IrParameter("left", int), IrParameter("right", int)),
                    blocks = listOf(
                        IrBasicBlock(
                            "entry",
                            listOf(IrInstruction.Binary(result, IrBinaryOp.ADD, left, right)),
                            IrTerminator.Return(result),
                        ),
                    ),
                ),
            ),
        )
    }
}
