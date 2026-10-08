package tcc.kt

/** C6x COFF output, debug symbol ordering, and loader helpers from tcccoff.c. */
object TccCoff {
    const val MAX_SECTIONS = 255
    const val MAX_STRING_TABLE = 1_000_000
    const val MAX_FUNCTIONS = 1000
    const val MAX_FUNCTION_NAME_LENGTH = 128
    const val FILE_HEADER_SIZE = 22
    const val OPTIONAL_HEADER_SIZE = 28
    const val SECTION_HEADER_SIZE = 48
    const val SYMBOL_SIZE = 18
    const val LINE_NUMBER_SIZE = 6
    const val MAGIC = 0x00c2
    const val DEBUG_SECTION = -2
    const val FILE_SYMBOL = 4
    const val FUNCTION_SYMBOL = 0x12
    const val TEXT_FLAGS = 0x20 or 0x40 or 0x100 or 0x400
    const val DATA_FLAGS = 0x40
    const val BSS_FLAGS = 0x80
    const val STACK_FLAGS = 0x80 or 0x100 or 0x200
    const val CINIT_FLAGS = 0x10 or 0x40 or 0x100 or 0x200

    data class Section(
        val name: String,
        val address: Long = 0,
        val size: Int = 0,
        val data: ByteArray = byteArrayOf(),
        val relocations: List<Relocation> = emptyList(),
        val lineNumbers: List<LineNumber> = emptyList(),
    )
    data class Relocation(val address: Long, val symbolIndex: Int, val displacement: Int, val type: Int)
    data class LineNumber(val address: Long, val line: Int, val symbolIndex: Int? = null, val symbolName: String? = null)
    data class StabSymbol(val type: Int, val stringOffset: Int, val value: Long, val description: Int)
    data class DebugLineData(val lines: List<LineNumber>, val functions: List<FunctionDebug>)
    data class ElfSymbol(val name: String, val value: Long, val info: Int, val other: Int = 0, val sectionIndex: Int = 0)
    data class FunctionDebug(
        val name: String,
        val file: String,
        val address: Long,
        val endAddress: Long,
        val lineEntryCount: Int,
        val lastLine: Int,
        val lineFilePointer: Int = 0,
    )
    data class FileHeader(
        var magic: Int = MAGIC,
        var sections: Int = 0,
        var timestamp: Int = 0,
        var symbolOffset: Int = 0,
        var symbolCount: Int = 0,
        var optionalHeaderSize: Int = OPTIONAL_HEADER_SIZE,
        var flags: Int = 0x1143,
        var targetId: Int = 0x99,
    )
    data class OptionalHeader(
        var magic: Int = 0x0108,
        var version: Int = 0x0190,
        var textSize: Int = 0,
        var dataSize: Int = 0,
        var bssSize: Int = 0,
        var entryPoint: Int = 0,
        var textStart: Int = 0,
        var dataStart: Int = 0,
    )
    data class SectionHeader(
        val name: String,
        var physicalAddress: Long = 0,
        var virtualAddress: Long = 0,
        var size: Int = 0,
        var dataOffset: Int = 0,
        var relocationOffset: Int = 0,
        var lineOffset: Int = 0,
        var relocationCount: Int = 0,
        var lineCount: Int = 0,
        var flags: Int = 0,
    )
    data class State(
        val sections: MutableList<Section> = mutableListOf(),
        val symbols: MutableList<ElfSymbol> = mutableListOf(),
        val functionDebug: MutableList<FunctionDebug> = mutableListOf(),
        var debugEnabled: Boolean = false,
        var mainEntryPoint: Int = 0,
    )
    data class Output(val bytes: ByteArray, val fileHeader: FileHeader, val optionalHeader: OptionalHeader, val sections: List<SectionHeader>)
    data class LoadedSymbol(val name: String, val value: Long, val type: Int, val storageClass: Int, val sectionNumber: Int)
    data class LoadedObject(val fileHeader: FileHeader, val optionalHeader: OptionalHeader, val symbols: List<LoadedSymbol>)

    fun outputTheSection(section: Section): Boolean = section.name == ".text" || section.name == ".data"

    fun getCoffFlags(name: String): Int = when (name) {
        ".text" -> TEXT_FLAGS
        ".data" -> DATA_FLAGS
        ".bss" -> BSS_FLAGS
        ".stack" -> STACK_FLAGS
        ".cinit" -> CINIT_FLAGS
        else -> 0
    }

