package org.tinycc.core

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.ir.ArtifactDifferential
import org.tinycc.core.preprocessor.Preprocessor

class DifferentialParityTest {
    @Test
    fun matchesCapturedPreprocessorGoldenWithKotlinImplementation() {
        val expected = TestPaths.repositoryPath("baseline", "pp", "01.output").toFile().readText().trim()
        val actual = Preprocessor(
            "#define STR(x) #x\nchar p[] = STR(x ## y);\n",
        ).process().text.trim()
        val report = ArtifactDifferential.compareText(expected, actual)
        assertTrue(report.matches, "preprocessor golden differs at ${report.firstDifference}")
    }

    @Test
    fun preservesDeterministicArtifactsAcrossTargetAndConfigurationReplays() {
        val module = org.tinycc.core.ir.IrModule("replay")
        val emitter = org.tinycc.core.ir.DeterministicObjectEmitter()
        val targets = listOf(
            org.tinycc.core.ir.ObjectTarget(org.tinycc.core.ir.IrObjectFormat.ELF, org.tinycc.core.ir.IrArchitecture.X86_64, 64, true),
            org.tinycc.core.ir.ObjectTarget(org.tinycc.core.ir.IrObjectFormat.PE_COFF, org.tinycc.core.ir.IrArchitecture.X86_64, 64, true),
            org.tinycc.core.ir.ObjectTarget(org.tinycc.core.ir.IrObjectFormat.MACH_O, org.tinycc.core.ir.IrArchitecture.ARM64, 64, true),
        )
        targets.forEach { target ->
            val first = emitter.emit(module, target)
            val second = emitter.emit(module.copy(name = module.name), target)
            assertEquals(first.assemblyDigest, second.assemblyDigest)
            assertTrue(ArtifactDifferential.compare(first.bytes, second.bytes).matches)
            assertNotNull(first.target)
        }
    }
}
