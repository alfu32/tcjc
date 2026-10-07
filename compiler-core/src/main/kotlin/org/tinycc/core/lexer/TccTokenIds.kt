package org.tinycc.core.lexer

/**
 * Token values used by the historical TinyCC front end. Punctuation retains
 * its C character value where TinyCC does so; compound operators, literals,
 * and identifiers use the private ranges from tcc.h.
 */
object TccTokenIds {
    const val EOF = -1
    const val INVALID = -2
    const val IDENTIFIER = 256

    const val DECREMENT = 0x80
    const val INCREMENT = 0x82
    const val UNSIGNED_DIVIDE = 0x83
    const val UNSIGNED_REMAINDER = 0x84
    const val POINTER_DIVIDE = 0x85
    const val UNSIGNED_MULTIPLY_LONG = 0x86
    const val ARROW = 0xA0
    const val ELLIPSIS = 0xA1
    const val DOUBLE_DOT = 0xA2
    const val HASH_HASH = 0xA3

    const val LOGICAL_AND = 0x90
    const val LOGICAL_OR = 0x91
    const val UNSIGNED_LESS = 0x92
    const val UNSIGNED_GREATER_EQUAL = 0x93
    const val EQUAL = 0x94
    const val NOT_EQUAL = 0x95
    const val UNSIGNED_LESS_EQUAL = 0x96
    const val UNSIGNED_GREATER = 0x97
    const val LESS_THAN = 0x9C
    const val GREATER_EQUAL = 0x9D
    const val LESS_EQUAL = 0x9E
    const val GREATER_THAN = 0x9F

    const val ASSIGN_ADD = 0xB0
    const val ASSIGN_SUBTRACT = 0xB1
    const val ASSIGN_MULTIPLY = 0xB2
    const val ASSIGN_DIVIDE = 0xB3
    const val ASSIGN_REMAINDER = 0xB4
    const val ASSIGN_AND = 0xB5
    const val ASSIGN_OR = 0xB6
    const val ASSIGN_XOR = 0xB7
    const val ASSIGN_SHIFT_LEFT = 0xB8
    const val ASSIGN_SHIFT_RIGHT = 0xB9

    const val CHARACTER = 0xC0
    const val WIDE_CHARACTER = 0xC1
    const val INTEGER = 0xC2
    const val UNSIGNED_INTEGER = 0xC3
    const val LONG_LONG = 0xC4
    const val UNSIGNED_LONG_LONG = 0xC5
    const val LONG = 0xC6
    const val UNSIGNED_LONG = 0xC7
    const val STRING = 0xC8
    const val WIDE_STRING = 0xC9
    const val FLOAT = 0xCA
    const val DOUBLE = 0xCB
    const val LONG_DOUBLE = 0xCC
    const val PREPROCESSOR_NUMBER = 0xCD
    const val PREPROCESSOR_STRING = 0xCE
    const val LINE_NUMBER = 0xCF

    enum class TargetProfile { I386, I386_PE, X86_64_LINUX, X86_64_PE }

    data class KeywordSpec(val kind: TokenKind, val tccId: Int)

