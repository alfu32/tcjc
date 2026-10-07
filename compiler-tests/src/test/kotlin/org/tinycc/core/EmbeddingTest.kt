package org.tinycc.core

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.api.embedding.CompilerOutputType
import org.tinycc.api.embedding.CompilerOptions
import org.tinycc.api.embedding.KotlinCompilerSession

class EmbeddingTest {
    @Test
    fun compilesPreprocessedUnitsAndOwnsOutputLifecycle() {
        val diagnostics = mutableListOf<String>()
        KotlinCompilerSession(CompilerOptions(outputType = CompilerOutputType.PREPROCESSED)).use { compiler ->
            compiler
                .define("ANSWER", "42")
                .setDiagnosticCallback { diagnostics += it.message }
            val result = compiler.compileString(
                "sample.c",
                "#if ANSWER\nint answer = ANSWER;\n#endif\n",
            )
            assertTrue(result.success)
            assertEquals("int answer = 42;\n", result.preprocessedSource)
            assertTrue(diagnostics.isEmpty())
            assertEquals(result.preprocessedSource, compiler.outputBytes().decodeToString())

            val output = Files.createTempFile("tcjc-output-", ".i")
            try {
                compiler.writeOutput(output)
                assertEquals(result.preprocessedSource, output.readText())
            } finally {
                Files.deleteIfExists(output)
            }
        }
    }

    @Test
    fun selectsX86TokenTableFromCompilationTarget() {
        KotlinCompilerSession(CompilerOptions(target = "i386-linux")).use { compiler ->
            val result = compiler.compileString("tokens.c", "__atomic_store __builtin_va_arg_types")

            assertTrue(result.success)
            assertEquals(listOf(411, 256, -1), result.tokens.map { it.tccId })
        }
    }

    @Test
    fun reportsFrontendDiagnosticsThroughCallbacksAndBlocksInvalidOutput() {
        val diagnostics = mutableListOf<String>()
        KotlinCompilerSession().use { compiler ->
            compiler.setDiagnosticCallback { diagnostics += it.message }
            val result = compiler.compileString("broken.c", "int value = @;\n")
            assertTrue(!result.success)
            assertTrue(diagnostics.any { it.contains("unrecognized character") })
            assertFailsWith<IllegalStateException> { compiler.outputBytes() }
            assertFailsWith<IllegalStateException> { compiler.relocate() }
        }
    }

    @Test
    fun relocatesRegisteredSymbolsAndReleasesSessionResources() {
        val compiler = KotlinCompilerSession()
            .registerSymbol("answer", 1)
            .setSymbolResolver { name -> if (name == "answer") 42 else null }
        compiler.compileString("unit.c", "int answer;\n")
        val relocated = compiler.relocate()
        assertEquals(42, relocated.symbol("answer"))
        compiler.close()
        compiler.close()
        assertFailsWith<IllegalStateException> { compiler.compileString("closed.c", "int x;\n") }
    }

    @Test
    fun rejectsNativeLibrariesAtTheEmbeddingBoundary() {
        KotlinCompilerSession().use { compiler ->
            assertFailsWith<UnsupportedOperationException> {
                compiler.registerLibrary(java.nio.file.Path.of("libexample.so"))
            }
        }
    }
}
