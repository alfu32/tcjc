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
import org.tinycc.core.types.TypeQualifiers

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

    @Test
    fun appliesCConversionsPointerRulesCompoundLvaluesAndGenericSelection() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        symbols.declare(ObjectDeclaration("pointer", CTypes.pointer(CTypes.int)))
        symbols.declare(ObjectDeclaration("readonly", CTypes.qualified(CTypes.int, TypeQualifiers(isConst = true))))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val postfix = analyzer.analyze(ExpressionParser(Lexer("value++").tokenize()).parse())
        val pointer = analyzer.analyze(ExpressionParser(Lexer("pointer + 2").tokenize()).parse())
        val compound = analyzer.analyze(ExpressionParser(Lexer("(int){1}").tokenize()).parse())
        val generic = analyzer.analyze(ExpressionParser(Lexer("_Generic(value, int: 1, default: 2)").tokenize()).parse())
        val statementExpression = analyzer.analyze(ExpressionParser(Lexer("({ value + 1; })").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("readonly = 2").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("1.0 % 2").tokenize()).parse())

        assertEquals(CTypes.int, postfix.type)
        assertEquals(CTypes.pointer(CTypes.int), pointer.type)
        assertEquals(ValueCategory.LVALUE, compound.category)
        assertEquals(CTypes.int, generic.type)
        assertEquals(CTypes.int, statementExpression.type)
        assertTrue(diagnostics.render().contains("not an lvalue"))
        assertTrue(diagnostics.render().contains("integer operands are required"))
    }

    @Test
    fun recognizesCoreGnuBuiltinExpressionExtensions() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val constant = analyzer.analyze(ExpressionParser(Lexer("__builtin_constant_p(value)").tokenize()).parse())
        val expected = analyzer.analyze(ExpressionParser(Lexer("__builtin_expect(value, 1)").tokenize()).parse())
        val chosen = analyzer.analyze(ExpressionParser(Lexer("__builtin_choose_expr(1, value, 0)").tokenize()).parse())
        val address = analyzer.analyze(ExpressionParser(Lexer("__builtin_return_address(0)").tokenize()).parse())
        val unreachable = analyzer.analyze(ExpressionParser(Lexer("__builtin_unreachable()").tokenize()).parse())

        assertEquals(CTypes.int, constant.type)
        assertEquals(CTypes.int, expected.type)
        assertEquals(CTypes.int, chosen.type)
        assertEquals(CTypes.pointer(CTypes.void), address.type)
        assertEquals(CTypes.void, unreachable.type)
        assertEquals(0, diagnostics.errorCount)
    }
}
