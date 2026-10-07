package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.functions.FunctionParser
import org.tinycc.core.functions.FunctionSemanticValidator
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.types.CType

class FunctionParserTest {
    @Test
    fun parsesDefinitionsPrototypesNamedParametersAndVariadics() {
        val source = """
            int sum(int a, int b) { return a + b; }
            int log(const char *format, ...) { return 0; }
            extern int declared(int);
        """.trimIndent()
        val diagnostics = DiagnosticEngine()
        val functions = FunctionParser(Lexer(source, diagnostics = diagnostics).tokenize(), diagnostics).parse()

        assertEquals(3, functions.size)
        assertNotNull(functions[0].body)
        assertEquals(listOf("a", "b"), functions[0].parameters.map { it.name })
        assertTrue(functions[1].declaration.type.variadic)
        assertEquals(null, functions[2].body)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun validatesReturnShapeAgainstFunctionType() {
        val diagnostics = DiagnosticEngine()
        val functions = FunctionParser(
            Lexer("int missing() { return; } void extra() { return 1; }", diagnostics = diagnostics).tokenize(),
            diagnostics,
        ).parse()
        val validator = FunctionSemanticValidator(diagnostics)
        functions.forEach { validator.validate(it) }

        assertTrue(diagnostics.render().contains("non-void function must return a value"))
        assertTrue(diagnostics.render().contains("void function must not return a value"))
    }
}
