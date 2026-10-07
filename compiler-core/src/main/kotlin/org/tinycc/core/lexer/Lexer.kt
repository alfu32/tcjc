package org.tinycc.core.lexer

import java.math.BigInteger
import java.nio.file.Path
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.LineMap
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.diagnostics.SourceSpan

data class LexerOptions(
    val dollarsInIdentifiers: Boolean = true,
    val binaryLiterals: Boolean = true,
)

/** Lexes one C translation unit while retaining exact source spans. */
class Lexer(
    private val source: String,
    private val path: Path? = null,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val options: LexerOptions = LexerOptions(),
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
        if (isIdentifierStart(character)) return identifier(start)
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
            if (!isIdentifierPart(character)) break
            index++
        }
        val text = source.substring(start, index)
        return token(keywordKinds[text] ?: TokenKind.IDENTIFIER, start, index)
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
            val value = numberPart.toDoubleOrNull()
            if (value == null && !numberPart.contains('p', true)) error(start, "invalid floating constant '$raw'")
            return token(TokenKind.FLOAT_LITERAL, start, index, LiteralValue.Floating(raw, suffix.singleOrNull(), value))
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
        return token(
            TokenKind.INTEGER_LITERAL,
            start,
            index,
            LiteralValue.Integer(value, base, unsigned, longRank),
        )
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
            return token(TokenKind.CHARACTER_LITERAL, start, index, LiteralValue.Character(value, wide))
        }
        return token(TokenKind.STRING_LITERAL, start, index, LiteralValue.StringValue(decoded, wide))
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
                'x' -> appendCodePoint(readDigits(body, cursor, 2, 16, start).also { cursor = digitCursor })
                'u' -> appendCodePoint(readDigits(body, cursor, 4, 16, start, exact = true).also { cursor = digitCursor })
                'U' -> appendCodePoint(readDigits(body, cursor, 8, 16, start, exact = true).also { cursor = digitCursor })
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
            value = value * radix + digit
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

    private fun peek(distance: Int): Char? = source.getOrNull(index + distance)

    private fun token(kind: TokenKind, start: Int, end: Int, literal: LiteralValue? = null): Token =
        Token(kind, source.substring(start, end), span(start, end), literal)

    private fun span(start: Int, end: Int): SourceSpan = SourceSpan(location(start), location(end))

    private fun location(offset: Int): SourceLocation = lineMap.locationAt(path, offset)

    private fun error(offset: Int, message: String) = diagnostics.error(location(offset), message)

    private data class Operator(val text: String, val kind: TokenKind)

    private companion object {
        val operators = listOf(
            Operator("<<=", TokenKind.LEFT_SHIFT_ASSIGN), Operator(">>=", TokenKind.RIGHT_SHIFT_ASSIGN),
            Operator("...", TokenKind.ELLIPSIS), Operator("->", TokenKind.ARROW), Operator("++", TokenKind.PLUS_PLUS),
            Operator("--", TokenKind.MINUS_MINUS), Operator("<<", TokenKind.LEFT_SHIFT), Operator(">>", TokenKind.RIGHT_SHIFT),
            Operator("<=", TokenKind.LESS_EQUAL), Operator(">=", TokenKind.GREATER_EQUAL), Operator("==", TokenKind.EQUAL_EQUAL),
            Operator("!=", TokenKind.BANG_EQUAL), Operator("&&", TokenKind.AND_AND), Operator("||", TokenKind.OR_OR),
            Operator("+=", TokenKind.PLUS_ASSIGN), Operator("-=", TokenKind.MINUS_ASSIGN), Operator("*=", TokenKind.STAR_ASSIGN),
            Operator("/=", TokenKind.SLASH_ASSIGN), Operator("%=", TokenKind.PERCENT_ASSIGN), Operator("&=", TokenKind.AMPERSAND_ASSIGN),
            Operator("|=", TokenKind.PIPE_ASSIGN), Operator("^=", TokenKind.CARET_ASSIGN), Operator("##", TokenKind.HASH_HASH),
            Operator("(", TokenKind.LEFT_PAREN), Operator(")", TokenKind.RIGHT_PAREN), Operator("[", TokenKind.LEFT_BRACKET),
            Operator("]", TokenKind.RIGHT_BRACKET), Operator("{", TokenKind.LEFT_BRACE), Operator("}", TokenKind.RIGHT_BRACE),
            Operator(",", TokenKind.COMMA), Operator(";", TokenKind.SEMICOLON), Operator(":", TokenKind.COLON),
            Operator("?", TokenKind.QUESTION), Operator("#", TokenKind.HASH), Operator(".", TokenKind.DOT),
            Operator("+", TokenKind.PLUS), Operator("-", TokenKind.MINUS), Operator("*", TokenKind.STAR),
            Operator("/", TokenKind.SLASH), Operator("%", TokenKind.PERCENT), Operator("&", TokenKind.AMPERSAND),
            Operator("|", TokenKind.PIPE), Operator("^", TokenKind.CARET), Operator("~", TokenKind.TILDE),
            Operator("!", TokenKind.BANG), Operator("=", TokenKind.ASSIGN), Operator("<", TokenKind.LESS), Operator(">", TokenKind.GREATER),
        )

        val keywordKinds = mapOf(
            "if" to TokenKind.IF, "else" to TokenKind.ELSE, "while" to TokenKind.WHILE, "for" to TokenKind.FOR,
            "do" to TokenKind.DO, "continue" to TokenKind.CONTINUE, "break" to TokenKind.BREAK, "return" to TokenKind.RETURN,
            "goto" to TokenKind.GOTO, "switch" to TokenKind.SWITCH, "case" to TokenKind.CASE, "default" to TokenKind.DEFAULT,
            "asm" to TokenKind.ASM, "__asm" to TokenKind.ASM, "__asm__" to TokenKind.ASM, "extern" to TokenKind.EXTERN,
            "static" to TokenKind.STATIC, "unsigned" to TokenKind.UNSIGNED, "_Atomic" to TokenKind.ATOMIC, "const" to TokenKind.CONST,
            "__const" to TokenKind.CONST, "__const__" to TokenKind.CONST, "volatile" to TokenKind.VOLATILE,
            "__volatile" to TokenKind.VOLATILE, "__volatile__" to TokenKind.VOLATILE, "register" to TokenKind.REGISTER,
            "signed" to TokenKind.SIGNED, "__signed" to TokenKind.SIGNED, "__signed__" to TokenKind.SIGNED,
            "auto" to TokenKind.AUTO, "inline" to TokenKind.INLINE, "__inline" to TokenKind.INLINE, "__inline__" to TokenKind.INLINE,
            "restrict" to TokenKind.RESTRICT, "__restrict" to TokenKind.RESTRICT, "__restrict__" to TokenKind.RESTRICT,
            "__extension__" to TokenKind.EXTENSION, "_Thread_local" to TokenKind.THREAD_LOCAL, "__thread" to TokenKind.THREAD_LOCAL,
            "_Generic" to TokenKind.GENERIC, "_Static_assert" to TokenKind.STATIC_ASSERT, "void" to TokenKind.VOID,
            "char" to TokenKind.CHAR, "int" to TokenKind.INT, "float" to TokenKind.FLOAT, "double" to TokenKind.DOUBLE,
            "_Bool" to TokenKind.BOOL, "_Complex" to TokenKind.COMPLEX, "short" to TokenKind.SHORT, "long" to TokenKind.LONG,
            "struct" to TokenKind.STRUCT, "union" to TokenKind.UNION, "typedef" to TokenKind.TYPEDEF, "enum" to TokenKind.ENUM,
            "sizeof" to TokenKind.SIZEOF, "__attribute" to TokenKind.ATTRIBUTE, "__attribute__" to TokenKind.ATTRIBUTE,
            "__alignof" to TokenKind.ALIGNOF, "__alignof__" to TokenKind.ALIGNOF, "_Alignof" to TokenKind.ALIGNOF,
            "_Alignas" to TokenKind.ALIGNAS, "typeof" to TokenKind.TYPEOF, "__typeof" to TokenKind.TYPEOF,
            "__typeof__" to TokenKind.TYPEOF, "__label__" to TokenKind.LABEL,
            "define" to TokenKind.DEFINE, "include" to TokenKind.INCLUDE, "include_next" to TokenKind.INCLUDE_NEXT,
            "ifdef" to TokenKind.IFDEF, "ifndef" to TokenKind.IFNDEF, "elif" to TokenKind.ELIF, "endif" to TokenKind.ENDIF,
            "defined" to TokenKind.DEFINED, "undef" to TokenKind.UNDEF, "error" to TokenKind.ERROR, "warning" to TokenKind.WARNING,
            "line" to TokenKind.LINE, "pragma" to TokenKind.PRAGMA, "__func__" to TokenKind.FUNC, "__nan__" to TokenKind.NAN,
            "__snan__" to TokenKind.SNAN, "__inf__" to TokenKind.INF, "pack" to TokenKind.PACK, "comment" to TokenKind.COMMENT,
            "lib" to TokenKind.LIB, "push_macro" to TokenKind.PUSH_MACRO, "pop_macro" to TokenKind.POP_MACRO,
            "once" to TokenKind.ONCE, "option" to TokenKind.OPTION,
        )
    }
}