    fun findSection(sections: List<Section>, name: String): Section =
        sections.firstOrNull { it.name == name } ?: error("could not find section $name")

    /** Sorts filename/function symbols by source file, retaining all other entries afterwards. */
    fun sortSymbolTable(symbols: List<ElfSymbol>, functions: List<FunctionDebug>): List<ElfSymbol> {
        val result = ArrayList<ElfSymbol>(symbols.size)
        for (file in symbols.filter { it.info == FILE_SYMBOL }) {
            result += file
            for (function in symbols.filter { it.info == FUNCTION_SYMBOL }) {
                val metadata = functions.firstOrNull { it.name == function.name }
                    ?: error("debug (sort) info can't find function: ${function.name}")
                if (metadata.file == file.name) result += function
            }
        }
        result += symbols.filter { it.info != FILE_SYMBOL && it.info != FUNCTION_SYMBOL }
        check(result.size == symbols.size) { "Internal Compiler error, debug info" }
        return result
    }

    fun findCoffSymbolIndex(symbols: List<ElfSymbol>, functionName: String): Int {
        var index = 0
        for (symbol in symbols) when (symbol.info) {
            FILE_SYMBOL -> index++
            FUNCTION_SYMBOL -> {
                if (symbol.name == functionName) return index
                index += 6
            }
            else -> index += 2
        }
        return index
    }

    /** Converts supported STABS function, source, include, and line records to COFF lines. */
    fun convertStabsToCoffLines(records: List<StabSymbol>, stringTable: ByteArray, lineTableFileOffset: Int = 0): DebugLineData {
        val lines = mutableListOf<LineNumber>()
        val functions = mutableListOf<FunctionDebug>()
        val includes = mutableListOf<String>()
        var currentFile = ""
        var functionName = ""
        var functionStart = 0L
        var lastPc = 0L
        var lastLine = 1
        var functionLineStart = 0

        fun stabString(offset: Int): String {
            if (offset !in stringTable.indices) return ""
            var end = offset
            while (end < stringTable.size && stringTable[end].toInt() != 0) end++
            return stringTable.copyOfRange(offset, end).toString(Charsets.UTF_8)
        }

        for (record in records) when (record.type) {
            0x24 -> { // N_FUN
                if (record.stringOffset == 0) {
                    lines += LineNumber(lastPc, lastLine + 1)
                    val endAddress = functionStart + record.value
                    functions += FunctionDebug(functionName, includes.lastOrNull() ?: currentFile, functionStart, endAddress,
                        lines.size - functionLineStart - 1, lastLine + 1, lineTableFileOffset + functionLineStart * LINE_NUMBER_SIZE)
                    functionName = ""
                } else {
                    functionName = stabString(record.stringOffset).substringBefore(':')
                    functionStart = record.value
                    lastPc = functionStart
                    lastLine = -1
                    functionLineStart = lines.size
                    lines += LineNumber(0, 0, symbolName = functionName)
                }
            }
            0x44 -> { // N_SLINE
                val pc = functionStart + record.value
                lines += LineNumber(lastPc, if (lastLine == -1) record.description else lastLine + 1)
                lastPc = pc
                lastLine = record.description
            }
            0x82 -> includes += stabString(record.stringOffset) // N_BINCL
            0xa2 -> if (includes.size > 1) includes.removeAt(includes.lastIndex) // N_EINCL
            0x64 -> { // N_SO
                if (record.stringOffset == 0) includes.clear()
                else {
                    val file = stabString(record.stringOffset)
                    if (file.isNotEmpty() && !file.endsWith('/')) {
                        includes += file
                        currentFile = file
                    }
                }
            }
        }
        return DebugLineData(lines, functions)
    }

    fun createOutputHeaders(state: State): Pair<FileHeader, OptionalHeader> {
        val text = findSection(state.sections, ".text")
        val data = findSection(state.sections, ".data")
        val bss = findSection(state.sections, ".bss")
        val header = FileHeader()
        val optional = OptionalHeader(
            textSize = text.size,
            dataSize = data.size,
            bssSize = bss.size,
            entryPoint = state.mainEntryPoint,
            textStart = text.address.toInt(),
            dataStart = data.address.toInt(),
        )
        return header to optional
    }

