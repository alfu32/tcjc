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
    }

    @Test
    fun reportsMalformedExpressionAndStillReturnsInvalidNode() {
        val diagnostics = DiagnosticEngine()
        val expression = ExpressionParser(Lexer("a + )", diagnostics = diagnostics).tokenize(), diagnostics).parse()

        assertTrue(expression is Expression.Binary)
        assertEquals(TokenKind.EOF, Lexer("").tokenize().last().kind)
        assertTrue(diagnostics.hasErrors)
    }
}
