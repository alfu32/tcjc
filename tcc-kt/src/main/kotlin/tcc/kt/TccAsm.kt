package tcc.kt

/** GAS-like assembler expression, symbol, and section state from tccasm.c. */
class TccAsm(
    private val leadingUnderscore: Boolean = false,
    private val currentSection: () -> Section = { Section(".text", 1) },
    private val currentPosition: () -> Long = { 0L },
    private val findSymbol: (String) -> Symbol? = { null },
    private val defineSymbol: (String, Symbol) -> Unit = { _, _ -> },
    private val localLabelName: (Long) -> String = { "L..$it" },
) {
    data class Section(
        val name: String,
        val index: Int,
        var offset: Long = 0,
        var previous: Section? = null,
        var alignment: Int = 1,
        var flags: Int = 2,
        var noBits: Boolean = false,
        var bytes: ByteArray = byteArrayOf(),
    )
    data class Symbol(
        val name: String,
        var value: Long = 0,
        var sectionIndex: Int = 0,
        var defined: Boolean = false,
        var external: Boolean = true,
        var set: Boolean = false,
        var asmLabel: String? = null,
        var weak: Boolean = false,
        var hidden: Boolean = false,
        var elfType: String? = null,
        var size: Long = 0,
    )
    data class Expression(var value: Long = 0, var symbol: Symbol? = null, var pcRelative: Boolean = false)
    data class Token(val text: String, val kind: Kind) {
        enum class Kind { NUMBER, IDENTIFIER, CHARACTER, OPERATOR, END }
    }
    data class AsmRelocation(val section: String, val offset: Long, val symbol: String, val type: String, val addend: Long)
    data class DirectiveResult(val section: String, val emittedBytes: Int, val relocations: List<AsmRelocation> = emptyList())

    private val labels = mutableMapOf<String, Symbol>()
    private val numericLabels = mutableMapOf<Long, MutableList<Symbol>>()
    private val sections = linkedMapOf(currentSection().name to currentSection())
    private var activeSection = currentSection()
    private var previousSection: Section? = null
    private val sectionStack = mutableListOf<Section>()
    val relocations = mutableListOf<AsmRelocation>()
    var nopBytes: (Int) -> ByteArray = { count -> ByteArray(count) { 0x90.toByte() } }

    fun asmGetLocalLabelName(number: Long): String = localLabelName(number)

    fun getAsmSymbol(name: String, cSymbol: Symbol? = null): Symbol {
        val (assemblerName, dotted) = asmToCName(name)
        val found = findSymbol(assemblerName) ?: labels[assemblerName]
        if (found != null) return found
        val symbol = Symbol(
            assemblerName,
            value = cSymbol?.value ?: 0,
            sectionIndex = cSymbol?.sectionIndex ?: 0,
            defined = cSymbol?.defined ?: false,
            external = cSymbol?.external ?: true,
        )
        if (dotted) symbol.asmLabel = name
        labels[assemblerName] = symbol
        defineSymbol(assemblerName, symbol)
        return symbol
    }

    fun defineLabel(name: String, local: Boolean = false): Symbol {
        val transformed = asmToCName(name).first
        val symbol = if (local) {
            val localNumber = transformed.removePrefix("L.." ).toLongOrNull()
            if (localNumber != null) {
                val versions = numericLabels.getOrPut(localNumber) { mutableListOf() }
                versions.lastOrNull()?.takeIf { !it.defined } ?: Symbol(transformed).also { versions += it }
            } else getAsmSymbol(name)
        } else getAsmSymbol(name)
        require(!symbol.defined || symbol.external) { "assembler label '$name' already defined" }
        symbol.value = activeSection.offset
        symbol.sectionIndex = activeSection.index
        symbol.defined = true
        symbol.external = false
        return symbol
    }

    private fun asmToCName(name: String): Pair<String, Boolean> {
        if (!leadingUnderscore) return name to false
        if (name.startsWith('_')) return name.drop(1) to false
        if (!name.contains('.')) return ".$name" to true
        return name to false
    }

    fun evaluateExpression(source: String): Expression = ExpressionParser(source).parse()

    fun asmIntExpression(source: String): Int {
        val expression = evaluateExpression(source)
        require(expression.symbol == null) { "constant expected" }
        require(expression.value.toInt().toLong() == expression.value) { "integer out of range ${expression.value}" }
        return expression.value.toInt()
    }

    fun section(name: String): Section = sections.getOrPut(name) { Section(name, sections.size + 1) }

    fun useSection(name: String): Section { activeSection = section(name); return activeSection }
    fun pushSection(name: String): Section { sectionStack += activeSection; return useSection(name) }
    fun popSection(): Section {
        require(sectionStack.isNotEmpty()) { ".popsection without .pushsection" }
        activeSection = sectionStack.removeAt(sectionStack.lastIndex)
        return activeSection
    }
    fun previousSection(): Section {
        val previous = previousSection ?: error("no previous section referenced")
        val old = activeSection
        activeSection = previous
        previousSection = old
        return activeSection
    }

    /** Parses common GAS data, padding, section, and symbol directives. */
    fun parseDirective(name: String, operandText: String = ""): DirectiveResult {
        val directive = name.removePrefix(".")
        val startSection = activeSection
        val startOffset = activeSection.offset
        val args = splitOperands(operandText)
        when (directive) {
            "align", "balign", "p2align", "skip", "space", "org" -> directivePadding(directive, args)
            "byte", "short", "word", "long", "int", "quad" -> directiveData(directive, args)
            "fill" -> directiveFill(args)
            "ascii", "asciz", "string" -> directiveStrings(directive, args)
            "text", "data", "bss" -> {
                val suffix = args.firstOrNull()?.let(::asmIntExpression)?.takeIf { it != 0 }?.toString() ?: ""
                val sec = useSection(directive + suffix)
                if (directive == "bss") sec.noBits = true
            }
            "section", "pushsection" -> {
                require(args.isNotEmpty()) { "section name expected" }
                val sectionName = unquote(args[0])
                val old = sections[sectionName]
                val flagsText = args.getOrNull(1)?.let(::unquote).orEmpty()
                previousSection = activeSection
                val sec = if (directive == "pushsection") pushSection(sectionName) else useSection(sectionName)
                if (old == null) {
                    sec.alignment = 1
                    sec.flags = 2 or (if ('w' in flagsText) 1 else 0) or
                        (if ('x' in flagsText || sectionName == ".init" || sectionName == ".fini") 4 else 0)
                }
            }
            "popsection" -> popSection()
            "previous" -> previousSection()
            "globl", "global", "weak", "hidden" -> args.forEach { operand ->
                val symbol = getAsmSymbol(operand.trim())
                if (directive == "weak") symbol.weak = true
                if (directive == "hidden") symbol.hidden = true
                symbol.external = true
            }
            "set" -> if (args.size > 1) setAsmSymbol(args[0], args.drop(1).joinToString(","))
            "type" -> {
                val symbol = getAsmSymbol(args.firstOrNull()?.trim() ?: error("identifier expected"))
                val type = args.getOrNull(1)?.trim()?.removePrefix("@").orEmpty()
                if (type in setOf("function", "STT_FUNC")) symbol.elfType = "STT_FUNC"
                else if (type in setOf("object", "STT_OBJECT")) symbol.elfType = "STT_OBJECT"
            }
            "size" -> {
                val symbol = findAsmSymbol(args.firstOrNull()?.trim() ?: error("identifier expected")) ?: error("label not found")
                symbol.size = asmIntExpression(args.getOrElse(1) { "0" }).toLong()
            }
            "reloc" -> {
                require(args.size >= 3) { ".reloc expects offset, relocation type, and symbol" }
                val offset = evaluateExpression(args[0])
                val relocation = args[1].trim().removePrefix("R_")
                val supported = setOf(
                    "AARCH64_CALL26", "RISCV_CALL", "RISCV_CALL_PLT", "RISCV_BRANCH", "RISCV_JAL",
                    "RISCV_PCREL_HI20", "RISCV_PCREL_LO12_I", "RISCV_PCREL_LO12_S", "RISCV_32_PCREL",
                    "RISCV_32", "RISCV_64",
                )
                require(relocation in supported) { "unimplemented relocation '$relocation'" }
                val symbol = getAsmSymbol(args[2].trim())
                relocations += AsmRelocation(activeSection.name, offset.value, symbol.name, relocation, 0)
            }
            "ident", "file", "symver", "code16", "code32", "code64", "option" -> Unit
            else -> error("unknown assembler directive '.$directive'")
        }
        return DirectiveResult(startSection.name, (startSection.offset - startOffset).toInt(), relocations.toList())
    }

    private fun setAsmSymbol(name: String, expressionText: String): Symbol {
        val expression = evaluateExpression(expressionText)
        return getAsmSymbol(name.trim()).apply {
            value = expression.value + (expression.symbol?.value ?: 0)
            sectionIndex = expression.symbol?.sectionIndex ?: -1
            defined = true
            external = false
            set = true
        }
    }

    private fun findAsmSymbol(name: String): Symbol? {
        val transformed = asmToCName(name).first
        return findSymbol(transformed) ?: labels[transformed]
    }

    private fun directivePadding(kind: String, args: List<String>) {
        var count = args.firstOrNull()?.let(::asmIntExpression) ?: 0
        if (kind == "p2align") { require(count in 0..30) { "invalid p2align" }; count = 1 shl count }
        if (kind in setOf("align", "balign", "p2align")) {
            require(count > 0 && count and (count - 1) == 0) { "alignment must be a positive power of two" }
            val padding = ((activeSection.offset + count - 1) and -count.toLong()) - activeSection.offset
            activeSection.alignment = maxOf(activeSection.alignment, count)
            val fill = args.getOrNull(1)?.let(::asmIntExpression) ?: 0
            if (activeSection.flags and 4 != 0 && args.size < 2) appendBytes(nopBytes(padding.toInt()))
            else appendRepeated(fill, padding.toInt())
        } else if (kind == "org") {
            val expression = evaluateExpression(args.firstOrNull() ?: "0")
            val symbol = expression.symbol
            require(symbol == null || symbol.sectionIndex == activeSection.index) { "constant or same-section symbol expected" }
            val target = expression.value + (symbol?.value ?: 0)
            require(target >= activeSection.offset) { "attempt to .org backwards" }
            appendRepeated(0, (target - activeSection.offset).toInt())
        } else {
            val size = count.coerceAtLeast(0)
            val fill = args.getOrNull(1)?.let(::asmIntExpression) ?: 0
            appendRepeated(fill, size)
        }
    }

    private fun directiveData(kind: String, args: List<String>) {
        val width = when (kind) { "byte" -> 1; "short", "word" -> 2; "long", "int" -> 4; else -> 8 }
        for (operand in args) {
            val expression = evaluateExpression(operand)
            require(expression.symbol == null || width >= 4) { "constant expected" }
            if (expression.symbol != null) relocations += AsmRelocation(activeSection.name, activeSection.offset,
                expression.symbol!!.name, if (width == 8) "R_DATA_PTR" else "R_DATA_32", expression.value)
            val value = if (expression.symbol == null) expression.value else 0L
            repeat(width) { byte -> appendByte((value ushr (8 * byte)).toInt()) }
        }
    }

    private fun directiveFill(args: List<String>) {
        val repeatCount = asmIntExpression(args.getOrElse(0) { "0" })
        require(repeatCount >= 0) { "repeat < 0; .fill ignored" }
        val size = args.getOrNull(1)?.let(::asmIntExpression)?.coerceIn(0, 8) ?: 1
        val value = args.getOrNull(2)?.let(::asmIntExpression) ?: 0
        repeat(repeatCount) { repeat(size) { byte -> appendByte(value ushr (8 * byte)) } }
    }

    private fun directiveStrings(kind: String, args: List<String>) {
        args.forEach { text ->
            val bytes = decodeString(unquote(text))
            val length = if (kind == "ascii") (bytes.size - 1).coerceAtLeast(0) else bytes.size
            appendBytes(bytes.copyOf(length))
        }
    }

    private fun appendByte(value: Int) {
        if (!activeSection.noBits) activeSection.bytes += value.toByte()
        activeSection.offset++
    }
    private fun appendBytes(bytes: ByteArray) = bytes.forEach { appendByte(it.toInt()) }
    private fun appendRepeated(value: Int, count: Int) { repeat(count.coerceAtLeast(0)) { appendByte(value) } }

    private fun splitOperands(source: String): List<String> {
        if (source.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var quoted = false
        var escaped = false
        var start = 0
        source.forEachIndexed { index, ch ->
            if (escaped) escaped = false
            else if (quoted && ch == '\\') escaped = true
            else if (ch == '"') quoted = !quoted
            else if (!quoted) when (ch) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) { result += source.substring(start, index).trim(); start = index + 1 }
            }
        }
        result += source.substring(start).trim()
        return result
    }

    private fun unquote(text: String): String = text.trim().removeSurrounding("\"", "\"")
    private fun decodeString(value: String): ByteArray {
        val result = mutableListOf<Byte>()
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch != '\\' || index == value.length) result += ch.code.toByte()
            else when (val escaped = value[index++]) {
                'n' -> result += '\n'.code.toByte(); 'r' -> result += '\r'.code.toByte(); 't' -> result += '\t'.code.toByte()
                '0' -> result += 0
                else -> result += escaped.code.toByte()
            }
        }
        result += 0
        return result.toByteArray()
    }

    private inner class ExpressionParser(source: String) {
        private val tokens = tokenize(source)
        private var index = 0
        private val section get() = activeSection
        private val position get() = if (activeSection.offset != 0L || currentPosition() == 0L) activeSection.offset else currentPosition()

        fun parse(): Expression {
            val result = comparison()
            require(peek().kind == Token.Kind.END) { "unexpected token ${peek().text}" }
            return result
        }

        private fun comparison(): Expression {
            var left = sum()
            while (peek().text in setOf("==", "!=", "<", ">", "<=", ">=")) {
                val op = take().text
                val right = sum()
                require(left.symbol == null && right.symbol == null) { "invalid operation with label" }
                val result = when (op) {
                    "==" -> left.value == right.value
                    "!=" -> left.value != right.value
                    "<" -> left.value < right.value
                    ">" -> left.value > right.value
                    "<=" -> left.value <= right.value
                    else -> left.value >= right.value
                }
                left = Expression(if (result) -1 else 0)
            }
            return left
        }

        private fun sum(): Expression {
            var left = logic()
            while (peek().text == "+" || peek().text == "-") {
                val op = take().text
                val right = logic()
                if (op == "+") {
                    require(left.symbol == null || right.symbol == null) { "invalid operation with label" }
                    left.value += right.value
                    if (left.symbol == null) left.symbol = right.symbol
                } else {
                    left.value -= right.value
                    val leftSymbol = left.symbol
                    val rightSymbol = right.symbol
                    when {
                        rightSymbol == null -> Unit
                        leftSymbol === rightSymbol -> left.symbol = null
                        leftSymbol != null && leftSymbol.sectionIndex == rightSymbol.sectionIndex && leftSymbol.sectionIndex != 0 -> {
                            left.value += leftSymbol.value - rightSymbol.value
                            left.symbol = null
                        }
                        rightSymbol.sectionIndex == section.index -> {
                            left.value -= rightSymbol.value
                            left.pcRelative = true
                        }
                        else -> error("invalid operation with label")
                    }
                }
            }
            return left
        }

        private fun logic(): Expression {
            var left = product()
            while (peek().text in setOf("&", "|", "^")) {
                val op = take().text
                val right = product()
                require(left.symbol == null && right.symbol == null) { "invalid operation with label" }
                left.value = when (op) { "&" -> left.value and right.value; "|" -> left.value or right.value; else -> left.value xor right.value }
            }
            return left
        }

        private fun product(): Expression {
            var left = unary()
            while (peek().text in setOf("*", "/", "%", "<<", ">>")) {
                val op = take().text
                val right = unary()
                require(left.symbol == null && right.symbol == null) { "invalid operation with label" }
                left.value = when (op) {
                    "*" -> left.value * right.value
                    "/" -> left.value / right.value
                    "%" -> left.value % right.value
                    "<<" -> left.value shl right.value.toInt()
                    else -> left.value shr right.value.toInt()
                }
            }
            return left
        }

        private fun unary(): Expression {
            val token = peek().text
            if (token == "+") { take(); return unary() }
            if (token == "-" || token == "~") {
                take()
                val value = unary()
                require(value.symbol == null) { "invalid operation with label" }
                value.value = if (token == "-") -value.value else value.value.inv()
                return value
            }
            if (token == "(") { take(); val value = comparison(); require(take().text == ")") { "expected )" }; return value }
            val value = take()
            return when (value.kind) {
                Token.Kind.NUMBER -> parseNumber(value.text)
                Token.Kind.CHARACTER -> Expression(parseCharacter(value.text))
                Token.Kind.OPERATOR -> if (value.text == ".") {
                    val sectionSymbol = getAsmSymbol("L.${section.name}", Symbol("L.${section.name}", 0, section.index, true, false))
                    Expression(position, sectionSymbol)
                } else error("bad expression syntax near ${value.text}")
                Token.Kind.IDENTIFIER -> {
                    val symbol = getAsmSymbol(value.text)
                    if (symbol.defined && symbol.sectionIndex == -1) Expression(symbol.value)
                    else Expression(symbol = symbol)
                }
                else -> error("bad expression syntax near ${value.text}")
            }
        }

        private fun parseNumber(text: String): Expression {
            if (text.matches(Regex("[0-9]+[bf]"))) {
                val number = parseInteger(text.dropLast(1))
                val localSuffix = text.last()
                val versions = numericLabels.getOrPut(number) { mutableListOf() }
                val symbol = if (localSuffix == 'b') versions.lastOrNull { it.defined }
                    ?: error("local label '$number' not found backward")
                else versions.lastOrNull()?.takeIf { !it.defined }
                    ?: Symbol(localLabelName(number)).also { versions += it }
                return Expression(symbol = symbol)
            }
            return Expression(parseInteger(text))
        }

        private fun peek(): Token = tokens[index]
        private fun take(): Token = tokens[index++]
    }

    private fun tokenize(source: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            if (source[i].isWhitespace()) { i++; continue }
            val start = i
            val ch = source[i]
            when {
                ch == '\'' -> {
                    i++
                    while (i < source.length && source[i] != '\'') { if (source[i] == '\\') i++; i++ }
                    require(i < source.length) { "unterminated character constant" }
                    i++
                    out += Token(source.substring(start, i), Token.Kind.CHARACTER)
                }
                ch.isDigit() -> {
                    i++
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    out += Token(source.substring(start, i), Token.Kind.NUMBER)
                }
                ch.isLetter() || ch == '_' || ch == '$' || (ch == '.' && i + 1 < source.length && (source[i + 1].isLetter() || source[i + 1] == '_')) -> {
                    i++
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '.' || source[i] == '$')) i++
                    out += Token(source.substring(start, i), Token.Kind.IDENTIFIER)
                }
                source.startsWith("<<", i) || source.startsWith(">>", i) || source.startsWith("==", i) || source.startsWith("!=", i) || source.startsWith("<=", i) || source.startsWith(">=", i) -> {
                    out += Token(source.substring(i, i + 2), Token.Kind.OPERATOR); i += 2
                }
                else -> { out += Token(ch.toString(), Token.Kind.OPERATOR); i++ }
            }
        }
        out += Token("<end>", Token.Kind.END)
        return out
    }

    private fun parseCharacter(text: String): Long {
        val body = text.substring(1, text.lastIndex)
        return when {
            body == "\\n" -> '\n'.code.toLong()
            body == "\\r" -> '\r'.code.toLong()
            body == "\\t" -> '\t'.code.toLong()
            body.startsWith("\\x") -> body.drop(2).toLong(16)
            body.startsWith("\\") -> body.drop(1).toLongOrNull(8) ?: body[1].code.toLong()
            else -> body.first().code.toLong()
        }
    }

    private fun parseInteger(text: String): Long {
        val clean = text.replace("_", "")
        return when {
            clean.startsWith("0x", true) -> clean.drop(2).toLong(16)
            clean.startsWith("0b", true) -> clean.drop(2).toLong(2)
            clean.length > 1 && clean.startsWith('0') -> clean.drop(1).ifEmpty { "0" }.toLong(8)
            else -> clean.toLong(10)
        }
    }

    /** Parses GAS source lines; architecture instruction encoding is supplied by the target backend. */
    fun assemble(
        source: String,
        instruction: (mnemonic: String, operands: String) -> Unit,
        global: Boolean = true,
        preprocess: ((String) -> String)? = null,
        hashComments: Boolean = true,
    ): Int {
        val lines = splitLines(preprocess?.invoke(source) ?: source, hashComments)
        var emitted = 0
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isEmpty()) { index++; continue }
            if (line.startsWith(".rept ")) {
                val count = asmIntExpression(line.substringAfter(' ')).coerceAtLeast(0)
                var depth = 1
                val start = ++index
                while (index < lines.size && depth > 0) {
                    if (lines[index].trim().startsWith(".rept ")) depth++
                    if (lines[index].trim() == ".endr") depth--
                    index++
                }
                require(depth == 0) { "we are at end of file, .endr not found" }
                val body = lines.subList(start, index - 1).joinToString("\n")
                repeat(count) { emitted += assemble(body, instruction, global, null, hashComments) }
                continue
            }
            require(line != ".endr") { "unexpected .endr" }
            var rest = line
            while (true) {
                val colon = findOutsideQuotes(rest, ':')
                val equals = findOutsideQuotes(rest, '=')
                if (colon > 0 && (equals < 0 || colon < equals) &&
                    rest.substring(0, colon).trim().matches(Regex("[A-Za-z_.$][A-Za-z0-9_.$]*|[0-9]+"))) {
                    val label = rest.substring(0, colon).trim()
                    defineLabel(label, label.all(Char::isDigit))
                    rest = rest.substring(colon + 1).trim()
                    if (rest.isEmpty()) break
                } else if (equals > 0 && (colon < 0 || equals < colon)) {
                    setAsmSymbol(rest.substring(0, equals).trim(), rest.substring(equals + 1).trim())
                    break
                } else break
            }
            if (rest.isNotEmpty()) {
                if (rest.startsWith('.')) {
                    val split = rest.indexOfFirst(Char::isWhitespace)
                    val directive = if (split < 0) rest else rest.substring(0, split)
                    val operands = if (split < 0) "" else rest.substring(split + 1).trim()
                    parseDirective(directive, operands)
                } else {
                    val split = rest.indexOfFirst(Char::isWhitespace)
                    val mnemonic = if (split < 0) rest else rest.substring(0, split)
                    val operands = if (split < 0) "" else rest.substring(split + 1).trim()
                    instruction(mnemonic, operands)
                    emitted++
                }
            }
            index++
        }
        return emitted
    }

    private fun splitLines(source: String, hashComments: Boolean): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        var comment = false
        fun flush() { lines += current.toString(); current.setLength(0) }
        for (char in source) {
            if (comment) {
                if (char == '\n') { flush(); comment = false }
                continue
            }
            if (escaped) { current.append(char); escaped = false; continue }
            if (quoted && char == '\\') { current.append(char); escaped = true; continue }
            if (char == '"') quoted = !quoted
            if (!quoted && hashComments && char == '#') {
                comment = true
                continue
            }
            if (!quoted && (char == '\n' || char == ';')) flush() else current.append(char)
        }
        if (current.isNotEmpty()) flush()
        return lines
    }

    private fun findOutsideQuotes(source: String, wanted: Char): Int {
        var quoted = false
        var escaped = false
        source.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else if (quoted && char == '\\') escaped = true
            else if (char == '"') quoted = !quoted
            else if (!quoted && char == wanted) return index
        }
        return -1
    }
}
