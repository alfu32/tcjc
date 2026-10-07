package org.tinycc.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

private data class ParityArea(
    val name: String,
    val baselineEvidence: List<String>,
    val kotlinSuites: List<String>,
)

/** Keeps the migrated Gradle suite accountable to the compatibility areas in the C baseline. */
class ParityMatrixTest {
    @Test
    fun everyBaselineAreaHasAKotlinJvmSuite() {
        val missingClasses = parityAreas.flatMap { area ->
            area.kotlinSuites.filter { suite ->
                val className = if (suite.contains('.')) suite else "org.tinycc.core.$suite"
                runCatching { Class.forName(className) }.isFailure
            }.map { suite -> "${area.name}: $suite" }
        }
        assertTrue(missingClasses.isEmpty(), "missing Kotlin/JVM parity suites: $missingClasses")
    }

    @Test
    fun capturedParityEvidenceRemainsAvailableUntilCutover() {
        val missingEvidence = parityAreas.flatMap { area ->
            area.baselineEvidence.filterNot { TestPaths.repositoryPath(*it.split('/').toTypedArray()).let(Files::exists) }
                .map { "${area.name}: $it" }
        }
        assertTrue(missingEvidence.isEmpty(), "missing captured parity evidence: $missingEvidence")
    }

    private companion object {
        val parityAreas = listOf(
            ParityArea(
                "language front-end",
                listOf("baseline/pp/01.output"),
                listOf("LexerTest", "PreprocessorTest", "ExpressionParserTest", "StatementParserTest", "FunctionParserTest"),
            ),
            ParityArea(
                "semantic model and ABI",
                emptyList(),
                listOf("TypesTest", "SymbolsTest", "LayoutTest", "ExpressionSemanticsTest", "ControlFlowValidatorTest"),
            ),
            ParityArea(
                "compile-time and IR",
                emptyList(),
                listOf("ConstantEvaluationTest", "IrModelTest", "BackendContractsTest", "EmissionTest"),
            ),
            ParityArea(
                "lowering and native targets",
                listOf("baseline/object/ex1.headers"),
                listOf("X86BackendTest", "X86MachineCodeTest", "ArmBackendTest", "Arm64BackendTest", "RiscVBackendTest", "C67BackendTest", "CrossTargetMatrixTest"),
            ),
            ParityArea(
                "objects, linker, and runtime",
                emptyList(),
                listOf("ElfWriterTest", "PortableObjectWriterTest", "RuntimeTest", "ExecutionTest", "EmbeddingTest"),
            ),
            ParityArea(
                "CLI and build integration",
                listOf("baseline/examples/ex1.stdout", "baseline/examples/ex2.stdout", "baseline/examples/ex3-10.stdout"),
                listOf("CliTest", "org.tinycc.BuildSmokeTest", "BuildConfigurationTest"),
            ),
        )
    }
}
