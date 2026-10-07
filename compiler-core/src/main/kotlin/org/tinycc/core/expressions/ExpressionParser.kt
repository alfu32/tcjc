package org.tinycc.core.expressions

import java.math.BigInteger
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.lexer.LiteralValue
import org.tinycc.core.lexer.Token
import org.tinycc.core.lexer.TokenKind
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.TypeQualifiers

/** Recursive-descent expression parser with C operator precedence. */
class ExpressionParser(
    private val tokens: List<Token>,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
) {
    private var index = 0

    fun parse(): Expression {
        val expression = parseExpression()
        if (!at(TokenKind.EOF)) error(current(), "unexpected token '${current().lexeme}' after expression")
        return expression
    }

    fun parseExpression(): Expression {
        var expression = parseAssignment()
        while (match(TokenKind.COMMA) != null) {
            val right = parseAssignment()
            expression = Expression.Binary(BinaryOperator.COMMA, expression, right, expression.span.merge(right.span))
        }
        return expression
    }

    fun parseInitializer(): Initializer {
        if (match(TokenKind.LEFT_BRACE) == null) return Initializer.ExpressionValue(parseAssignment())
        val values = ArrayList<Initializer>()
        if (!at(TokenKind.RIGHT_BRACE)) {
            do {
                values += parseInitializer()
            } while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_BRACE))
        }
        expect(TokenKind.RIGHT_BRACE, "'}'")
        return Initializer.ListValue(values)
    }

    private fun parseAssignment(): Expression {
        val target = parseConditional()
        val operator = assignmentOperator(current().kind) ?: return target
        val token = take()
        return Expression.Assignment(operator, target, parseAssignment(), target.span.merge(token.span))
    }

    private fun parseConditional(): Expression {
        val condition = parseBinary(0)
        if (match(TokenKind.QUESTION) == null) return condition
        val whenTrue = parseExpression()
        expect(TokenKind.COLON, "':'")
        val whenFalse = parseAssignment()
        return Expression.Conditional(condition, whenTrue, whenFalse, condition.span.merge(whenFalse.span))
    }

    private fun parseBinary(minimumPrecedence: Int): Expression {
        var left = parseUnary()
        while (true) {
            val operator = binaryOperator(current().kind) ?: break
            if (operator.precedence < minimumPrecedence) break
            take()
            val right = parseBinary(operator.precedence + 1)
            left = Expression.Binary(operator.value, left, right, left.span.merge(right.span))
        }
        return left
    }

    private fun parseUnary(): Expression {
        val token = current()
        val operator = when (token.kind) {
            TokenKind.PLUS -> UnaryOperator.PLUS
            TokenKind.MINUS -> UnaryOperator.MINUS
            TokenKind.BANG -> UnaryOperator.LOGICAL_NOT
            TokenKind.TILDE -> UnaryOperator.BITWISE_NOT
            TokenKind.AMPERSAND -> UnaryOperator.ADDRESS
            TokenKind.STAR -> UnaryOperator.DEREFERENCE
            TokenKind.PLUS_PLUS -> UnaryOperator.PRE_INCREMENT
            TokenKind.MINUS_MINUS -> UnaryOperator.PRE_DECREMENT
            else -> null
        }
        if (operator != null) {
            take()
            return Expression.Unary(operator, parseUnary(), token.span.merge(previous().span))
        }
        if (match(TokenKind.SIZEOF) != null) return parseSizeOf(token)
        if (match(TokenKind.EXTENSION) != null) return parseUnary()
        return parsePostfix()
    }

    private fun parseSizeOf(keyword: Token): Expression {
        if (match(TokenKind.LEFT_PAREN) != null) {
            if (isTypeStart(current().kind)) {
                val type = parseTypeName()
                val close = expect(TokenKind.RIGHT_PAREN, "')'")
                return Expression.SizeOf(SizeOperand.Type(type), keyword.span.merge(close.span))
            }
            val value = parseExpression()
            val close = expect(TokenKind.RIGHT_PAREN, "')'")
            return Expression.SizeOf(SizeOperand.Expression(value), keyword.span.merge(close.span))
        }
        return Expression.SizeOf(SizeOperand.Expression(parseUnary()), keyword.span.merge(previous().span))
    }

    private fun parsePostfix(): Expression {
        var expression = parsePrimary()
        while (true) {
            expression = when {
                match(TokenKind.LEFT_PAREN) != null -> {
                    val arguments = ArrayList<Expression>()
                    if (!at(TokenKind.RIGHT_PAREN)) {
                        do arguments += parseAssignment() while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_PAREN))
                    }
                    val close = expect(TokenKind.RIGHT_PAREN, "')'")
                    Expression.Call(expression, arguments, expression.span.merge(close.span))
                }
                match(TokenKind.LEFT_BRACKET) != null -> {
                    val index = parseExpression()
                    val close = expect(TokenKind.RIGHT_BRACKET, "']'")
                    Expression.Index(expression, index, expression.span.merge(close.span))
                }
                match(TokenKind.DOT) != null -> {
                    val name = expect(TokenKind.IDENTIFIER, "member name")
                    Expression.Member(expression, name.lexeme, false, expression.span.merge(name.span))
                }
                match(TokenKind.ARROW) != null -> {
                    val name = expect(TokenKind.IDENTIFIER, "member name")
                    Expression.Member(expression, name.lexeme, true, expression.span.merge(name.span))
                }
                else -> break
            }
        }
        return expression
    }

    private fun parsePrimary(): Expression {
        val token = take()
        return when (token.kind) {
            TokenKind.IDENTIFIER -> Expression.Name(token.lexeme, token.span)
            TokenKind.INTEGER_LITERAL -> Expression.Integer((token.literal as LiteralValue.Integer).value, token.span)
            TokenKind.FLOAT_LITERAL -> Expression.Floating(token.lexeme, token.span)
            TokenKind.CHARACTER_LITERAL -> Expression.Character((token.literal as LiteralValue.Character).value, token.span)
            TokenKind.STRING_LITERAL -> {
                val value = token.literal as LiteralValue.StringValue
                Expression.StringLiteral(value.value, value.wide, token.span)
            }
            TokenKind.LEFT_PAREN -> parseParenthesizedOrCast(token)
            else -> {
                error(token, "expression expected")
                Expression.Invalid(token.span)
            }
        }
    }

    private fun parseParenthesizedOrCast(open: Token): Expression {
        if (isTypeStart(current().kind)) {
            val type = parseTypeName()
            val close = expect(TokenKind.RIGHT_PAREN, "')'")
            if (match(TokenKind.LEFT_BRACE) != null) {
                val initializer = parseInitializerAfterOpenBrace()
                return Expression.CompoundLiteral(type, initializer, open.span.merge(previous().span))
            }
            return Expression.Cast(type, parseUnary(), open.span.merge(close.span))
        }
        val expression = parseExpression()
        expect(TokenKind.RIGHT_PAREN, "')'")
        return expression
    }

    private fun parseInitializerAfterOpenBrace(): Initializer {
        val values = ArrayList<Initializer>()
        if (!at(TokenKind.RIGHT_BRACE)) {
            do values += parseInitializer() while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_BRACE))
        }
        expect(TokenKind.RIGHT_BRACE, "'}'")
        return Initializer.ListValue(values)
    }

    private fun parseTypeName(): CType {
        var qualifiers = TypeQualifiers()
        if (match(TokenKind.CONST) != null) qualifiers = qualifiers.copy(isConst = true)
        val base = when (take().kind) {
            TokenKind.VOID -> CTypes.void
            TokenKind.CHAR -> CTypes.char
            TokenKind.BOOL -> CTypes.bool
            TokenKind.FLOAT -> CTypes.float
            TokenKind.DOUBLE -> CTypes.double
            TokenKind.SHORT -> CType.Primitive(PrimitiveKind.SHORT)
            TokenKind.LONG -> CType.Primitive(PrimitiveKind.LONG)
            TokenKind.UNSIGNED -> when {
                match(TokenKind.SHORT) != null -> CType.Primitive(PrimitiveKind.UNSIGNED_SHORT)
                match(TokenKind.LONG) != null -> CType.Primitive(PrimitiveKind.UNSIGNED_LONG)
                else -> CType.Primitive(PrimitiveKind.UNSIGNED_INT)
            }
            TokenKind.SIGNED -> CTypes.int
            TokenKind.INT -> CTypes.int
            else -> CType.Error
        }
        var result: CType = if (qualifiers == TypeQualifiers()) base else CTypes.qualified(base, qualifiers)
        while (match(TokenKind.STAR) != null) result = CTypes.pointer(result)
        return result
    }

    private fun isTypeStart(kind: TokenKind): Boolean = kind in setOf(
        TokenKind.VOID, TokenKind.CHAR, TokenKind.BOOL, TokenKind.INT, TokenKind.FLOAT, TokenKind.DOUBLE,
        TokenKind.SHORT, TokenKind.LONG, TokenKind.SIGNED, TokenKind.UNSIGNED, TokenKind.CONST,
    )

    private fun assignmentOperator(kind: TokenKind): AssignmentOperator? = when (kind) {
        TokenKind.ASSIGN -> AssignmentOperator.ASSIGN
        TokenKind.PLUS_ASSIGN -> AssignmentOperator.ADD
        TokenKind.MINUS_ASSIGN -> AssignmentOperator.SUBTRACT
        TokenKind.STAR_ASSIGN -> AssignmentOperator.MULTIPLY
        TokenKind.SLASH_ASSIGN -> AssignmentOperator.DIVIDE
        TokenKind.PERCENT_ASSIGN -> AssignmentOperator.REMAINDER
        TokenKind.AMPERSAND_ASSIGN -> AssignmentOperator.AND
        TokenKind.PIPE_ASSIGN -> AssignmentOperator.OR
        TokenKind.CARET_ASSIGN -> AssignmentOperator.XOR
        TokenKind.LEFT_SHIFT_ASSIGN -> AssignmentOperator.SHIFT_LEFT
        TokenKind.RIGHT_SHIFT_ASSIGN -> AssignmentOperator.SHIFT_RIGHT
        else -> null
    }

    private fun binaryOperator(kind: TokenKind): BinaryOperatorInfo? = when (kind) {
        TokenKind.STAR -> BinaryOperatorInfo(BinaryOperator.MULTIPLY, 12)
        TokenKind.SLASH -> BinaryOperatorInfo(BinaryOperator.DIVIDE, 12)
        TokenKind.PERCENT -> BinaryOperatorInfo(BinaryOperator.REMAINDER, 12)
        TokenKind.PLUS -> BinaryOperatorInfo(BinaryOperator.ADD, 11)
        TokenKind.MINUS -> BinaryOperatorInfo(BinaryOperator.SUBTRACT, 11)
        TokenKind.LEFT_SHIFT -> BinaryOperatorInfo(BinaryOperator.SHIFT_LEFT, 10)
        TokenKind.RIGHT_SHIFT -> BinaryOperatorInfo(BinaryOperator.SHIFT_RIGHT, 10)
        TokenKind.LESS -> BinaryOperatorInfo(BinaryOperator.LESS, 9)
        TokenKind.LESS_EQUAL -> BinaryOperatorInfo(BinaryOperator.LESS_EQUAL, 9)
        TokenKind.GREATER -> BinaryOperatorInfo(BinaryOperator.GREATER, 9)
        TokenKind.GREATER_EQUAL -> BinaryOperatorInfo(BinaryOperator.GREATER_EQUAL, 9)
        TokenKind.EQUAL_EQUAL -> BinaryOperatorInfo(BinaryOperator.EQUAL, 8)
        TokenKind.BANG_EQUAL -> BinaryOperatorInfo(BinaryOperator.NOT_EQUAL, 8)
        TokenKind.AMPERSAND -> BinaryOperatorInfo(BinaryOperator.BITWISE_AND, 7)
        TokenKind.CARET -> BinaryOperatorInfo(BinaryOperator.BITWISE_XOR, 6)
        TokenKind.PIPE -> BinaryOperatorInfo(BinaryOperator.BITWISE_OR, 5)
        TokenKind.AND_AND -> BinaryOperatorInfo(BinaryOperator.LOGICAL_AND, 4)
        TokenKind.OR_OR -> BinaryOperatorInfo(BinaryOperator.LOGICAL_OR, 3)
        else -> null
    }

    private fun current(): Token = tokens.getOrElse(index) { tokens.last() }

    private fun previous(): Token = tokens[(index - 1).coerceAtLeast(0)]

    private fun take(): Token {
        val token = current()
        if (!at(TokenKind.EOF)) index++
        return token
    }

    private fun match(kind: TokenKind): Token? = if (at(kind)) take() else null

    private fun expect(kind: TokenKind, description: String): Token {
        if (at(kind)) return take()
        error(current(), "$description expected")
        return current()
    }

    private fun at(kind: TokenKind): Boolean = current().kind == kind

    private fun error(token: Token, message: String) = diagnostics.error(token.span.start, message)

    private data class BinaryOperatorInfo(val value: BinaryOperator, val precedence: Int)
}

private fun SourceSpan.merge(other: SourceSpan): SourceSpan = SourceSpan(start, other.end)
