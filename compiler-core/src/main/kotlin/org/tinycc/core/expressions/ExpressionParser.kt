package org.tinycc.core.expressions

import java.math.BigInteger
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.lexer.LiteralValue
import org.tinycc.core.lexer.Token
import org.tinycc.core.lexer.TokenKind
import org.tinycc.core.statements.StatementParser
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.TypeQualifiers

private typealias TypeTransform = (CType) -> CType

/** Recursive-descent expression parser with C operator precedence. */
class ExpressionParser(
    private val tokens: List<Token>,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val typeNames: Map<String, CType> = emptyMap(),
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
                values += parseDesignatedOrInitializer()
            } while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_BRACE))
        }
        expect(TokenKind.RIGHT_BRACE, "'}'")
        return Initializer.ListValue(values)
    }

    private fun parseDesignatedOrInitializer(): Initializer {
        val designator = when {
            match(TokenKind.DOT) != null -> {
                val field = expect(TokenKind.IDENTIFIER, "designated field")
                Designator.Field(field.lexeme)
            }
            match(TokenKind.LEFT_BRACKET) != null -> {
                val index = parseExpression()
                expect(TokenKind.RIGHT_BRACKET, "']'")
                Designator.Index(index)
            }
            else -> null
        }
        return if (designator == null) parseInitializer() else {
            expect(TokenKind.ASSIGN, "'='")
            Initializer.Designated(designator, parseInitializer())
        }
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
        val whenTrue = if (at(TokenKind.COLON)) condition else parseExpression()
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
        if (match(TokenKind.AND_AND) != null) {
            val label = expect(TokenKind.IDENTIFIER, "label")
            return Expression.LabelAddress(label.lexeme, token.span.merge(label.span))
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
                match(TokenKind.LEFT_PAREN) != null -> parseCall(expression)
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
                match(TokenKind.PLUS_PLUS) != null ->
                    Expression.Unary(UnaryOperator.POST_INCREMENT, expression, expression.span.merge(previous().span))
                match(TokenKind.MINUS_MINUS) != null ->
                    Expression.Unary(UnaryOperator.POST_DECREMENT, expression, expression.span.merge(previous().span))
                else -> break
            }
        }
        return expression
    }

    private fun parseCall(callee: Expression): Expression {
        val typeArguments = callee is Expression.Name && callee.identifier in setOf(
            "__builtin_types_compatible_p",
            "__builtin_va_arg",
            "__builtin_offsetof",
        )
        val arguments = ArrayList<Expression>()
        if (!at(TokenKind.RIGHT_PAREN)) {
            do {
                if (typeArguments && (callee as Expression.Name).identifier == "__builtin_offsetof" && arguments.isEmpty()) {
                    val start = current()
                    arguments += Expression.TypeOperand(parseTypeName(), start.span.merge(previous().span))
                } else if (typeArguments && (callee as Expression.Name).identifier == "__builtin_offsetof" && arguments.size == 1) {
                    val field = expect(TokenKind.IDENTIFIER, "field name")
                    arguments += Expression.Name(field.lexeme, field.span)
                } else if (typeArguments && (callee as Expression.Name).identifier == "__builtin_va_arg" && arguments.isNotEmpty()) {
                    val start = current()
                    arguments += Expression.TypeOperand(parseTypeName(), start.span.merge(previous().span))
                } else if (typeArguments && (callee as Expression.Name).identifier == "__builtin_types_compatible_p") {
                    val start = current()
                    arguments += Expression.TypeOperand(parseTypeName(), start.span.merge(previous().span))
                } else {
                    arguments += parseAssignment()
                }
            } while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_PAREN))
        }
        val close = expect(TokenKind.RIGHT_PAREN, "')'")
        return Expression.Call(callee, arguments, callee.span.merge(close.span))
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
                var text = value.value
                var wide = value.wide
                var end = token.span
                while (at(TokenKind.STRING_LITERAL)) {
                    val adjacent = take()
                    val next = adjacent.literal as LiteralValue.StringValue
                    text += next.value
                    wide = wide || next.wide
                    end = end.merge(adjacent.span)
                }
                Expression.StringLiteral(text, wide, end)
            }
            TokenKind.LEFT_PAREN -> parseParenthesizedOrCast(token)
            TokenKind.ALIGNOF -> parseAlignOf(token)
            TokenKind.TYPEOF -> parseTypeOf(token)
            TokenKind.GENERIC -> parseGenericSelection(token)
            TokenKind.NAN -> Expression.Floating("NAN", token.span)
            TokenKind.SNAN -> Expression.Floating("SNAN", token.span)
            TokenKind.INF -> Expression.Floating("INF", token.span)
            else -> {
                error(token, "expression expected")
                Expression.Invalid(token.span)
            }
        }
    }

    private fun parseParenthesizedOrCast(open: Token): Expression {
        if (match(TokenKind.LEFT_BRACE) != null) {
            val bodyStart = index - 1
            val closeIndex = findClosingBrace(bodyStart)
            val bodyTokens = ArrayList<Token>(tokens.subList(bodyStart, closeIndex + 1))
            bodyTokens += tokens.last()
            val body = StatementParser(bodyTokens, diagnostics).parse()
            index = closeIndex + 1
            val close = expect(TokenKind.RIGHT_PAREN, "')'")
            return Expression.StatementExpression(body, open.span.merge(close.span))
        }
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
        error(tokens.last(), "unterminated statement expression")
        return tokens.lastIndex
    }

    private fun parseAlignOf(keyword: Token): Expression {
        val open = expect(TokenKind.LEFT_PAREN, "'('")
        val operand = parseTypeOrExpressionOperand()
        val close = expect(TokenKind.RIGHT_PAREN, "')'")
        return Expression.AlignOf(operand, keyword.span.merge(close.span))
    }

    private fun parseTypeOf(keyword: Token): Expression {
        val open = expect(TokenKind.LEFT_PAREN, "'('")
        val operand = parseTypeOrExpressionOperand()
        val close = expect(TokenKind.RIGHT_PAREN, "')'")
        return Expression.TypeOf(operand, keyword.span.merge(close.span))
    }

    private fun parseTypeOrExpressionOperand(): SizeOperand = if (isTypeStart(current().kind)) {
        SizeOperand.Type(parseTypeName())
    } else {
        SizeOperand.Expression(parseExpression())
    }

    private fun parseGenericSelection(keyword: Token): Expression {
        expect(TokenKind.LEFT_PAREN, "'('")
        val controlling = parseAssignment()
        expect(TokenKind.COMMA, "','")
        val associations = ArrayList<GenericAssociation>()
        var hasDefault = false
        do {
            val type = if (match(TokenKind.DEFAULT) != null) {
                if (hasDefault) error(previous(), "duplicate default association in _Generic")
                hasDefault = true
                null
            } else {
                if (!isTypeStart(current().kind)) error(current(), "type name expected in _Generic association")
                parseTypeName()
            }
            expect(TokenKind.COLON, "':'")
            associations += GenericAssociation(type, parseAssignment())
        } while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_PAREN))
        val close = expect(TokenKind.RIGHT_PAREN, "')'")
        return Expression.GenericSelection(controlling, associations, keyword.span.merge(close.span))
    }

    private fun parseInitializerAfterOpenBrace(): Initializer {
        val values = ArrayList<Initializer>()
        if (!at(TokenKind.RIGHT_BRACE)) {
            do values += parseDesignatedOrInitializer() while (match(TokenKind.COMMA) != null && !at(TokenKind.RIGHT_BRACE))
        }
        expect(TokenKind.RIGHT_BRACE, "'}'")
        return Initializer.ListValue(values)
    }

    private fun parseTypeName(): CType {
        val specifiers = parseTypeSpecifiers()
        val base = specifiers.base
        val qualifiers = specifiers.qualifiers
        val qualifiedBase = if (qualifiers == TypeQualifiers()) base else CTypes.qualified(base, qualifiers)
        return parseAbstractDeclarator()(qualifiedBase)
    }

    private fun parseTypeSpecifiers(): ParsedTypeSpecifiers {
        var qualifiers = TypeQualifiers()
        var signed = false
        var unsigned = false
        var short = false
        var longCount = 0
        var complex = false
        var scalar: TokenKind? = null
        var invalid = false
        fun duplicate(token: Token, description: String) {
            error(token, "duplicate $description type specifier")
            invalid = true
        }
        while (true) {
            when (current().kind) {
                TokenKind.CONST -> {
                    take()
                    qualifiers = qualifiers.copy(isConst = true)
                }
                TokenKind.VOLATILE -> {
                    take()
                    qualifiers = qualifiers.copy(isVolatile = true)
                }
                TokenKind.RESTRICT -> {
                    take()
                    qualifiers = qualifiers.copy(isRestrict = true)
                }
                TokenKind.ATOMIC -> {
                    take()
                    if (match(TokenKind.LEFT_PAREN) != null) {
                        val atomicType = parseTypeName()
                        expect(TokenKind.RIGHT_PAREN, "')'")
                        return ParsedTypeSpecifiers(atomicType, qualifiers.copy(isAtomic = true))
                    }
                    qualifiers = qualifiers.copy(isAtomic = true)
                }
                TokenKind.SIGNED -> {
                    val token = take()
                    if (signed) duplicate(token, "signed")
                    signed = true
                }
                TokenKind.UNSIGNED -> {
                    val token = take()
                    if (unsigned) duplicate(token, "unsigned")
                    unsigned = true
                }
                TokenKind.SHORT -> {
                    val token = take()
                    if (short) duplicate(token, "short")
                    short = true
                }
                TokenKind.LONG -> {
                    val token = take()
                    longCount++
                    if (longCount > 2) duplicate(token, "long")
                }
                TokenKind.COMPLEX -> {
                    val token = take()
                    if (complex) duplicate(token, "complex")
                    complex = true
                }
                TokenKind.VOID, TokenKind.CHAR, TokenKind.BOOL, TokenKind.INT,
                TokenKind.FLOAT, TokenKind.DOUBLE,
                -> {
                    val token = take()
                    if (scalar != null) duplicate(token, token.lexeme)
                    scalar = token.kind
                }
                TokenKind.STRUCT -> {
                    take()
                    val type = parseTaggedType("struct", org.tinycc.core.types.RecordKind.STRUCT)
                    return ParsedTypeSpecifiers(type, qualifiers)
                }
                TokenKind.UNION -> {
                    take()
                    val type = parseTaggedType("union", org.tinycc.core.types.RecordKind.UNION)
                    return ParsedTypeSpecifiers(type, qualifiers)
                }
                TokenKind.ENUM -> {
                    take()
                    val tag = expect(TokenKind.IDENTIFIER, "enum tag")
                    val type = typeNames["enum ${tag.lexeme}"] ?: typeNames[tag.lexeme]
                        ?: org.tinycc.core.types.CType.Enumeration(tag.lexeme)
                    return ParsedTypeSpecifiers(type, qualifiers)
                }
                TokenKind.TYPEOF -> {
                    val keyword = take()
                    expect(TokenKind.LEFT_PAREN, "'('")
                    val operand = parseTypeOrExpressionOperand()
                    expect(TokenKind.RIGHT_PAREN, "')'")
                    val type = (operand as? SizeOperand.Type)?.value ?: run {
                        error(keyword, "typeof expression type is unavailable during parsing")
                        CType.Error
                    }
                    return ParsedTypeSpecifiers(type, qualifiers)
                }
                TokenKind.IDENTIFIER -> {
                    val token = take()
                    val type = typeNames[token.lexeme] ?: run {
                        error(token, "unknown type name '${token.lexeme}'")
                        CType.Error
                    }
                    return ParsedTypeSpecifiers(type, qualifiers)
                }
                else -> break
            }
        }
        val invalidCombination = signed && unsigned || short && longCount > 0 || longCount > 2 ||
            when (scalar) {
                TokenKind.VOID, TokenKind.BOOL -> signed || unsigned || short || longCount > 0 || complex
                TokenKind.CHAR -> short || longCount > 0 || complex
                TokenKind.FLOAT -> signed || unsigned || short || longCount > 0
                TokenKind.DOUBLE -> signed || unsigned || short || longCount > 1
                else -> false
            } || complex && (signed || unsigned || short || scalar !in setOf(null, TokenKind.FLOAT, TokenKind.DOUBLE))
        if (invalidCombination) {
            error(previous(), "invalid combination of C type specifiers")
            invalid = true
        }
        if (invalid) return ParsedTypeSpecifiers(CType.Error, qualifiers)
        val base = when {
            complex -> when {
                scalar == TokenKind.FLOAT -> CTypes.floatComplex
                longCount > 0 -> CTypes.longDoubleComplex
                else -> CTypes.doubleComplex
            }
            scalar == TokenKind.VOID -> CTypes.void
            scalar == TokenKind.BOOL -> CTypes.bool
            scalar == TokenKind.CHAR && unsigned -> CTypes.unsignedChar
            scalar == TokenKind.CHAR && signed -> CTypes.signedChar
            scalar == TokenKind.CHAR -> CTypes.char
            scalar == TokenKind.FLOAT -> CTypes.float
            scalar == TokenKind.DOUBLE && longCount > 0 -> CTypes.longDouble
            scalar == TokenKind.DOUBLE -> CTypes.double
            short && unsigned -> CType.Primitive(PrimitiveKind.UNSIGNED_SHORT)
            short -> CType.Primitive(PrimitiveKind.SHORT)
            longCount >= 2 && unsigned -> CTypes.unsignedLongLong
            longCount >= 2 -> CTypes.longLong
            longCount == 1 && unsigned -> CTypes.unsignedLong
            longCount == 1 -> CTypes.long
            unsigned -> CTypes.unsignedInt
            else -> CTypes.int
        }
        return ParsedTypeSpecifiers(base, qualifiers)
    }

    private fun parseTaggedType(prefix: String, kind: org.tinycc.core.types.RecordKind): CType {
        val tag = expect(TokenKind.IDENTIFIER, "$prefix tag")
        return typeNames["$prefix ${tag.lexeme}"] ?: typeNames[tag.lexeme]
            ?: org.tinycc.core.types.CType.Record(kind, tag.lexeme)
    }

    /** Parses C's abstract-declarator grammar, retaining pointer/function/array binding. */
    private fun parseAbstractDeclarator(): TypeTransform {
        val pointerQualifiers = ArrayList<TypeQualifiers>()
        while (match(TokenKind.STAR) != null) pointerQualifiers += parseQualifiers()
        val pointerTransform: TypeTransform = { base ->
            pointerQualifiers.fold(base) { current, qualifiers -> CTypes.pointer(current, qualifiers) }
        }

        val grouped = match(TokenKind.LEFT_PAREN) != null
        val directTransform = if (grouped) {
            val nested = if (at(TokenKind.RIGHT_PAREN)) ({ type: CType -> type }) else parseAbstractDeclarator()
            expect(TokenKind.RIGHT_PAREN, "')'")
            nested
        } else {
            { type: CType -> type }
        }

        var transform: TypeTransform = if (grouped) directTransform else pointerTransform
        while (true) {
            transform = when {
                match(TokenKind.LEFT_BRACKET) != null -> {
                    val bound = parseArrayBound()
                    val suffix: TypeTransform = { type -> CType.Array(type, bound) }
                    if (grouped) compose(transform, suffix) else compose(suffix, transform)
                }
                match(TokenKind.LEFT_PAREN) != null -> {
                    val parameters = parseFunctionParameters()
                    val suffix: TypeTransform = { type ->
                        CType.Function(type, parameters.first, parameters.second, parameters.third)
                    }
                    if (grouped) compose(transform, suffix) else compose(suffix, transform)
                }
                else -> break
            }
        }
        return if (grouped) compose(pointerTransform, transform) else transform
    }

    private fun parseArrayBound(): ArrayBound {
        if (match(TokenKind.RIGHT_BRACKET) != null) return ArrayBound.Unspecified
        val bound = if (current().literal is LiteralValue.Integer) {
            ArrayBound.Constant((take().literal as LiteralValue.Integer).value.longValueExact())
        } else {
            ArrayBound.Variable(parseExpression().toString())
        }
        expect(TokenKind.RIGHT_BRACKET, "']'")
        return bound
    }

    private fun parseFunctionParameters(): Triple<List<CType.Parameter>, Boolean, Boolean> {
        if (match(TokenKind.RIGHT_PAREN) != null) return Triple(emptyList(), false, true)
        if (at(TokenKind.VOID) && peek(1).kind == TokenKind.RIGHT_PAREN) {
            take()
            expect(TokenKind.RIGHT_PAREN, "')'")
            return Triple(emptyList(), false, false)
        }
        val parameters = ArrayList<CType.Parameter>()
        var variadic = false
        while (!at(TokenKind.RIGHT_PAREN) && !at(TokenKind.EOF)) {
            if (match(TokenKind.ELLIPSIS) != null) {
                variadic = true
                expect(TokenKind.RIGHT_PAREN, "')'")
                break
            }
            val type = parseTypeName()
            val name = if (at(TokenKind.IDENTIFIER)) take().lexeme else null
            parameters += CType.Parameter(name, type)
            if (match(TokenKind.COMMA) == null) {
                expect(TokenKind.RIGHT_PAREN, "')'")
                break
            }
        }
        return Triple(parameters, variadic, false)
    }

    private fun compose(outer: TypeTransform, inner: TypeTransform): TypeTransform = { base -> outer(inner(base)) }

    private fun parseQualifiers(): TypeQualifiers {
        var qualifiers = TypeQualifiers()
        var parsing = true
        while (parsing) {
            qualifiers = when {
                match(TokenKind.CONST) != null -> qualifiers.copy(isConst = true)
                match(TokenKind.VOLATILE) != null -> qualifiers.copy(isVolatile = true)
                match(TokenKind.RESTRICT) != null -> qualifiers.copy(isRestrict = true)
                match(TokenKind.ATOMIC) != null -> qualifiers.copy(isAtomic = true)
                else -> {
                    parsing = false
                    qualifiers
                }
            }
        }
        return qualifiers
    }

    private fun isTypeStart(kind: TokenKind): Boolean = kind in setOf(
        TokenKind.VOID, TokenKind.CHAR, TokenKind.BOOL, TokenKind.INT, TokenKind.FLOAT, TokenKind.DOUBLE,
        TokenKind.SHORT, TokenKind.LONG, TokenKind.SIGNED, TokenKind.UNSIGNED, TokenKind.COMPLEX, TokenKind.CONST,
        TokenKind.VOLATILE, TokenKind.RESTRICT, TokenKind.ATOMIC, TokenKind.STRUCT, TokenKind.UNION, TokenKind.ENUM,
        TokenKind.TYPEOF,
    ) || current().kind == TokenKind.IDENTIFIER && current().lexeme in typeNames

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
        error(current(), "$description expected")
        return current()
    }

    private fun at(kind: TokenKind): Boolean = current().kind == kind

    private fun error(token: Token, message: String) = diagnostics.error(token.span.start, message)

    private data class BinaryOperatorInfo(val value: BinaryOperator, val precedence: Int)

    private data class ParsedTypeSpecifiers(val base: CType, val qualifiers: TypeQualifiers)
}

private fun SourceSpan.merge(other: SourceSpan): SourceSpan = SourceSpan(start, other.end)