    /** Base, target-independent keyword IDs from tcctok.h. */
    private val keywordSpecs = linkedMapOf(
        "if" to KeywordSpec(TokenKind.IF, 256),
        "else" to KeywordSpec(TokenKind.ELSE, 257),
        "while" to KeywordSpec(TokenKind.WHILE, 258),
        "for" to KeywordSpec(TokenKind.FOR, 259),
        "do" to KeywordSpec(TokenKind.DO, 260),
        "continue" to KeywordSpec(TokenKind.CONTINUE, 261),
        "break" to KeywordSpec(TokenKind.BREAK, 262),
        "return" to KeywordSpec(TokenKind.RETURN, 263),
        "goto" to KeywordSpec(TokenKind.GOTO, 264),
        "switch" to KeywordSpec(TokenKind.SWITCH, 265),
        "case" to KeywordSpec(TokenKind.CASE, 266),
        "default" to KeywordSpec(TokenKind.DEFAULT, 267),
        "asm" to KeywordSpec(TokenKind.ASM, 268),
        "__asm" to KeywordSpec(TokenKind.ASM, 269),
        "__asm__" to KeywordSpec(TokenKind.ASM, 270),
        "extern" to KeywordSpec(TokenKind.EXTERN, 271),
        "static" to KeywordSpec(TokenKind.STATIC, 272),
        "unsigned" to KeywordSpec(TokenKind.UNSIGNED, 273),
        "_Atomic" to KeywordSpec(TokenKind.ATOMIC, 274),
        "const" to KeywordSpec(TokenKind.CONST, 275),
        "__const" to KeywordSpec(TokenKind.CONST, 276),
        "__const__" to KeywordSpec(TokenKind.CONST, 277),
        "volatile" to KeywordSpec(TokenKind.VOLATILE, 278),
        "__volatile" to KeywordSpec(TokenKind.VOLATILE, 279),
        "__volatile__" to KeywordSpec(TokenKind.VOLATILE, 280),
        "register" to KeywordSpec(TokenKind.REGISTER, 281),
        "signed" to KeywordSpec(TokenKind.SIGNED, 282),
        "__signed" to KeywordSpec(TokenKind.SIGNED, 283),
        "__signed__" to KeywordSpec(TokenKind.SIGNED, 284),
        "auto" to KeywordSpec(TokenKind.AUTO, 285),
        "inline" to KeywordSpec(TokenKind.INLINE, 286),
        "__inline" to KeywordSpec(TokenKind.INLINE, 287),
        "__inline__" to KeywordSpec(TokenKind.INLINE, 288),
        "restrict" to KeywordSpec(TokenKind.RESTRICT, 289),
        "__restrict" to KeywordSpec(TokenKind.RESTRICT, 290),
        "__restrict__" to KeywordSpec(TokenKind.RESTRICT, 291),
        "__extension__" to KeywordSpec(TokenKind.EXTENSION, 292),
        "_Thread_local" to KeywordSpec(TokenKind.THREAD_LOCAL, 293),
        "__thread" to KeywordSpec(TokenKind.THREAD_LOCAL, 294),
        "_Generic" to KeywordSpec(TokenKind.GENERIC, 295),
        "_Static_assert" to KeywordSpec(TokenKind.STATIC_ASSERT, 296),
        "void" to KeywordSpec(TokenKind.VOID, 297),
        "char" to KeywordSpec(TokenKind.CHAR, 298),
        "int" to KeywordSpec(TokenKind.INT, 299),
        "float" to KeywordSpec(TokenKind.FLOAT, 300),
        "double" to KeywordSpec(TokenKind.DOUBLE, 301),
        "_Bool" to KeywordSpec(TokenKind.BOOL, 302),
        "_Complex" to KeywordSpec(TokenKind.COMPLEX, 303),
        "short" to KeywordSpec(TokenKind.SHORT, 304),
        "long" to KeywordSpec(TokenKind.LONG, 305),
        "struct" to KeywordSpec(TokenKind.STRUCT, 306),
        "union" to KeywordSpec(TokenKind.UNION, 307),
        "typedef" to KeywordSpec(TokenKind.TYPEDEF, 308),
        "enum" to KeywordSpec(TokenKind.ENUM, 309),
        "sizeof" to KeywordSpec(TokenKind.SIZEOF, 310),
        "__attribute" to KeywordSpec(TokenKind.ATTRIBUTE, 311),
        "__attribute__" to KeywordSpec(TokenKind.ATTRIBUTE, 312),
        "__alignof" to KeywordSpec(TokenKind.ALIGNOF, 313),
        "__alignof__" to KeywordSpec(TokenKind.ALIGNOF, 314),
        "_Alignof" to KeywordSpec(TokenKind.ALIGNOF, 315),
        "_Alignas" to KeywordSpec(TokenKind.ALIGNAS, 316),
        "typeof" to KeywordSpec(TokenKind.TYPEOF, 317),
        "__typeof" to KeywordSpec(TokenKind.TYPEOF, 318),
        "__typeof__" to KeywordSpec(TokenKind.TYPEOF, 319),
        "__label__" to KeywordSpec(TokenKind.LABEL, 320),
        "define" to KeywordSpec(TokenKind.DEFINE, 321),
        "include" to KeywordSpec(TokenKind.INCLUDE, 322),
        "include_next" to KeywordSpec(TokenKind.INCLUDE_NEXT, 323),
        "ifdef" to KeywordSpec(TokenKind.IFDEF, 324),
        "ifndef" to KeywordSpec(TokenKind.IFNDEF, 325),
        "elif" to KeywordSpec(TokenKind.ELIF, 326),
        "endif" to KeywordSpec(TokenKind.ENDIF, 327),
        "defined" to KeywordSpec(TokenKind.DEFINED, 328),
        "undef" to KeywordSpec(TokenKind.UNDEF, 329),
        "error" to KeywordSpec(TokenKind.ERROR, 330),
        "warning" to KeywordSpec(TokenKind.WARNING, 331),
        "line" to KeywordSpec(TokenKind.LINE, 332),
        "pragma" to KeywordSpec(TokenKind.PRAGMA, 333),
        "__LINE__" to KeywordSpec(TokenKind.IDENTIFIER, 334),
        "__FILE__" to KeywordSpec(TokenKind.IDENTIFIER, 335),
        "__DATE__" to KeywordSpec(TokenKind.IDENTIFIER, 336),
        "__TIME__" to KeywordSpec(TokenKind.IDENTIFIER, 337),
        "__FUNCTION__" to KeywordSpec(TokenKind.IDENTIFIER, 338),
        "__VA_ARGS__" to KeywordSpec(TokenKind.IDENTIFIER, 339),
        "__COUNTER__" to KeywordSpec(TokenKind.IDENTIFIER, 340),
        "__has_include" to KeywordSpec(TokenKind.IDENTIFIER, 341),
        "__has_include_next" to KeywordSpec(TokenKind.IDENTIFIER, 342),
        "__func__" to KeywordSpec(TokenKind.FUNC, 343),
        "__nan__" to KeywordSpec(TokenKind.NAN, 344),
        "__snan__" to KeywordSpec(TokenKind.SNAN, 345),
        "__inf__" to KeywordSpec(TokenKind.INF, 346),
        "pack" to KeywordSpec(TokenKind.PACK, 428),
        "comment" to KeywordSpec(TokenKind.COMMENT, 429),
        "lib" to KeywordSpec(TokenKind.LIB, 430),
        "push_macro" to KeywordSpec(TokenKind.PUSH_MACRO, 431),
        "pop_macro" to KeywordSpec(TokenKind.POP_MACRO, 432),
        "once" to KeywordSpec(TokenKind.ONCE, 433),
        "option" to KeywordSpec(TokenKind.OPTION, 434),
    )

