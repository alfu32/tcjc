package org.tinycc.core.lexer

import java.math.BigInteger
import java.nio.file.Path
import kotlin.math.pow
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.LineMap
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.diagnostics.SourceSpan

data class LexerOptions(
    val dollarsInIdentifiers: Boolean = true,
    val binaryLiterals: Boolean = true,
    val tokenTarget: TccTokenIds.TargetProfile = TccTokenIds.TargetProfile.X86_64_LINUX,
    val boundsCheckTokens: Boolean = false,
)

/** Lexes one C translation unit while retaining exact source spans. */
class Lexer(
    private val source: String,
    private val path: Path? = null,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val options: LexerOptions = LexerOptions(),
    private val identifierAllocator: TccTokenIds.IdentifierAllocator =
        TccTokenIds.IdentifierAllocator(options.tokenTarget, options.boundsCheckTokens),
) {
    private val lineMap = LineMap(source)
    private var index = 0

    constructor(
        sourceFile: org.tinycc.core.io.SourceFile,
        diagnostics: DiagnosticEngine = DiagnosticEngine(),
        options: LexerOptions = LexerOptions(),
    ) : this(sourceFile.text, sourceFile.path, diagnostics, options)

    fun nextToken(): Token {
        skipTrivia()
        val start = index
        if (index >= source.length) return token(TokenKind.EOF, start, start)

        val character = source[index]
        if (character == 'L' || character == 'u' || character == 'U') {
            val prefixLength = literalPrefixLength()
            val next = peek(prefixLength)
            if (next == '\'' || next == '"') return quotedLiteral(start, prefixLength)
        }
        if (isIdentifierStart(character) || isUniversalEscapeStart(index)) return identifier(start)
        if (character.isDigit() || (character == '.' && peek(1)?.isDigit() == true)) return number(start)

        if (character == '\'' || character == '"') return quotedLiteral(start, 0)

        val operator = operators.firstOrNull { source.startsWith(it.text, index) }
        if (operator != null) {
            index += operator.text.length
            return token(operator.kind, start, index)
        }

        index++
        error(start, "unrecognized character \\x%02x".format(character.code))
        return token(TokenKind.INVALID, start, index)
    }

    fun tokenize(): List<Token> = buildList {
        do {
            val next = nextToken()
            add(next)
        } while (next.kind != TokenKind.EOF)
    }

    private fun identifier(start: Int): Token {
        while (index < source.length) {
            val character = source[index]
            when {
                isIdentifierPart(character) -> index++
                isUniversalEscapeStart(index) -> consumeUniversalEscape()
                else -> break
            }
        }
        val text = source.substring(start, index)
        val keyword = TccTokenIds.keyword(text, options.tokenTarget, options.boundsCheckTokens)
        val tokenId = keyword?.tccId ?: identifierAllocator.id(text)
        return token(keyword?.kind ?: TokenKind.IDENTIFIER, start, index, tccId = tokenId)
    }

    private fun number(start: Int): Token {
        var previous = '\u0000'
        while (index < source.length) {
            val character = source[index]
            val allowed = character.isLetterOrDigit() || character == '.' ||
                ((character == '+' || character == '-') && (previous == 'e' || previous == 'E' || previous == 'p' || previous == 'P'))
            if (!allowed) break
            previous = character
            index++
        }
        val raw = source.substring(start, index)
        val suffixMatch = Regex("(?i)(ull|llu|ul|lu|ll|u|l|f)$").find(raw)
        val suffix = suffixMatch?.value ?: ""
        val numberPart = raw.removeSuffix(suffix)
        val isFloating = numberPart.any { it == '.' || it == 'e' || it == 'E' || it == 'p' || it == 'P' } ||
            suffix.equals("f", true)
        if (index < source.length && isIdentifierPart(source[index])) {
            while (index < source.length && isIdentifierPart(source[index])) index++
            error(start, "invalid number '${source.substring(start, index)}'")
            return token(TokenKind.INVALID, start, index)
        }
        if (isFloating) {
            val value = parseFloating(numberPart)
            if (value == null && !numberPart.contains('p', true)) error(start, "invalid floating constant '$raw'")
            val literal = LiteralValue.Floating(raw, suffix.singleOrNull(), value)
            return token(TokenKind.FLOAT_LITERAL, start, index, literal, TccTokenIds.literalId(literal))
        }

        val (base, digits) = when {
            numberPart.startsWith("0x", true) -> 16 to numberPart.substring(2)
            numberPart.startsWith("0b", true) -> 2 to numberPart.substring(2)
            numberPart.length > 1 && numberPart.startsWith('0') -> 8 to numberPart.substring(1)
            else -> 10 to numberPart
        }
        val value = runCatching { BigInteger(if (digits.isEmpty()) "0" else digits, base) }.getOrNull()
        if (value == null || (base == 2 && !options.binaryLiterals)) {
            error(start, "invalid number '$raw'")
            return token(TokenKind.INVALID, start, index)
        }
        val unsigned = suffix.contains('u', true)
        val longRank = when {
            suffix.contains("ll", true) -> 2
            suffix.contains('l', true) -> 1
            else -> 0
        }
        val literal = LiteralValue.Integer(value, base, unsigned, longRank)
        return token(TokenKind.INTEGER_LITERAL, start, index, literal, TccTokenIds.literalId(literal))
    }

    private fun quotedLiteral(start: Int, prefixLength: Int): Token {
        val prefix = source.substring(start, start + prefixLength)
        val quote = source[start + prefixLength]
        index = start + prefixLength + 1
        val bodyStart = index
        var closed = false
        while (index < source.length) {
            when (source[index]) {
                '\\' -> {
                    index++
                    if (index < source.length) {
                        if (source[index] == '\r' && peek(1) == '\n') index++
                        index++
                    }
                }
                quote -> {
                    closed = true
                    break
                }
                '\n', '\r' -> {
                    error(start, "missing terminating $quote character")
                    break
                }
                else -> index++
            }
        }
        if (closed) index++
        else if (index >= source.length) error(start, "missing terminating $quote character")
        val bodyEnd = if (closed) index - 1 else index
        val body = source.substring(bodyStart, bodyEnd)
        val wide = prefix.isNotEmpty()
        val decoded = decodeEscapes(body, start)
        if (quote == '\'') {
            val codePoints = decoded.codePoints().toArray()
            if (codePoints.isEmpty()) error(start, "empty character constant")
            if (codePoints.size > 1) diagnostics.warning(location(start), "multi-character character constant")
            val value = if (wide) codePoints.firstOrNull() ?: 0 else codePoints.fold(0) { acc, value -> (acc shl 8) or (value and 0xff) }
            val literal = LiteralValue.Character(value, wide)
            return token(TokenKind.CHARACTER_LITERAL, start, index, literal, TccTokenIds.literalId(literal))
        }
        val literal = LiteralValue.StringValue(
            decoded,
            wide,
            prefix,
            decodeStringUnits(body, prefix == "L", start),
        )
        return token(TokenKind.STRING_LITERAL, start, index, literal, TccTokenIds.literalId(literal))
    }

    private fun decodeStringUnits(body: String, wide: Boolean, start: Int): List<Int> = buildList {
        var cursor = 0
        fun appendValue(value: Int, universal: Boolean = false) {
            if (wide) {
                add(value)
            } else if (universal) {
                value.takeIf { it in 0..Character.MAX_CODE_POINT && it !in 0xD800..0xDFFF }
                    ?.let { String(Character.toChars(it)) }
                    ?.encodeToByteArray()
                    ?.forEach { add(it.toInt() and 0xff) }
                    ?: addAll("\uFFFD".encodeToByteArray().map { it.toInt() and 0xff })
            } else {
                add(value and 0xff)
            }
        }
        while (cursor < body.length) {
            val character = body[cursor++]
            if (character != '\\') {
                val codePoint = body.codePointAt(cursor - 1)
                if (codePoint > Char.MAX_VALUE.code) cursor++
                if (wide) add(codePoint) else {
                    String(Character.toChars(codePoint)).encodeToByteArray().forEach { add(it.toInt() and 0xff) }
                }
                continue
            }
            if (cursor >= body.length) break
            when (val escaped = body[cursor++]) {
                'a' -> appendValue(7)
                'b' -> appendValue(8)
                'f' -> appendValue(12)
                'n' -> appendValue(10)
                'r' -> appendValue(13)
                't' -> appendValue(9)
                'v' -> appendValue(11)
                '\\', '\'', '"', '?' -> appendValue(escaped.code)
                '\n' -> Unit
                '\r' -> if (cursor < body.length && body[cursor] == '\n') cursor++
                'x', 'u', 'U' -> {
                    val universal = escaped != 'x'
                    val digits = if (escaped == 'u') 4 else if (escaped == 'U') 8 else Int.MAX_VALUE
                    val number = readDigits(body, cursor, digits, 16, start, exact = universal)
                    cursor = digitCursor
                    appendValue(number, universal)
                }
                in '0'..'7' -> {
                    var number = escaped - '0'
                    var count = 1
                    while (count < 3 && cursor < body.length && body[cursor] in '0'..'7') {
                        number = number * 8 + body[cursor++].digitToInt()
                        count++
                    }
                    appendValue(number)
                }
                'e' -> appendValue(27)
                else -> {
                    diagnostics.warning(location(start), "unknown escape sequence \\$escaped")
                    appendValue(escaped.code)
                }
            }
        }
    }

    private fun decodeEscapes(body: String, start: Int): String = buildString {
        var cursor = 0
        while (cursor < body.length) {
            val character = body[cursor++]
            if (character != '\\') {
                append(character)
                continue
            }
            if (cursor == body.length) {
                error(start, "stray '\\' in literal")
                break
            }
            when (val escaped = body[cursor++]) {
                'a' -> append('\u0007')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'v' -> append('\u000b')
                '\\', '\'', '"', '?' -> append(escaped)
                '\n' -> Unit
                '\r' -> if (cursor < body.length && body[cursor] == '\n') cursor++
                'x' -> appendCodePoint(readDigits(body, cursor, Int.MAX_VALUE, 16, start).also { cursor = digitCursor })
                'u' -> appendCodePoint(readDigits(body, cursor, 4, 16, start, exact = true).also { cursor = digitCursor })
                'U' -> appendCodePoint(readDigits(body, cursor, 8, 16, start, exact = true).also { cursor = digitCursor })
                'e' -> append('\u001B')
                in '0'..'7' -> {
                    var value = escaped - '0'
                    var count = 1
                    while (count < 3 && cursor < body.length && body[cursor] in '0'..'7') {
                        value = value * 8 + (body[cursor++] - '0')
                        count++
                    }
                    appendCodePoint(value)
                }
                else -> {
                    diagnostics.warning(location(start), "unknown escape sequence \\$escaped")
                    append(escaped)
                }
            }
        }
    }

    private var digitCursor = 0

    private fun readDigits(
        text: String,
        start: Int,
        maximum: Int,
        radix: Int,
        location: Int,
        exact: Boolean = false,
    ): Int {
        var cursor = start
        var value = 0
        var count = 0
        while (cursor < text.length && count < maximum) {
            val digit = text[cursor].digitToIntOrNull(radix) ?: break
            if (value > (Int.MAX_VALUE - digit) / radix) {
                error(location, "escape sequence is too large")
            } else {
                value = value * radix + digit
            }
            cursor++
            count++
        }
        if (count == 0 || (exact && count != maximum)) error(location, "invalid escape sequence")
        digitCursor = cursor
        return value
    }

    private fun appendCodePoint(value: Int): String =
        if (value in 0..Character.MAX_CODE_POINT) String(Character.toChars(value)) else {
            error(index, "escape sequence is not a Unicode code point")
            "\uFFFD"
        }

    private fun skipTrivia() {
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index++
                source[index] == '\\' && peek(1) == '\n' -> index += 2
                source[index] == '\\' && peek(1) == '\r' -> {
                    index += if (peek(2) == '\n') 3 else 2
                }
                source.startsWith("//", index) -> {
                    index += 2
                    while (index < source.length && source[index] != '\n') index++
                }
                source.startsWith("/*", index) -> {
                    val start = index
                    index += 2
                    val end = source.indexOf("*/", index)
                    if (end < 0) {
                        index = source.length
                        error(start, "unexpected end of file in comment")
                    } else index = end + 2
                }
                else -> return
            }
        }
    }

    private fun literalPrefixLength(): Int = when {
        source.startsWith("u8", index) -> 2
        source[index] == 'L' || source[index] == 'u' || source[index] == 'U' -> 1
        else -> 0
    }

    private fun isIdentifierStart(character: Char): Boolean =
        character == '_' || character == '$' && options.dollarsInIdentifiers || character.isLetter() || character.code >= 0x80

    private fun isIdentifierPart(character: Char): Boolean = isIdentifierStart(character) || character.isDigit()

    private fun isUniversalEscapeStart(offset: Int): Boolean =
        source.getOrNull(offset) == '\\' && source.getOrNull(offset + 1) in setOf('u', 'U')

    private fun consumeUniversalEscape() {
        val start = index
        val width = if (source[index + 1] == 'u') 4 else 8
        index += 2
        val digitStart = index
        repeat(width) {
            if (source.getOrNull(index)?.digitToIntOrNull(16) == null) {
                error(start, "invalid universal character name")
                return@repeat
            }
            index++
        }
        if (index - digitStart != width) {
            while (source.getOrNull(index)?.digitToIntOrNull(16) != null) index++
        }
        val value = source.substring(digitStart, minOf(index, digitStart + width)).toLongOrNull(16) ?: -1
        if (value !in 0L..Character.MAX_CODE_POINT.toLong() || value in 0xD800L..0xDFFFL) {
            error(start, "universal character name is not a Unicode scalar value")
        }
    }

    private fun peek(distance: Int): Char? = source.getOrNull(index + distance)

    private fun token(
        kind: TokenKind,
        start: Int,
        end: Int,
        literal: LiteralValue? = null,
        tccId: Int? = null,
    ): Token = Token(kind, source.substring(start, end), span(start, end), literal, tccId ?: literal?.let(TccTokenIds::literalId) ?: kind.tccId)

    private fun span(start: Int, end: Int): SourceSpan = SourceSpan(location(start), location(end))

    private fun location(offset: Int): SourceLocation = lineMap.locationAt(path, offset)

    private fun error(offset: Int, message: String) = diagnostics.error(location(offset), message)

    private fun parseFloating(number: String): Double? {
        if (!number.startsWith("0x", ignoreCase = true)) return number.toDoubleOrNull()
        val exponentMarker = number.indexOfFirst { it == 'p' || it == 'P' }
        if (exponentMarker < 0) return null
        val mantissa = number.substring(2, exponentMarker)
        val exponent = number.substring(exponentMarker + 1).toIntOrNull() ?: return null
        val point = mantissa.indexOf('.')
        val whole = if (point < 0) mantissa else mantissa.removeRange(point, point + 1)
        if (whole.isEmpty() || whole.any { it.digitToIntOrNull(16) == null }) return null
        val fractionalDigits = if (point < 0) 0 else mantissa.length - point - 1
        val significand = BigInteger(whole, 16).toDouble() / 16.0.pow(fractionalDigits)
        return Math.scalb(significand, exponent)
    }

    private data class Operator(val text: String, val kind: TokenKind)

    private companion object {
        val operators = listOf(
            Operator("<<=", TokenKind.LEFT_SHIFT_ASSIGN), Operator(">>=", TokenKind.RIGHT_SHIFT_ASSIGN),
            Operator("%:%:", TokenKind.HASH_HASH),
            Operator("...", TokenKind.ELLIPSIS), Operator("->", TokenKind.ARROW), Operator("++", TokenKind.PLUS_PLUS),
            Operator("--", TokenKind.MINUS_MINUS), Operator("<<", TokenKind.LEFT_SHIFT), Operator(">>", TokenKind.RIGHT_SHIFT),
            Operator("<=", TokenKind.LESS_EQUAL), Operator(">=", TokenKind.GREATER_EQUAL), Operator("==", TokenKind.EQUAL_EQUAL),
            Operator("!=", TokenKind.BANG_EQUAL), Operator("&&", TokenKind.AND_AND), Operator("||", TokenKind.OR_OR),
            Operator("+=", TokenKind.PLUS_ASSIGN), Operator("-=", TokenKind.MINUS_ASSIGN), Operator("*=", TokenKind.STAR_ASSIGN),
            Operator("/=", TokenKind.SLASH_ASSIGN), Operator("%=", TokenKind.PERCENT_ASSIGN), Operator("&=", TokenKind.AMPERSAND_ASSIGN),
            Operator("|=", TokenKind.PIPE_ASSIGN), Operator("^=", TokenKind.CARET_ASSIGN), Operator("##", TokenKind.HASH_HASH),
            Operator("<:", TokenKind.LEFT_BRACKET), Operator(":>", TokenKind.RIGHT_BRACKET),
            Operator("<%", TokenKind.LEFT_BRACE), Operator("%>", TokenKind.RIGHT_BRACE), Operator("%:", TokenKind.HASH),
            Operator("(", TokenKind.LEFT_PAREN), Operator(")", TokenKind.RIGHT_PAREN), Operator("[", TokenKind.LEFT_BRACKET),
            Operator("]", TokenKind.RIGHT_BRACKET), Operator("{", TokenKind.LEFT_BRACE), Operator("}", TokenKind.RIGHT_BRACE),
            Operator(",", TokenKind.COMMA), Operator(";", TokenKind.SEMICOLON), Operator(":", TokenKind.COLON),
            Operator("?", TokenKind.QUESTION), Operator("#", TokenKind.HASH), Operator(".", TokenKind.DOT),
            Operator("+", TokenKind.PLUS), Operator("-", TokenKind.MINUS), Operator("*", TokenKind.STAR),
            Operator("/", TokenKind.SLASH), Operator("%", TokenKind.PERCENT), Operator("&", TokenKind.AMPERSAND),
            Operator("|", TokenKind.PIPE), Operator("^", TokenKind.CARET), Operator("~", TokenKind.TILDE),
            Operator("!", TokenKind.BANG), Operator("=", TokenKind.ASSIGN), Operator("<", TokenKind.LESS), Operator(">", TokenKind.GREATER),
        )
    }
}
