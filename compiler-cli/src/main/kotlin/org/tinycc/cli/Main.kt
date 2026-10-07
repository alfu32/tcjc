package org.tinycc.cli

import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import org.tinycc.core.BuildInfo
import org.tinycc.api.embedding.CompilationResult
import org.tinycc.api.embedding.CompilerOutputType
import org.tinycc.api.embedding.CompilerOptions
import org.tinycc.api.embedding.KotlinCompilerSession
import org.tinycc.core.diagnostics.DiagnosticFormatter

fun main(args: Array<String>) {
    execute(args.toList(), System.out, System.err)
}

fun execute(args: List<String>, output: PrintStream, error: PrintStream, input: InputStream = System.`in`): Int {
    val options = try {
        CommandLineParser().parse(args)
    } catch (failure: CliParseException) {
        error.println("tcc-jvm: ${failure.message}")
        error.println("Try 'tcc-jvm --help' for usage.")
        return 2
    }
    when (options.action) {
        CliAction.HELP -> {
            output.println(cliHelp())
            return 0
        }
        CliAction.VERSION -> {
            output.println(BuildInfo.PROJECT_NAME)
            return 0
        }
        CliAction.COMPILE -> Unit
    }
    val inputs = options.inputFiles + options.scriptFiles
    if (inputs.isEmpty()) {
        error.println("tcc-jvm: no input files")
        return 2
    }
    val predefined = options.defines.toMutableMap().apply { options.undefines.forEach(::remove) }
    return try {
        KotlinCompilerSession(
            CompilerOptions(
                target = options.target,
                includePaths = options.includePaths,
                systemIncludePaths = options.systemIncludePaths,
                predefined = predefined,
                outputType = options.outputType,
                lineMarkerMode = options.lineMarkerMode,
            ),
        ).use { compiler ->
            compiler.setDiagnosticCallback { diagnostic -> error.println(DiagnosticFormatter.DEFAULT.format(diagnostic)) }
            val results = inputs.map { inputPath ->
                val source = if (inputPath.toString() == "-") {
                    inputStreamText(input)
                } else {
                    require(Files.isRegularFile(inputPath)) { "input file does not exist: $inputPath" }
                    Files.readString(inputPath)
                }
                val prefix = options.forcedIncludes.joinToString(separator = "\n") { forced ->
                    "#include \"${forced.toAbsolutePath().normalize()}\""
                }
                compiler.compileString(inputPath.toString(), if (prefix.isEmpty()) source else "$prefix\n$source")
            }
            if (results.any { !it.success }) return 1
            if (options.run) {
                error.println("tcc-jvm: -run requires a native executable artifact from the target backend")
                return 2
            }
            val outputBytes = if (options.numericPreprocessing && options.outputType == CompilerOutputType.PREPROCESSED) {
                results.joinToString(separator = "") { result ->
                    renderNumericPreprocessed(result.preprocessedSource, result.tokens)
                        .let { if (it.isNotEmpty() && !it.endsWith('\n')) "$it\n" else it }
                }.encodeToByteArray()
            } else if (results.size == 1) {
                compiler.outputBytes()
            } else {
                aggregateOutput(results, options.outputType)
            }
            val writeToStdout = options.outputPath == null || options.outputPath.toString() == "-"
            if (!writeToStdout) {
                val outputPath = requireNotNull(options.outputPath)
                outputPath.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
                Files.write(outputPath, outputBytes)
            } else output.write(outputBytes)
            if (writeToStdout && options.outputType == CompilerOutputType.TOKENS) {
                output.println()
            }
            0
        }
    } catch (failure: Exception) {
        error.println("tcc-jvm: ${failure.message ?: failure::class.simpleName}")
        1
    }
}

private fun inputStreamText(stream: InputStream): String = stream.readBytes().decodeToString()

private fun aggregateOutput(
    results: List<CompilationResult>,
    outputType: CompilerOutputType,
): ByteArray = when (outputType) {
    CompilerOutputType.PREPROCESSED -> buildString {
        results.forEach { result ->
            append(result.preprocessedSource)
            if (result.preprocessedSource.isNotEmpty() && !result.preprocessedSource.endsWith('\n')) append('\n')
        }
    }.encodeToByteArray()
    CompilerOutputType.TOKENS -> results
        .flatMap { it.tokens }
        .joinToString("\n") { token ->
            buildString {
                append(token.kind.name)
                append('\t')
                append(token.lexeme.replace("\\", "\\\\").replace("\n", "\\n"))
            }
        }
        .encodeToByteArray()
}

private fun renderNumericPreprocessed(source: String, tokens: List<org.tinycc.core.lexer.Token>): String = buildString {
    var cursor = 0
    tokens.forEach { token ->
        if (token.kind != org.tinycc.core.lexer.TokenKind.INTEGER_LITERAL &&
            token.kind != org.tinycc.core.lexer.TokenKind.FLOAT_LITERAL &&
            token.kind != org.tinycc.core.lexer.TokenKind.CHARACTER_LITERAL &&
            token.kind != org.tinycc.core.lexer.TokenKind.STRING_LITERAL
        ) return@forEach
        val start = token.span.start.offset
        val end = token.span.end.offset
        if (start < cursor || end > source.length) return@forEach
        append(source, cursor, start)
        append(
            when (token.kind) {
                org.tinycc.core.lexer.TokenKind.INTEGER_LITERAL -> {
                    val value = (token.literal as? org.tinycc.core.lexer.LiteralValue.Integer)?.value
                        ?: return@forEach
                    value.mod(java.math.BigInteger.ONE.shiftLeft(64)).toString()
                }
                org.tinycc.core.lexer.TokenKind.FLOAT_LITERAL -> when {
                    token.lexeme.endsWith('f', ignoreCase = true) -> "<float>"
                    token.lexeme.endsWith('l', ignoreCase = true) -> "<long double>"
                    else -> "<double>"
                }
                org.tinycc.core.lexer.TokenKind.CHARACTER_LITERAL -> {
                    val literal = token.literal as? org.tinycc.core.lexer.LiteralValue.Character
                        ?: return@forEach
                    val value = literal.value
                    val escaped = when {
                        value == '\n'.code -> "\\n"
                        value in 32..126 && value != '\''.code && value != '\\'.code -> value.toChar().toString()
                        value == '\''.code || value == '\\'.code -> "\\${value.toChar()}"
                        else -> "\\%03o".format(value and 0x1ff)
                    }
                    "${if (literal.wide) "L" else ""}'$escaped'"
                }
                org.tinycc.core.lexer.TokenKind.STRING_LITERAL -> {
                    val literal = token.literal as? org.tinycc.core.lexer.LiteralValue.StringValue
                        ?: return@forEach
                    val wide = literal.prefix == "L"
                    val characters = if (wide) {
                        literal.value.codePoints().toArray().asIterable()
                    } else {
                        literal.value.encodeToByteArray().map { it.toInt() and 0xff }
                    }
                    val body = characters.joinToString(separator = "") { value -> escapeTinyCcStringCharacter(value) }
                    "${if (wide) "L" else ""}\"$body\""
                }
                else -> token.lexeme
            },
        )
        cursor = end
    }
    append(source, cursor, source.length)
}

private fun escapeTinyCcStringCharacter(value: Int): String = when {
    value == '"'.code || value == '\\'.code -> "\\${value.toChar()}"
    value in 32..126 -> value.toChar().toString()
    value == '\n'.code -> "\\n"
    else -> "\\%03o".format(value and 0x1ff)
}