    private val extensionSpellings = listOf(
        "section", "__section__", "aligned", "__aligned__", "packed", "__packed__",
        "weak", "__weak__", "alias", "__alias__", "used", "__used__", "unused", "__unused__",
        "format", "__format__", "nodebug", "__nodebug__", "cdecl", "__cdecl", "__cdecl__",
        "stdcall", "__stdcall", "__stdcall__", "fastcall", "__fastcall", "__fastcall__",
        "thiscall", "__thiscall", "__thiscall__", "regparm", "__regparm__", "cleanup", "__cleanup__",
        "constructor", "__constructor__", "destructor", "__destructor__", "always_inline", "__always_inline__",
        "__noinline__", "pure", "__pure__", "__mode__", "__QI__", "__DI__", "__HI__", "__SI__", "__word__",
        "dllexport", "dllimport", "nodecorate", "noreturn", "__noreturn__", "_Noreturn",
        "visibility", "__visibility__", "__builtin_types_compatible_p", "__builtin_choose_expr",
        "__builtin_constant_p", "__builtin_frame_address", "__builtin_return_address", "__builtin_expect",
        "__builtin_unreachable", "__builtin_va_arg_types",
        "__atomic_store", "__atomic_load", "__atomic_exchange", "__atomic_compare_exchange",
        "__atomic_fetch_add", "__atomic_fetch_sub", "__atomic_fetch_or", "__atomic_fetch_xor",
        "__atomic_fetch_and", "__atomic_fetch_nand", "__atomic_add_fetch", "__atomic_sub_fetch",
        "__atomic_or_fetch", "__atomic_xor_fetch", "__atomic_and_fetch", "__atomic_nand_fetch",
    )

