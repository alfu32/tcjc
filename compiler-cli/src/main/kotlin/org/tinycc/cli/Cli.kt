package org.tinycc.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile

enum class CliAction { COMPILE, HELP, VERSION }

data class CliOptions(
    val action: CliAction = CliAction.COMPILE,
    val run: Boolean = false,
    val outputType: org.tinycc.api.embedding.CompilerOutputType = org.tinycc.api.embedding.CompilerOutputType.TOKENS,
    val outputPath: Path? = null,
    val target: String = "x86_64-linux",
    val includePaths: List<Path> = emptyList(),
    val systemIncludePaths: List<Path> = emptyList(),
    val libraryPaths: List<Path> = emptyList(),
    val programSearchPaths: List<Path> = emptyList(),
    val libraries: List<String> = emptyList(),
    val defines: Map<String, String> = emptyMap(),
    val undefines: List<String> = emptyList(),
    val forcedIncludes: List<Path> = emptyList(),
    val scriptFiles: List<Path> = emptyList(),
    val linkerOptions: List<String> = emptyList(),
    val preprocessorOptions: List<String> = emptyList(),
    val inputFiles: List<Path> = emptyList(),
    val runtimeArguments: List<String> = emptyList(),
)

class CliParseException(message: String) : IllegalArgumentException(message)

class ResponseFileExpander(
    private val maxDepth: Int = 32,
) {
    fun expand(arguments: List<String>, workingDirectory: Path = Path.of(".").toAbsolutePath().normalize()): List<String> =
        expand(arguments, workingDirectory, emptyList(), 0)

    private fun expand(
        arguments: List<String>,
        baseDirectory: Path,
        stack: List<Path>,
        depth: Int,
    ): List<String> {
        if (depth > maxDepth) throw CliParseException("response file nesting exceeds $maxDepth levels")
        return buildList {
            arguments.forEach { argument ->
                if (!argument.startsWith("@") || argument == "@") {
                    add(argument)
                    return@forEach
                }
                if (argument.startsWith("@@")) {
                    add(argument.removePrefix("@"))
                    return@forEach
                }
                val file = baseDirectory.resolve(argument.substring(1)).normalize()
                if (!file.isRegularFile()) throw CliParseException("response file does not exist: $file")
                if (file in stack) {
                    val cycle = (stack + file).joinToString(" -> ") { it.absolutePathString() }
                    throw CliParseException("response file cycle: $cycle")
                }
                val nested = tokenize(Files.readString(file), file)
                addAll(expand(nested, file.parent ?: baseDirectory, stack + file, depth + 1))
            }
        }
    }

    private fun tokenize(text: String, file: Path): List<String> {
        val result = ArrayList<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var index = 0
        fun finish() {
            if (token.isNotEmpty()) {
                result += token.toString()
                token.clear()
            }
        }
        while (index < text.length) {
            val character = text[index]
            if (escaped) {
                token.append(character)
                escaped = false
                index++
                continue
            }
            if (character == '\\') {
                escaped = true
                index++
                continue
            }
            if (quote != null) {
                if (character == quote) quote = null else token.append(character)
                index++
                continue
            }
            when {
                character == '\'' || character == '"' -> quote = character
                character.isWhitespace() -> finish()
                character == '#' -> {
                    finish()
                    while (index < text.length && text[index] != '\n') index++
                }
                else -> token.append(character)
            }
            index++
        }
        if (escaped) throw CliParseException("unterminated escape in response file: $file")
        if (quote != null) throw CliParseException("unterminated quote in response file: $file")
        finish()
        return result
    }
}

