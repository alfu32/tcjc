package org.tinycc.core.preprocessor

import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.LineMap
import org.tinycc.core.diagnostics.SourceLocation

data class PreprocessorOptions(
    val predefined: Map<String, String> = emptyMap(),
    val clock: Clock = Clock.systemUTC(),
)

data class PreprocessedSource(val text: String, val macros: List<MacroDefinition>)

/** Handles macro definitions, expansion, and conditional compilation. */
class Preprocessor(
    private val source: String,
    private val path: Path? = null,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val options: PreprocessorOptions = PreprocessorOptions(),
) {
    private val lineMap = LineMap(source)
    private val macros = MacroTable()
    private var counter = 0L

    constructor(
        sourceFile: org.tinycc.core.io.SourceFile,
        diagnostics: DiagnosticEngine = DiagnosticEngine(),
        options: PreprocessorOptions = PreprocessorOptions(),
    ) : this(sourceFile.text, sourceFile.path, diagnostics, options)

    fun process(): PreprocessedSource {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val output = StringBuilder(source.length)
        val conditionals = ArrayDeque<ConditionalFrame>()
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val startLine = lineIndex + 1
            var line = lines[lineIndex]
            while (line.endsWith("\\") && lineIndex + 1 < lines.size) {
                line = line.dropLast(1) + lines[++lineIndex]
            }
            val directive = parseDirective(line)
            if (directive != null) {
                processDirective(directive, startLine, conditionals, output)
            } else if (conditionals.isActive()) {
                output.append(expandText(line, emptySet(), startLine))
                if (lineIndex < lines.lastIndex) output.append('\n')
            }
            lineIndex++
        }
        if (conditionals.isNotEmpty()) {
            report(1, "unterminated conditional directive")
        }
        return PreprocessedSource(output.toString(), macros.snapshot())
    }

    private fun processDirective(
        directive: Directive,
        line: Int,
        conditionals: ArrayDeque<ConditionalFrame>,
        output: StringBuilder,
    ) {
        when (directive.name) {
            "if" -> {
                val parentActive = conditionals.isActive()
                val branch = parentActive && evaluate(directive.body, line)
                conditionals.addLast(ConditionalFrame(parentActive, branch, branch))
            }
            "ifdef", "ifndef" -> {
                val parentActive = conditionals.isActive()
                val defined = macros[directive.body.trim()] != null
                val branch = parentActive && if (directive.name == "ifdef") defined else !defined
                conditionals.addLast(ConditionalFrame(parentActive, branch, branch))
            }
            "elif" -> {
                val frame = conditionals.peekLast()
                if (frame == null) {
                    report(line, "#elif without matching #if")
                } else if (frame.elseSeen) {
                    report(line, "#elif after #else")
                } else {
                    frame.active = frame.parentActive && !frame.branchTaken && evaluate(directive.body, line)
                    frame.branchTaken = frame.branchTaken || frame.active
                }
            }
            "else" -> {
                val frame = conditionals.peekLast()
                if (frame == null) {
                    report(line, "#else without matching #if")
                } else if (frame.elseSeen) {
                    report(line, "duplicate #else")
                } else {
                    frame.elseSeen = true
                    frame.active = frame.parentActive && !frame.branchTaken
                    frame.branchTaken = true
                }
            }
            "endif" -> {
                if (conditionals.pollLast() == null) report(line, "#endif without matching #if")
            }
            else -> if (conditionals.isActive()) when (directive.name) {
                "define" -> define(directive.body, line)
                "undef" -> macros.undef(directive.body.trim())
                "error" -> report(line, directive.body.trim().ifEmpty { "#error" })
                "warning" -> diagnostics.warning(location(line), directive.body.trim().ifEmpty { "#warning" })
                else -> output.append(directive.original).append('\n')
            }
        }
    }

    private fun define(body: String, line: Int) {
        val match = Regex("^([A-Za-z_$][A-Za-z0-9_$]*)(.*)$").find(body.trimStart())
        if (match == null) {
            report(line, "macro name must be an identifier")
            return
        }
        val name = match.groupValues[1]
        val tail = match.groupValues[2]
        if (tail.startsWith('(')) {
            val close = tail.indexOf(')')
            if (close < 0) {
                report(line, "unterminated macro parameter list")
                return
            }
            val parameterText = tail.substring(1, close).trim()
            val parameters = if (parameterText.isEmpty()) emptyList() else parameterText.split(',').map { it.trim() }
            val variadic = parameters.lastOrNull() == "..." || parameters.lastOrNull()?.endsWith("...") == true
            val normalized = parameters.dropLastWhile { it == "..." }.map { it.removeSuffix("...").trim() }
            if (normalized.any { !Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(it) }) {
                report(line, "invalid macro parameter list")
                return
            }
            macros.define(MacroDefinition(name, normalized, tail.substring(close + 1).trim(), variadic))
        } else {
            macros.define(MacroDefinition(name, null, tail.trim()))
        }
    }

    private fun evaluate(expression: String, line: Int): Boolean {
        val protected = protectDefined(expression)
        val expanded = expandText(protected.text, emptySet(), line)
        return IfExpression(expanded) { name -> protected.values[name] ?: 0L }.evaluate() != 0L
    }

    private fun protectDefined(expression: String): ProtectedExpression {
        val values = HashMap<String, Long>()
        val result = Regex("\\bdefined\\s*(?:\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)|([A-Za-z_$][A-Za-z0-9_$]*))")
            .replace(expression) { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val marker = "__TCC_DEFINED_${values.size}__"
                values[marker] = if (macros[name] != null) 1 else 0
                marker
            }
        return ProtectedExpression(result, values)
    }

    private fun expandText(text: String, disabled: Set<String>, line: Int): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '/' && text.getOrNull(index + 1) == '/') {
                append(text.substring(index))
                break
            }
            if (character == '/' && text.getOrNull(index + 1) == '*') {
                val end = text.indexOf("*/", index + 2)
                val limit = if (end < 0) text.length else end + 2
                append(text.substring(index, limit))
                index = limit
                continue
            }
            if (character == '\'' || character == '"') {
                val end = quotedEnd(text, index)
                append(text.substring(index, end))
                index = end
                continue
            }
            if (isIdentifierStart(character)) {
                val begin = index++
                while (index < text.length && isIdentifierPart(text[index])) index++
                val name = text.substring(begin, index)
                val definition = macros[name]
                val builtin = builtinValue(name, line)
                if (name in disabled) {
                    append(name)
                } else if (definition?.parameters == null) {
                    append(builtin ?: if (definition == null) name else expandText(definition.replacement, disabled + name, line))
                } else {
                    val open = skipSpaces(text, index)
                    if (open >= text.length || text[open] != '(') {
                        append(name)
                    } else {
                        val invocation = readInvocation(text, open)
                        if (invocation == null) append(name) else {
                            append(expandFunction(definition, invocation.arguments, disabled + name, line))
                            index = invocation.end
                        }
                    }
                }
                continue
            }
            append(character)
            index++
        }
    }

    private fun expandFunction(
        definition: MacroDefinition,
        arguments: List<String>,
        disabled: Set<String>,
        line: Int,
    ): String {
        val parameters = definition.parameters ?: return definition.replacement
        val expected = parameters.size
        if ((!definition.variadic && arguments.size != expected) || (definition.variadic && arguments.size < expected)) {
            report(line, "macro '${definition.name}' expects ${if (definition.variadic) "at least " else ""}$expected argument(s)")
            return definition.name
        }
        val raw = LinkedHashMap<String, String>()
        val expanded = LinkedHashMap<String, String>()
        parameters.forEachIndexed { index, parameter ->
            val value = arguments.getOrElse(index) { "" }
            raw[parameter] = value
            expanded[parameter] = expandText(value, disabled, line)
        }
        if (definition.variadic) {
            val varargs = arguments.drop(expected).joinToString(", ")
            raw["__VA_ARGS__"] = varargs
            expanded["__VA_ARGS__"] = expandText(varargs, disabled, line)
        }
        var replacement = definition.replacement
        val stringized = HashMap<String, String>()
        replacement = Regex("(?<!#)#\\s*([A-Za-z_$][A-Za-z0-9_$]*)").replace(replacement) { match ->
            val parameter = match.groupValues[1]
            val value = raw[parameter] ?: return@replace match.value
            val marker = "__TCC_STRINGIZED_${stringized.size}__"
            stringized[marker] = stringize(value)
            marker
        }
        val hasPaste = replacement.contains("##")
        val parts = replacement.split("##")
        replacement = parts.map { part ->
            substitute(part, if (hasPaste) raw else expanded)
        }.let { substituted ->
            if (hasPaste) substituted.joinToString("") { it.trim() } else substituted.joinToString("")
        }
        stringized.forEach { (marker, value) -> replacement = replacement.replace(marker, value) }
        return expandText(replacement, disabled, line)
    }

    private fun substitute(text: String, values: Map<String, String>): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            if (isIdentifierStart(text[index])) {
                val start = index++
                while (index < text.length && isIdentifierPart(text[index])) index++
                val name = text.substring(start, index)
                append(values[name] ?: name)
            } else {
                append(text[index++])
            }
        }
    }

    private fun builtinValue(name: String, line: Int): String? = options.predefined[name] ?: when (name) {
        "__LINE__" -> line.toString()
        "__FILE__" -> stringize(path?.toString() ?: "<input>")
        "__COUNTER__" -> (counter++).toString()
        "__DATE__" -> stringize(LocalDateTime.now(options.clock).format(DateTimeFormatter.ofPattern("MMM dd yyyy")))
        "__TIME__" -> stringize(LocalDateTime.now(options.clock).format(DateTimeFormatter.ofPattern("HH:mm:ss")))
        "__STDC__", "__TINYC__" -> "1"
        "__STDC_VERSION__" -> "201112L"
        else -> null
    }

    private fun parseDirective(line: String): Directive? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith('#')) return null
        val match = Regex("^#\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*))?$").find(trimmed) ?: return null
        return Directive(match.groupValues[1], match.groupValues.getOrElse(2) { "" }, line)
    }

    private fun readInvocation(text: String, open: Int): Invocation? {
        val arguments = ArrayList<String>()
        var depth = 0
        var start = open + 1
        var index = open + 1
        while (index < text.length) {
            if (text[index] == '\'' || text[index] == '"') {
                index = quotedEnd(text, index)
                continue
            }
            when (text[index]) {
                '(' -> depth++
                ')' -> if (depth-- == 0) {
                    if (index > start || arguments.isNotEmpty()) arguments += text.substring(start, index).trim()
                    return Invocation(index + 1, arguments)
                }
                ',' -> if (depth == 0) {
                    arguments += text.substring(start, index).trim()
                    start = index + 1
                }
            }
            index++
        }
        return null
    }

    private fun quotedEnd(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            if (text[index] == '\\') index += 2
            else if (text[index++] == text[start]) return index
        }
        return text.length
    }

    private fun skipSpaces(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun stringize(value: String): String =
        "\"${value.trim().replace(Regex("\\s+"), " ").replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun isIdentifierStart(character: Char): Boolean = character == '_' || character == '$' || character.isLetter()

    private fun isIdentifierPart(character: Char): Boolean = isIdentifierStart(character) || character.isDigit()

    private fun location(line: Int): SourceLocation = lineMap.locationAt(path, sourceLineOffset(line))

    private fun sourceLineOffset(line: Int): Int {
        if (line <= 1) return 0
        var current = 1
        var offset = 0
        while (current < line && offset < source.length) {
            if (source[offset++] == '\n') current++
        }
        return offset.coerceAtMost(source.length)
    }

    private fun report(line: Int, message: String) = diagnostics.error(location(line), message)

    private data class Directive(val name: String, val body: String, val original: String)
    private data class ProtectedExpression(val text: String, val values: Map<String, Long>)
    private data class Invocation(val end: Int, val arguments: List<String>)

    private data class ConditionalFrame(
        val parentActive: Boolean,
        var active: Boolean,
        var branchTaken: Boolean,
        var elseSeen: Boolean = false,
    )

    private companion object {
        fun ArrayDeque<ConditionalFrame>.isActive(): Boolean = peekLast()?.active ?: true
    }
}

