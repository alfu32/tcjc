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
    data class Section(val name: String, val index: Int, var offset: Long = 0, var previous: Section? = null)
    data class Symbol(
        val name: String,
        var value: Long = 0,
        var sectionIndex: Int = 0,
        var defined: Boolean = false,
        var external: Boolean = true,
        var set: Boolean = false,
        var asmLabel: String? = null,
    )
    data class Expression(var value: Long = 0, var symbol: Symbol? = null, var pcRelative: Boolean = false)
    data class Token(val text: String, val kind: Kind) {
        enum class Kind { NUMBER, IDENTIFIER, CHARACTER, OPERATOR, END }
    }

    private val labels = mutableMapOf<String, Symbol>()

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

    private inner class ExpressionParser(source: String) {
        private val tokens = tokenize(source)
        private var index = 0
        private val section get() = currentSection()
        private val position get() = currentPosition()

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
                val symbol = getAsmSymbol(localLabelName(number))
                if (localSuffix == 'b' && !symbol.defined) error("local label '$number' not found backward")
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
}
