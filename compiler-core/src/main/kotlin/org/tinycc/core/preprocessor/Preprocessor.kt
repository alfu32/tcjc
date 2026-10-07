package org.tinycc.core.preprocessor

import java.nio.file.Path
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.io.IOException
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.IncludeStack
import org.tinycc.core.diagnostics.LineMap
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.io.SourceFile
import org.tinycc.core.io.SourceFileLoader

data class PreprocessorOptions(
    val predefined: Map<String, String> = emptyMap(),
    val clock: Clock = Clock.systemUTC(),
    val includePaths: List<Path> = emptyList(),
    val systemIncludePaths: List<Path> = emptyList(),
    val sourceLoader: SourceFileLoader = SourceFileLoader(),
)

data class PreprocessedSource(
    val text: String,
    val macros: List<MacroDefinition>,
    val pragmas: List<PreprocessorPragma> = emptyList(),
)

/** Handles macro definitions, expansion, and conditional compilation. */
class Preprocessor private constructor(
    private val source: String,
    private val path: Path? = null,
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val options: PreprocessorOptions = PreprocessorOptions(),
    private val state: SharedState = SharedState(),
    private val includeSearchIndex: Int = -1,
) {
    private val lineMap = LineMap(source)
    private var lineDelta = 0
    private var logicalFile: String? = null

    constructor(
        source: String,
        path: Path? = null,
        diagnostics: DiagnosticEngine = DiagnosticEngine(),
        options: PreprocessorOptions = PreprocessorOptions(),
    ) : this(source, path, diagnostics, options, SharedState(), -1)

    constructor(
        sourceFile: SourceFile,
        diagnostics: DiagnosticEngine = DiagnosticEngine(),
        options: PreprocessorOptions = PreprocessorOptions(),
    ) : this(sourceFile.text, sourceFile.path, diagnostics, options)

    fun process(): PreprocessedSource {
        val normalizedPath = path?.toAbsolutePath()?.normalize()
        if (normalizedPath != null && !state.activeFiles.add(normalizedPath)) {
            report(1, "recursive include of '$normalizedPath'")
            return PreprocessedSource("", state.macros.snapshot(), state.pragmas.toList())
        }
        try {
            return processUnit()
        } finally {
            if (normalizedPath != null) state.activeFiles.remove(normalizedPath)
        }
    }

    private fun processUnit(): PreprocessedSource {
        val lines = stripComments(source.replace("\r\n", "\n").replace('\r', '\n')).split('\n')
        val output = StringBuilder(source.length)
        val conditionals = ArrayDeque<ConditionalFrame>()
        val pending = StringBuilder()
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val physicalLine = lineIndex + 1
            val logicalLine = physicalLine + lineDelta
            var line = lines[lineIndex]
            while (line.endsWith("\\") && lineIndex + 1 < lines.size) {
                line = line.dropLast(1) + lines[++lineIndex]
            }
            val directive = parseDirective(line)
            if (directive != null) {
                val conditionalDirective = directive.name in setOf("if", "ifdef", "ifndef", "elif", "else", "endif")
                if (pending.isNotEmpty() && !conditionalDirective) {
                    output.append(expandText(pending.toString(), emptySet(), logicalLine))
                    pending.clear()
                }
                processDirective(directive, logicalLine, physicalLine, conditionals, output)
            } else if (conditionals.isActive()) {
                pending.append(line)
                if (lineIndex < lines.lastIndex) pending.append('\n')
                if (balancedParentheses(pending.toString()) && !endsWithFunctionMacroName(line)) {
                    output.append(expandText(pending.toString(), emptySet(), logicalLine))
                    pending.clear()
                }
            }
            lineIndex++
        }
        if (pending.isNotEmpty()) output.append(expandText(pending.toString(), emptySet(), lines.size))
        if (conditionals.isNotEmpty()) {
            report(1, "unterminated conditional directive")
        }
        return PreprocessedSource(output.toString(), state.macros.snapshot(), state.pragmas.toList())
    }

    private fun processDirective(
        directive: Directive,
        logicalLine: Int,
        physicalLine: Int,
        conditionals: ArrayDeque<ConditionalFrame>,
        output: StringBuilder,
    ) {
        when (directive.name) {
            "if" -> {
                val parentActive = conditionals.isActive()
                val branch = parentActive && evaluate(directive.body, logicalLine)
                conditionals.addLast(ConditionalFrame(parentActive, branch, branch))
            }
            "ifdef", "ifndef" -> {
                val parentActive = conditionals.isActive()
                val defined = isDefined(directive.body.trim())
                val branch = parentActive && if (directive.name == "ifdef") defined else !defined
                conditionals.addLast(ConditionalFrame(parentActive, branch, branch))
            }
            "elif" -> {
                val frame = conditionals.peekLast()
                if (frame == null) {
                    report(physicalLine, "#elif without matching #if")
                } else if (frame.elseSeen) {
                    report(physicalLine, "#elif after #else")
                } else {
                    frame.active = frame.parentActive && !frame.branchTaken && evaluate(directive.body, logicalLine)
                    frame.branchTaken = frame.branchTaken || frame.active
                }
            }
            "else" -> {
                val frame = conditionals.peekLast()
                if (frame == null) {
                    report(physicalLine, "#else without matching #if")
                } else if (frame.elseSeen) {
                    report(physicalLine, "duplicate #else")
                } else {
                    frame.elseSeen = true
                    frame.active = frame.parentActive && !frame.branchTaken
                    frame.branchTaken = true
                }
            }
            "endif" -> {
                if (conditionals.pollLast() == null) report(physicalLine, "#endif without matching #if")
            }
            else -> if (conditionals.isActive()) when (directive.name) {
                "define" -> define(directive.body, physicalLine)
                "undef" -> state.macros.undef(directive.body.trim())
                "include", "include_next" -> include(directive.body, directive.name == "include_next", physicalLine, output)
                "pragma" -> pragma(directive.body, physicalLine)
                "line" -> lineDirective(directive.body, logicalLine, physicalLine)
                "error" -> report(physicalLine, directive.body.trim().ifEmpty { "#error" })
                "warning" -> diagnostics.warning(location(physicalLine), directive.body.trim().ifEmpty { "#warning" }, state.includeStack.snapshot())
                else -> Unit
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
            val lastParameter = parameters.lastOrNull()
            val variadic = lastParameter == "..." || lastParameter?.endsWith("...") == true
            val variadicName = when {
                lastParameter == "..." -> "__VA_ARGS__"
                lastParameter?.endsWith("...") == true -> lastParameter.removeSuffix("...").trim()
                else -> null
            }
            val normalized = if (variadic) parameters.dropLast(1) else parameters
            if (normalized.any { !Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(it) }) {
                report(line, "invalid macro parameter list")
                return
            }
            val definition = MacroDefinition(name, normalized, normalizeReplacement(tail.substring(close + 1).trim()), variadic, variadicName)
            val previous = state.macros.define(definition)
            if (previous != null && previous != definition) diagnostics.warning(location(line), "$name redefined", state.includeStack.snapshot())
        } else {
            val definition = MacroDefinition(name, null, normalizeReplacement(tail.trim()))
            val previous = state.macros.define(definition)
            if (previous != null && previous != definition) diagnostics.warning(location(line), "$name redefined", state.includeStack.snapshot())
        }
    }

    private fun include(body: String, next: Boolean, line: Int, output: StringBuilder) {
        val expanded = expandText(body.trim(), emptySet(), line).trim()
        val angled = expanded.startsWith('<') && expanded.endsWith('>')
        val quoted = expanded.startsWith('"') && expanded.endsWith('"')
        if ((!angled && !quoted) || expanded.length < 2) {
            report(line, "#include expects \"FILENAME\" or <FILENAME>")
            return
        }
        val filename = expanded.substring(1, expanded.length - 1)
        val resolved = resolveInclude(filename, !angled, next)
        if (resolved == null) {
            report(line, "include file '$filename' not found")
            return
        }
        val normalized = resolved.path.toAbsolutePath().normalize()
        if (normalized in state.onceFiles) return
        try {
            val sourceFile = options.sourceLoader.read(normalized)
            val child = Preprocessor(sourceFile.text, sourceFile.path, diagnostics, options, state, resolved.searchIndex)
            state.includeStack.withFrame(sourceFile.path, location(line)) {
                output.append(child.process().text)
            }
            if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
        } catch (error: IOException) {
            report(line, "unable to read include file '$filename': ${error.message ?: "I/O error"}")
        }
    }

    private fun resolveInclude(filename: String, quoted: Boolean, next: Boolean): ResolvedInclude? {
        val candidate = Path.of(filename)
        if (candidate.isAbsolute()) return if (options.sourceLoader.exists(candidate)) ResolvedInclude(candidate, -1) else null
        val search = ArrayList<Path>()
        if (quoted) path?.parent?.let(search::add)
        search += options.includePaths
        search += options.systemIncludePaths
        val start = if (next) (includeSearchIndex + 1).coerceAtLeast(0) else 0
        for (index in start until search.size) {
            val resolved = search[index].resolve(filename)
            if (options.sourceLoader.exists(resolved)) return ResolvedInclude(resolved, index)
        }
        return null
    }

    private fun pragma(body: String, line: Int) {
        val trimmed = body.trim()
        when {
            trimmed == "once" -> path?.toAbsolutePath()?.normalize()?.let(state.onceFiles::add)
            trimmed.startsWith("push_macro") -> pragmaMacro(trimmed, line, push = true)
            trimmed.startsWith("pop_macro") -> pragmaMacro(trimmed, line, push = false)
            trimmed.startsWith("pack") -> pragmaPack(trimmed, line)
            trimmed.startsWith("comment") -> pragmaComment(trimmed, line)
            else -> diagnostics.warning(location(line), "#pragma $trimmed ignored", state.includeStack.snapshot())
        }
    }

    private fun pragmaPack(text: String, line: Int) {
        val body = Regex("^pack\\s*\\((.*)\\)$").find(text)?.groupValues?.get(1)?.trim()
        if (body == null || body.isEmpty()) {
            if (body == null) report(line, "malformed #pragma pack directive")
            else state.pragmas += PreprocessorPragma.Pack(PackAction.RESET)
            return
        }
        when {
            body == "pop" -> state.pragmas += PreprocessorPragma.Pack(PackAction.POP)
            body == "push" -> state.pragmas += PreprocessorPragma.Pack(PackAction.PUSH)
            body.startsWith("push,") -> {
                val alignment = body.substringAfter(',').trim().toIntOrNull()
                if (!validPackAlignment(alignment)) report(line, "invalid #pragma pack alignment")
                else state.pragmas += PreprocessorPragma.Pack(PackAction.PUSH_SET, alignment)
            }
            else -> {
                val alignment = body.toIntOrNull()
                if (!validPackAlignment(alignment)) report(line, "invalid #pragma pack alignment")
                else state.pragmas += PreprocessorPragma.Pack(PackAction.SET, alignment)
            }
        }
    }

    private fun pragmaComment(text: String, line: Int) {
        val match = Regex("^comment\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*,\\s*\\\"([^\\\"]*)\\\"\\s*\\)$").find(text)
        if (match == null) {
            report(line, "malformed #pragma comment directive")
            return
        }
        val kind = match.groupValues[1]
        val value = match.groupValues[2]
        when (kind) {
            "lib" -> state.pragmas += PreprocessorPragma.Library(value)
            "option" -> state.pragmas += PreprocessorPragma.Option(value)
            else -> state.pragmas += PreprocessorPragma.Comment(kind, value)
        }
    }

    private fun validPackAlignment(value: Int?): Boolean =
        value != null && value in 1..16 && value and (value - 1) == 0

    private fun pragmaMacro(text: String, line: Int, push: Boolean) {
        val match = Regex("^[a-z_]+\\s*\\(\\s*\"([^\"]+)\"\\s*\\)$").find(text)
        if (match == null) {
            report(line, "malformed #pragma macro directive")
        } else if (push) {
            state.macros.push(match.groupValues[1])
        } else if (!state.macros.pop(match.groupValues[1])) {
            report(line, "unbalanced #pragma pop_macro")
        }
    }

    private fun lineDirective(body: String, logicalLine: Int, physicalLine: Int) {
        val match = Regex("^(\\d+)(?:\\s+\"([^\"]*)\")?$").find(expandText(body.trim(), emptySet(), logicalLine).trim())
        if (match == null) {
            report(physicalLine, "invalid #line directive")
            return
        }
        lineDelta = match.groupValues[1].toInt() - (physicalLine + 1)
        logicalFile = match.groupValues[2].ifEmpty { logicalFile }
    }

    private fun evaluate(expression: String, line: Int): Boolean {
        val withIncludeQueries = expandIncludeQueries(expression, line)
        val protected = protectDefined(withIncludeQueries)
        val expanded = expandText(protected.text, emptySet(), line)
        val expandedProtected = protectDefined(expanded)
        val values = protected.values + expandedProtected.values
        return IfExpression(expandedProtected.text) { name -> values[name] ?: 0L }.evaluate().signum() != 0
    }

    private fun isDefined(name: String): Boolean =
        state.macros[name] != null || name == "__has_include" || name == "__has_include_next"

    private fun expandIncludeQueries(expression: String, line: Int): String =
        Regex("__has_include(_next)?\\s*\\(\\s*([<\\\"][^>\\\"]+[>\\\"])\\s*\\)").replace(expression) { match ->
            val operand = expandText(match.groupValues[2], emptySet(), line).trim()
            val angled = operand.startsWith('<') && operand.endsWith('>')
            val quoted = operand.startsWith('"') && operand.endsWith('"')
            if (!angled && !quoted) return@replace "0"
            val filename = operand.substring(1, operand.length - 1)
            val found = resolveInclude(filename, quoted, match.groupValues[1] == "_next") != null
            if (found) "1" else "0"
        }

    private fun protectDefined(expression: String): ProtectedExpression {
        val values = HashMap<String, Long>()
        val result = Regex("\\bdefined\\s*(?:\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)|([A-Za-z_$][A-Za-z0-9_$]*))")
            .replace(expression) { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val marker = "__TCC_DEFINED_${values.size}__"
                values[marker] = if (isDefined(name)) 1 else 0
                marker
            }
        return ProtectedExpression(result, values)
    }

    private fun expandText(text: String, disabled: Set<String>, line: Int): String =
        renderTokens(expandTokens(tokenize(text).map { it.copy(blocked = disabled) }, line)) +
            text.takeLastWhile(Char::isWhitespace)

    private fun expandTokens(input: List<PpToken>, line: Int): List<PpToken> {
        val result = ArrayList<PpToken>(input.size)
        var index = 0
        while (index < input.size) {
            val token = input[index]
            if (token.kind != PpTokenKind.IDENT || token.text in token.blocked) {
                result += token
                index++
                continue
            }
            val name = token.text
            if (name == "defined") {
                val operandEnd = if (input.getOrNull(index + 1)?.text == "(") {
                    readTokenInvocation(input, index + 1)?.end ?: index + 2
                } else {
                    index + 2
                }
                if (operandEnd > index + 1) {
                    val macroNames = state.macros.snapshot().mapTo(HashSet(), MacroDefinition::name)
                    result += input.subList(index, operandEnd).map { item ->
                        if (item !== token && item.kind == PpTokenKind.IDENT) item.copy(blocked = item.blocked + macroNames) else item
                    }
                    index = operandEnd
                    continue
                }
            }
            val definition = state.macros[name]
            if (definition == null) {
                val builtin = builtinValue(name, line)
                if (builtin == null) {
                    result += token
                } else {
                    result += tokenize(builtin).map { it.copy(leading = token.leading + it.leading, blocked = token.blocked + name) }
                }
                index++
                continue
            }
            if (definition.parameters == null) {
                val replacement = tokenize(definition.replacement).map {
                    it.copy(blocked = it.blocked + token.blocked + name)
                }.withLeading(token.leading)
                return result + expandTokens(replacement + input.subList(index + 1, input.size), line)
            }
            val open = input.getOrNull(index + 1)
            if (open?.text != "(") {
                result += token
                index++
                continue
            }
            val invocation = readTokenInvocation(input, index + 1)
            if (invocation == null) {
                result += token
                index++
                continue
            }
            val replacement = substituteMacro(definition, invocation.arguments, line).withLeading(token.leading)
            val blockedReplacement = replacement.map { it.copy(blocked = it.blocked + (token.blocked - it.text) + name) }
            return result + expandTokens(blockedReplacement + input.subList(invocation.end, input.size), line)
        }
        return result
    }

    private fun substituteMacro(
        definition: MacroDefinition,
        arguments: List<List<PpToken>>,
        line: Int,
    ): List<PpToken> {
        val parameters = definition.parameters ?: return tokenize(definition.replacement)
        val normalizedArguments = when {
            parameters.isEmpty() && arguments.size == 1 && arguments[0].isEmpty() -> emptyList()
            arguments.isEmpty() && parameters.isNotEmpty() -> listOf(emptyList())
            else -> arguments
        }
        val expected = parameters.size
        if ((!definition.variadic && normalizedArguments.size != expected) ||
            (definition.variadic && normalizedArguments.size < expected)
        ) {
            report(line, "macro '${definition.name}' expects ${if (definition.variadic) "at least " else ""}$expected argument(s)")
            return listOf(PpToken(definition.name, PpTokenKind.IDENT))
        }
        val replacement = tokenize(definition.replacement)
        val raw = LinkedHashMap<String, List<PpToken>>()
        val expanded = LinkedHashMap<String, List<PpToken>>()
        fun usesRaw(parameter: String): Boolean = replacement.withIndex().any { (index, token) ->
            token.kind == PpTokenKind.IDENT && token.text == parameter &&
                (replacement.getOrNull(index - 1)?.text == "#" || replacement.getOrNull(index - 1)?.text == "##" ||
                    replacement.getOrNull(index + 1)?.text == "##")
        }
        parameters.forEachIndexed { index, parameter ->
            val argument = normalizedArguments.getOrElse(index) { emptyList() }
            raw[parameter] = argument
            expanded[parameter] = if (usesRaw(parameter)) argument else expandTokens(argument, line)
        }
        if (definition.variadic) {
            val varargs = normalizedArguments.drop(expected).flatMapIndexed { index, argument ->
                if (index == 0) argument else listOf(PpToken(",", PpTokenKind.OP)) + argument
            }
            raw["__VA_ARGS__"] = varargs
            expanded["__VA_ARGS__"] = if (usesRaw("__VA_ARGS__")) varargs else expandTokens(varargs, line)
            definition.variadicName?.let { name ->
                raw[name] = varargs
                expanded[name] = if (usesRaw(name)) varargs else expanded.getValue("__VA_ARGS__")
            }
        }

        val substituted = ArrayList<PpToken>()
        var index = 0
        while (index < replacement.size) {
            val token = replacement[index]
            if (token.text == "#" && replacement.getOrNull(index + 1)?.text in raw) {
                val parameter = replacement[index + 1].text
                substituted += PpToken(stringizeTokens(raw.getValue(parameter)), PpTokenKind.STRING, token.leading)
                index += 2
                continue
            }
            if (token.text == "##") {
                substituted += token
                index++
                continue
            }
            val value = if (token.kind == PpTokenKind.IDENT && token.text in raw) {
                val usesPaste = replacement.getOrNull(index - 1)?.text == "##" ||
                    replacement.getOrNull(index + 1)?.text == "##"
                if (usesPaste) raw.getValue(token.text) else expanded.getValue(token.text)
            } else {
                listOf(token)
            }
            if (value.isEmpty()) {
                substituted += PpToken.empty(token.leading)
            } else {
                substituted += value.map { it.copy(pasteOperator = false) }.withParameterLeading(token.leading)
            }
            index++
        }
        return pasteTokens(substituted)
    }

    private fun pasteTokens(tokens: List<PpToken>): List<PpToken> {
        val result = ArrayList<PpToken>()
        var index = 0
        while (index < tokens.size) {
            if (!tokens[index].pasteOperator) {
                result += tokens[index]
                index++
                continue
            }
            val left = result.removeLastOrNull() ?: PpToken.empty()
            val right = tokens.getOrNull(index + 1) ?: PpToken.empty()
            if (left.isEmpty && right.isEmpty) {
                // Both placemarkers disappear.
            } else if (right.isEmpty && left.text == ",") {
                // GNU comma elision for an empty variadic argument.
            } else if (left.isEmpty) {
                result += right.copy(leading = left.leading + right.leading)
            } else if (left.text == ",") {
                result += left
                result += right.copy(leading = "")
            } else if (right.isEmpty) {
                result += left
            } else {
                result += left.copy(text = left.text + right.text)
            }
            index += 2
        }
        return result.filterNot(PpToken::isEmpty)
    }

    private fun followFunctionInvocation(
        expanded: List<PpToken>,
        input: List<PpToken>,
        nextIndex: Int,
        line: Int,
    ): FollowedInvocation? {
        val name = expanded.lastOrNull()?.takeIf { it.kind == PpTokenKind.IDENT }?.text ?: return null
        if (state.macros[name]?.parameters == null || input.getOrNull(nextIndex)?.text != "(") return null
        val invocation = readTokenInvocation(input, nextIndex) ?: return null
        val combined = expanded + input.subList(nextIndex, invocation.end)
        return FollowedInvocation(expandTokens(combined, line), invocation.end)
    }

    private fun readTokenInvocation(input: List<PpToken>, openIndex: Int): TokenInvocation? {
        if (input.getOrNull(openIndex)?.text != "(") return null
        val arguments = ArrayList<List<PpToken>>()
        var depth = 0
        var start = openIndex + 1
        var index = start
        while (index < input.size) {
            when (input[index].text) {
                "(" -> depth++
                ")" -> if (depth-- == 0) {
                    if (index > start || arguments.isNotEmpty()) arguments += input.subList(start, index)
                    return TokenInvocation(index + 1, arguments)
                }
                "," -> if (depth == 0) {
                    arguments += input.subList(start, index)
                    start = index + 1
                }
            }
            index++
        }
        return null
    }

    private fun stringizeTokens(tokens: List<PpToken>): String {
        val body = tokens.joinToString("") {
            val text = if (it.kind == PpTokenKind.STRING || it.kind == PpTokenKind.CHAR) {
                it.text.replace("\\", "\\\\").replace("\"", "\\\"")
            } else {
                it.text
            }
            it.leading + text
        }.trim().replace(Regex("\\s+"), " ")
        return "\"$body\""
    }

    private fun renderTokens(tokens: List<PpToken>): String = buildString {
        tokens.forEachIndexed { index, token ->
            val previous = tokens.getOrNull(index - 1)
            if (previous != null && token.leading.isEmpty() && needsSeparator(previous.text, token.text)) append(' ')
            append(token.leading)
            append(token.text)
        }
    }

    private fun needsSeparator(left: String, right: String): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        if ((left.last().isLetterOrDigit() || left.last() == '_' || left.last() == '$') &&
            (right.first().isLetterOrDigit() || right.first() == '_' || right.first() == '$')) return true
        if (left.firstOrNull()?.let { it in "+-*/%&|^<>=!" } == true &&
            right.firstOrNull()?.let { it in "+-*/%&|^<>=!" } == true
        ) {
            return tokenize(left + right).map(PpToken::text) != listOf(left, right)
        }
        return false
    }

    private fun tokenize(text: String): List<PpToken> {
        val result = ArrayList<PpToken>()
        var index = 0
        var leading = ""
        while (index < text.length) {
            if (text[index].isWhitespace()) {
                leading += text[index++]
                continue
            }
            val start = index
            val kind: PpTokenKind
            when {
                text[index] == '"' || text[index] == '\'' -> {
                    index = quotedEnd(text, index)
                    kind = if (text[start] == '"') PpTokenKind.STRING else PpTokenKind.CHAR
                }
                isIdentifierStart(text[index]) -> {
                    index++
                    while (index < text.length && isIdentifierPart(text[index])) index++
                    kind = PpTokenKind.IDENT
                }
                text[index].isDigit() || (text[index] == '.' && text.getOrNull(index + 1)?.isDigit() == true) -> {
                    index++
                    while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '.' || text[index] == '_')) index++
                    kind = PpTokenKind.NUMBER
                }
                else -> {
                    val operator = PP_OPERATORS.firstOrNull { text.startsWith(it, index) } ?: text[index].toString()
                    index += operator.length
                    kind = PpTokenKind.OP
                }
            }
            result += PpToken(text.substring(start, index), kind, leading)
            leading = ""
        }
        return result
    }

    private fun expandFunction(
        definition: MacroDefinition,
        arguments: List<String>,
        disabled: Set<String>,
        line: Int,
    ): String {
        val parameters = definition.parameters ?: return definition.replacement
        val normalizedArguments = if (parameters.isEmpty() && arguments.size == 1 && arguments[0].isEmpty()) {
            emptyList()
        } else {
            arguments
        }
        val expected = parameters.size
        if ((!definition.variadic && normalizedArguments.size != expected) || (definition.variadic && normalizedArguments.size < expected)) {
            report(line, "macro '${definition.name}' expects ${if (definition.variadic) "at least " else ""}$expected argument(s)")
            return definition.name
        }
        val raw = LinkedHashMap<String, String>()
        val expanded = LinkedHashMap<String, String>()
        parameters.forEachIndexed { index, parameter ->
            val value = normalizedArguments.getOrElse(index) { "" }
            raw[parameter] = value
            expanded[parameter] = expandText(value, disabled, line)
        }
        if (definition.variadic) {
            val varargs = normalizedArguments.drop(expected).joinToString(", ")
            raw["__VA_ARGS__"] = varargs
            expanded["__VA_ARGS__"] = expandText(varargs, disabled, line)
            definition.variadicName?.let { name ->
                raw[name] = varargs
                expanded[name] = expanded["__VA_ARGS__"].orEmpty()
            }
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
        replacement = substituteReplacement(replacement, raw, expanded)
        stringized.forEach { (marker, value) -> replacement = replacement.replace(marker, value) }
        val argumentMacros = raw.values
            .flatMap { Regex("[A-Za-z_$][A-Za-z0-9_$]*").findAll(it).map(MatchResult::value).toList() }
            .filter { state.macros[it] != null }
            .toSet()
        return expandText(replacement, disabled + argumentMacros, line)
    }

    private fun substituteReplacement(
        replacement: String,
        raw: Map<String, String>,
        expanded: Map<String, String>,
    ): String {
        val parts = replacement.split("##")
        if (parts.size == 1) return substituteIdentifiers(parts.single(), expanded)
        val substituted = parts.mapIndexed { index, part ->
            val rawNames = buildSet {
                if (index > 0) boundaryParameter(part, fromEnd = false)?.let(::add)
                if (index < parts.lastIndex) boundaryParameter(part, fromEnd = true)?.let(::add)
            }
            substituteIdentifiers(part, raw, expanded, rawNames).trim()
        }.toMutableList()
        val result = StringBuilder()
        substituted.forEachIndexed { index, part ->
            if (index > 0 && part.isEmpty() && result.trimEnd().endsWith(",")) {
                while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.lastIndex)
                if (result.lastOrNull() == ',') result.deleteCharAt(result.lastIndex)
                while (result.isNotEmpty() && result.last().isWhitespace()) result.deleteCharAt(result.lastIndex)
            }
            result.append(part)
        }
        return result.toString()
    }

    private fun substituteIdentifiers(text: String, values: Map<String, String>): String =
        substituteIdentifiers(text, values, values, emptySet())

    private fun substituteIdentifiers(
        text: String,
        raw: Map<String, String>,
        expanded: Map<String, String>,
        rawNames: Set<String>,
    ): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            if (text[index] == '/' && text.getOrNull(index + 1) == '*') {
                val end = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }
                append(text.substring(index, end))
                index = end
            } else if (text[index] == '/' && text.getOrNull(index + 1) == '/') {
                append(text.substring(index))
                break
            } else if (text[index] == '\'' || text[index] == '"') {
                val end = quotedEnd(text, index)
                append(text.substring(index, end))
                index = end
            } else if (isIdentifierStart(text[index])) {
                val start = index++
                while (index < text.length && isIdentifierPart(text[index])) index++
                val name = text.substring(start, index)
                append(if (name in rawNames) raw[name] ?: name else expanded[name] ?: name)
            } else {
                append(text[index++])
            }
        }
    }

    private fun boundaryParameter(text: String, fromEnd: Boolean): String? {
        val match = if (fromEnd) {
            Regex("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$").find(text)
        } else {
            Regex("^\\s*([A-Za-z_$][A-Za-z0-9_$]*)").find(text)
        }
        return match?.groupValues?.get(1)
    }

    private fun builtinValue(name: String, line: Int): String? = options.predefined[name] ?: when (name) {
        "__LINE__" -> line.toString()
        "__FILE__" -> stringize(logicalFile ?: path?.fileName?.toString() ?: "<input>")
        "__COUNTER__" -> (state.counter++).toString()
        "__DATE__" -> stringize(LocalDateTime.now(options.clock).format(DateTimeFormatter.ofPattern("MMM dd yyyy")))
        "__TIME__" -> stringize(LocalDateTime.now(options.clock).format(DateTimeFormatter.ofPattern("HH:mm:ss")))
        "__STDC__", "__TINYC__" -> "1"
        "__STDC_VERSION__" -> "201112L"
        else -> null
    }

    private fun parseDirective(line: String): Directive? {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith('#')) return null
        val match = Regex("^#\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s+(.*))?$").find(trimmed)
            ?: return Directive("", "", line)
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

    private fun normalizeReplacement(value: String): String =
        value.replace(Regex("(^|\\s)#\\s*##\\s*#(?=\\s|$)"), "$1##")

    private fun stripComments(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            when {
                text[index] == '"' || text[index] == '\'' -> {
                    val end = quotedEnd(text, index)
                    append(text, index, end)
                    index = end
                }
                text[index] == '/' && text.getOrNull(index + 1) == '/' -> {
                    append(' ')
                    index += 2
                    while (index < text.length && text[index] != '\n') index++
                }
                text[index] == '/' && text.getOrNull(index + 1) == '*' -> {
                    append(' ')
                    index += 2
                    while (index < text.length) {
                        if (text[index] == '*' && text.getOrNull(index + 1) == '/') {
                            index += 2
                            break
                        }
                        if (text[index] == '\n') append('\n')
                        index++
                    }
                }
                else -> append(text[index++])
            }
        }
    }

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

    private fun report(line: Int, message: String) = diagnostics.error(location(line), message, state.includeStack.snapshot())

    private fun balancedParentheses(text: String): Boolean {
        var depth = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\'', '"' -> index = quotedEnd(text, index)
                '(' -> depth++
                ')' -> depth--
            }
            index++
        }
        return depth <= 0
    }

    private fun endsWithFunctionMacroName(text: String): Boolean {
        val name = Regex("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$").find(text)?.groupValues?.get(1) ?: return false
        return state.macros[name]?.parameters != null
    }

    private data class Directive(val name: String, val body: String, val original: String)
    private data class ProtectedExpression(val text: String, val values: Map<String, Long>)
    private data class Invocation(val end: Int, val arguments: List<String>)
    private data class TokenInvocation(val end: Int, val arguments: List<List<PpToken>>)
    private data class FollowedInvocation(val tokens: List<PpToken>, val end: Int)

    private data class ResolvedInclude(val path: Path, val searchIndex: Int)

    private data class PpToken(
        val text: String,
        val kind: PpTokenKind,
        val leading: String = "",
        val blocked: Set<String> = emptySet(),
        val isEmpty: Boolean = false,
        val pasteOperator: Boolean = text == "##",
    ) {
        companion object {
            fun empty(leading: String = "") = PpToken("", PpTokenKind.OTHER, leading, isEmpty = true, pasteOperator = false)
        }
    }

    private enum class PpTokenKind { IDENT, NUMBER, STRING, CHAR, OP, OTHER }

    private fun List<PpToken>.withLeading(leading: String): List<PpToken> =
        if (isEmpty()) this else mapIndexed { index, token ->
            if (index == 0) token.copy(leading = leading + token.leading) else token
        }

    private fun List<PpToken>.withParameterLeading(leading: String): List<PpToken> =
        if (isEmpty()) this else mapIndexed { index, token ->
            if (index == 0) token.copy(leading = leading) else token
        }

    private class SharedState {
        val macros = MacroTable()
        var counter = 0L
        val onceFiles = HashSet<Path>()
        val activeFiles = HashSet<Path>()
        val includeStack = IncludeStack()
        val pragmas = ArrayList<PreprocessorPragma>()
    }

    private data class ConditionalFrame(
        val parentActive: Boolean,
        var active: Boolean,
        var branchTaken: Boolean,
        var elseSeen: Boolean = false,
    )

    private companion object {
        val PP_OPERATORS = listOf(
            "##", "...", ">>=", "<<=", "->", "++", "--", "&&", "||", "==", "!=",
            "<=", ">=", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        )

        fun ArrayDeque<ConditionalFrame>.isActive(): Boolean = peekLast()?.active ?: true
    }
}

