package org.tinycc.cli

import java.io.PrintStream
import java.nio.file.Files
import org.tinycc.core.BuildInfo
import org.tinycc.api.embedding.CompilerOptions
import org.tinycc.api.embedding.KotlinCompilerSession
import org.tinycc.core.diagnostics.DiagnosticFormatter

fun main(args: Array<String>) {
    execute(args.toList(), System.out, System.err)
}

fun execute(args: List<String>, output: PrintStream, error: PrintStream): Int {
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
    if (inputs.size > 1) {
        error.println("tcc-jvm: multiple input units are not supported by this output mode yet")
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
            val results = inputs.map { input ->
                require(input.toString() != "-") { "stdin input is not implemented yet" }
                require(Files.isRegularFile(input)) { "input file does not exist: $input" }
                val source = Files.readString(input)
                val prefix = options.forcedIncludes.joinToString(separator = "\n") { forced ->
                    "#include \"${forced.toAbsolutePath().normalize()}\""
                }
                compiler.compileString(input.toString(), if (prefix.isEmpty()) source else "$prefix\n$source")
            }
            if (results.any { !it.success }) return 1
            if (options.run) {
                error.println("tcc-jvm: -run requires a native executable artifact from the target backend")
                return 2
            }
            if (options.outputPath != null) compiler.writeOutput(options.outputPath)
            else output.write(compiler.outputBytes())
            if (options.outputPath == null && options.outputType == org.tinycc.api.embedding.CompilerOutputType.TOKENS) {
                output.println()
            }
            0
        }
    } catch (failure: Exception) {
        error.println("tcc-jvm: ${failure.message ?: failure::class.simpleName}")
        1
    }
}