    /** Lays out and serializes the file, optional header, section headers, data, relocations, and line records. */
    fun serialize(state: State): Output {
        val (file, optional) = createOutputHeaders(state)
        val included = state.sections.filter(::outputTheSection)
        val headers = included.map { section ->
            SectionHeader(section.name, section.address, section.address, section.size, flags = getCoffFlags(section.name),
                relocationCount = section.relocations.size, lineCount = section.lineNumbers.size)
        }
        file.sections = included.size
        file.symbolCount = if (state.debugEnabled) state.symbols.sumOf { coffEntryCount(it) } else 0
        var cursor = FILE_HEADER_SIZE + OPTIONAL_HEADER_SIZE + headers.size * SECTION_HEADER_SIZE
        included.forEachIndexed { index, section ->
            headers[index].dataOffset = cursor
            cursor += section.size
        }
        included.forEachIndexed { index, section ->
            if (section.relocations.isNotEmpty()) {
                headers[index].relocationOffset = cursor
                cursor += section.relocations.size * 10
            }
        }
        included.forEachIndexed { index, section ->
            if (section.lineNumbers.isNotEmpty()) {
                headers[index].lineOffset = cursor
                cursor += section.lineNumbers.size * LINE_NUMBER_SIZE
            }
        }
        file.symbolOffset = cursor
        cursor += file.symbolCount * SYMBOL_SIZE
        val outputSymbols = if (state.debugEnabled) sortSymbolTable(state.symbols, state.functionDebug) else state.symbols
        val strings = if (state.debugEnabled) buildStringTable(outputSymbols) else byteArrayOf()
        if (state.debugEnabled) cursor += 4 + strings.size
        val output = ByteArray(cursor)
        writeFileHeader(output, file)
        writeOptionalHeader(output, FILE_HEADER_SIZE, optional)
        headers.forEachIndexed { index, header -> writeSectionHeader(output, FILE_HEADER_SIZE + OPTIONAL_HEADER_SIZE + index * SECTION_HEADER_SIZE, header) }
        included.forEachIndexed { index, section ->
            val header = headers[index]
            copyAt(output, header.dataOffset, section.data, section.size)
            var offset = header.relocationOffset
            section.relocations.forEach { relocation ->
                put32(output, offset, relocation.address.toInt()); put16(output, offset + 4, relocation.symbolIndex)
                put16(output, offset + 6, relocation.displacement); put16(output, offset + 8, relocation.type)
                offset += 10
            }
            offset = header.lineOffset
            section.lineNumbers.forEach { line ->
                val symbolIndex = line.symbolName?.let { findCoffSymbolIndex(outputSymbols, it) } ?: line.symbolIndex
                put32(output, offset, symbolIndex ?: line.address.toInt())
                put16(output, offset + 4, if (symbolIndex != null) 0 else line.line)
                offset += LINE_NUMBER_SIZE
            }
        }
        if (state.debugEnabled) {
            val symbolData = serializeSymbols(outputSymbols, state.functionDebug)
            copyAt(output, file.symbolOffset, symbolData, symbolData.size)
            put32(output, file.symbolOffset + symbolData.size, strings.size + 4)
            copyAt(output, file.symbolOffset + symbolData.size + 4, strings, strings.size)
        }
        return Output(output, file, optional, headers)
    }

