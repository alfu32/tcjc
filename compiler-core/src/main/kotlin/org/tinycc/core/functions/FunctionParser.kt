package org.tinycc.core.functions

import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.lexer.Token
import org.tinycc.core.lexer.TokenKind
import org.tinycc.core.statements.Statement
import org.tinycc.core.statements.StatementParser
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.DeclarationAttributes
import org.tinycc.core.types.FunctionDeclaration
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.StorageClass
import org.tinycc.core.types.TypeQualifiers

/** Parses top-level C function prototypes and definitions. */
class FunctionParser(
    private val tokens: List<Token>,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
) {
    private var index = 0

    fun parse(): List<ParsedFunction> {
        val functions = ArrayList<ParsedFunction>()
        while (!at(TokenKind.EOF)) functions += parseFunction()
        return functions
    }

    private fun parseFunction(): ParsedFunction {
        val start = current()
        var storage = StorageClass.AUTO
        var inline = false
        if (match(TokenKind.STATIC) != null) storage = StorageClass.STATIC
        if (match(TokenKind.EXTERN) != null) storage = StorageClass.EXTERN
        if (match(TokenKind.INLINE) != null) inline = true
        val returnType = parseType()
        val name = expect(TokenKind.IDENTIFIER, "function name")
        expect(TokenKind.LEFT_PAREN, "'('")
        val parameters = parseParameters()
        expect(TokenKind.RIGHT_PAREN, "')'")
        val functionType = CType.Function(
            returnType,
            parameters.map { CType.Parameter(it.name, it.type) },
            variadic = parameters.any { it.name == null && it.type === CType.Error },
        )
        val attributes = DeclarationAttributes(storage = storage, isInline = inline)
        if (match(TokenKind.LEFT_BRACE) != null) {
            val bodyStart = index - 1
            val close = findClosingBrace(bodyStart)
            val bodyTokens = ArrayList<Token>(tokens.subList(bodyStart, close + 1))
            bodyTokens += tokens.last()
            val body = StatementParser(bodyTokens, diagnostics).parse()
            index = close + 1
            val compound = body as? Statement.Compound
            if (compound == null) diagnostics.error(start.span.start, "function body must be a compound statement")
            val declaration = FunctionDeclaration(name.lexeme, functionType, attributes, isDefinition = true)
            return ParsedFunction(declaration, parameters, compound, start.span.merge((compound ?: body).span))
        }
        val semicolon = expect(TokenKind.SEMICOLON, "';'")
        val declaration = FunctionDeclaration(name.lexeme, functionType, attributes, isDefinition = false)
        return ParsedFunction(declaration, parameters, null, start.span.merge(semicolon.span))
    }

    private fun parseParameters(): List<ParameterDeclaration> {
        if (at(TokenKind.RIGHT_PAREN)) return emptyList()
        if (at(TokenKind.VOID) && peek(1).kind == TokenKind.RIGHT_PAREN) {
            take()
            return emptyList()
        }
        val parameters = ArrayList<ParameterDeclaration>()
        while (!at(TokenKind.RIGHT_PAREN) && !at(TokenKind.EOF)) {
            if (match(TokenKind.ELLIPSIS) != null) {
                parameters += ParameterDeclaration(null, CType.Error, previous().span)
                break
            }
            val start = current()
            val type = parseType()
            val name = if (at(TokenKind.IDENTIFIER)) take().lexeme else null
            parameters += ParameterDeclaration(name, type, start.span.merge(previous().span))
            if (match(TokenKind.COMMA) == null) break
        }
        return parameters
    }

    private fun parseType(): CType {
        var qualifiers = TypeQualifiers()
        if (match(TokenKind.CONST) != null) qualifiers = qualifiers.copy(isConst = true)
        val base = when (take().kind) {
            TokenKind.VOID -> CTypes.void
            TokenKind.CHAR -> CTypes.char
            TokenKind.BOOL -> CTypes.bool
            TokenKind.FLOAT -> CTypes.float
            TokenKind.DOUBLE -> CTypes.double
            TokenKind.SHORT -> CType.Primitive(PrimitiveKind.SHORT)
            TokenKind.LONG -> CTypes.long
            TokenKind.UNSIGNED -> when {
                match(TokenKind.SHORT) != null -> CType.Primitive(PrimitiveKind.UNSIGNED_SHORT)
                match(TokenKind.LONG) != null -> CType.Primitive(PrimitiveKind.UNSIGNED_LONG)
                else -> CTypes.unsignedInt
            }
            TokenKind.SIGNED -> CTypes.int
            TokenKind.INT -> CTypes.int
            else -> CType.Error
        }
        var type: CType = if (qualifiers == TypeQualifiers()) base else CTypes.qualified(base, qualifiers)
        while (match(TokenKind.STAR) != null) type = CTypes.pointer(type)
        return type
    }

    private fun findClosingBrace(open: Int): Int {
        var depth = 0
        for (cursor in open until tokens.size) {
            when (tokens[cursor].kind) {
                TokenKind.LEFT_BRACE -> depth++
                TokenKind.RIGHT_BRACE -> {
                    depth--
                    if (depth == 0) return cursor
                }
                else -> Unit
            }
        }
        diagnostics.error(tokens.last().span.start, "unterminated function body")
        return tokens.lastIndex
    }

    private fun current(): Token = tokens.getOrElse(index) { tokens.last() }
    private fun peek(distance: Int): Token = tokens.getOrElse(index + distance) { tokens.last() }
    private fun previous(): Token = tokens[(index - 1).coerceAtLeast(0)]
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
}

private fun SourceSpan.merge(other: SourceSpan): SourceSpan = SourceSpan(start, other.end)
