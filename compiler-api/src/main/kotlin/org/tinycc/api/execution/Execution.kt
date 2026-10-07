package org.tinycc.api.execution

import java.io.IOException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A native executable invocation requested by the Kotlin/JVM compiler API. */
data class RunRequest(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val inheritEnvironment: Boolean = true,
    val workingDirectory: Path? = null,
    val timeout: Duration? = null,
) {
    init {
        require(executable.isAbsolute) { "run executable must be an absolute path" }
        require(environment.keys.none { it.isEmpty() }) { "environment variable names must not be empty" }
        require(timeout == null || !timeout.isNegative && !timeout.isZero) { "run timeout must be positive" }
    }
}

data class RunResult(
    val exitCode: Int,
    val standardOutput: String,
    val standardError: String,
    val timedOut: Boolean,
    val duration: Duration,
)

/** Executes generated files without invoking a compiler, linker, shell, or native library loader. */
object KotlinProcessRunner {
    fun run(request: RunRequest): RunResult {
        require(request.executable.isRegularFile()) {
            "run executable does not exist: ${request.executable}"
        }
        if (request.workingDirectory != null) {
            require(request.workingDirectory.isDirectory()) {
                "run working directory does not exist: ${request.workingDirectory}"
            }
        }

        val command = buildList {
            add(request.executable.absolutePathString())
            addAll(request.arguments)
        }
        val processBuilder = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
        if (request.workingDirectory != null) {
            processBuilder.directory(request.workingDirectory.toFile())
        }
        val processEnvironment = processBuilder.environment()
        if (!request.inheritEnvironment) {
            processEnvironment.clear()
        }
        request.environment.forEach { (name, value) -> processEnvironment[name] = value }

        val startedAt = System.nanoTime()
        val process = try {
            processBuilder.start()
        } catch (error: IOException) {
            throw IllegalStateException("unable to execute ${request.executable}", error)
        }
        val stdout = process.inputStream.readInBackground()
        val stderr = process.errorStream.readInBackground()
        val completed = if (request.timeout == null) {
            process.waitFor()
            true
        } else {
            process.waitFor(request.timeout.toNanos(), TimeUnit.NANOSECONDS)
        }
        val timedOut = !completed
        if (timedOut) {
            process.destroy()
            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
        return RunResult(
            exitCode = if (timedOut) -1 else process.exitValue(),
            standardOutput = stdout.await(),
            standardError = stderr.await(),
            timedOut = timedOut,
            duration = Duration.ofNanos(System.nanoTime() - startedAt),
        )
    }
}

/** Owns one generated executable and removes it, and its private directory, on close. */
class TemporaryExecutable private constructor(
    val path: Path,
    private val directory: Path,
) : AutoCloseable {
    override fun close() {
        deleteTree(directory)
    }

    companion object {
        fun create(
            bytes: ByteArray,
            fileName: String = "tcjc-program",
            parent: Path? = null,
        ): TemporaryExecutable {
            require(fileName.isNotEmpty() && fileName != "." && fileName != "..") {
                "temporary executable name must not be empty"
            }
            require(Path.of(fileName).fileName.toString() == fileName) {
                "temporary executable name must be a single file name"
            }
            val directory = if (parent == null) {
                Files.createTempDirectory("tcjc-run-")
            } else {
                parent.createDirectories()
                Files.createTempDirectory(parent, "tcjc-run-")
            }
            val path = directory.resolve(fileName)
            Files.write(path, bytes)
            makeExecutable(path)
            return TemporaryExecutable(path, directory)
        }

        private fun makeExecutable(path: Path) {
            try {
                Files.setPosixFilePermissions(
                    path,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE,
                    ),
                )
            } catch (_: UnsupportedOperationException) {
                require(path.toFile().setExecutable(true, false)) {
                    "unable to mark temporary executable as executable: $path"
                }
            }
        }

        private fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}

/** A dynamically loadable JVM library; native shared libraries are intentionally unsupported. */
class KotlinJvmLibrary private constructor(
    private val loader: URLClassLoader,
) : AutoCloseable {
    fun loadClass(className: String): Class<*> = Class.forName(className, true, loader)

    fun <T : Any> services(serviceType: Class<T>): List<T> =
        ServiceLoader.load(serviceType, loader).toList()

    override fun close() {
        loader.close()
    }

    companion object {
        fun open(jar: Path, parent: ClassLoader = KotlinJvmLibrary::class.java.classLoader): KotlinJvmLibrary {
            require(jar.isRegularFile()) { "JVM library does not exist: $jar" }
            require(jar.extension.equals("jar", ignoreCase = true)) {
                "only JVM .jar libraries can be loaded: ${jar.name}"
            }
            return KotlinJvmLibrary(URLClassLoader(arrayOf(jar.toUri().toURL()), parent))
        }
    }
}

object NativeLibraryLoading {
    fun reject(path: Path): Nothing = throw UnsupportedOperationException(
        "native library loading is unavailable in the pure Kotlin/JVM artifact: ${path.fileName}",
    )
}

private fun java.io.InputStream.readInBackground(): OutputCapture {
    val capture = OutputCapture()
    Thread {
        capture.bytes = use { it.readAllBytes() }
    }.apply {
        isDaemon = true
        start()
    }
    return capture
}

private class OutputCapture {
    @Volatile
    var bytes: ByteArray? = null

    fun await(): String {
        while (bytes == null) Thread.yield()
        return bytes!!.toString(StandardCharsets.UTF_8)
    }
}