    /** Reads the C67 COFF headers and imports the external symbol kinds accepted by tcc_load_coff(). */
    fun load(data: ByteArray, addSymbol: (String, Long) -> Unit = { _, _ -> }): LoadedObject {
        require(data.size >= FILE_HEADER_SIZE + OPTIONAL_HEADER_SIZE) { "error reading .out file for input" }
        val file = FileHeader(
            magic = getU16(data, 0), sections = getU16(data, 2), timestamp = getI32(data, 4),
            symbolOffset = getI32(data, 8), symbolCount = getI32(data, 12), optionalHeaderSize = getU16(data, 16),
            flags = getU16(data, 18), targetId = getU16(data, 20),
        )
        val optionalOffset = FILE_HEADER_SIZE
        val optional = OptionalHeader(
            magic = getU16(data, optionalOffset), version = getU16(data, optionalOffset + 2),
            textSize = getI32(data, optionalOffset + 4), dataSize = getI32(data, optionalOffset + 8),
            bssSize = getI32(data, optionalOffset + 12), entryPoint = getI32(data, optionalOffset + 16),
            textStart = getI32(data, optionalOffset + 20), dataStart = getI32(data, optionalOffset + 24),
        )
        val stringOffset = file.symbolOffset + file.symbolCount * SYMBOL_SIZE
        require(stringOffset >= 0 && stringOffset + 4 <= data.size) { "error reading .out file for input" }
        val stringSize = getI32(data, stringOffset)
        require(stringSize >= 4 && stringOffset.toLong() + stringSize <= data.size) { "error reading .out file for input" }
        val strings = data.copyOfRange(stringOffset + 4, stringOffset + stringSize)
        val result = mutableListOf<LoadedSymbol>()
        var index = 0
        while (index < file.symbolCount) {
            val offset = file.symbolOffset + index * SYMBOL_SIZE
            require(offset >= 0 && offset + SYMBOL_SIZE <= data.size) { "error reading .out file for input" }
            val shortName = data.copyOfRange(offset, offset + 8)
            val name = if (getI32(data, offset) == 0) {
                val nameOffset = getI32(data, offset + 4) - 4
                require(nameOffset in strings.indices) { "invalid COFF string table symbol offset" }
                readCString(strings, nameOffset)
            } else readCString(shortName, 0)
            val value = getI32(data, offset + 8).toLong() and 0xffffffffL
            val section = getI16(data, offset + 12).toInt()
            val type = getU16(data, offset + 14)
            val storage = data[offset + 16].toInt() and 0xff
            val auxCount = data[offset + 17].toInt() and 0xff
            if (isImportedSymbol(type, storage)) {
                val importedName = if (name.startsWith('_') && name != "_main") name.drop(1) else name
                result += LoadedSymbol(importedName, value, type, storage, section)
                addSymbol(importedName, value)
            }
            index += 1 + auxCount
        }
        return LoadedObject(file, optional, result)
    }

    private fun isImportedSymbol(type: Int, storageClass: Int): Boolean = storageClass == 2 &&
        ((type and 0x30) == 0x20 || (type and 0x30) == 0x30 || type == 0x4 || type == 0x8 ||
            type == 0x18 || type == 0x7 || type == 0x6)

    private fun readCString(data: ByteArray, start: Int): String {
        var end = start
        while (end < data.size && data[end].toInt() != 0) end++
        return data.copyOfRange(start, end).toString(Charsets.UTF_8)
    }

    private fun coffEntryCount(symbol: ElfSymbol): Int = when (symbol.info) {
        FILE_SYMBOL -> 1
        FUNCTION_SYMBOL -> 6
        else -> 2
    }

    private fun buildStringTable(symbols: List<ElfSymbol>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        symbols.filter { it.name.toByteArray().size > 8 }.forEach { symbol ->
            require(out.size() + symbol.name.toByteArray().size < MAX_STRING_TABLE) { "String table too large" }
            out.write(symbol.name.toByteArray(Charsets.UTF_8)); out.write(0)
        }
        return out.toByteArray()
    }

    private fun serializeSymbols(symbols: List<ElfSymbol>, functions: List<FunctionDebug>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var symbolIndex = 0
        var stringOffset = 4
        for (symbol in symbols) {
            val entry = ByteArray(SYMBOL_SIZE)
            val symbolNameOffset = stringOffset
            if (symbol.name.toByteArray(Charsets.UTF_8).size > 8) stringOffset += symbol.name.toByteArray(Charsets.UTF_8).size + 1
            writeSymbolName(entry, symbol.name, symbolNameOffset)
            put32(entry, 8, symbol.value.toInt())
            when (symbol.info) {
                FILE_SYMBOL -> { put32(entry, 8, 33); put16(entry, 12, DEBUG_SECTION); put8(entry, 16, 103) }
                FUNCTION_SYMBOL -> {
                    val debug = functions.firstOrNull { it.name == symbol.name } ?: error("debug info can't find function: ${symbol.name}")
                    put16(entry, 12, 1); put16(entry, 14, 4 or (2 shl 4)); put8(entry, 16, 2); put8(entry, 17, 1)
                    out.write(entry)
                    val auxFunc = ByteArray(18)
                    put32(auxFunc, 4, (debug.endAddress - symbol.value).toInt())
                    put32(auxFunc, 8, debug.lineFilePointer)
                    put32(auxFunc, 12, symbolIndex + 6)
                    out.write(auxFunc)
                    writeFunctionBoundary(out, ".bf", symbol.value.toInt(), 1, debug.lineEntryCount, symbolIndex + 6, true)
                    writeFunctionBoundary(out, ".ef", debug.endAddress.toInt(), 1, debug.lastLine, 0, false)
                    symbolIndex += 6
                    continue
                }
                else -> {
                    val (type, storage) = coffType(symbol.other)
                    put16(entry, 12, 2); put16(entry, 14, type); put8(entry, 16, storage); put8(entry, 17, 1)
                }
            }
            out.write(entry)
            if (symbol.info != FILE_SYMBOL) out.write(ByteArray(18))
            symbolIndex += coffEntryCount(symbol)
        }
        return out.toByteArray()
    }