private class IfExpression(private val text: String, private val markerValue: (String) -> Long) {
    private val tokens = tokenize(text)
    private var index = 0

    private data class Value(val number: java.math.BigInteger, val unsigned: Boolean = false) {
        fun normalizedUnsigned(): java.math.BigInteger = number.mod(TWO_TO_64)

        companion object {
            val ZERO = Value(java.math.BigInteger.ZERO)
            val ONE = Value(java.math.BigInteger.ONE)
            val TWO_TO_64 = java.math.BigInteger.ONE.shiftLeft(64)
        }
    }

    fun evaluate(): java.math.BigInteger = parseConditional().number

    private fun parseConditional(): Value {
        val condition = parseOr()
        if (peek() != "?") return condition
        take()
        val whenTrue = parseConditional()
        if (peek() == ":") take()
        val whenFalse = parseConditional()
        return if (condition.number.signum() != 0) whenTrue else whenFalse
    }

    private fun parseOr(): Value {
        var value = parseAnd()
        while (peek() == "||") {
            take()
            val right = parseAnd()
            value = if (value.number.signum() != 0 || right.number.signum() != 0) Value.ONE else Value.ZERO
        }
        return value
    }

    private fun parseAnd(): Value {
        var value = parseBitOr()
        while (peek() == "&&") {
            take()
            val right = parseBitOr()
            value = if (value.number.signum() != 0 && right.number.signum() != 0) Value.ONE else Value.ZERO
        }
        return value
    }

