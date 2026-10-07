package org.tinycc.core

import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.preprocessor.Preprocessor
import org.tinycc.core.preprocessor.PreprocessorOptions

class PreprocessorFixtureTest {
    @Test
    fun historicalCFixturesMatchExpectedOutput() {
        val root = TestPaths.repositoryPath("tests", "pp")
        val fixtures = Files.list(root).use { stream ->
            stream.filter { it.extension == "c" || it.extension == "S" }
                .sorted()
                .toList()
        }
        val failures = fixtures.mapNotNull { fixture ->
            val expectedPath = root.resolve("${fixture.nameWithoutExtension}.expect")
            if (!Files.exists(expectedPath)) return@mapNotNull null
            val diagnostics = DiagnosticEngine()
            val actual = Preprocessor(
                Files.readString(fixture),
                fixture,
                diagnostics = diagnostics,
                options = PreprocessorOptions(includePaths = listOf(root)),
            ).process().text
            val expected = Files.readString(expectedPath).lineSequence()
                .filterNot { it.contains("warning:") }
                .joinToString("\n")
            val warningFailure = if (fixture.nameWithoutExtension == "16" &&
                !diagnostics.render().contains("warning")
            ) "missing redefinition warning" else null
            if (canonical(actual) == canonical(expected) && warningFailure == null) null
            else "${fixture.fileName}: ${warningFailure ?: "token stream differs"}\nexpected:\n$expected\nactual:\n$actual"
        }
        assertEquals(emptyList(), failures, failures.joinToString("\n\n"))
    }

    private fun canonical(text: String): List<String> {
        val tokens = ArrayList<String>()
        val operators = listOf(
            "##", "...", ">>=", "<<=", "->", "++", "--", "&&", "||", "==", "!=",
            "<=", ">=", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        )
        var index = 0
        while (index < text.length) {
            when {
                text[index].isWhitespace() -> index++
                text[index] == '"' || text[index] == '\'' -> {
                    val quote = text[index]
                    val start = index++
                    while (index < text.length) {
                        if (text[index] == '\\') index += 2 else if (text[index++] == quote) break
                    }
                    tokens += text.substring(start, index)
                }
                text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '$' -> {
                    val start = index++
                    while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '$' || text[index] == '.')) index++
                    tokens += text.substring(start, index)
                }
                else -> {
                    val operator = operators.firstOrNull { text.startsWith(it, index) } ?: text[index].toString()
                    tokens += operator
                    index += operator.length
                }
            }
        }
        return tokens
    }
}
