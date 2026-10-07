package org.tinycc.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.api.execution.KotlinProcessRunner
import org.tinycc.api.execution.NativeLibraryLoading
import org.tinycc.api.execution.RunRequest
import org.tinycc.api.execution.TemporaryExecutable

class ExecutionTest {
    @Test
    fun runsTemporaryExecutableWithEnvironmentAndExitBehavior() {
        TemporaryExecutable.create(
            "#!/bin/sh\nprintf '%s' \"\$TCJC_EXECUTION_TEST\"\nexit 7\n".encodeToByteArray(),
            fileName = "program.sh",
        ).use { executable ->
            val result = KotlinProcessRunner.run(
                RunRequest(
                    executable = executable.path,
                    environment = mapOf("TCJC_EXECUTION_TEST" to "kotlin-jvm"),
                    inheritEnvironment = false,
                ),
            )
            assertEquals(7, result.exitCode)
            assertEquals("kotlin-jvm", result.standardOutput)
            assertEquals("", result.standardError)
            assertFalse(result.timedOut)
            assertTrue(result.duration.toNanos() >= 0)
        }
    }

    @Test
    fun removesTemporaryExecutableWhenClosed() {
        val executable = TemporaryExecutable.create(byteArrayOf(1, 2, 3), fileName = "artifact")
        val directory = executable.path.parent
        assertTrue(executable.path.exists())
        assertEquals(byteArrayOf(1, 2, 3).toList(), Files.readAllBytes(executable.path).toList())
        executable.close()
        assertFalse(executable.path.exists())
        assertFalse(directory.exists())
    }

    @Test
    fun timesOutAndTerminatesLongRunningProgram() {
        TemporaryExecutable.create(
            "#!/bin/sh\nsleep 5\n".encodeToByteArray(),
            fileName = "slow.sh",
        ).use { executable ->
            val result = KotlinProcessRunner.run(
                RunRequest(
                    executable = executable.path,
                    timeout = java.time.Duration.ofMillis(50),
                ),
            )
            assertEquals(-1, result.exitCode)
            assertTrue(result.timedOut)
        }
    }

    @Test
    fun nativeLibraryLoadingIsExplicitlyRejected() {
        val error = runCatching { NativeLibraryLoading.reject(Path.of("libexample.so")) }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertTrue(error.message!!.contains("pure Kotlin/JVM"))
    }

    @Test
    fun rejectsNonJvmDynamicLibraries() {
        val file = Files.createTempFile("tcjc-library-", ".dll")
        try {
            val error = runCatching {
                org.tinycc.api.execution.KotlinJvmLibrary.open(file)
            }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error.message!!.contains("only JVM .jar"))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