private class IfExpression(private val text: String, private val markerValue: (String) -> Long) {
    private val tokens = tokenize(text)
    private var index = 0

    fun evaluate(): Long = parseOr()

    private fun parseOr(): Long {
        var value = parseAnd()
        while (peek() == "||") {
            take()
            value = if (value != 0L || parseAnd() != 0L) 1 else 0
        }
        return value
    }

    private fun parseAnd(): Long {
        var value = parseBitOr()
        while (peek() == "&&") {
            take()
            value = if (value != 0L && parseBitOr() != 0L) 1 else 0
        }
        return value
    }

    private fun parseBitOr(): Long {
        var value = parseBitXor()
        while (peek() == "|") {
            take()
            value = value or parseBitXor()
        }
        return value
    }

    private fun parseBitXor(): Long {
        var value = parseBitAnd()
        while (peek() == "^") {
            take()
            value = value xor parseBitAnd()
        }
        return value
    }

    private fun parseBitAnd(): Long {
        var value = parseEquality()
        while (peek() == "&") {
            take()
            value = value and parseEquality()
        }
        return value
    }

    private fun parseEquality(): Long = compare(::parseRelational, setOf("==", "!="))
    private fun parseRelational(): Long = compare(::parseShift, setOf("<", "<=", ">", ">="))
    private fun parseShift(): Long {
        var value = parseAdditive()
        while (peek() == "<<" || peek() == ">>") {
            val operator = take()
            val right = parseAdditive()
            value = if (operator == "<<") value shl right.toInt() else value shr right.toInt()
        }
        return value
    }

