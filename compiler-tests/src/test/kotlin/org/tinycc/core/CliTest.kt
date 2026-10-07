package org.tinycc.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
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
                execute(listOf("-E", "-P", "-o", destination.toString(), source.toString()), PrintStream(output), PrintStream(errors)),
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
        val stdin = ByteArrayInputStream(
            "#define ANSWER 42\nconst char *input_name = __FILE__;\nint answer = ANSWER;\n".encodeToByteArray(),
        )

        val status = execute(listOf("-E", "-P", "-"), PrintStream(output), PrintStream(errors), stdin)
        assertEquals(0, status, "stderr=${errors}; stdout=${output}")
        assertEquals("const char *input_name = \"-\";\nint answer = 42;\n", output.toString())
        assertEquals("", errors.toString())
    }

    @Test
    fun reportsStdinDiagnosticsUsingDashAsTheFilename() {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val stdin = ByteArrayInputStream("int value = @;\n".encodeToByteArray())

        assertEquals(1, execute(listOf("-E", "-"), PrintStream(output), PrintStream(errors), stdin))
        assertTrue(errors.toString().startsWith("-:1:13: error: unrecognized character"))
    }

    @Test
    fun resolvesQuotedIncludesFromStdinAgainstTheWorkingDirectory() {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val include = Files.createTempFile(Path.of("").toAbsolutePath(), "tcjc-stdin-include-", ".h")
        include.writeText("int from_stdin_include;\n")
        val stdin = ByteArrayInputStream("#include \"${include.fileName}\"\n".encodeToByteArray())

        try {
            assertEquals(0, execute(listOf("-E", "-P", "-"), PrintStream(output), PrintStream(errors), stdin))
            assertTrue(output.toString().contains("int from_stdin_include;"))
            assertEquals("", errors.toString())
        } finally {
            Files.deleteIfExists(include)
        }
    }

    @Test
    fun aggregatesMultipleSourcesInPreprocessAndTokenModes() {
        val root = Files.createTempDirectory("tcjc-cli-multi-")
        val first = root.resolve("first.c")
        val second = root.resolve("second.c")
        first.writeText("int first_value;\n")
        second.writeText("int second_value;\n")
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()

        try {
            assertEquals(
                0,
                execute(listOf("-E", "-P", first.toString(), second.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertEquals("int first_value;\nint second_value;\n", output.toString())

            output.reset()
            assertEquals(
                0,
                execute(listOf("-c", first.toString(), second.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertEquals(2, output.toString().lines().count { it == "EOF\t" })
            assertEquals("", errors.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun routesDashOutputToStandardOutput() {
        val root = Files.createTempDirectory("tcjc-cli-output-stdout-")
        val source = root.resolve("source.c")
        source.writeText("int stdout_value;\n")
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()

        try {
            assertEquals(
                0,
                execute(listOf("-E", "-P", "-o", "-", source.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertEquals("int stdout_value;\n", output.toString())
            assertEquals("", errors.toString())

            output.reset()
            assertEquals(
                0,
                execute(listOf("-E", "-P", "--output=-", source.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertEquals("int stdout_value;\n", output.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun emitsHistoricalPreprocessorLineMarkerModesAndIncludeTransitions() {
        val root = Files.createTempDirectory("tcjc-cli-line-markers-")
        val header = root.resolve("included.h")
        val source = root.resolve("source.c")
        header.writeText("int included_value;\n")
        source.writeText("#include \"${header.fileName}\"\nint source_value;\n")
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()

        try {
            assertEquals(
                0,
                execute(listOf("-E", "-I", root.toString(), source.toString()), PrintStream(output), PrintStream(errors)),
            )
            val gccOutput = output.toString()
            assertTrue(gccOutput.startsWith("# 1 \"${source.toAbsolutePath()}\"\n"))
            assertTrue(gccOutput.contains("# 1 \"${header.toAbsolutePath()}\" 1\n"))
            assertTrue(gccOutput.contains("# 2 \"${source.toAbsolutePath()}\" 2\n"))
            assertTrue(gccOutput.contains("int source_value;"))

            output.reset()
            val relativeSource = Path.of("").toAbsolutePath().relativize(source.toAbsolutePath()).toString()
            assertEquals(
                0,
                execute(listOf("-E", relativeSource), PrintStream(output), PrintStream(errors)),
            )
            assertTrue(output.toString().startsWith("# 1 \"$relativeSource\"\n"))

            output.reset()
            assertEquals(
                0,
                execute(listOf("-E", "-P1", "-I", root.toString(), source.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertTrue(output.toString().startsWith("#line 1 \"${source.toAbsolutePath()}\"\n"))
            assertTrue(output.toString().contains("#line 1 \"${header.toAbsolutePath()}\"\n"))

            output.reset()
            assertEquals(
                0,
                execute(listOf("-E", "-P", "-I", root.toString(), source.toString()), PrintStream(output), PrintStream(errors)),
            )
            assertTrue(!output.toString().contains("# 1 \""))
            assertTrue(!output.toString().contains("#line"))
            assertEquals("", errors.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun numericOnlyPreprocessingConvertsIntegerAndFloatingTokens() {
        val root = Files.createTempDirectory("tcjc-cli-p10-")
        val source = root.resolve("numbers.c")
        source.writeText("""#define HEX_VALUE 0x2a
int value = HEX_VALUE + 17UL; double d = 1.5; float f = 2.0f; char nl = '\n'; char quote = '\'';
""")
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()

        try {
            assertEquals(0, execute(listOf("-E", "-P10", source.toString()), PrintStream(output), PrintStream(errors)))
            val text = output.toString()
            assertTrue(text.contains("int value = 42 + 17;"), text)
            assertTrue(text.contains("double d = <double>;"), text)
            assertTrue(text.contains("float f = <float>;"), text)
            assertTrue(text.contains("char nl = '\\n';"), text)
            assertTrue(text.contains("char quote = '\\'';"), text)
            assertTrue(!text.contains("# 1 \""), text)
            assertEquals("", errors.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun laterLineMarkerOptionOverridesNumericPreprocessingMode() {
        val options = CommandLineParser().parse(listOf("-P10", "-P1", "unit.c"))
        assertEquals(false, options.numericPreprocessing)
        assertEquals(org.tinycc.core.preprocessor.LineMarkerMode.STANDARD, options.lineMarkerMode)
    }
}
