package tcc.kt

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private data class CoverageLine(val firstLine: Int, val lastLine: Int, var count: ULong)
private data class CoverageFunction(
    val name: String,
    val firstLine: Int,
    val lines: MutableList<CoverageLine> = mutableListOf(),
)
private data class CoverageFile(val name: String, val functions: MutableList<CoverageFunction> = mutableListOf())
private data class PreviousLineCount(val count: ULong, val hasDuplicate: Boolean)

/** Kotlin port of lib/tcov.c's binary coverage decoder and gcov text writer. */
object TcovRuntime {
    private val countLine = Regex("^\\s*(#*|[0-9]+)(\\*)?:\\s*([0-9]+):")
    private val runCount = Regex("Runs:([0-9]+)")
    private val fileSection = Regex("0:File:(.*?) Functions:")
    private val functionSection = Regex("0:Function:(.*?) ")

    /** Decode and merge the coverage block, then update its gcov-format output file. */
    fun __store_test_coverage(data: ByteArray) {
        try {
            val output = outputFileName(data)
            val coverage = decode(data)
            FileChannel.open(
                Path.of(output),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use {
                    val previousText = readChannel(channel)
                    val previousRuns = runCount.find(previousText)?.groupValues?.get(1)?.toUIntOrNull() ?: 0u
                    val previousCounts = parsePreviousCounts(previousText)
                    val runs = previousRuns + 1u
                    mergeCounts(coverage, previousCounts)
                    val rendered = render(output, coverage, runs)
                    channel.position(0)
                    val bytes = ByteBuffer.wrap(rendered.toByteArray(Charset.defaultCharset()))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        } catch (exception: IOException) {
            System.err.println("Cannot create coverage file: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            System.err.println("Invalid test coverage data: ${exception.message}")
        }
    }

    private fun outputFileName(data: ByteArray): String {
        val offset = readUnsigned(data, 0, 4).toLong()
        require(offset in 4 until data.size.toLong()) { "coverage filename offset is out of range" }
        return readCString(data, offset.toInt()).first
    }

    private fun decode(data: ByteArray): List<CoverageFile> {
        val files = mutableListOf<CoverageFile>()
        var cursor = 4
        while (cursor < data.size && data[cursor] != 0.toByte()) {
            val (filename, afterFilename) = readCString(data, cursor)
            cursor = afterFilename
            var file = files.firstOrNull { it.name == filename }
            if (file == null) {
                file = CoverageFile(filename)
                files.add(file)
            }

            while (cursor < data.size && data[cursor] != 0.toByte()) {
                val (functionName, afterName) = readCString(data, cursor)
                cursor = align(afterName, 8)
                require(cursor + 8 <= data.size) { "truncated function record" }
                val firstLine = readUnsigned(data, cursor, 8).toInt()
                cursor += 8
                var function = file.functions.firstOrNull { it.name == functionName }
                if (function == null) {
                    function = CoverageFunction(functionName, firstLine)
                    file.functions.add(function)
                }

                while (cursor < data.size && data[cursor] != 0.toByte()) {
                    require(cursor + 16 <= data.size) { "truncated line record" }
                    val packed = readUnsigned(data, cursor, 8)
                    val lineCount = readUnsigned(data, cursor + 8, 8).toULong()
                    val first = ((packed ushr 8) and 0x0fff_ffffL).toInt()
                    val last = (packed ushr 36).toInt()
                    function.lines.add(CoverageLine(first, last, lineCount))
                    cursor += 16
                }
                cursor++ // end of this function's line records
            }
            cursor++ // end of this file's function records
        }

        files.forEach { file ->
            file.functions.sortBy { it.firstLine }
            file.functions.forEach { function ->
                function.lines.sortWith(compareBy<CoverageLine> { it.firstLine }.thenByDescending { it.count })
            }
        }
        return files
    }

    private fun mergeCounts(
        files: List<CoverageFile>,
        previous: Map<String, Map<String, Map<Int, PreviousLineCount>>>,
    ) {
        files.forEach { file ->
            file.functions.forEach { function ->
                val oldLines = previous[file.name]?.get(function.name).orEmpty()
                var skipNext = false
                function.lines.forEach { line ->
                    val old = oldLines[line.firstLine]
                    if (old != null && !skipNext) line.count += old.count
                    skipNext = old?.hasDuplicate == true
                }
            }
        }
    }

    private fun parsePreviousCounts(text: String): Map<String, Map<String, Map<Int, PreviousLineCount>>> {
        val result = linkedMapOf<String, MutableMap<String, MutableMap<Int, PreviousLineCount>>>()
        var currentFile: String? = null
        var currentFunction: String? = null
        text.lineSequence().forEach { line ->
            fileSection.find(line)?.let {
                currentFile = it.groupValues[1]
                currentFunction = null
            }
            functionSection.find(line)?.let { currentFunction = it.groupValues[1] }
            val filename = currentFile ?: return@forEach
            val functionName = currentFunction ?: return@forEach
            val match = countLine.find(line) ?: return@forEach
            val countText = match.groupValues[1]
            val lineNumber = match.groupValues[3].toIntOrNull() ?: return@forEach
            if (lineNumber == 0 || countText == "-" || countText.isEmpty()) return@forEach
            val count = countText.trimStart('#').toULongOrNull() ?: 0uL
            result.getOrPut(filename) { linkedMapOf() }
                .getOrPut(functionName) { linkedMapOf() }[lineNumber] =
                    PreviousLineCount(count, match.groupValues[2] == "*")
        }
        return result
    }

    private fun render(outputName: String, files: List<CoverageFile>, runs: UInt): String {
        val out = StringBuilder()
        var totalBlocks = 0
        var totalRun = 0
        var totalFunctions = 0
        files.forEach { file ->
            totalFunctions += file.functions.size
            file.functions.forEach { function ->
                totalBlocks += function.lines.size
                totalRun += function.lines.count { it.count != 0uL }
            }
        }
        val allBlocks = totalBlocks.coerceAtLeast(1)
        out.append("        -:    0:Runs:").append(runs).append('\n')
        out.append("        -:    0:All:").append(outputName)
            .append(" Files:").append(files.size)
            .append(" Functions:").append(totalFunctions)
            .append(' ').append(percent(totalRun, allBlocks)).append("%\n")

        files.forEach { file -> renderFile(out, file) }
        return out.toString()
    }

    private fun renderFile(out: StringBuilder, file: CoverageFile) {
        val sourceLines = try {
            Path.of(file.name).toFile().readLines(Charset.defaultCharset())
        } catch (_: IOException) {
            return
        }
        var blocks = file.functions.sumOf { it.lines.size }
        var runBlocks = file.functions.sumOf { function -> function.lines.count { it.count != 0uL } }
        if (blocks == 0) blocks = 1
        out.append("        -:    0:File:").append(file.name)
            .append(" Functions:").append(file.functions.size)
            .append(' ').append(percent(runBlocks, blocks)).append("%\n")

        var currentLine = 1
        file.functions.forEach { function ->
            while (currentLine < function.firstLine && currentLine <= sourceLines.size) {
                out.append("        -:").append(String.format("%5d", currentLine))
                    .append(':').append(sourceLines[currentLine - 1]).append('\n')
                currentLine++
            }
            blocks = function.lines.size.coerceAtLeast(1)
            runBlocks = function.lines.count { it.count != 0uL }
            out.append("        -:    0:Function:").append(function.name)
                .append(' ').append(percent(runBlocks, blocks)).append("%\n")
            renderFunctionLines(out, function, sourceLines, currentLine).also { currentLine = it }
        }
        while (currentLine <= sourceLines.size) {
            out.append("        -:").append(String.format("%5d", currentLine))
                .append(':').append(sourceLines[currentLine - 1]).append('\n')
            currentLine++
        }
    }

    private fun renderFunctionLines(
        out: StringBuilder,
        function: CoverageFunction,
        sourceLines: List<String>,
        initialLine: Int,
    ): Int {
        var currentLine = initialLine
        var index = 0
        while (index < function.lines.size) {
            val firstLine = function.lines[index].firstLine
            var lastLine = function.lines[index].lastLine
            var count = function.lines[index].count
            var hasZero = false
            var sameLine = firstLine == lastLine
            index++
            while (index < function.lines.size && function.lines[index].firstLine == firstLine) {
                val next = function.lines[index]
                if (next.count == 0uL) hasZero = true
                else if (next.count > count) count = next.count
                sameLine = next.firstLine == next.lastLine
                lastLine = next.lastLine
                index++
            }
            if (sameLine) lastLine++
            while (currentLine < firstLine && currentLine <= sourceLines.size) {
                out.append("        -:").append(String.format("%5d", currentLine))
                    .append(':').append(sourceLines[currentLine - 1]).append('\n')
                currentLine++
            }
            while (currentLine < lastLine && currentLine <= sourceLines.size) {
                when {
                    count == 0uL -> out.append("    #####:")
                        .append(String.format("%5d", currentLine)).append(':')
                    hasZero -> out.append(String.format("%8s*:", count.toString()))
                        .append(String.format("%5d", currentLine)).append(':')
                    else -> out.append(String.format("%9s:", count.toString()))
                        .append(String.format("%5d", currentLine)).append(':')
                }
                out.append(sourceLines[currentLine - 1]).append('\n')
                currentLine++
            }
        }
        return currentLine
    }

    private fun percent(numerator: Int, denominator: Int): String =
        String.format("%.02f", 100.0 * numerator / denominator)

    private fun readChannel(channel: FileChannel): String {
        channel.position(0)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8192)
        while (channel.read(buffer) > 0) {
            buffer.flip()
            val part = ByteArray(buffer.remaining())
            buffer.get(part)
            bytes.write(part)
            buffer.clear()
        }
        return bytes.toByteArray().toString(Charset.defaultCharset())
    }

    private fun readUnsigned(data: ByteArray, offset: Int, size: Int): Long {
        require(offset >= 0 && size in 1..8 && offset + size <= data.size) { "read outside coverage data" }
        val buffer = ByteBuffer.wrap(data, offset, size).order(ByteOrder.LITTLE_ENDIAN)
        return when (size) {
            1 -> buffer.get().toLong() and 0xff
            2 -> buffer.short.toLong() and 0xffff
            4 -> buffer.int.toLong() and 0xffff_ffffL
            else -> buffer.long
        }
    }

    private fun readCString(data: ByteArray, offset: Int): Pair<String, Int> {
        require(offset in data.indices) { "string offset is out of range" }
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        require(end < data.size) { "unterminated string in coverage data" }
        return data.copyOfRange(offset, end).toString(Charset.defaultCharset()) to end + 1
    }

    private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) and -alignment
}