    private fun parseBitOr(): Value {
        var value = parseBitXor()
        while (peek() == "|") {
            take()
            val right = parseBitXor()
            value = Value(value.number.or(right.number), value.unsigned || right.unsigned)
        }
        return value
    }

    private fun parseBitXor(): Value {
        var value = parseBitAnd()
        while (peek() == "^") {
            take()
            val right = parseBitAnd()
            value = Value(value.number.xor(right.number), value.unsigned || right.unsigned)
        }
        return value
    }

    private fun parseBitAnd(): Value {
        var value = parseEquality()
        while (peek() == "&") {
            take()
            val right = parseEquality()
            value = Value(value.number.and(right.number), value.unsigned || right.unsigned)
        }
        return value
    }

    private fun parseEquality(): Value = compare(::parseRelational, setOf("==", "!="))
    private fun parseRelational(): Value = compare(::parseShift, setOf("<", "<=", ">", ">="))

    private fun parseShift(): Value {
        var value = parseAdditive()
        while (peek() == "<<" || peek() == ">>") {
            val operator = take()
            val shift = parseAdditive().number.toInt()
            val shifted = if (operator == "<<") value.number.shiftLeft(shift) else {
                if (value.unsigned) value.normalizedUnsigned().shiftRight(shift) else value.number.shiftRight(shift)
            }
            value = Value(if (!value.unsigned && operator == "<<") signed64(shifted) else shifted, value.unsigned)
        }
        return value
    }

