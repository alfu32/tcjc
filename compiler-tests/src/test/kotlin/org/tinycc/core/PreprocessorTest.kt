package org.tinycc.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.preprocessor.Preprocessor
import org.tinycc.core.preprocessor.PreprocessorOptions
import org.tinycc.core.preprocessor.PackAction
import org.tinycc.core.preprocessor.PreprocessorPragma

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
    fun respectsRawArgumentsAtPasteSitesAndErasesEmptyVariadicCommas() {
        val result = Preprocessor(
            """
            #define VALUE 41
            #define CAT(a, b) a ## b
            #define USE(a, b) a + b
            #define LOG(fmt, ...) fmt , ## __VA_ARGS__
            int pasted = CAT(VALUE, suffix);
            int expanded = USE(VALUE, VALUE);
            int no_args = LOG(value);
            int many_args = LOG(value, left, right);
            """.trimIndent(),
        ).process().text
        assertTrue(result.contains("int pasted = VALUEsuffix;"))
        assertTrue(result.contains("int expanded = 41 + 41;"))
        assertTrue(result.contains("int no_args = value;"))
        assertTrue(result.contains("int many_args = value"))
        assertTrue(result.contains("left, right;"))
    }

    @Test
    fun supportsNamedGnuVariadicsIncludeQueriesAndFullIfIntegerGrammar() {
        val directory = Files.createTempDirectory("tinycc-pp-query")
        Files.writeString(directory.resolve("present.h"), "int present;\n")
        val result = Preprocessor(
            """
            #define GNU_COMMA(x, y...) x, ## y
            #if defined(__has_include) && __has_include("present.h")
            int found = ((010 == 8) && (0x10 == 16)) ? 1 : 0;
            #endif
            #if __has_include("missing.h")
            int missing = 1;
            #else
            int missing = 0;
            #endif
            int args = GNU_COMMA(left, middle, right);
            """.trimIndent(),
            path = directory.resolve("main.c"),
            options = PreprocessorOptions(includePaths = listOf(directory)),
        ).process().text
        println("PREPROC_RESULT_2=<$result>")

        assertTrue(result.contains("int found = ((010 == 8) && (0x10 == 16)) ? 1 : 0;"))
        assertTrue(result.contains("int missing = 0;"))
        assertTrue(result.contains("int args = left,middle, right;"))
    }

    @Test
    fun recordsPackAndCommentPragmasForLaterSemanticAndLinkerStages() {
        val result = Preprocessor(
            """
            #pragma pack(push, 1)
            #pragma pack(pop)
            #pragma comment(lib, "user32")
            #pragma comment(option, "-subsystem windows")
            int value;
            """.trimIndent(),
        ).process()

        assertTrue(result.pragmas.contains(PreprocessorPragma.Pack(PackAction.PUSH_SET, 1)))
        assertTrue(result.pragmas.contains(PreprocessorPragma.Pack(PackAction.POP)))
        assertTrue(result.pragmas.contains(PreprocessorPragma.Library("user32")))
        assertTrue(result.pragmas.contains(PreprocessorPragma.Option("-subsystem windows")))
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
            "#define F(a, b) a\nF(a)\n#if 1\nvalue\n",
            diagnostics = diagnostics,
        ).process()

        assertTrue(diagnostics.render().contains("expects 2 argument"))
        assertTrue(diagnostics.render().contains("unterminated conditional"))
    }

    @Test
    fun resolvesIncludesOncePragmasMacroStackAndLineDirectives() {
        val directory = Files.createTempDirectory("tinycc-pp")
        Files.writeString(directory.resolve("header.h"), "#pragma once\n#define HEADER_VALUE 9\n")
        val source = """
            #include "header.h"
            #include "header.h"
            #define VALUE 1
            #pragma push_macro("VALUE")
            #undef VALUE
            #define VALUE 2
            #pragma pop_macro("VALUE")
            #line 100 "virtual.c"
            int header = HEADER_VALUE;
            int value = VALUE;
            int line = __LINE__;
            const char *file = __FILE__;
        """.trimIndent()

        val result = Preprocessor(
            source,
            directory.resolve("main.c"),
            options = PreprocessorOptions(includePaths = listOf(directory)),
        ).process().text

        assertEquals(1, result.split("int header = 9;").size - 1)
        assertTrue(result.contains("int value = 1;"))
        assertTrue(result.contains("int line = 102;"))
        assertTrue(result.contains("const char *file = \"virtual.c\";"))
    }
}