    private fun parseAdditive(): Long {
        var value = parseMultiplicative()
        while (peek() == "+" || peek() == "-") {
            val operator = take()
            value = if (operator == "+") value + parseMultiplicative() else value - parseMultiplicative()
        }
        return value
    }

    private fun parseMultiplicative(): Long {
        var value = parseUnary()
        while (peek() == "*" || peek() == "/" || peek() == "%") {
            val operator = take()
            val right = parseUnary()
            value = when (operator) {
                "*" -> value * right
                "/" -> if (right == 0L) 0 else value / right
                else -> if (right == 0L) 0 else value % right
            }
        }
        return value
    }

    private fun parseUnary(): Long = when (peek()) {
        "!" -> { take(); if (parseUnary() == 0L) 1 else 0 }
        "~" -> { take(); parseUnary().inv() }
        "+" -> { take(); parseUnary() }
        "-" -> { take(); -parseUnary() }
        else -> parsePrimary()
    }

    private fun parsePrimary(): Long {
        if (peek() == "(") {
            take()
            val value = parseOr()
            if (peek() == ")") take()
            return value
        }
        val value = take()
        if (value == null) return 0
        if (value.startsWith("'")) return value.getOrNull(1)?.code?.toLong() ?: 0
        return value.removeSuffix("u").removeSuffix("U").removeSuffix("l").removeSuffix("L")
            .let { number -> number.toLongOrNull() ?: runCatching { java.math.BigInteger(number.removePrefix("0x"), if (number.startsWith("0x")) 16 else 10).toLong() }.getOrElse { markerValue(value) } }
    }

