package org.tinycc.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.preprocessor.Preprocessor
import org.tinycc.core.preprocessor.PreprocessorOptions

class PreprocessorTest {
    @Test
    fun expandsObjectFunctionStringizePasteAndVariadicMacros() {
        val source = """
            #define VALUE 41
            #define ADD(a, b) ((a) + (b))
            #define NAME(x) #x
            #define JOIN(a, b) a ## b
            #define LOG(fmt, ...) fmt __VA_ARGS__
            int a = ADD(VALUE, 1);
            const char *name = NAME(hello world);
            int xy = 7;
            int b = JOIN(x, y);
            LOG(a, b, c);
        """.trimIndent()

        val result = Preprocessor(source).process().text

        assertTrue(result.contains("int a = ((41) + (1));"))
        assertTrue(result.contains("const char *name = \"hello world\";"))
        assertTrue(result.contains("int b = xy;"))
        assertTrue(result.contains("a b, c;"))
    }

    @Test
    fun evaluatesConditionalBranchesAndPredefinedValues() {
        val diagnostics = DiagnosticEngine()
        val options = PreprocessorOptions(
            predefined = mapOf("FEATURE" to "3"),
            clock = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC),
        )
        val result = Preprocessor(
            """
            #define ENABLED 1
            #if defined(ENABLED) && FEATURE == 3
            int selected = __LINE__;
            #elif 1
            int wrong = 1;
            #else
            int alsoWrong = 1;
            #endif
            #if 0
            int omitted = 1;
            #endif
            const char *file = __FILE__;
            const char *date = __DATE__;
        """.trimIndent(),
            diagnostics = diagnostics,
            options = options,
        ).process().text

        assertTrue(result.contains("int selected = 3;"))
        assertTrue(!result.contains("wrong"))
        assertTrue(!result.contains("omitted"))
        assertTrue(result.contains("\"<input>\""))
        assertTrue(result.contains("\"Jan 02 2024\""))
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun reportsUnbalancedConditionalsAndMacroArity() {
        val diagnostics = DiagnosticEngine()
        Preprocessor(
            "#define F(a) a\nF()\n#if 1\nvalue\n",
            diagnostics = diagnostics,
        ).process()

        assertTrue(diagnostics.render().contains("expects 1 argument"))
        assertTrue(diagnostics.render().contains("unterminated conditional"))
    }
}
