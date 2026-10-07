package org.tinycc.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.cli.CliParseException
import org.tinycc.cli.CommandLineParser
import org.tinycc.cli.ResponseFileExpander
import org.tinycc.cli.execute

class CliTest {
    @Test
    fun parsesTargetSearchDefinesScriptsAndRuntimeArguments() {
        val root = Files.createTempDirectory("tcjc-cli-")
        val include = Files.createDirectories(root.resolve("include"))
        val system = Files.createDirectories(root.resolve("system"))
        val response = root.resolve("options.rsp")
        response.writeText("-DNAME='hello world' -I ${include.toAbsolutePath()} --target arm64-linux")
        try {
            val options = CommandLineParser().parse(
                listOf(
                    "@${response.toAbsolutePath()}",
                    "-m32",
                    "-UOLD",
                    "-isystem",
                    system.toString(),
                    "-L",
                    root.toString(),
                    "-lmath",
                    "-B${root}",
                    "-include",
                    root.resolve("forced.h").toString(),
                    "--script",
                    root.resolve("script.tcc").toString(),
                    "-Wl,--gc-sections",
                    "-Wp,-traditional",
                    "unit.c",
                    "--",
                    "arg one",
                ),
            )
            assertEquals("i386-linux", options.target)
            assertEquals("hello world", options.defines["NAME"])
            assertEquals(listOf("OLD"), options.undefines)
            assertEquals(listOf(include.toAbsolutePath().normalize()), options.includePaths)
            assertEquals(listOf(system.toAbsolutePath().normalize()), options.systemIncludePaths)
            assertEquals(listOf("math"), options.libraries)
            assertEquals(listOf("--gc-sections"), options.linkerOptions)
            assertEquals(listOf("-traditional"), options.preprocessorOptions)
            assertEquals(listOf("arg one"), options.runtimeArguments)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun expandsNestedResponseFilesAndRejectsCycles() {
        val root = Files.createTempDirectory("tcjc-response-")
        val first = root.resolve("first.rsp")
        val second = root.resolve("second.rsp")
        first.writeText("@second.rsp 'quoted value'")
        second.writeText("-DVALUE=1")
        try {
            assertEquals(listOf("-DVALUE=1", "quoted value"), ResponseFileExpander().expand(listOf("@first.rsp"), root))
            first.writeText("@second.rsp")
            second.writeText("@first.rsp")
            assertFailsWith<CliParseException> { ResponseFileExpander().expand(listOf("@first.rsp"), root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun routesHelpVersionAndPreprocessingThroughKotlinCompiler() {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        assertEquals(0, execute(listOf("--help"), PrintStream(output), PrintStream(errors)))
        assertTrue(output.toString().contains("Usage: tcc-jvm"))
        output.reset()
        assertEquals(0, execute(listOf("--version"), PrintStream(output), PrintStream(errors)))
        assertEquals("tinycc-jvm\n", output.toString())

        val source = Files.createTempFile("tcjc-cli-source-", ".c")
        val destination = Files.createTempFile("tcjc-cli-output-", ".i")
        try {
            source.writeText("#define ANSWER 42\nint answer = ANSWER;\n")
            assertEquals(
                0,
                execute(listOf("-E", "-o", destination.toString(), source.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertEquals("int answer = 42;\n", destination.toFile().readText())
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(destination)
        }
    }

    @Test
    fun preprocessesStandardInputWhenDashIsTheInputFile() {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val stdin = ByteArrayInputStream("#define ANSWER 42\nint answer = ANSWER;\n".encodeToByteArray())

        assertEquals(0, execute(listOf("-E", "-"), PrintStream(output), PrintStream(errors), stdin))
        assertEquals("int answer = 42;\n", output.toString())
        assertEquals("", errors.toString())
    }
}
