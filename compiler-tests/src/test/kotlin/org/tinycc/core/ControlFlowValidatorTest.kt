package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.functions.FunctionParser
import org.tinycc.core.functions.FunctionSemanticValidator
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.semantics.ControlFlowValidator
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.CType

class ControlFlowValidatorTest {
    @Test
    fun preservesVlaBoundsAndNestedBlockScopes() {
        val diagnostics = DiagnosticEngine()
        val functions = parse(
            "int read(int count) { int values[count]; { int values[2]; return values[0]; } return count; }",
            diagnostics,
        )
        val declaration = assertIs<org.tinycc.core.statements.Statement.DeclarationStatement>(
            assertIs<org.tinycc.core.statements.Statement.Compound>(functions.single().body).statements.first(),
        )
        val array = assertIs<CType.Array>(declaration.declaration.type)
        assertIs<ArrayBound.Variable>(array.bound)
        assertEquals(0, FunctionSemanticValidator(diagnostics).let { validator ->
            functions.forEach { validator.validate(it) }
            diagnostics.errorCount
        })
    }

    @Test
    fun reportsInvalidJumpsLabelsAndUnreachableStatements() {
        val diagnostics = DiagnosticEngine()
        val functions = parse(
            "int invalid(int value) { break; continue; case 1: return value; goto missing; return 0; }",
            diagnostics,
        )
        val validator = ControlFlowValidator(diagnostics)
        validator.validate(functions.single().body!!)

        val rendered = diagnostics.render()
        assertTrue(rendered.contains("break statement not within a loop or switch"))
        assertTrue(rendered.contains("continue statement not within a loop"))
        assertTrue(rendered.contains("case label not within a switch statement"))
        assertTrue(rendered.contains("use of undeclared label 'missing'"))
        assertTrue(rendered.contains("unreachable statement"))
    }

    @Test
    fun reportsDuplicateDeclarationsAndSwitchDefaults() {
        val diagnostics = DiagnosticEngine()
        val functions = parse(
            "int duplicate() { int local = 1; int local = 2; return 0; } int switches(int value) { switch (value) { default: break; default: break; } return 0; }",
            diagnostics,
        )
        FunctionSemanticValidator(diagnostics).also { validator -> functions.forEach { validator.validate(it) } }
        ControlFlowValidator(diagnostics).also { validator -> functions.forEach { validator.validate(it.body!!) } }

        val rendered = diagnostics.render()
        assertTrue(rendered.contains("redefinition of 'local'"))
        assertTrue(rendered.contains("multiple default labels in one switch statement"))
    }

    private fun parse(source: String, diagnostics: DiagnosticEngine) =
        FunctionParser(Lexer(source, diagnostics = diagnostics).tokenize(), diagnostics).parse()
}