    private fun compare(parse: () -> Long, operators: Set<String>): Long {
        var value = parse()
        while (peek() in operators) {
            val operator = take()
            val right = parse()
            value = when (operator) {
                "==" -> if (value == right) 1 else 0
                "!=" -> if (value != right) 1 else 0
                "<" -> if (value < right) 1 else 0
                "<=" -> if (value <= right) 1 else 0
                ">" -> if (value > right) 1 else 0
                else -> if (value >= right) 1 else 0
            }
        }
        return value
    }

    private fun peek(): String? = tokens.getOrNull(index)
    private fun take(): String? = tokens.getOrNull(index++)

    private companion object {
        fun tokenize(text: String): List<String> {
            val tokens = ArrayList<String>()
            var index = 0
            while (index < text.length) {
                when {
                    text[index].isWhitespace() -> index++
                    text[index].isDigit() -> {
                        val start = index++
                        while (index < text.length && text[index].isLetterOrDigit()) index++
                        tokens += text.substring(start, index)
                    }
                    text[index].isLetter() || text[index] == '_' -> {
                        val start = index++
                        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
                        tokens += text.substring(start, index)
                    }
                    text[index] == '\'' -> {
                        val start = index++
                        while (index < text.length && text[index++] != '\'') Unit
                        tokens += text.substring(start, index)
                    }
                    index + 1 < text.length && text.substring(index, index + 2) in setOf("||", "&&", "==", "!=", "<=", ">=", "<<", ">>") -> {
                        tokens += text.substring(index, index + 2)
                        index += 2
                    }
                    else -> tokens += text[index++].toString()
                }
            }
            return tokens
        }
    }
}
