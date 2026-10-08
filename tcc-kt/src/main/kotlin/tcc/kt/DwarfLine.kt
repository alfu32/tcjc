package tcc.kt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** DWARF .debug_line state-machine reader used by tccrun's source backtrace. */
object DwarfLine {
    private const val DW_FORM_DATA1 = 0x0b
    private const val DW_FORM_DATA2 = 0x05
    private const val DW_FORM_DATA4 = 0x06
    private const val DW_FORM_DATA8 = 0x07
    private const val DW_FORM_DATA16 = 0x1e
    private const val DW_FORM_UDATA = 0x0f
    private const val DW_FORM_LINE_STRP = 0x1f
    private const val DW_LNCT_PATH = 1L
    private const val DW_LNCT_DIRECTORY_INDEX = 2L
    private const val DW_LNE_END_SEQUENCE = 1
    private const val DW_LNE_SET_ADDRESS = 2
    private const val DW_LNE_DEFINE_FILE = 3
    private const val DW_LNE_HI_USER_MINUS_ONE = 0xfe
    private const val DW_LNS_ADVANCE_PC = 2
    private const val DW_LNS_ADVANCE_LINE = 3
    private const val DW_LNS_SET_FILE = 4
    private const val DW_LNS_CONST_ADD_PC = 8
    private const val DW_LNS_FIXED_ADVANCE_PC = 9

    private data class Format(val content: Long, val form: Long)
    private data class FileEntry(val name: String, val directory: Long)

    /** Mirrors rt_printline_dwarf(); returns the function address or zero when no row matches. */
    fun rt_printline_dwarf(
        context: TccRunDebugContext,
        wantedPc: Long,
        info: TccRunBacktraceInfo,
    ): Long {
        val lineData = context.dwarfLine
        val lineStrings = context.dwarfLineStrings
        var unitStart = 0
        while (unitStart + 4 <= lineData.size) {
            val reader = Reader(lineData, unitStart, lineData.size)
            val initialLength = reader.u32()
            var offsetSize = 4
            val unitLength = if (initialLength == 0xffff_ffffL) {
                offsetSize = 8
                reader.u64()
            } else initialLength
            if (unitLength <= 0 || unitLength > (lineData.size - reader.position).toLong()) break
            val unitEnd = reader.position + unitLength.toInt()
            val version = reader.u16()
            val addressSize: Int
            if (version >= 5) {
                addressSize = reader.u8()
                reader.u8() // segment selector size
            } else addressSize = context.pointerSize
            val headerLength = if (offsetSize == 4) reader.u32() else reader.u64()
            val headerEnd = reader.position + headerLength.toInt()
            if (headerEnd > unitEnd || headerEnd < reader.position) break

            val minimumInstructionLength = reader.u8()
            val maximumOperations = if (version >= 4) reader.u8().coerceAtLeast(1) else 1
            reader.u8() // default_is_stmt
            val lineBase = reader.u8().toByte().toInt()
            val lineRange = reader.u8().coerceAtLeast(1)
            val opcodeBase = reader.u8().coerceAtLeast(1)
            val opcodeLengths = IntArray(opcodeBase)
            for (i in 1 until opcodeBase) opcodeLengths[i] = reader.u8()
            val files = if (version >= 5) {
                val version5Files = readV5Files(reader, headerEnd, offsetSize, lineStrings)
                if (version5Files == null) {
                    unitStart = unitEnd
                    continue
                }
                version5Files
            } else readLegacyFiles(reader, headerEnd)
            reader.position = headerEnd

            var address = 0L
            var lastAddress = 0L
            var operationIndex = 0
            var line = 1
            var fileIndex = 0
            var functionName: String? = null
            var functionAddress = 0L

            while (reader.position < unitEnd) {
                lastAddress = address
                val opcode = reader.u8()
                if (opcode >= opcodeBase) {
                    val operation = opcode - opcodeBase
                    val operationAdvance = operation / lineRange
                    if (maximumOperations == 1) {
                        address += operationAdvance.toLong() * minimumInstructionLength
                    } else {
                        address += ((operationIndex + operationAdvance) / maximumOperations).toLong() * minimumInstructionLength
                        operationIndex = (operationIndex + operationAdvance) % maximumOperations
                    }
                    val lineAdvance = operation % lineRange + lineBase
                    if (matches(address, lastAddress, wantedPc))
                        return store(info, files, fileIndex, line, functionName, functionAddress)
                    line += lineAdvance
                    continue
                }

                if (opcode == 0) {
                    val extendedLength = reader.uleb().toInt()
                    if (extendedLength <= 0) break
                    val payloadStart = reader.position
                    val payloadEnd = (payloadStart + extendedLength).coerceAtMost(unitEnd)
                    val extendedOpcode = reader.u8()
                    when (extendedOpcode) {
                        DW_LNE_END_SEQUENCE -> Unit
                        DW_LNE_SET_ADDRESS -> {
                            address = reader.unsigned(addressSize)
                            if (context.targetIsMachO) address += context.programBase
                            operationIndex = 0
                        }
                        DW_LNE_DEFINE_FILE -> readLegacyFile(reader, payloadEnd)?.let { files.add(it) }
                        DW_LNE_HI_USER_MINUS_ONE -> {
                            functionAddress = address
                            functionName = reader.cstring(payloadEnd)
                        }
                    }
                    reader.position = payloadEnd
                    continue
                }

                when (opcode) {
                    DW_LNS_ADVANCE_PC -> {
                        val advance = reader.uleb().toInt()
                        if (maximumOperations == 1) address += advance.toLong() * minimumInstructionLength
                        else {
                            address += ((operationIndex + advance) / maximumOperations).toLong() * minimumInstructionLength
                            operationIndex = (operationIndex + advance) % maximumOperations
                        }
                        if (matches(address, lastAddress, wantedPc))
                            return store(info, files, fileIndex, line, functionName, functionAddress)
                    }
                    DW_LNS_ADVANCE_LINE -> line += reader.sleb().toInt()
                    DW_LNS_SET_FILE -> {
                        val selected = reader.uleb().toInt() - if (version < 5) 1 else 0
                        if (selected in files.indices) fileIndex = selected
                    }
                    DW_LNS_CONST_ADD_PC -> {
                        val advance = (255 - opcodeBase) / lineRange
                        if (maximumOperations == 1) address += advance.toLong() * minimumInstructionLength
                        else {
                            address += ((operationIndex + advance) / maximumOperations).toLong() * minimumInstructionLength
                            operationIndex = (operationIndex + advance) % maximumOperations
                        }
                        if (matches(address, lastAddress, wantedPc))
                            return store(info, files, fileIndex, line, functionName, functionAddress)
                    }
                    DW_LNS_FIXED_ADVANCE_PC -> {
                        address += reader.u16().toLong()
                        operationIndex = 0
                        if (matches(address, lastAddress, wantedPc))
                            return store(info, files, fileIndex, line, functionName, functionAddress)
                    }
                    else -> {
                        val operands = opcodeLengths.getOrElse(opcode - 1) { 0 }
                        repeat(operands) { reader.uleb() }
                    }
                }
            }
            unitStart = unitEnd
        }
        info.file = ""
        info.function = ""
        info.functionAddress = 0
        return 0
    }