    // In tcctok.h, runtime helper identifiers follow the pragma token block.
    // The x86_64 token configuration has no intervening target-specific entries.
    private val runtimeSpellings = listOf(
        "memcpy", "memmove", "memset", "__divdi3", "__moddi3", "__udivdi3", "__umoddi3",
        "__ashrdi3", "__lshrdi3", "__ashldi3", "__floatundisf", "__floatundidf", "__floatundixf",
        "__fixunsxfdi", "__fixunssfdi", "__fixunsdfdi", "__fixxfdi",
    )

    private val allSpecs: Map<String, KeywordSpec> = buildMap {
        putAll(keywordSpecs)
        extensionSpellings.forEachIndexed { index, spelling ->
            putIfAbsent(spelling, KeywordSpec(TokenKind.IDENTIFIER, 347 + index))
        }
        runtimeSpellings.forEachIndexed { index, spelling ->
            putIfAbsent(spelling, KeywordSpec(TokenKind.IDENTIFIER, 435 + index))
        }
        putIfAbsent("alloca", KeywordSpec(TokenKind.IDENTIFIER, 452))
    }

    fun keyword(text: String, target: TargetProfile = TargetProfile.X86_64_LINUX): KeywordSpec? {
        if (target == TargetProfile.I386 || target == TargetProfile.I386_PE) {
            when (text) {
                "__builtin_va_arg_types", "__builtin_va_start", "__builtin_va_arg" -> return null
                "__fixsfdi" -> return KeywordSpec(TokenKind.IDENTIFIER, 450)
                "__fixdfdi" -> return KeywordSpec(TokenKind.IDENTIFIER, 451)
                "__fixxfdi" -> return KeywordSpec(TokenKind.IDENTIFIER, 452)
                "alloca" -> return KeywordSpec(TokenKind.IDENTIFIER, 453)
                "__chkstk" -> if (target == TargetProfile.I386_PE) return KeywordSpec(TokenKind.IDENTIFIER, 454)
                "__tls_index" -> if (target == TargetProfile.I386_PE) return KeywordSpec(TokenKind.IDENTIFIER, 455)
            }
        }
        if (target == TargetProfile.X86_64_PE && text == "__builtin_va_arg_types") return null
        if (target == TargetProfile.X86_64_PE) {
            when (text) {
                "__builtin_va_start" -> return KeywordSpec(TokenKind.IDENTIFIER, 411)
                "__chkstk" -> return KeywordSpec(TokenKind.IDENTIFIER, 453)
                "__tls_index" -> return KeywordSpec(TokenKind.IDENTIFIER, 454)
            }
        }
        val spec = allSpecs[text] ?: return null
        if (target == TargetProfile.I386 || target == TargetProfile.I386_PE) {
            return if (spec.tccId >= 412) spec.copy(tccId = spec.tccId - 1) else spec
        }
        return spec
    }

    fun targetProfile(targetTriple: String): TargetProfile = when {
        targetTriple.startsWith("i386", ignoreCase = true) && targetTriple.contains("windows", ignoreCase = true) -> TargetProfile.I386_PE
        targetTriple.startsWith("i386", ignoreCase = true) -> TargetProfile.I386
        targetTriple.startsWith("x86_64", ignoreCase = true) && targetTriple.contains("windows", ignoreCase = true) -> TargetProfile.X86_64_PE
        else -> TargetProfile.X86_64_LINUX
    }

    fun literalId(literal: LiteralValue): Int = when (literal) {
        is LiteralValue.Character -> if (literal.wide) WIDE_CHARACTER else CHARACTER
        is LiteralValue.StringValue -> if (literal.wide) WIDE_STRING else STRING
        is LiteralValue.Floating -> when (literal.suffix?.lowercaseChar()) {
            'f' -> FLOAT
            'l' -> LONG_DOUBLE
            else -> DOUBLE
        }
        is LiteralValue.Integer -> when {
            literal.longRank >= 2 && literal.unsigned -> UNSIGNED_LONG_LONG
            literal.longRank >= 2 -> LONG_LONG
            literal.longRank == 1 && literal.unsigned -> UNSIGNED_LONG
            literal.longRank == 1 -> LONG
            literal.unsigned -> UNSIGNED_INTEGER
            else -> INTEGER
        }
    }
}

