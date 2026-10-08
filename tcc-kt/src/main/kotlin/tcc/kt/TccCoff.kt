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
    data class LineNumber(val address: Long, val line: Int, val symbolIndex: Int? = null)
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
}
