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
            val outputBytes = if (results.size == 1) compiler.outputBytes() else aggregateOutput(results, options.outputType)
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