val TokenKind.tccId: Int
    get() = when (this) {
        TokenKind.EOF -> TccTokenIds.EOF
        TokenKind.INVALID -> TccTokenIds.INVALID
        TokenKind.IDENTIFIER -> TccTokenIds.IDENTIFIER
        TokenKind.PLUS_PLUS -> TccTokenIds.INCREMENT
        TokenKind.MINUS_MINUS -> TccTokenIds.DECREMENT
        TokenKind.ARROW -> TccTokenIds.ARROW
        TokenKind.ELLIPSIS -> TccTokenIds.ELLIPSIS
        TokenKind.HASH_HASH -> TccTokenIds.HASH_HASH
        TokenKind.AND_AND -> TccTokenIds.LOGICAL_AND
        TokenKind.OR_OR -> TccTokenIds.LOGICAL_OR
        TokenKind.EQUAL_EQUAL -> TccTokenIds.EQUAL
        TokenKind.BANG_EQUAL -> TccTokenIds.NOT_EQUAL
        TokenKind.LESS_EQUAL -> TccTokenIds.LESS_EQUAL
        TokenKind.GREATER_EQUAL -> TccTokenIds.GREATER_EQUAL
        TokenKind.PLUS_ASSIGN -> TccTokenIds.ASSIGN_ADD
        TokenKind.MINUS_ASSIGN -> TccTokenIds.ASSIGN_SUBTRACT
        TokenKind.STAR_ASSIGN -> TccTokenIds.ASSIGN_MULTIPLY
        TokenKind.SLASH_ASSIGN -> TccTokenIds.ASSIGN_DIVIDE
        TokenKind.PERCENT_ASSIGN -> TccTokenIds.ASSIGN_REMAINDER
        TokenKind.AMPERSAND_ASSIGN -> TccTokenIds.ASSIGN_AND
        TokenKind.PIPE_ASSIGN -> TccTokenIds.ASSIGN_OR
        TokenKind.CARET_ASSIGN -> TccTokenIds.ASSIGN_XOR
        TokenKind.LEFT_SHIFT_ASSIGN -> TccTokenIds.ASSIGN_SHIFT_LEFT
        TokenKind.RIGHT_SHIFT_ASSIGN -> TccTokenIds.ASSIGN_SHIFT_RIGHT
        TokenKind.LEFT_SHIFT -> '<'.code
        TokenKind.RIGHT_SHIFT -> '>'.code
        TokenKind.LEFT_PAREN -> '('.code
        TokenKind.RIGHT_PAREN -> ')'.code
        TokenKind.LEFT_BRACKET -> '['.code
        TokenKind.RIGHT_BRACKET -> ']'.code
        TokenKind.LEFT_BRACE -> '{'.code
        TokenKind.RIGHT_BRACE -> '}'.code
        TokenKind.COMMA -> ','.code
        TokenKind.SEMICOLON -> ';'.code
        TokenKind.COLON -> ':'.code
        TokenKind.QUESTION -> '?'.code
        TokenKind.DOT -> '.'.code
        TokenKind.PLUS -> '+'.code
        TokenKind.MINUS -> '-'.code
        TokenKind.STAR -> '*'.code
        TokenKind.SLASH -> '/'.code
        TokenKind.PERCENT -> '%'.code
        TokenKind.AMPERSAND -> '&'.code
        TokenKind.PIPE -> '|'.code
        TokenKind.CARET -> '^'.code
        TokenKind.TILDE -> '~'.code
        TokenKind.BANG -> '!'.code
        TokenKind.ASSIGN -> '='.code
        TokenKind.LESS -> '<'.code
        TokenKind.GREATER -> '>'.code
        else -> TccTokenIds.IDENTIFIER
    }
