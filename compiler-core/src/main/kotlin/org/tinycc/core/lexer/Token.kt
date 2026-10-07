package org.tinycc.core.lexer

import java.math.BigInteger
import org.tinycc.core.diagnostics.SourceSpan

/** Stable token categories shared by the lexer, parser, and preprocessor. */
enum class TokenKind {
    EOF,
    INVALID,
    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    CHARACTER_LITERAL,
    STRING_LITERAL,

    IF, ELSE, WHILE, FOR, DO, CONTINUE, BREAK, RETURN, GOTO, SWITCH, CASE, DEFAULT,
    ASM, EXTERN, STATIC, UNSIGNED, ATOMIC, CONST, VOLATILE, REGISTER, SIGNED, AUTO,
    INLINE, RESTRICT, EXTENSION, THREAD_LOCAL, GENERIC, STATIC_ASSERT,
    VOID, CHAR, INT, FLOAT, DOUBLE, BOOL, COMPLEX, SHORT, LONG, STRUCT, UNION,
    TYPEDEF, ENUM, SIZEOF, ATTRIBUTE, ALIGNOF, ALIGNAS, TYPEOF, LABEL,
    DEFINE, INCLUDE, INCLUDE_NEXT, IFDEF, IFNDEF, ELIF, ENDIF, DEFINED, UNDEF,
    ERROR, WARNING, LINE, PRAGMA,
    FUNC, NAN, SNAN, INF, PACK, COMMENT, LIB, PUSH_MACRO, POP_MACRO, ONCE, OPTION,

    HASH, HASH_HASH,
    LEFT_PAREN, RIGHT_PAREN, LEFT_BRACKET, RIGHT_BRACKET, LEFT_BRACE, RIGHT_BRACE,
    COMMA, SEMICOLON, COLON, QUESTION, DOT, ELLIPSIS,
    PLUS, MINUS, STAR, SLASH, PERCENT, AMPERSAND, PIPE, CARET, TILDE, BANG,
    ASSIGN, PLUS_PLUS, MINUS_MINUS, ARROW,
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,
    AMPERSAND_ASSIGN, PIPE_ASSIGN, CARET_ASSIGN, LEFT_SHIFT_ASSIGN, RIGHT_SHIFT_ASSIGN,
    EQUAL_EQUAL, BANG_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    LEFT_SHIFT, RIGHT_SHIFT, AND_AND, OR_OR,
}

sealed interface LiteralValue {
    data class Integer(
        val value: BigInteger,
        val base: Int,
        val unsigned: Boolean,
        val longRank: Int,
    ) : LiteralValue

    data class Floating(val raw: String, val suffix: Char?, val value: Double?) : LiteralValue

    data class Character(val value: Int, val wide: Boolean) : LiteralValue

    data class StringValue(val value: String, val wide: Boolean) : LiteralValue
}

data class Token(
    val kind: TokenKind,
    val lexeme: String,
    val span: SourceSpan,
    val literal: LiteralValue? = null,
)