    private fun parseAdditive(): Value {
        var value = parseMultiplicative()
        while (peek() == "+" || peek() == "-") {
            val operator = take()
            val right = parseMultiplicative()
            value = Value(if (operator == "+") value.number + right.number else value.number - right.number, value.unsigned || right.unsigned)
        }
        return value
    }

    private fun parseMultiplicative(): Value {
        var value = parseUnary()
        while (peek() == "*" || peek() == "/" || peek() == "%") {
            val operator = take()
            val right = parseUnary()
            val zero = right.number.signum() == 0
            value = Value(
                when (operator) {
                    "*" -> value.number * right.number
                    "/" -> if (zero) java.math.BigInteger.ZERO else value.number.divide(right.number)
                    else -> if (zero) java.math.BigInteger.ZERO else value.number.remainder(right.number)
                },
                value.unsigned || right.unsigned,
            )
        }
        return value
    }

    private fun parseUnary(): Value = when (peek()) {
        "!" -> { take(); if (parseUnary().number.signum() == 0) Value.ONE else Value.ZERO }
        "~" -> { take(); val value = parseUnary(); Value(value.number.not(), value.unsigned) }
        "+" -> { take(); parseUnary() }
        "-" -> {
            take()
            val value = parseUnary()
            if (value.unsigned) Value(value.number.negate().mod(Value.TWO_TO_64), true) else Value(value.number.negate())
        }
        else -> parsePrimary()
    }

