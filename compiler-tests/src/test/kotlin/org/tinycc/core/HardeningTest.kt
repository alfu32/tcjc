package org.tinycc.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.preprocessor.Preprocessor

class HardeningTest {
    @Test
    fun deterministicFuzzCorpusDoesNotCrashFrontEnd() {
        var state = 0x13579BDF
        repeat(256) { iteration ->
            val source = buildString {
                repeat(48 + (iteration % 32)) {
                    state = state * 1103515245 + 12345
                    append(FUZZ_ALPHABET[(state ushr 16).and(Int.MAX_VALUE) % FUZZ_ALPHABET.length])
                }
                append("\n")
            }
            val firstDiagnostics = DiagnosticEngine()
            val firstPreprocessed = Preprocessor(source, diagnostics = firstDiagnostics).process().text
            val secondPreprocessed = Preprocessor(source, diagnostics = DiagnosticEngine()).process().text
            assertEquals(firstPreprocessed, secondPreprocessed, "nondeterministic preprocessor output at $iteration")
            val tokens = Lexer(firstPreprocessed, diagnostics = firstDiagnostics).tokenize()
            assertTrue(tokens.isNotEmpty())
        }
    }

    @Test
    fun recordsRepeatableLexerBenchmarkSnapshot() {
        val fixture = "int add(int left, int right) { return left + right; }\n".repeat(4)
        val iterations = 256
        val started = System.nanoTime()
        var tokenCount = 0
        repeat(iterations) {
            tokenCount += Lexer(fixture).tokenize().size
        }
        val elapsed = System.nanoTime() - started
        assertEquals(iterations * Lexer(fixture).tokenize().size, tokenCount)
        assertTrue(elapsed > 0, "benchmark clock did not advance")
    }

    @Test
    fun releaseSourcesDoNotInvokeNativeLoaders() {
        val forbidden = listOf("System.load(", "System.loadLibrary(", "JNI_OnLoad")
        val roots = listOf("compiler-core", "compiler-backends", "compiler-runtime", "compiler-api", "compiler-cli")
            .map { Path.of(it, "src", "main") }
        val sourceFiles = roots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
            }
        }
        val violations = sourceFiles.flatMap { path ->
            val text = Files.readString(path)
            forbidden.filter(text::contains).map { "$path: $it" }
        }
        assertTrue(violations.isEmpty(), "native loader calls found in release sources: $violations")
    }

    private companion object {
        const val FUZZ_ALPHABET = "abcXYZ0123_#(){}[];,+-*/\\\"' \n"
    }
}
