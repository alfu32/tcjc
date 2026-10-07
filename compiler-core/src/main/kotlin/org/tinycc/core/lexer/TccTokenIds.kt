package org.tinycc.core.lexer

import java.io.BufferedReader

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

    enum class TargetProfile {
        I386, I386_PE, X86_64_LINUX, X86_64_PE, ARM_SOFT, ARM_VFP, ARM_EABI, ARM_EABI_PE,
        ARM64, ARM64_PE, RISCV64, C67,
    }

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

    private val earlyExtensions = extensionSpellings.takeWhile { it != "__builtin_va_arg_types" }
    private val atomicSpellings = extensionSpellings.dropWhile { it != "__atomic_store" }
    private val pragmaKinds = listOf(
        "comment" to TokenKind.COMMENT, "lib" to TokenKind.LIB,
        "push_macro" to TokenKind.PUSH_MACRO, "pop_macro" to TokenKind.POP_MACRO,
        "once" to TokenKind.ONCE, "option" to TokenKind.OPTION,
    )

    fun keyword(
        text: String,
        target: TargetProfile = TargetProfile.X86_64_LINUX,
        boundsCheckTokens: Boolean = false,
    ): KeywordSpec? {
        val id = tokenTablesByTarget.getValue(target to boundsCheckTokens).idsBySpelling[text] ?: return null
        return KeywordSpec(tokenSpecsByTarget.getValue(target)[text]?.kind ?: TokenKind.IDENTIFIER, id)
    }

    fun firstIdentifierId(target: TargetProfile, boundsCheckTokens: Boolean = false): Int =
        tokenTablesByTarget.getValue(target to boundsCheckTokens).firstIdentifierId

    class IdentifierAllocator(target: TargetProfile, boundsCheckTokens: Boolean = false) {
        private var nextId = firstIdentifierId(target, boundsCheckTokens)
        private val ids = HashMap<String, Int>()

        @Synchronized
        fun id(spelling: String): Int = ids.getOrPut(spelling) { nextId++ }
    }

    fun targetProfile(targetTriple: String): TargetProfile = when {
        targetTriple.startsWith("i386", ignoreCase = true) && isWindows(targetTriple) -> TargetProfile.I386_PE
        targetTriple.startsWith("i386", ignoreCase = true) -> TargetProfile.I386
        targetTriple.startsWith("x86_64", ignoreCase = true) && isWindows(targetTriple) -> TargetProfile.X86_64_PE
        targetTriple.startsWith("x86_64", ignoreCase = true) -> TargetProfile.X86_64_LINUX
        targetTriple.startsWith("aarch64", ignoreCase = true) && isWindows(targetTriple) -> TargetProfile.ARM64_PE
        targetTriple.startsWith("arm64", ignoreCase = true) && isWindows(targetTriple) -> TargetProfile.ARM64_PE
        targetTriple.startsWith("aarch64", ignoreCase = true) || targetTriple.startsWith("arm64", ignoreCase = true) -> TargetProfile.ARM64
        targetTriple.startsWith("armv7-windows", ignoreCase = true) -> TargetProfile.ARM_EABI_PE
        targetTriple.startsWith("arm", ignoreCase = true) && targetTriple.contains("vfp", ignoreCase = true) -> TargetProfile.ARM_VFP
        targetTriple.startsWith("arm", ignoreCase = true) && targetTriple.contains("soft", ignoreCase = true) -> TargetProfile.ARM_SOFT
        targetTriple.startsWith("arm", ignoreCase = true) -> if (isWindows(targetTriple)) TargetProfile.ARM_EABI_PE else TargetProfile.ARM_EABI
        targetTriple.startsWith("riscv64", ignoreCase = true) -> TargetProfile.RISCV64
        targetTriple.startsWith("c67", ignoreCase = true) -> TargetProfile.C67
        else -> throw IllegalArgumentException("unsupported TinyCC token target '$targetTriple'")
    }

    private fun isWindows(targetTriple: String): Boolean =
        targetTriple.contains("windows", ignoreCase = true) ||
            targetTriple.contains("win32", ignoreCase = true) ||
            targetTriple.contains("mingw", ignoreCase = true)

    private fun buildTargetSpecs(target: TargetProfile): Map<String, KeywordSpec> = buildMap {
        putAll(keywordSpecs)
        var token = 347
        fun define(spelling: String, kind: TokenKind = TokenKind.IDENTIFIER) {
            put(spelling, KeywordSpec(kind, token++))
        }

        earlyExtensions.forEach(::define)
        variadicBuiltins(target).forEach(::define)
        atomicSpellings.forEach(::define)
        define("pack", TokenKind.PACK)
        if (target == TargetProfile.C67) {
            define("push")
            define("pop")
        }
        pragmaKinds.forEach { (spelling, kind) -> define(spelling, kind) }
        runtimeBeforeAlloca(target).forEach(::define)
        define("alloca")
        runtimeAfterAlloca(target).forEach(::define)
    }

    private fun variadicBuiltins(target: TargetProfile): List<String> = when (target) {
        TargetProfile.X86_64_LINUX -> listOf("__builtin_va_arg_types")
        TargetProfile.X86_64_PE -> listOf("__builtin_va_start")
        TargetProfile.ARM64, TargetProfile.ARM64_PE -> listOf("__builtin_va_start", "__builtin_va_arg")
        TargetProfile.RISCV64 -> listOf("__builtin_va_start")
        else -> emptyList()
    }

    private val genericRuntime = listOf(
        "memcpy", "memmove", "memset", "__divdi3", "__moddi3", "__udivdi3", "__umoddi3",
        "__ashrdi3", "__lshrdi3", "__ashldi3", "__floatundisf", "__floatundidf", "__floatundixf",
        "__fixunsxfdi", "__fixunssfdi", "__fixunsdfdi",
    )

    private val armEabiRuntime = listOf(
        "__aeabi_memcpy", "__aeabi_memmove", "__aeabi_memmove4", "__aeabi_memmove8", "__aeabi_memset",
        "__aeabi_ldivmod", "__aeabi_uldivmod", "__aeabi_idivmod", "__aeabi_uidivmod", "__aeabi_idiv",
        "__aeabi_uidiv", "__aeabi_l2f", "__aeabi_l2d", "__aeabi_f2lz", "__aeabi_d2lz", "__aeabi_lasr",
        "__aeabi_llsr", "__aeabi_llsl", "__aeabi_ul2f", "__aeabi_ul2d", "__aeabi_f2ulz", "__aeabi_d2ulz",
    )

    private val armSoftRuntime = listOf(
        "__modsi3", "__umodsi3", "__divsi3", "__udivsi3", "__floatdisf", "__floatdidf", "__floatdixf",
        "__fixunssfsi", "__fixunsdfsi", "__fixunsxfsi", "__fixxfdi", "__fixsfdi", "__fixdfdi",
    )

    private val armVfpRuntime = listOf(
        "__modsi3", "__umodsi3", "__divsi3", "__udivsi3", "__floatdisf", "__floatdidf", "__fixsfdi", "__fixdfdi",
    )

    private val quadRuntime = listOf(
        "__addtf3", "__subtf3", "__multf3", "__divtf3", "__extendsftf2", "__extenddftf2", "__trunctfsf2",
        "__trunctfdf2", "__negtf2", "__fixtfsi", "__fixtfdi", "__fixunstfsi", "__fixunstfdi", "__floatsitf",
        "__floatditf", "__floatunsitf", "__floatunditf", "__eqtf2", "__netf2", "__lttf2", "__letf2", "__gttf2", "__getf2",
    )

    private val tokenSpecsByTarget = TargetProfile.entries.associateWith(::buildTargetSpecs)

    private data class TargetTokenTable(val idsBySpelling: Map<String, Int>, val firstIdentifierId: Int)

    private val tokenTablesByTarget: Map<Pair<TargetProfile, Boolean>, TargetTokenTable> = loadTokenTables()

    private fun loadTokenTables(): Map<Pair<TargetProfile, Boolean>, TargetTokenTable> {
        val keys = TargetProfile.entries.flatMap { target -> listOf(target to false, target to true) }
        val ids = keys.associateWith { LinkedHashMap<String, Int>() }
        val firstIdentifiers = HashMap<Pair<TargetProfile, Boolean>, Int>()
        val stream = TccTokenIds::class.java.getResourceAsStream("/org/tinycc/core/lexer/tokens.tsv")
            ?: error("missing historical token data resource")
        BufferedReader(stream.reader()).useLines { lines ->
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val fields = line.split('\t', limit = 3)
                require(fields.size == 3) { "malformed token data at line ${index + 1}" }
                val boundsCheckTokens = fields[0].endsWith("_BCHECK")
                val profileName = if (boundsCheckTokens) fields[0].removeSuffix("_BCHECK") else fields[0]
                val target = TargetProfile.valueOf(profileName)
                val key = target to boundsCheckTokens
                if (fields[1] == "FIRST_IDENTIFIER") {
                    firstIdentifiers[key] = fields[2].toInt()
                } else {
                    ids.getValue(key)[fields[2]] = fields[1].toInt()
                }
            }
        }
        return buildMap {
            for (target in TargetProfile.entries) {
                for (boundsCheckTokens in listOf(false, true)) {
                    val key = target to boundsCheckTokens
                    put(key, TargetTokenTable(ids.getValue(key), requireNotNull(firstIdentifiers[key]) {
                        "missing first identifier ID for $target (boundsCheckTokens=$boundsCheckTokens)"
                    }))
                }
            }
        }
    }

    private fun runtimeBeforeAlloca(target: TargetProfile): List<String> = when (target) {
        TargetProfile.I386, TargetProfile.I386_PE -> genericRuntime + listOf("__fixsfdi", "__fixdfdi", "__fixxfdi")
        TargetProfile.X86_64_LINUX, TargetProfile.X86_64_PE -> genericRuntime + "__fixxfdi"
        TargetProfile.ARM_EABI, TargetProfile.ARM_EABI_PE -> armEabiRuntime
        TargetProfile.ARM_SOFT -> genericRuntime + armSoftRuntime
        TargetProfile.ARM_VFP -> genericRuntime.filterNot { it == "__floatundixf" || it == "__fixunsxfdi" } + armVfpRuntime
        TargetProfile.ARM64, TargetProfile.ARM64_PE -> genericRuntime
        TargetProfile.RISCV64 -> genericRuntime
        TargetProfile.C67 -> genericRuntime + listOf("_divi", "_divu", "_divf", "_divd", "_remi", "_remu")
    }

    private fun runtimeAfterAlloca(target: TargetProfile): List<String> = buildList {
        if (target in setOf(TargetProfile.I386_PE, TargetProfile.X86_64_PE, TargetProfile.ARM_EABI_PE, TargetProfile.ARM64_PE)) {
            addAll(listOf("__chkstk", "__tls_index"))
        }
        when (target) {
            TargetProfile.ARM64, TargetProfile.ARM64_PE -> addAll(listOf("__arm64_clear_cache") + quadRuntime)
            TargetProfile.RISCV64 -> addAll(listOf("__riscv64_clear_cache") + quadRuntime)
            else -> Unit
        }
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