    private fun readLegacyFiles(reader: Reader, end: Int): MutableList<FileEntry> {
        while (reader.position < end) {
            if (reader.u8() == 0) break
            reader.skipCString(end)
        }
        val files = mutableListOf<FileEntry>()
        while (reader.position < end && reader.peek() != 0) {
            val name = reader.cstring(end)
            val directory = reader.uleb()
            reader.uleb() // modification time
            reader.uleb() // file size
            if (files.size < 512) files.add(FileEntry(name, directory))
        }
        return files
    }

    private fun readLegacyFile(reader: Reader, end: Int): FileEntry? {
        if (reader.position >= end) return null
        val name = reader.cstring(end)
        val directory = reader.uleb()
        reader.uleb()
        reader.uleb()
        return FileEntry(name, directory)
    }

    private fun readV5Files(
        reader: Reader,
        headerEnd: Int,
        offsetSize: Int,
        lineStrings: ByteArray,
    ): MutableList<FileEntry>? {
        val directoryFormats = readFormats(reader)
        val directoryCount = reader.uleb().toInt()
        repeat(directoryCount) {
            for (format in directoryFormats) {
                if (format.content == DW_LNCT_PATH && format.form.toInt() != DW_FORM_LINE_STRP) return null
                skipForm(reader, format.form, offsetSize)
            }
        }
        if (reader.position > headerEnd) return null
        val fileFormats = readFormats(reader)
        val fileCount = reader.uleb().toInt()
        val files = mutableListOf<FileEntry>()
        repeat(fileCount) { index ->
            var filename = ""
            var directory = 0L
            for (format in fileFormats) {
                when {
                    format.content == DW_LNCT_PATH -> {
                        if (format.form.toInt() != DW_FORM_LINE_STRP) return null
                        filename = cStringAt(lineStrings, reader.unsigned(offsetSize).toInt())
                    }
                    format.content == DW_LNCT_DIRECTORY_INDEX -> directory = when (format.form.toInt()) {
                        DW_FORM_DATA1 -> reader.u8().toLong()
                        DW_FORM_DATA2 -> reader.u16().toLong()
                        DW_FORM_DATA4 -> reader.u32()
                        DW_FORM_UDATA -> reader.uleb()
                        else -> return null
                    }
                    else -> skipForm(reader, format.form, offsetSize)
                }
            }
            if (index < 512) files.add(FileEntry(filename, directory))
        }
        return files
    }

