package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.expressions.ExpressionParser
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.semantics.ExpressionSemanticAnalyzer
import org.tinycc.core.semantics.ValueCategory
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.FunctionDeclaration
import org.tinycc.core.types.ObjectDeclaration

class ExpressionSemanticsTest {
    @Test
    fun appliesArithmeticPointerDecayLvaluesAndCalls() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        symbols.declare(ObjectDeclaration("pointer", CTypes.pointer(CTypes.int)))
        symbols.declare(FunctionDeclaration("run", CTypes.function(CTypes.int, listOf(CTypes.int)) as CType.Function))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val arithmetic = analyzer.analyze(ExpressionParser(Lexer("value + 2").tokenize()).parse())
        val dereference = analyzer.analyze(ExpressionParser(Lexer("*pointer").tokenize()).parse())
        val call = analyzer.analyze(ExpressionParser(Lexer("run(value)").tokenize()).parse())

        assertEquals(CTypes.int, arithmetic.type)
        assertEquals(ValueCategory.LVALUE, dereference.category)
        assertEquals(CTypes.int, call.type)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun diagnosesInvalidAssignmentsAndUnknownMembers() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val assignment = analyzer.analyze(ExpressionParser(Lexer("3 = value").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("value.missing").tokenize()).parse())

        assertTrue(assignment.type is CType.Error)
        assertTrue(diagnostics.render().contains("assignment target is not an lvalue"))
        assertTrue(diagnostics.render().contains("unknown member"))
    }
}
