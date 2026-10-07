package org.tinycc.core.statements

import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.expressions.Expression
import org.tinycc.core.expressions.ExpressionParser
import org.tinycc.core.lexer.Token
import org.tinycc.core.lexer.TokenKind
import org.tinycc.core.lexer.LiteralValue
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.ObjectDeclaration
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.TypeQualifiers

/** Parses C statements while delegating expressions to the precedence parser. */
class StatementParser(
    private val tokens: List<Token>,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
) {
    private var index = 0

    fun parse(): Statement {
        val statements = ArrayList<Statement>()
        while (!at(TokenKind.EOF)) statements += parseStatement()
        return if (statements.size == 1) statements.single() else Statement.Compound(statements, spanOf(0, previousIndex()))
    }

    private fun parseStatement(): Statement = when {
        at(TokenKind.LEFT_BRACE) -> parseCompound()
        match(TokenKind.SEMICOLON) != null -> Statement.Empty(previous().span)
        at(TokenKind.IF) -> parseIf()
        at(TokenKind.WHILE) -> parseWhile()
        at(TokenKind.DO) -> parseDoWhile()
        at(TokenKind.FOR) -> parseFor()
        at(TokenKind.SWITCH) -> parseSwitch()
        at(TokenKind.CASE) -> parseCase()
        at(TokenKind.DEFAULT) -> parseDefault()
        at(TokenKind.BREAK) -> parseSimpleJump { Statement.Break(it) }
        at(TokenKind.CONTINUE) -> parseSimpleJump { Statement.Continue(it) }
        at(TokenKind.RETURN) -> parseReturn()
        at(TokenKind.GOTO) -> parseGoto()
        at(TokenKind.ASM) -> parseInlineAssembly()
        at(TokenKind.IDENTIFIER) && peek(1).kind == TokenKind.COLON -> parseLabel()
        isDeclarationStart(current().kind) -> parseDeclarationStatement()
        else -> parseExpressionStatement()
    }

    private fun parseCompound(): Statement.Compound {
        val open = expect(TokenKind.LEFT_BRACE, "'{'")
        val statements = ArrayList<Statement>()
        while (!at(TokenKind.RIGHT_BRACE) && !at(TokenKind.EOF)) statements += parseStatement()
        val close = expect(TokenKind.RIGHT_BRACE, "'}'")
        return Statement.Compound(statements, open.span.merge(close.span))
    }

    private fun parseIf(): Statement {
        val keyword = expect(TokenKind.IF, "'if'")
        val condition = parseParenthesizedExpression()
        val thenBranch = parseStatement()
        val elseBranch = if (match(TokenKind.ELSE) != null) parseStatement() else null
        return Statement.If(condition, thenBranch, elseBranch, keyword.span.merge((elseBranch ?: thenBranch).span))
    }

    private fun parseWhile(): Statement {
        val keyword = expect(TokenKind.WHILE, "'while'")
        val condition = parseParenthesizedExpression()
        val body = parseStatement()
        return Statement.While(condition, body, keyword.span.merge(body.span))
    }

    private fun parseDoWhile(): Statement {
        val keyword = expect(TokenKind.DO, "'do'")
        val body = parseStatement()
        expect(TokenKind.WHILE, "'while'")
        val condition = parseParenthesizedExpression()
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        return Statement.DoWhile(body, condition, keyword.span.merge(semicolon.span))
    }

    private fun parseFor(): Statement {
        val keyword = expect(TokenKind.FOR, "'for'")
        expect(TokenKind.LEFT_PAREN, "'('")
        val initializerStart = index
        val initializerEnd = findDelimiter(TokenKind.SEMICOLON)
        val initializer = if (initializerStart == initializerEnd) null else parseHeaderStatement(initializerStart, initializerEnd)
        index = initializerEnd + 1
        val conditionStart = index
        val conditionEnd = findDelimiter(TokenKind.SEMICOLON)
        val condition = if (conditionStart == conditionEnd) null else parseExpressionRange(conditionStart, conditionEnd)
        index = conditionEnd + 1
        val updateStart = index
        val close = findDelimiter(TokenKind.RIGHT_PAREN)
        val update = if (updateStart == close) null else parseExpressionRange(updateStart, close)
        index = close + 1
        val body = parseStatement()
        return Statement.For(initializer, condition, update, body, keyword.span.merge(body.span))
    }

    private fun parseSwitch(): Statement {
        val keyword = expect(TokenKind.SWITCH, "'switch'")
        val condition = parseParenthesizedExpression()
        val body = parseStatement()
        return Statement.Switch(condition, body, keyword.span.merge(body.span))
    }

    private fun parseCase(): Statement {
        val keyword = expect(TokenKind.CASE, "'case'")
        val value = parseExpressionUntil(TokenKind.COLON)
        expect(TokenKind.COLON, "':'")
        val statements = parseCaseBody()
        return Statement.Case(value, statements, keyword.span.merge((statements.lastOrNull()?.span ?: value.span)))
    }

    private fun parseDefault(): Statement {
        val keyword = expect(TokenKind.DEFAULT, "'default'")
        expect(TokenKind.COLON, "':'")
        val statements = parseCaseBody()
        return Statement.Default(statements, keyword.span.merge(statements.lastOrNull()?.span ?: keyword.span))
    }

    private fun parseCaseBody(): List<Statement> {
        val statements = ArrayList<Statement>()
        while (!at(TokenKind.CASE) && !at(TokenKind.DEFAULT) && !at(TokenKind.RIGHT_BRACE) && !at(TokenKind.EOF)) {
            statements += parseStatement()
        }
        return statements
    }

    private fun parseSimpleJump(factory: (SourceSpan) -> Statement): Statement {
        val keyword = take()
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        return factory(keyword.span.merge(semicolon.span))
    }

    private fun parseReturn(): Statement {
        val keyword = expect(TokenKind.RETURN, "'return'")
        val expression = if (at(TokenKind.SEMICOLON)) null else parseExpressionUntil(TokenKind.SEMICOLON)
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        return Statement.Return(expression, keyword.span.merge(semicolon.span))
    }

    private fun parseGoto(): Statement {
        val keyword = expect(TokenKind.GOTO, "'goto'")
        val label = expect(TokenKind.IDENTIFIER, "label")
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        return Statement.Goto(label.lexeme, keyword.span.merge(semicolon.span))
    }

    private fun parseInlineAssembly(): Statement.InlineAssembly {
        val keyword = expect(TokenKind.ASM, "'asm'")
        val isVolatile = match(TokenKind.VOLATILE) != null
        expect(TokenKind.LEFT_PAREN, "'('")
        val templateToken = expect(TokenKind.STRING_LITERAL, "assembly template")
        val template = (templateToken.literal as? LiteralValue.StringValue)?.value ?: templateToken.lexeme
        val sections = arrayOf(ArrayList<AsmOperand>(), ArrayList<AsmOperand>())
        val clobbers = ArrayList<String>()
        var section = 0
        while (!at(TokenKind.RIGHT_PAREN) && !at(TokenKind.EOF)) {
            if (match(TokenKind.COLON) != null) {
                section++
                if (section > 3) diagnostics.error(current().span.start, "too many inline assembly sections")
                continue
            }
            if (section >= 3) {
                val clobber = expect(TokenKind.STRING_LITERAL, "clobber string")
                clobbers += (clobber.literal as? LiteralValue.StringValue)?.value ?: clobber.lexeme
            } else {
                val operand = parseAsmOperand()
                sections[(section - 1).coerceIn(0, 1)] += operand
            }
            if (match(TokenKind.COMMA) == null && !at(TokenKind.RIGHT_PAREN) && !at(TokenKind.COLON)) {
                diagnostics.error(current().span.start, "',' or ')' expected in inline assembly")
                break
            }
        }
        val close = expect(TokenKind.RIGHT_PAREN, "')'")
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        return Statement.InlineAssembly(template, sections[0], sections[1], clobbers, isVolatile, keyword.span.merge(semicolon.span))
    }

    private fun parseAsmOperand(): AsmOperand {
        if (match(TokenKind.LEFT_BRACKET) != null) {
            expect(TokenKind.IDENTIFIER, "operand name")
            expect(TokenKind.RIGHT_BRACKET, "']'")
        }
        val constraintToken = expect(TokenKind.STRING_LITERAL, "operand constraint")
        val constraint = (constraintToken.literal as? LiteralValue.StringValue)?.value ?: constraintToken.lexeme
        val expression = if (match(TokenKind.LEFT_PAREN) != null) {
            val start = index
            val end = findDelimiter(TokenKind.RIGHT_PAREN)
            val parsed = parseExpressionRange(start, end)
            index = (end + 1).coerceAtMost(tokens.lastIndex)
            parsed
        } else null
        return AsmOperand(constraint, expression)
    }

    private fun parseLabel(): Statement {
        val label = expect(TokenKind.IDENTIFIER, "label")
        expect(TokenKind.COLON, "':'")
        val statement = parseStatement()
        return Statement.Label(label.lexeme, statement, label.span.merge(statement.span))
    }

    private fun parseDeclarationStatement(): Statement {
        val start = index
        val end = findDelimiter(TokenKind.SEMICOLON)
        val declaration = parseDeclaration(start, end)
        index = end + 1
        return Statement.DeclarationStatement(declaration, spanOf(start, index - 1))
    }

    private fun parseHeaderStatement(start: Int, end: Int): Statement {
        return if (isDeclarationStart(tokens[start].kind)) {
            Statement.DeclarationStatement(parseDeclaration(start, end), spanOf(start, end - 1))
        } else Statement.ExpressionStatement(parseExpressionRange(start, end), spanOf(start, end - 1))
    }

    private fun parseDeclaration(start: Int, end: Int): ObjectDeclaration {
        index = start
        var qualifiers = TypeQualifiers()
        if (match(TokenKind.CONST) != null) qualifiers = qualifiers.copy(isConst = true)
        val base = when (take().kind) {
            TokenKind.VOID -> CTypes.void
            TokenKind.CHAR -> CTypes.char
            TokenKind.FLOAT -> CTypes.float
            TokenKind.DOUBLE -> CTypes.double
            TokenKind.SHORT -> CType.Primitive(PrimitiveKind.SHORT)
            TokenKind.LONG -> CTypes.long
            TokenKind.UNSIGNED -> CType.Primitive(PrimitiveKind.UNSIGNED_INT)
            TokenKind.BOOL -> CTypes.bool
            else -> CTypes.int
        }
        var type: CType = if (qualifiers == TypeQualifiers()) base else CTypes.qualified(base, qualifiers)
        while (match(TokenKind.STAR) != null) type = CTypes.pointer(type)
        val name = expect(TokenKind.IDENTIFIER, "declarator name")
        while (match(TokenKind.LEFT_BRACKET) != null) {
            val boundStart = index
            val boundEnd = findDelimiter(TokenKind.RIGHT_BRACKET)
            val bound = when {
                boundStart == boundEnd -> org.tinycc.core.types.ArrayBound.Unspecified
                boundEnd - boundStart == 1 && tokens[boundStart].literal is org.tinycc.core.lexer.LiteralValue.Integer -> {
                    val literal = tokens[boundStart].literal as org.tinycc.core.lexer.LiteralValue.Integer
                    org.tinycc.core.types.ArrayBound.Constant(literal.value.longValueExact())
                }
                else -> org.tinycc.core.types.ArrayBound.Variable(
                    tokens.subList(boundStart, boundEnd).joinToString(" ") { it.lexeme },
                )
            }
            type = CType.Array(type, bound)
            index = (boundEnd + 1).coerceAtMost(end)
        }
        val initializer = if (match(TokenKind.ASSIGN) != null) {
            val expressionStart = index
            parseExpressionRange(expressionStart, end).also { index = end }
        } else null
        return ObjectDeclaration(name.lexeme, type, initializer = initializer?.toString())
    }

    private fun parseExpressionStatement(): Statement {
        val start = index
        val expression = parseExpressionUntil(TokenKind.SEMICOLON)
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        return Statement.ExpressionStatement(expression, spanOf(start, index - 1).merge(semicolon.span))
    }

    private fun parseParenthesizedExpression(): Expression {
        expect(TokenKind.LEFT_PAREN, "'('")
        val start = index
        val close = findDelimiter(TokenKind.RIGHT_PAREN)
        val expression = parseExpressionRange(start, close)
        index = close + 1
        return expression
    }

    private fun parseExpressionUntil(delimiter: TokenKind): Expression {
        val start = index
        val end = findDelimiter(delimiter)
        val expression = parseExpressionRange(start, end)
        index = end
        return expression
    }

    private fun parseExpressionRange(start: Int, end: Int): Expression {
        if (start >= end) {
            diagnostics.error(current().span.start, "expression expected")
            return Expression.Invalid(current().span)
        }
        val expressionTokens = ArrayList<Token>(tokens.subList(start, end))
        expressionTokens += tokens.last()
        return ExpressionParser(expressionTokens, diagnostics).parse()
    }

    private fun findDelimiter(delimiter: TokenKind): Int {
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var cursor = index
        while (cursor < tokens.size) {
            when (tokens[cursor].kind) {
                TokenKind.LEFT_PAREN -> parentheses++
                TokenKind.RIGHT_PAREN -> if (parentheses > 0) parentheses-- else if (delimiter == TokenKind.RIGHT_PAREN) return cursor
                TokenKind.LEFT_BRACKET -> brackets++
                TokenKind.RIGHT_BRACKET -> if (brackets > 0) brackets--
                TokenKind.LEFT_BRACE -> braces++
                TokenKind.RIGHT_BRACE -> if (braces > 0) braces--
                else -> Unit
            }
            if (tokens[cursor].kind == delimiter && parentheses == 0 && brackets == 0 && braces == 0) return cursor
            cursor++
        }
        diagnostics.error(tokens.last().span.start, "expected '${delimiter.name.lowercase()}'")
        return tokens.lastIndex
    }

    private fun isDeclarationStart(kind: TokenKind): Boolean = kind in setOf(
        TokenKind.VOID, TokenKind.CHAR, TokenKind.BOOL, TokenKind.INT, TokenKind.FLOAT, TokenKind.DOUBLE,
        TokenKind.SHORT, TokenKind.LONG, TokenKind.SIGNED, TokenKind.UNSIGNED, TokenKind.CONST,
    )

    private fun current(): Token = tokens.getOrElse(index) { tokens.last() }
    private fun peek(distance: Int): Token = tokens.getOrElse(index + distance) { tokens.last() }
    private fun previous(): Token = tokens[(index - 1).coerceAtLeast(0)]
    private fun previousIndex(): Int = (index - 1).coerceAtLeast(0)

    private fun take(): Token {
        val token = current()
        if (!at(TokenKind.EOF)) index++
        return token
    }

    private fun match(kind: TokenKind): Token? = if (at(kind)) take() else null

    private fun expect(kind: TokenKind, description: String): Token {
        if (at(kind)) return take()
        diagnostics.error(current().span.start, "$description expected")
        return current()
    }

    private fun at(kind: TokenKind): Boolean = current().kind == kind

    private fun spanOf(start: Int, end: Int): SourceSpan = tokens[start].span.merge(tokens[end.coerceAtLeast(start)].span)
}

private fun SourceSpan.merge(other: SourceSpan): SourceSpan = SourceSpan(start, other.end)
