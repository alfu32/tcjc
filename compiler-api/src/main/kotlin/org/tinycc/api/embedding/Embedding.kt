package org.tinycc.api.embedding

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import org.tinycc.api.execution.KotlinJvmLibrary
import org.tinycc.api.execution.NativeLibraryLoading
import org.tinycc.core.diagnostics.Diagnostic
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.DiagnosticSink
import org.tinycc.core.diagnostics.DiagnosticSeverity
import org.tinycc.core.io.SourceFileLoader
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.lexer.Token
import org.tinycc.core.lexer.TccTokenIds
import org.tinycc.core.preprocessor.Preprocessor
import org.tinycc.core.preprocessor.PreprocessorOptions

enum class CompilerOutputType { PREPROCESSED, TOKENS }

data class CompilerOptions(
    val target: String = "x86_64-linux",
    val includePaths: List<Path> = emptyList(),
    val systemIncludePaths: List<Path> = emptyList(),
    val predefined: Map<String, String> = emptyMap(),
    val outputType: CompilerOutputType = CompilerOutputType.TOKENS,
)

fun interface CompilerDiagnosticCallback {
    fun onDiagnostic(diagnostic: Diagnostic)
}

fun interface CompilerSymbolResolver {
    fun resolve(name: String): Long?
}

data class CompilationResult(
    val sourceName: String,
    val preprocessedSource: String,
    val tokens: List<Token>,
    val diagnostics: List<Diagnostic>,
) {
    val success: Boolean
        get() = diagnostics.none { it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL }
}

data class RegisteredSymbol(val name: String, val value: Long)

data class RelocatedCompilation(
    val target: String,
    val outputType: CompilerOutputType,
    val bytes: ByteArray,
    val symbols: List<RegisteredSymbol>,
) {
    fun symbol(name: String): Long? = symbols.firstOrNull { it.name == name }?.value
}