    private fun writeFunctionBoundary(out: java.io.ByteArrayOutputStream, name: String, value: Int, section: Int, line: Int, nextEntry: Int, begin: Boolean) {
        val entry = ByteArray(18)
        writeInlineName(entry, name)
        put32(entry, 8, value); put16(entry, 12, section); put8(entry, 16, 101); put8(entry, 17, 1)
        out.write(entry)
        val aux = ByteArray(18)
        if (begin) {
            put16(aux, 6, line)
            put32(aux, 12, nextEntry)
        } else put16(aux, 4, line)
        out.write(aux)
    }

    private fun coffType(baseType: Int): Pair<Int, Int> = when (baseType and 0xf) {
        1 -> 2 to 2; 2 -> 3 to 2; 3 -> 4 to 2; 8 -> 6 to 2; 9 -> 7 to 2
        else -> 4 to 6
    }

    private fun writeSymbolName(target: ByteArray, name: String, stringOffset: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        if (nameBytes.size <= 8) writeInlineName(target, name)
        else {
            put32(target, 0, 0)
            put32(target, 4, stringOffset)
        }
    }

    private fun writeInlineName(target: ByteArray, name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8).take(8)
        bytes.forEachIndexed { index, byte -> target[index] = byte }
    }

    private fun writeFileHeader(output: ByteArray, header: FileHeader) {
        put16(output, 0, header.magic); put16(output, 2, header.sections); put32(output, 4, header.timestamp)
        put32(output, 8, header.symbolOffset); put32(output, 12, header.symbolCount); put16(output, 16, header.optionalHeaderSize)
        put16(output, 18, header.flags); put16(output, 20, header.targetId)
    }

    private fun writeOptionalHeader(output: ByteArray, offset: Int, header: OptionalHeader) {
        put16(output, offset, header.magic); put16(output, offset + 2, header.version)
        put32(output, offset + 4, header.textSize); put32(output, offset + 8, header.dataSize); put32(output, offset + 12, header.bssSize)
        put32(output, offset + 16, header.entryPoint); put32(output, offset + 20, header.textStart); put32(output, offset + 24, header.dataStart)
    }

    private fun writeSectionHeader(output: ByteArray, offset: Int, header: SectionHeader) {
        val name = header.name.toByteArray().take(8); name.forEachIndexed { i, value -> output[offset + i] = value }
        put32(output, offset + 8, header.physicalAddress.toInt()); put32(output, offset + 12, header.virtualAddress.toInt())
        put32(output, offset + 16, header.size); put32(output, offset + 20, header.dataOffset); put32(output, offset + 24, header.relocationOffset)
        put32(output, offset + 28, header.lineOffset); put32(output, offset + 32, header.relocationCount); put32(output, offset + 36, header.lineCount)
        put32(output, offset + 40, header.flags); put16(output, offset + 44, 0); put16(output, offset + 46, 0)
    }

    private fun copyAt(target: ByteArray, offset: Int, source: ByteArray, length: Int) {
        if (length > 0) source.copyInto(target, offset, 0, minOf(length, source.size))
    }
    private fun put8(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte() }
    private fun put16(data: ByteArray, offset: Int, value: Int) { put8(data, offset, value); put8(data, offset + 1, value ushr 8) }
    private fun put32(data: ByteArray, offset: Int, value: Int) { put16(data, offset, value); put16(data, offset + 2, value ushr 16) }
    private fun getU16(data: ByteArray, offset: Int): Int = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    private fun getI16(data: ByteArray, offset: Int): Short = getU16(data, offset).toShort()
    private fun getI32(data: ByteArray, offset: Int): Int = getU16(data, offset) or (getU16(data, offset + 2) shl 16)
}