    private fun parsePrimary(): Value {
        if (peek() == "(") {
            take()
            val value = parseConditional()
            if (peek() == ")") take()
            return value
        }
        val token = take() ?: return Value.ZERO
        if (token.startsWith("'")) return Value(characterValue(token).toBigInteger())
        val unsignedSuffix = Regex("(?i)(?:ull|llu|ul|lu|ll|u|l)+$").find(token)?.value?.contains('u', ignoreCase = true) == true
        val number = token.replace(Regex("(?i)(ull|llu|ul|lu|ll|u|l)+$"), "")
        return runCatching {
            val normalized = number.lowercase()
            val parsed = when {
                normalized.startsWith("0x") -> java.math.BigInteger(normalized.substring(2), 16)
                normalized.startsWith("0b") -> java.math.BigInteger(normalized.substring(2), 2)
                normalized.length > 1 && normalized.startsWith('0') -> java.math.BigInteger(normalized.substring(1), 8)
                else -> java.math.BigInteger(number)
            }
            Value(parsed, unsignedSuffix || parsed > java.math.BigInteger.valueOf(Long.MAX_VALUE))
        }.getOrElse { Value(markerValue(token).toBigInteger()) }
    }

    private fun characterValue(token: String): Long {
        if (token.length < 2) return 0
        if (token[1] != '\\') return token[1].code.toLong()
        return when (token.getOrNull(2)) {
            'n' -> '\n'.code.toLong()
            'r' -> '\r'.code.toLong()
            't' -> '\t'.code.toLong()
            '\\' -> '\\'.code.toLong()
            '\'' -> '\''.code.toLong()
            '"' -> '"'.code.toLong()
            'x' -> token.drop(3).dropLastWhile { it == '\'' }.toLongOrNull(16) ?: 0
            else -> token.getOrNull(2)?.digitToIntOrNull(8)?.toLong() ?: 0
        }
    }

    private fun compare(parse: () -> Value, operators: Set<String>): Value {
        var value = parse()
        while (peek() in operators) {
            val operator = take()
            val right = parse()
            val leftNumber = if (value.unsigned || right.unsigned) value.normalizedUnsigned() else value.number
            val rightNumber = if (value.unsigned || right.unsigned) right.normalizedUnsigned() else right.number
            val comparison = leftNumber.compareTo(rightNumber)
            value = Value(
                when (operator) {
                    "==" -> if (comparison == 0) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                    "!=" -> if (comparison != 0) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                    "<" -> if (comparison < 0) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                    "<=" -> if (comparison <= 0) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                    ">" -> if (comparison > 0) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                    else -> if (comparison >= 0) java.math.BigInteger.ONE else java.math.BigInteger.ZERO
                },
            )
        }
        return value
    }

    private fun signed64(value: java.math.BigInteger): java.math.BigInteger {
        val masked = value.mod(Value.TWO_TO_64)
        return if (masked.testBit(63)) masked - Value.TWO_TO_64 else masked
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
                        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
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