class CommandLineParser(
    private val responseFiles: ResponseFileExpander = ResponseFileExpander(),
) {
    fun parse(arguments: List<String>): CliOptions {
        val expanded = responseFiles.expand(arguments)
        val includePaths = ArrayList<Path>()
        val systemIncludePaths = ArrayList<Path>()
        val libraryPaths = ArrayList<Path>()
        val programSearchPaths = ArrayList<Path>()
        val libraries = ArrayList<String>()
        val defines = LinkedHashMap<String, String>()
        val undefines = ArrayList<String>()
        val forcedIncludes = ArrayList<Path>()
        val scriptFiles = ArrayList<Path>()
        val linkerOptions = ArrayList<String>()
        val preprocessorOptions = ArrayList<String>()
        val inputFiles = ArrayList<Path>()
        var action = CliAction.COMPILE
        var run = false
        var outputType = org.tinycc.api.embedding.CompilerOutputType.TOKENS
        var outputPath: Path? = null
        var target = "x86_64-linux"
        var runtimeArguments = emptyList<String>()
        var index = 0
        var endOfOptions = false
        while (index < expanded.size) {
            val argument = expanded[index]
            if (endOfOptions) {
                runtimeArguments += argument
                index++
                continue
            }
            if (argument == "--") {
                endOfOptions = true
                index++
                continue
            }
            when {
                argument == "-h" || argument == "--help" -> action = CliAction.HELP
                argument == "--version" -> action = CliAction.VERSION
                argument == "-run" || argument == "--run" -> run = true
                argument == "-E" -> outputType = org.tinycc.api.embedding.CompilerOutputType.PREPROCESSED
                argument == "-c" -> outputType = org.tinycc.api.embedding.CompilerOutputType.TOKENS
                argument == "-m32" -> target = target.replace(Regex("^[^-]+"), "i386")
                argument == "-m64" -> target = target.replace(Regex("^[^-]+"), "x86_64")
                argument == "-o" || argument == "--output" -> outputPath = nextValue(expanded, ++index, argument).toPath()
                argument.startsWith("--output=") -> outputPath = argument.substringAfter('=').toPath()
                argument == "--target" -> target = nextValue(expanded, ++index, argument)
                argument.startsWith("--target=") -> target = argument.substringAfter('=')
                argument == "-I" -> includePaths.add(nextPath(expanded, ++index, argument))
                argument.startsWith("-I") -> includePaths.add(argument.substring(2).toPath())
                argument == "-isystem" -> systemIncludePaths.add(nextPath(expanded, ++index, argument))
                argument.startsWith("-isystem") -> systemIncludePaths.add(argument.substring("-isystem".length).toPath())
                argument == "-L" -> libraryPaths.add(nextPath(expanded, ++index, argument))
                argument.startsWith("-L") -> libraryPaths.add(argument.substring(2).toPath())
                argument == "-B" -> programSearchPaths.add(nextPath(expanded, ++index, argument))
                argument.startsWith("-B") -> programSearchPaths.add(argument.substring(2).toPath())
                argument == "-l" -> libraries.add(nextValue(expanded, ++index, argument))
                argument.startsWith("-l") -> libraries.add(argument.substring(2))
                argument == "-D" -> addDefine(defines, nextValue(expanded, ++index, argument))
                argument.startsWith("-D") -> addDefine(defines, argument.substring(2))
                argument == "-U" -> undefines.add(identifier(nextValue(expanded, ++index, argument), argument))
                argument.startsWith("-U") -> undefines.add(identifier(argument.substring(2), argument))
                argument == "-include" -> forcedIncludes.add(nextPath(expanded, ++index, argument))
                argument.startsWith("-include=") -> forcedIncludes.add(argument.substringAfter('=').toPath())
                argument == "--script" -> scriptFiles.add(nextPath(expanded, ++index, argument))
                argument.startsWith("--script=") -> scriptFiles.add(argument.substringAfter('=').toPath())
                argument.startsWith("-Wl,") -> linkerOptions.add(argument.substring(4))
                argument.startsWith("-Wp,") -> preprocessorOptions.add(argument.substring(4))
                argument.startsWith("-") -> throw CliParseException("unknown option: $argument")
                else -> inputFiles.add(argument.toPath())
            }
            index++
        }
        if (action != CliAction.COMPILE && (expanded.size > 1 || action == CliAction.HELP && expanded.any { it != "-h" && it != "--help" })) {
            if (action == CliAction.VERSION && expanded.any { it != "--version" }) {
                throw CliParseException("--version cannot be combined with compilation options")
            }
        }
        return CliOptions(
            action = action,
            run = run,
            outputType = outputType,
            outputPath = outputPath,
            target = target,
            includePaths = includePaths.map(Path::toAbsolutePath).map(Path::normalize),
            systemIncludePaths = systemIncludePaths.map(Path::toAbsolutePath).map(Path::normalize),
            libraryPaths = libraryPaths.map(Path::toAbsolutePath).map(Path::normalize),
            programSearchPaths = programSearchPaths.map(Path::toAbsolutePath).map(Path::normalize),
            libraries = libraries,
            defines = defines,
            undefines = undefines,
            forcedIncludes = forcedIncludes,
            scriptFiles = scriptFiles,
            linkerOptions = linkerOptions,
            preprocessorOptions = preprocessorOptions,
            inputFiles = inputFiles,
            runtimeArguments = runtimeArguments,
        )
    }

    private fun addDefine(defines: MutableMap<String, String>, value: String) {
        val name = value.substringBefore('=')
        identifier(name, "-D")
        defines[name] = value.substringAfter('=', "1")
    }

    private fun identifier(value: String, option: String): String {
        if (!Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(value)) {
            throw CliParseException("$option expects an identifier: $value")
        }
        return value
    }

    private fun nextValue(arguments: List<String>, index: Int, option: String): String {
        if (index >= arguments.size || arguments[index].startsWith("-")) {
            throw CliParseException("$option expects a value")
        }
        return arguments[index]
    }

    private fun nextPath(arguments: List<String>, index: Int, option: String): Path = nextValue(arguments, index, option).toPath()

    private fun String.toPath(): Path = Path.of(this)
}

fun cliHelp(): String = """
    Usage: tcc-jvm [options] file...

    Actions:
      -E, -c                 emit preprocessed text or deterministic token output
      -run, --run            request execution after compilation
      -o, --output FILE      write output to FILE
      -h, --help             show this help
      --version              show version

    Front end and target options:
      -DNAME[=VALUE]         define a preprocessor symbol
      -UNAME                 undefine a preprocessor symbol
      -I PATH, -isystem PATH add include search paths
      --target TRIPLE        select a target (for example x86_64-linux)
      -m32, -m64             select 32-bit or 64-bit Linux target

    Link and script options:
      -L PATH, -l NAME       record library search paths and libraries
      -B PATH                record program search paths
      -include FILE          force an include
      --script FILE          add a script input
      @FILE                  read additional options from a response file

    The compiler is implemented in Kotlin/JVM. Native DLL/SO loading is not supported.
""".trimIndent()
