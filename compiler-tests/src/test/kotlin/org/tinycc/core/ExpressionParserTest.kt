package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.expressions.BinaryOperator
import org.tinycc.core.expressions.Expression
import org.tinycc.core.expressions.ExpressionParser
import org.tinycc.core.expressions.Initializer
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.lexer.TokenKind

class ExpressionParserTest {
    @Test
    fun respectsCPrecedenceAndParsesPostfixExpressions() {
        val tokens = Lexer("a + b * 2").tokenize()
        val expression = ExpressionParser(tokens).parse()

        val add = assertIs<Expression.Binary>(expression)
        assertEquals(BinaryOperator.ADD, add.operator)
        assertIs<Expression.Name>(add.left)
        val multiply = assertIs<Expression.Binary>(add.right)
        assertEquals(BinaryOperator.MULTIPLY, multiply.operator)

        val postfix = ExpressionParser(Lexer("call(1, 2)[index].field").tokenize()).parse()
        assertIs<Expression.Member>(postfix)
    }

    @Test
    fun parsesCastsSizeofCompoundLiteralsAndInitializers() {
        val cast = ExpressionParser(Lexer("(int)(value + 1)").tokenize()).parse()
        assertIs<Expression.Cast>(cast)
        val size = ExpressionParser(Lexer("sizeof(int)").tokenize()).parse()
        assertIs<Expression.SizeOf>(size)
        val compound = ExpressionParser(Lexer("(int){1, 2}").tokenize()).parse()
        val literal = assertIs<Expression.CompoundLiteral>(compound)
        val values = assertIs<Initializer.ListValue>(literal.initializer).values
        assertEquals(2, values.size)

        val arrayLiteral = assertIs<Expression.CompoundLiteral>(
            ExpressionParser(Lexer("(int[3]){1, [2] = 3}").tokenize()).parse(),
        )
        val designated = assertIs<Initializer.ListValue>(arrayLiteral.initializer).values[1]
        assertIs<Initializer.Designated>(designated)
    }

    @Test
    fun parsesPostfixTypeQueriesAndGenericSelections() {
        val postfix = ExpressionParser(Lexer("value++").tokenize()).parse()
        assertEquals(org.tinycc.core.expressions.UnaryOperator.POST_INCREMENT, assertIs<Expression.Unary>(postfix).operator)
        assertIs<Expression.AlignOf>(ExpressionParser(Lexer("_Alignof(long long)").tokenize()).parse())
        assertIs<Expression.TypeOf>(ExpressionParser(Lexer("typeof(value)").tokenize()).parse())

        val generic = assertIs<Expression.GenericSelection>(
            ExpressionParser(Lexer("_Generic(value, int: 1, default: 2)").tokenize()).parse(),
        )
        assertEquals(2, generic.associations.size)
        assertTrue(generic.associations.any { it.type == null })

        val labelAddress = assertIs<Expression.LabelAddress>(ExpressionParser(Lexer("&&done").tokenize()).parse())
        assertEquals("done", labelAddress.label)

        val statementExpression = assertIs<Expression.StatementExpression>(
            ExpressionParser(Lexer("({ value + 1; })").tokenize()).parse(),
        )
        assertIs<org.tinycc.core.statements.Statement.Compound>(statementExpression.body)
    }

    @Test
    fun reportsMalformedExpressionAndStillReturnsInvalidNode() {
        val diagnostics = DiagnosticEngine()
        val expression = ExpressionParser(Lexer("a + )", diagnostics = diagnostics).tokenize(), diagnostics).parse()

        assertTrue(expression is Expression.Binary)
        assertEquals(TokenKind.EOF, Lexer("").tokenize().last().kind)
        assertTrue(diagnostics.hasErrors)
    }

    @Test
    fun parsesGnuElvisComplexTypeNamesAndAdjacentStrings() {
        val elvis = assertIs<Expression.Conditional>(ExpressionParser(Lexer("value ?: 1").tokenize()).parse())
        assertIs<Expression.Name>(elvis.whenTrue)
        val complex = assertIs<Expression.Cast>(ExpressionParser(Lexer("(_Complex float)value").tokenize()).parse())
        assertEquals(org.tinycc.core.types.CTypes.floatComplex, complex.type)
        val string = assertIs<Expression.StringLiteral>(ExpressionParser(Lexer("\"a\" \"b\"").tokenize()).parse())
        assertEquals("ab", string.value)
    }
}