/** Pure Kotlin/JVM embedding session corresponding to the lifecycle of a libtcc state. */
class KotlinCompilerSession(
    initialOptions: CompilerOptions = CompilerOptions(),
) : AutoCloseable {
    val options: CompilerOptions
        get() = sessionOptions

    private val sourceLoader = SourceFileLoader()
    private val tokenTargetProfile = TccTokenIds.targetProfile(initialOptions.target)
    private val identifierAllocator = TccTokenIds.IdentifierAllocator(tokenTargetProfile)
    private val registeredSymbols = LinkedHashMap<String, Long>()
    private val ownedLibraries = ArrayList<KotlinJvmLibrary>()
    private var diagnosticCallback: CompilerDiagnosticCallback? = null
    private var symbolResolver: CompilerSymbolResolver? = null
    private var lastCompilation: CompilationResult? = null
    private var closed = false

    fun setDiagnosticCallback(callback: CompilerDiagnosticCallback?): KotlinCompilerSession = apply {
        checkOpen()
        diagnosticCallback = callback
    }

    fun setSymbolResolver(resolver: CompilerSymbolResolver?): KotlinCompilerSession = apply {
        checkOpen()
        symbolResolver = resolver
    }

    fun define(name: String, value: String = "1"): KotlinCompilerSession = apply {
        checkOpen()
        require(IDENTIFIER.matches(name)) { "invalid preprocessor symbol: $name" }
        val merged = options.predefined.toMutableMap()
        merged[name] = value
        replaceOptions(options.copy(predefined = merged))
    }

    fun addIncludePath(path: Path): KotlinCompilerSession = apply {
        checkOpen()
        require(path.isDirectory()) { "include path does not exist: $path" }
        replaceOptions(options.copy(includePaths = options.includePaths + path.toAbsolutePath().normalize()))
    }

    fun registerSymbol(name: String, value: Long): KotlinCompilerSession = apply {
        checkOpen()
        require(IDENTIFIER.matches(name)) { "invalid symbol name: $name" }
        registeredSymbols[name] = value
    }

    /** Registers a JVM jar as an owned library. Native shared libraries are never loaded. */
    fun registerLibrary(path: Path): KotlinCompilerSession = apply {
        checkOpen()
        if (!path.extension.equals("jar", ignoreCase = true)) NativeLibraryLoading.reject(path)
        ownedLibraries += KotlinJvmLibrary.open(path)
    }

    fun compileString(sourceName: String, source: String): CompilationResult {
        checkOpen()
        require(sourceName.isNotEmpty()) { "source name must not be empty" }
        val path = Path.of(sourceName).toAbsolutePath().normalize()
        val diagnostics = DiagnosticEngine(
            sink = DiagnosticSink { diagnostic -> diagnosticCallback?.onDiagnostic(diagnostic) },
        )
        val preprocessed = Preprocessor(
            source = source,
            path = path,
            diagnostics = diagnostics,
            options = PreprocessorOptions(
                predefined = options.predefined,
                includePaths = options.includePaths,
                systemIncludePaths = options.systemIncludePaths,
                sourceLoader = sourceLoader,
            ),
        ).process()
        val tokens = Lexer(
            preprocessed.text,
            path,
            diagnostics,
            options = org.tinycc.core.lexer.LexerOptions(tokenTarget = tokenTargetProfile),
            identifierAllocator = identifierAllocator,
        ).tokenize()
        return CompilationResult(sourceName, preprocessed.text, tokens, diagnostics.diagnostics()).also {
            lastCompilation = it
        }
    }

    fun compileFile(path: Path): CompilationResult {
        checkOpen()
        require(path.isRegularFile()) { "source file does not exist: $path" }
        val source = sourceLoader.read(path)
        return compileString(source.path.toString(), source.text)
    }

    fun outputBytes(): ByteArray {
        checkOpen()
        val compilation = lastCompilation ?: error("compileString or compileFile must be called before output")
        check(compilation.success) { "cannot emit output after compilation errors" }
        return when (options.outputType) {
            CompilerOutputType.PREPROCESSED -> compilation.preprocessedSource.encodeToByteArray()
            CompilerOutputType.TOKENS -> compilation.tokens.joinToString("\n") { token ->
                "${token.kind.name}\t${token.lexeme.replace("\\", "\\\\").replace("\n", "\\n")}"
            }.encodeToByteArray()
        }
    }

    fun writeOutput(path: Path) {
        checkOpen()
        path.toAbsolutePath().normalize().parent?.let(Files::createDirectories)
        Files.write(path, outputBytes())
    }

    fun relocate(): RelocatedCompilation {
        checkOpen()
        val compilation = lastCompilation ?: error("compileString or compileFile must be called before relocation")
        check(compilation.success) { "cannot relocate after compilation errors" }
        val symbols = registeredSymbols.entries.sortedBy { it.key }.map { RegisteredSymbol(it.key, it.value) }.toMutableList()
        symbolResolver?.let { resolver ->
            symbols.toList().forEach { symbol ->
                resolver.resolve(symbol.name)?.let { value ->
                    val index = symbols.indexOf(symbol)
                    symbols[index] = symbol.copy(value = value)
                }
            }
        }
        return RelocatedCompilation(options.target, options.outputType, outputBytes(), symbols)
    }

    override fun close() {
        if (closed) return
        closed = true
        ownedLibraries.asReversed().forEach(KotlinJvmLibrary::close)
        ownedLibraries.clear()
        registeredSymbols.clear()
        lastCompilation = null
        diagnosticCallback = null
        symbolResolver = null
    }

    private fun replaceOptions(updated: CompilerOptions) {
        // CompilerOptions is immutable; the session keeps the current copy for subsequent units.
        sessionOptions = updated
    }

    private var sessionOptions: CompilerOptions = initialOptions

    private fun checkOpen() {
        check(!closed) { "compiler session is closed" }
    }

    private companion object {
        val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")
    }
}