    private fun readFormats(reader: Reader): List<Format> {
        val count = reader.u8()
        return List(count) { Format(reader.uleb(), reader.uleb()) }
    }

    private fun skipForm(reader: Reader, form: Long, offsetSize: Int) {
        when (form.toInt()) {
            DW_FORM_DATA1 -> reader.u8()
            DW_FORM_DATA2 -> reader.u16()
            DW_FORM_DATA4 -> reader.skip(3) // Same byte advance as tccrun.c's dwarf_ignore_type macro.
            DW_FORM_DATA8 -> reader.u64()
            DW_FORM_DATA16 -> reader.skip(16)
            DW_FORM_UDATA -> reader.uleb()
            DW_FORM_LINE_STRP -> reader.unsigned(offsetSize)
            else -> throw IllegalArgumentException("unsupported DWARF line-table form: $form")
        }
    }

    private fun matches(address: Long, lastAddress: Long, wantedPc: Long): Boolean =
        address >= wantedPc && wantedPc >= lastAddress

    private fun store(
        info: TccRunBacktraceInfo,
        files: List<FileEntry>,
        fileIndex: Int,
        line: Int,
        functionName: String?,
        functionAddress: Long,
    ): Long {
        files.getOrNull(fileIndex)?.let { info.file = it.name.take(99); info.line = line }
        if (functionName != null) info.function = functionName.take(99)
        info.functionAddress = functionAddress
        return functionAddress
    }

    private fun cStringAt(data: ByteArray, offset: Int): String {
        if (offset !in data.indices) return ""
        var end = offset
        while (end < data.size && data[end] != 0.toByte()) end++
        return data.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private class Reader(private val data: ByteArray, var position: Int, private val limit: Int) {
        fun peek(): Int = if (position < limit) data[position].toInt() and 0xff else 0
        fun u8(): Int = if (position < limit) data[position++].toInt() and 0xff else 0
        fun u16(): Int = unsigned(2).toInt()
        fun u32(): Long = unsigned(4)
        fun u64(): Long = unsigned(8)

        fun unsigned(size: Int): Long {
            val actual = size.coerceIn(0, 8)
            val start = position
            val end = (start + actual).coerceAtMost(limit)
            val count = end - start
            val buffer = ByteBuffer.wrap(data, start, count).order(ByteOrder.LITTLE_ENDIAN)
            position = end
            return when (count) {
                1 -> buffer.get().toLong() and 0xff
                2 -> buffer.short.toLong() and 0xffff
                4 -> buffer.int.toLong() and 0xffff_ffffL
                8 -> buffer.long
                else -> 0
            }
        }

        fun uleb(): Long {
            var result = 0L
            var shift = 0
            repeat(10) {
                val byte = u8()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            return result
        }

        fun sleb(): Long {
            var result = 0L
            var shift = 0
            var byte: Int
            do {
                byte = u8()
                result = result or ((byte and 0x7f).toLong() shl shift)
                shift += 7
            } while (byte and 0x80 != 0 && shift < 70)
            if (shift < 64 && byte and 0x40 != 0) result = result or (-1L shl shift)
            return result
        }

        fun cstring(end: Int = limit): String {
            val start = position
            while (position < minOf(end, limit) && data[position] != 0.toByte()) position++
            val result = data.copyOfRange(start, position).toString(Charsets.UTF_8)
            if (position < minOf(end, limit)) position++
            return result
        }

        fun skipCString(end: Int) { cstring(end) }
        fun skip(count: Int) { position = (position + count).coerceAtMost(limit) }
    }
}
