package tcc.kt

/** STABS, DWARF, and exception-frame support mechanically ported from tccdbg.c. */
object TccDbg {
    const val DWARF_LINE_BASE = -5
    const val DWARF_LINE_RANGE = 14
    const val DWARF_OPCODE_BASE = 13
    const val N_STR_HASH = 251
    const val N_FUN = 0x24
    const val N_SLINE = 0x44
    const val N_SO = 0x64
    const val N_BINCL = 0x82
    const val N_EINCL = 0xa2

    data class DefaultType(val type: Int, val size: Int, val encoding: Int, val stabs: String)
    data class AttributeForm(val attribute: Int, val form: Int)
    data class Abbreviation(val code: Int, val tag: Int, val hasChildren: Boolean, val attributes: List<AttributeForm>)
    data class StabEntry(var stringOffset: Int, val type: Int, val other: Int, var description: Int, val value: Long)
    data class Relocation(val offset: Int, val type: String, val symbol: Int, val addend: Long = 0)
    data class DwarfSection(val name: String, var alignment: Int = 1, var entrySize: Int = 0, val bytes: MutableList<Byte> = mutableListOf(), var flags: Int = 0) {
        val size get() = bytes.size
        fun append(value: Int) { bytes += value.toByte() }
        fun append(values: ByteArray) { values.forEach { bytes += it } }
        fun data(): ByteArray = bytes.toByteArray()
    }
    data class DebugSections(
        val sections: LinkedHashMap<String, DwarfSection>,
        val dwarfVersion: Int,
        val dwarfEnabled: Boolean,
        val stabs: MutableList<StabEntry> = mutableListOf(),
        val stabStrings: MutableList<Byte> = mutableListOf(0),
        val stabStringOffsets: MutableMap<String, Int> = mutableMapOf("" to 0),
        val relocations: MutableMap<String, MutableList<Relocation>> = mutableMapOf(),
        val debugStrings: StringPool = StringPool(),
        val lineStrings: StringPool = StringPool(),
    )
    data class DwarfFile(val name: String, val directoryIndex: Int)
    data class DebugSymbol(
        val name: String,
        val stabType: Int,
        val value: Long,
        val section: String? = null,
        val symbolIndex: Int = 0,
        val typeOffset: Int = 0,
        val file: Int = 0,
        val line: Int = 0,
    )
    data class DebugScope(
        val start: Int,
        val lastTypeIndex: Int,
        val lastForwardTypeIndex: Int,
        var end: Int = 0,
        val symbols: MutableList<DebugSymbol> = mutableListOf(),
        val children: MutableList<DebugScope> = mutableListOf(),
    )
    data class DebugFunctionState(
        val name: String,
        val external: Boolean,
        val sourceFile: Int,
        val sourceLine: Int,
        val startAddress: Long,
        val typeOffset: Int,
        val lineState: DwarfLineState,
    )
    data class DebugTypeEntry(val identity: Long, val offset: Int)
    data class ForwardTypeEntry(val identity: Long, val pendingOffsets: MutableList<Int> = mutableListOf())
    sealed interface DebugType {
        data class Base(val code: Int) : DebugType
        data class Pointer(val target: DebugType) : DebugType
        data class ArrayType(val element: DebugType, val upperBound: Int) : DebugType
        data class Function(val result: DebugType, val parameters: List<DebugType>) : DebugType
        data class Aggregate(val name: String, val isUnion: Boolean, val byteSize: Int, val members: List<DebugMember>, val identity: Long = 0) : DebugType
        data class Enumeration(val name: String, val unsigned: Boolean, val values: List<Pair<String, Long>>, val identity: Long = 0) : DebugType
    }
    data class DebugMember(val name: String, val type: DebugType, val bitOffset: Int, val bitSize: Int = 0)
    data class StabsTypeContext(var nextId: Int = 0, val aggregateIds: MutableMap<Long, Int> = mutableMapOf(), val definedAggregates: MutableSet<Long> = mutableSetOf())
    data class DwarfTypeContext(
        val section: DwarfSection,
        val strings: DebugSections,
        val refs: DwarfSymbolRefs,
        val unitStart: Int,
        val pointerSize: Int,
        val file: Int,
        val line: Int,
        val baseTypes: MutableMap<Int, Int> = mutableMapOf(),
        val typeOffsets: MutableMap<Long, Int> = mutableMapOf(),
    )
    data class CoverageState(
        val section: DwarfSection = DwarfSection(".tcov", flags = 3),
        var lastFileName: String? = null,
        var lastFunctionName: String? = null,
        var line: Int = 0,
        var instruction: Int = 0,
        var counterOffset: Int = 0,
    )
    data class DwarfLineState(
        val directories: MutableList<String> = mutableListOf(),
        val files: MutableList<DwarfFile> = mutableListOf(DwarfFile("", 0), DwarfFile("", 0)),
        val operations: MutableList<Byte> = mutableListOf(),
        var currentFile: Int = 1,
        var lastFile: Int = 1,
        var lastPc: Int = 0,
        var lastLine: Int = 1,
        var lastSourceLine: Int = 0,
        var newFile: Boolean = false,
    )

    class StringPool {
        private val data = mutableListOf<Byte>()
        private val byHash = mutableMapOf<Int, MutableList<Pair<String, Int>>>()
        val size get() = data.size
        fun bytes(): ByteArray = data.toByteArray()

        /** Deduplicates exact strings and shares a matching suffix already present in the pool. */
        fun intern(value: String): Int {
            val hash = stringHash(value)
            val bucket = byHash.getOrPut(hash) { mutableListOf() }
            bucket.firstOrNull { it.first == value }?.let { return it.second }
            val existing = bucket.firstOrNull { it.first.endsWith(value) }
            val offset = if (existing != null) existing.second + existing.first.toByteArray(Charsets.UTF_8).size - value.toByteArray(Charsets.UTF_8).size else data.size
            if (existing == null) {
                value.toByteArray(Charsets.UTF_8).forEach { data += it }
                data += 0
            }
            bucket += value to offset
            return offset
        }
    }

    fun defaultTypes(longSize: Int = 8, longDoubleSize: Int = 16, charUnsignedByDefault: Boolean = false): List<DefaultType> {
        val result = mutableListOf(
            DefaultType(3, 4, 5, "int:t1=r1;-2147483648;2147483647;"),
            DefaultType(1, 1, 6, "char:t2=r2;0;127;"),
            if (longSize == 4) DefaultType(3 or 0x1000, 4, 5, "long int:t3=r3;-2147483648;2147483647;")
            else DefaultType(4 or 0x1000, 8, 5, "long int:t3=r3;-9223372036854775808;9223372036854775807;"),
            DefaultType(3 or 0x10, 4, 7, "unsigned int:t4=r4;0;037777777777;"),
            if (longSize == 4) DefaultType(3 or 0x1000 or 0x10, 4, 7, "long unsigned int:t5=r5;0;037777777777;")
            else DefaultType(4 or 0x1000 or 0x10, 8, 7, "long unsigned int:t5=r5;0;01777777777777777777777;"),
            DefaultType(13, 16, 5, "__int128:t6=r6;0;-1;"),
            DefaultType(13 or 0x10, 16, 7, "__int128 unsigned:t7=r7;0;-1;"),
            DefaultType(4, 8, 5, "long long int:t8=r8;-9223372036854775808;9223372036854775807;"),
            DefaultType(4 or 0x10, 8, 7, "long long unsigned int:t9=r9;0;01777777777777777777777;"),
            DefaultType(2, 2, 5, "short int:t10=r10;-32768;32767;"),
            DefaultType(2 or 0x10, 2, 7, "short unsigned int:t11=r11;0;65535;"),
            DefaultType(1 or 0x20, 1, 6, "signed char:t12=r12;-128;127;"),
            DefaultType(1 or 0x20 or 0x10, 1, 8, "unsigned char:t13=r13;0;255;"),
            DefaultType(8, 4, 4, "float:t14=r1;4;0;"),
            DefaultType(9, 8, 4, "double:t15=r1;8;0;"),
            DefaultType(10, longDoubleSize, 4, "long double:t16=r1;$longDoubleSize;0;"),
            DefaultType(-1, 4, 4, "_Float32:t17=r1;4;0;"),
            DefaultType(-1, 8, 4, "_Float64:t18=r1;8;0;"),
            DefaultType(-1, 16, 4, "_Float128:t19=r1;16;0;"),
            DefaultType(-1, 8, 4, "_Float32x:t20=r1;8;0;"),
            DefaultType(-1, 16, 4, "_Float64x:t21=r1;16;0;"),
            DefaultType(-1, 4, 4, "_Decimal32:t22=r1;4;0;"),
            DefaultType(-1, 8, 4, "_Decimal64:t23=r1;8;0;"),
            DefaultType(-1, 16, 4, "_Decimal128:t24=r1;16;0;"),
        )
        result += if (charUnsignedByDefault) DefaultType(1 or 0x10, 1, 8, "unsigned char:t25=r25;0;255;")
            else DefaultType(1, 1, 6, "unsigned char:t25=r25;0;255;")
        result += DefaultType(11, 1, 2, "bool:t26=r26;0;255;")
        if (longSize == 4) result += DefaultType(0, 1, 8, "void:t27=27")
        else {
            result += DefaultType(3 or 0x1000, 8, 5, "long int:t27=r27;-9223372036854775808;9223372036854775807;")
            result += DefaultType(3 or 0x1000 or 0x10, 8, 7, "long unsigned int:t28=r28;0;01777777777777777777777;")
            result += DefaultType(0, 1, 8, "void:t29=29")
        }
        return result
    }

    fun createSections(dwarfVersion: Int = 4, backtrace: Boolean = false): DebugSections {
        val flags = if (backtrace) 2 else 0
        val sections = linkedMapOf<String, DwarfSection>()
        if (dwarfVersion > 0) {
            listOf(".debug_info", ".debug_abbrev", ".debug_line", ".debug_aranges").forEach { sections[it] = DwarfSection(it, flags = flags) }
            listOf(".debug_macro", ".debug_loc", ".debug_ranges", ".debug_loclists", ".debug_rnglists", ".debug_str_offsets", ".debug_addr").forEach { sections[it] = DwarfSection(it) }
            sections[".debug_str"] = DwarfSection(".debug_str", entrySize = 1, flags = flags or 0x30)
            if (dwarfVersion >= 5) sections[".debug_line_str"] = DwarfSection(".debug_line_str", entrySize = 1, flags = flags or 0x30)
        } else {
            sections[".stab"] = DwarfSection(".stab", alignment = 8, entrySize = 12, flags = flags)
            sections[".stabstr"] = DwarfSection(".stabstr", flags = flags)
            sections[".stab"]!!.bytes += ByteArray(12).toList()
        }
        return DebugSections(sections, dwarfVersion, dwarfVersion > 0).also { if (dwarfVersion == 0) it.stabs += StabEntry(0, 0, 0, 0, 0) }
    }

    fun putStabs(state: DebugSections, text: String?, type: Int, other: Int, description: Int, value: Long): Boolean {
        if (type == N_SLINE && state.stabs.isNotEmpty()) {
            val previous = state.stabs.last()
            if (previous.type == type && previous.value == value) {
                previous.description = description
                return false
            }
        }
        val stringOffset = if (text == null) 0 else putStabString(state, text)
        state.stabs += StabEntry(stringOffset, type, other, description, value)
        return true
    }

    private fun putStabString(state: DebugSections, text: String): Int {
        state.stabStringOffsets[text]?.let { return it }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val offset = state.stabStrings.size
        bytes.forEach { state.stabStrings += it }
        state.stabStrings += 0
        state.stabStringOffsets[text] = offset
        return offset
    }

    fun uleb128(value: Long): ByteArray {
        var remaining = value.toULong()
        val out = mutableListOf<Byte>()
        do {
            var byte = (remaining and 0x7fu).toInt()
            remaining = remaining shr 7
            if (remaining != 0uL) byte = byte or 0x80
            out += byte.toByte()
        } while (remaining != 0uL)
        return out.toByteArray()
    }

    fun sleb128(value: Long): ByteArray {
        var remaining = value
        val out = mutableListOf<Byte>()
        var more: Boolean
        do {
            var byte = (remaining and 0x7f).toInt()
            remaining = remaining shr 7
            more = !((remaining == 0L && byte and 0x40 == 0) || (remaining == -1L && byte and 0x40 != 0))
            if (more) byte = byte or 0x80
            out += byte.toByte()
        } while (more)
        return out.toByteArray()
    }

    fun stringHash(value: String): Int {
        var hash = 5381
        value.toByteArray(Charsets.UTF_8).forEach { byte -> hash += (byte.toInt() and 0xff) + hash * 31 }
        return hash
    }

    val dwarfLineOpcodes = byteArrayOf(0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1)

    /** Builds the DWARF abbreviation table in the exact numeric order expected by tccdbg.c. */
    fun abbreviationTable(pointerSize: Int = 8, dwarfVersion: Int = 5): ByteArray {
        val attrs: (Int, Int) -> AttributeForm = { attribute, form -> AttributeForm(attribute, form) }
        val highPc = if (pointerSize == 4) 0x06 else 0x07
        val lineString = 0x1f
        val table = listOf(
            Abbreviation(1, 0x11, true, listOf(attrs(0x25, 0x0e), attrs(0x13, 0x0b), attrs(0x03, lineString), attrs(0x1b, lineString), attrs(0x11, 0x01), attrs(0x12, highPc), attrs(0x10, 0x17))),
            Abbreviation(2, 0x24, false, listOf(attrs(0x0b, 0x0f), attrs(0x3e, 0x0b), attrs(0x03, 0x0e))),
            Abbreviation(3, 0x34, false, listOf(attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13), attrs(0x3f, 0x0c), attrs(0x02, 0x18))),
            Abbreviation(4, 0x34, false, listOf(attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13), attrs(0x02, 0x18))),
            Abbreviation(5, 0x34, false, listOf(attrs(0x03, 0x0e), attrs(0x49, 0x13), attrs(0x02, 0x18))),
            Abbreviation(6, 0x05, false, listOf(attrs(0x03, 0x0e), attrs(0x49, 0x13), attrs(0x02, 0x18))),
            Abbreviation(7, 0x0f, false, listOf(attrs(0x0b, 0x0b), attrs(0x49, 0x13))),
            Abbreviation(8, 0x01, true, listOf(attrs(0x49, 0x13), attrs(0x01, 0x13))),
            Abbreviation(9, 0x21, false, listOf(attrs(0x49, 0x13), attrs(0x2f, 0x0f))),
            Abbreviation(10, 0x16, false, listOf(attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13))),
            Abbreviation(11, 0x28, false, listOf(attrs(0x03, 0x0e), attrs(0x1c, 0x0d))),
            Abbreviation(12, 0x28, false, listOf(attrs(0x03, 0x0e), attrs(0x1c, 0x0f))),
            Abbreviation(13, 0x04, true, listOf(attrs(0x03, 0x0e), attrs(0x3e, 0x0b), attrs(0x0b, 0x0b), attrs(0x49, 0x13), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x01, 0x13))),
            Abbreviation(14, 0x0d, false, listOf(attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13), attrs(0x38, 0x0f))),
            Abbreviation(15, 0x0d, false, listOf(attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13), attrs(0x0d, 0x0f), attrs(0x6b, 0x0f))),
            Abbreviation(16, 0x13, true, listOf(attrs(0x03, 0x0e), attrs(0x0b, 0x0f), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x01, 0x13))),
            Abbreviation(17, 0x13, false, listOf(attrs(0x03, 0x0e), attrs(0x0b, 0x0f), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f))),
            Abbreviation(18, 0x17, true, listOf(attrs(0x03, 0x0e), attrs(0x0b, 0x0f), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x01, 0x13))),
            Abbreviation(19, 0x17, false, listOf(attrs(0x03, 0x0e), attrs(0x0b, 0x0f), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f))),
            Abbreviation(20, 0x2e, true, listOf(attrs(0x3f, 0x0c), attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13), attrs(0x11, 0x01), attrs(0x12, highPc), attrs(0x01, 0x13), attrs(0x40, 0x18))),
            Abbreviation(21, 0x2e, true, listOf(attrs(0x03, 0x0e), attrs(0x3a, 0x0f), attrs(0x3b, 0x0f), attrs(0x49, 0x13), attrs(0x11, 0x01), attrs(0x12, highPc), attrs(0x01, 0x13), attrs(0x40, 0x18))),
            Abbreviation(22, 0x0b, true, listOf(attrs(0x11, 0x01), attrs(0x12, highPc))),
            Abbreviation(23, 0x0b, false, listOf(attrs(0x11, 0x01), attrs(0x12, highPc))),
            Abbreviation(24, 0x15, true, listOf(attrs(0x49, 0x13), attrs(0x01, 0x13))),
            Abbreviation(25, 0x15, false, listOf(attrs(0x49, 0x13))),
            Abbreviation(26, 0x05, false, listOf(attrs(0x49, 0x13))),
        )
        val bytes = mutableListOf<Byte>()
        table.forEach { abbreviation ->
            bytes += uleb128(abbreviation.code.toLong()).toList(); bytes += uleb128(abbreviation.tag.toLong()).toList()
            bytes += if (abbreviation.hasChildren) 1 else 0
            abbreviation.attributes.forEach { attribute ->
                var form = attribute.form
                if (dwarfVersion < 5 && form == lineString) form = 0x0e
                if (dwarfVersion < 4 && form == 0x17) form = 0x06
                if (dwarfVersion < 4 && form == 0x18) form = 0x0a
                bytes += uleb128(attribute.attribute.toLong()).toList()
                bytes += uleb128(form.toLong()).toList()
            }
            bytes += 0; bytes += 0
        }
        bytes += 0
        return bytes.toByteArray()
    }

    fun beginDwarfCompilationUnit(
        state: DebugSections,
        version: Int,
        pointerSize: Int,
        textStart: Long,
        filename: String,
        compilationDirectory: String,
        producer: String,
        cVersion: Int,
        refs: DwarfSymbolRefs,
        minimumInstructionLength: Int = 1,
    ): DwarfUnitState {
        require(version in 2..5 && pointerSize in setOf(4, 8))
        val info = state.sections.getValue(".debug_info")
        val abbrev = state.sections.getValue(".debug_abbrev")
        val line = state.sections.getValue(".debug_line")
        val abbrevStart = abbrev.size
        abbrev.append(abbreviationTable(pointerSize, version))
        val infoStart = info.size
        val infoLengthOffset = info.size
        writeData4(info, 0); writeData2(info, version)
        if (version >= 5) {
            writeData1(info, 1); writeData1(info, pointerSize)
            state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_32DW", refs.abbrev)
            writeData4(info, abbrevStart)
        } else {
            state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_32DW", refs.abbrev)
            writeData4(info, abbrevStart); writeData1(info, pointerSize)
        }
        writeData1(info, 1)
        writeStringReference(state, info, producer, refs.strings, pointerSize = pointerSize)
        writeData1(info, if (cVersion == 201112) 0x1d else 0x0c)
        val useLineStrings = version >= 5
        val lineStringSymbol = if (useLineStrings) refs.lineStrings else refs.strings
        writeStringReference(state, info, filename, lineStringSymbol, lineString = useLineStrings, pointerSize = pointerSize)
        writeStringReference(state, info, compilationDirectory, lineStringSymbol, lineString = useLineStrings, pointerSize = pointerSize)
        state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_PTR", refs.text)
        val highPcOffset = info.size + pointerSize
        if (pointerSize == 4) { writeData4(info, textStart.toInt()); writeData4(info, 0) }
        else { writeData8(info, textStart); writeData8(info, 0) }
        state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_32DW", refs.line)
        writeData4(info, line.size)

        val lineStart = line.size
        val lineLengthOffset = line.size
        writeData4(line, 0); writeData2(line, version)
        if (version >= 5) { writeData1(line, pointerSize); writeData1(line, 0) }
        val prologueLengthOffset = line.size
        writeData4(line, 0)
        val prologueStart = line.size
        writeData1(line, minimumInstructionLength)
        if (version >= 4) writeData1(line, 1)
        writeData1(line, 1); writeData1(line, DWARF_LINE_BASE); writeData1(line, DWARF_LINE_RANGE); writeData1(line, DWARF_OPCODE_BASE)
        line.append(dwarfLineOpcodes)
        val lineState = createDwarfLineState(filename, compilationDirectory, version)
        lineState.operations += 0
        lineState.operations.addAll(uleb128((1 + pointerSize).toLong()).toList())
        lineState.operations += 2
        repeat(pointerSize) { lineState.operations += 0 }
        return DwarfUnitState(version, pointerSize, infoStart, infoLengthOffset, highPcOffset,
            lineStart, lineLengthOffset, prologueLengthOffset, prologueStart, textStart, refs, lineState)
    }

    /** Completes the unit, aranges, file tables, and buffered line program. */
    fun finishDwarfCompilationUnit(state: DebugSections, unit: DwarfUnitState, textSize: Int) {
        val info = state.sections.getValue(".debug_info")
        val line = state.sections.getValue(".debug_line")
        val aranges = state.sections.getValue(".debug_aranges")
        writeData1(info, 0)
        patch32(info, unit.infoLengthOffset, info.size - unit.infoStart - 4)
        if (unit.pointerSize == 4) patch32(info, unit.highPcOffset, textSize)
        else patch64(info, unit.highPcOffset, textSize.toLong())

        val arangesStart = aranges.size
        writeData4(aranges, 0); writeData2(aranges, 2)
        state.relocations.getOrPut(aranges.name) { mutableListOf() } += Relocation(aranges.size, "R_DATA_32DW", unit.refs.info)
        writeData4(aranges, 0)
        writeData1(aranges, unit.pointerSize); writeData1(aranges, 0); writeData4(aranges, 0)
        state.relocations.getOrPut(aranges.name) { mutableListOf() } += Relocation(aranges.size, "R_DATA_PTR", unit.refs.text)
        if (unit.pointerSize == 4) {
            writeData4(aranges, 0); writeData4(aranges, textSize); writeData4(aranges, 0); writeData4(aranges, 0)
        } else {
            writeData8(aranges, 0); writeData8(aranges, textSize.toLong()); writeData8(aranges, 0); writeData8(aranges, 0)
        }
        patch32(aranges, arangesStart, aranges.size - arangesStart - 4)

        if (unit.version >= 5) {
            writeData1(line, 1)
            writeUleb(line, 1); writeUleb(line, 0x1f); writeUleb(line, unit.lineState.directories.size.toLong())
            unit.lineState.directories.forEach { writeStringReference(state, line, it, unit.refs.lineStrings, true, unit.pointerSize) }
            writeData1(line, 2)
            writeUleb(line, 1); writeUleb(line, 0x1f); writeUleb(line, 0x3b); writeUleb(line, 0x0f)
            writeUleb(line, unit.lineState.files.size.toLong())
            unit.lineState.files.forEach { file ->
                writeStringReference(state, line, file.name, unit.refs.lineStrings, true, unit.pointerSize)
                writeUleb(line, file.directoryIndex.toLong())
            }
        } else {
            unit.lineState.directories.forEach { writeCString(line, it) }
            writeData1(line, 0)
            unit.lineState.files.forEach { file ->
                writeCString(line, file.name)
                writeUleb(line, file.directoryIndex.toLong()); writeUleb(line, 0); writeUleb(line, 0)
            }
            writeData1(line, 0)
        }
        unit.lineState.operations += 0
        unit.lineState.operations.addAll(uleb128(1).toList())
        unit.lineState.operations += 1 // DW_LNE_end_sequence
        val prologueLength = line.size - unit.linePrologueStart
        patch32(line, unit.linePrologueLengthOffset, prologueLength)
        val programStart = line.size
        repeat(3) { writeData1(line, 0) }
        state.relocations.getOrPut(line.name) { mutableListOf() } += Relocation(line.size, "R_DATA_PTR", unit.refs.text)
        repeat((unit.lineState.operations.size - 3).coerceAtLeast(0)) { writeData1(line, 0) }
        unit.lineState.operations.forEachIndexed { index, byte -> line.bytes[programStart + index] = byte }
        patch32(line, unit.lineLengthOffset, line.size - unit.lineStart - 4)
        state.sections[".debug_str"]?.append(state.debugStrings.bytes())
        state.sections[".debug_line_str"]?.append(state.lineStrings.bytes())
    }

    private fun writeCString(section: DwarfSection, value: String) {
        section.append(value.toByteArray(Charsets.UTF_8)); section.append(0)
    }

    private fun patch64(section: DwarfSection, offset: Int, value: Long) {
        repeat(8) { byte -> section.bytes[offset + byte] = (value ushr (byte * 8)).toByte() }
    }

    fun registerDwarfFile(state: DwarfLineState, filename: String, dwarfVersion: Int): Int {
        val indexOffset = if (dwarfVersion < 5) 1 else 0
        if (filename == "<command line>") { state.currentFile = 1; return 1 }
        val slash = filename.lastIndexOf('/')
        val directory = if (slash < 0) "" else filename.substring(0, slash)
        val basename = if (slash < 0) filename else filename.substring(slash + 1)
        val directoryIndex = if (directory.isEmpty()) 0 else {
            val existing = state.directories.indexOf(directory)
            if (existing >= 0) existing + indexOffset else state.directories.apply { add(directory) }.lastIndex + indexOffset
        }
        val existingFile = state.files.drop(1).indexOfFirst { it.directoryIndex == directoryIndex && it.name == basename } + 1
        if (existingFile > 0 && state.files[existingFile].directoryIndex == directoryIndex && state.files[existingFile].name == basename) {
            state.currentFile = existingFile + indexOffset
            return state.currentFile
        }
        state.files += DwarfFile(basename, directoryIndex)
        state.currentFile = state.files.lastIndex + indexOffset
        return state.currentFile
    }

    fun createDwarfLineState(mainFile: String, compilationDirectory: String, dwarfVersion: Int): DwarfLineState {
        val slash = mainFile.lastIndexOf('/')
        val baseName = if (slash < 0) mainFile else mainFile.substring(slash + 1)
        val directories = mutableListOf(compilationDirectory)
        val firstDirectory = if (slash < 0) 0 else {
            directories += mainFile.substring(0, slash)
            1
        }
        return DwarfLineState(
            directories = directories,
            files = mutableListOf(DwarfFile(baseName, 0), DwarfFile(baseName, firstDirectory)),
            currentFile = 1,
        )
    }

    fun lineOperation(state: DwarfLineState, opcode: Int) { state.operations += opcode.toByte() }
    fun lineOperationUleb(state: DwarfLineState, value: Long) { state.operations.addAll(uleb128(value).toList()) }
    fun lineOperationSleb(state: DwarfLineState, value: Long) { state.operations.addAll(sleb128(value).toList()) }

    /** Emits the compact DWARF line opcodes used by tcc_debug_line. */
    fun emitDwarfLine(state: DwarfLineState, address: Int, sourceLine: Int, minimumInstructionLength: Int = 1): Boolean {
        if (sourceLine == state.lastSourceLine) return false
        state.lastSourceLine = sourceLine
        val pcDelta = (address - state.lastPc) / minimumInstructionLength
        val lineDelta = sourceLine - state.lastLine
        if (state.currentFile != state.lastFile) {
            state.lastFile = state.currentFile
            lineOperation(state, 4) // DW_LNS_set_file
            lineOperationUleb(state, state.currentFile.toLong())
        }
        var special = pcDelta * DWARF_LINE_RANGE + lineDelta + DWARF_OPCODE_BASE - DWARF_LINE_BASE
        if (pcDelta != 0 && lineDelta in DWARF_LINE_BASE..(DWARF_OPCODE_BASE + DWARF_LINE_BASE) && special in DWARF_OPCODE_BASE..255) {
            lineOperation(state, special)
        } else {
            if (pcDelta != 0) {
                special = pcDelta * DWARF_LINE_RANGE + DWARF_OPCODE_BASE - DWARF_LINE_BASE
                if (special in DWARF_OPCODE_BASE..255) lineOperation(state, special)
                else { lineOperation(state, 2); lineOperationUleb(state, pcDelta.toLong()) }
            }
            if (lineDelta != 0) {
                special = lineDelta + DWARF_OPCODE_BASE - DWARF_LINE_BASE
                if (lineDelta in DWARF_LINE_BASE..(DWARF_OPCODE_BASE + DWARF_LINE_BASE) && special in DWARF_OPCODE_BASE..255) {
                    lineOperation(state, special)
                } else {
                    lineOperation(state, 3); lineOperationSleb(state, lineDelta.toLong())
                    lineOperation(state, DWARF_OPCODE_BASE - DWARF_LINE_BASE)
                }
            }
        }
        state.lastPc = address
        state.lastLine = sourceLine
        return true
    }

    fun debugNewFile(state: DebugSections, line: DwarfLineState, filename: String): Int {
        if (!state.dwarfEnabled) { line.newFile = true; return line.currentFile }
        return registerDwarfFile(line, filename, state.dwarfVersion).also { line.newFile = true }
    }

    fun debugIncludeBegin(state: DebugSections, line: DwarfLineState, filename: String): Int {
        if (state.dwarfEnabled) return registerDwarfFile(line, filename, state.dwarfVersion).also { line.newFile = true }
        putStabs(state, filename, N_BINCL, 0, 0, 0)
        line.newFile = true
        return line.currentFile
    }

    fun debugIncludeEnd(state: DebugSections, line: DwarfLineState, filename: String = ""): Int {
        if (state.dwarfEnabled) return registerDwarfFile(line, filename, state.dwarfVersion).also { line.newFile = true }
        putStabs(state, null, N_EINCL, 0, 0, 0)
        line.newFile = true
        return line.currentFile
    }

    fun emitStabsSourceLine(state: DebugSections, line: DwarfLineState, sourceLine: Int, address: Long, functionAddress: Long? = null): Boolean {
        if (sourceLine == line.lastSourceLine) return false
        line.lastSourceLine = sourceLine
        val value = if (functionAddress == null) address else address - functionAddress
        putStabs(state, null, N_SLINE, 0, sourceLine, value)
        return true
    }

    /** Opens a lexical debug scope while retaining the type-table checkpoints to restore at close. */
    fun openDebugScope(scopes: MutableList<DebugScope>, start: Int, typeCount: Int, forwardTypeCount: Int): DebugScope {
        return DebugScope(start, typeCount, forwardTypeCount).also { scope ->
            scopes.lastOrNull()?.children?.add(scope)
            scopes += scope
        }
    }

    /** Closes the active lexical scope and returns its saved type-table checkpoints. */
    fun closeDebugScope(scopes: MutableList<DebugScope>, end: Int): DebugScope? {
        if (scopes.isEmpty()) return null
        return scopes.removeAt(scopes.lastIndex).also { it.end = end }
    }

    fun findDebugType(entries: List<DebugTypeEntry>, identity: Long): Int =
        entries.firstOrNull { it.identity == identity }?.offset ?: -1

    fun rememberDebugType(entries: MutableList<DebugTypeEntry>, identity: Long, offset: Int): Int {
        entries += DebugTypeEntry(identity, offset)
        return offset
    }

    fun rememberForwardType(entries: MutableList<ForwardTypeEntry>, identity: Long): ForwardTypeEntry =
        entries.firstOrNull { it.identity == identity } ?: ForwardTypeEntry(identity).also(entries::add)

    fun resolveForwardType(entries: MutableList<ForwardTypeEntry>, identity: Long, offset: Int, patch32: (Int, Int) -> Unit): Boolean {
        val pending = entries.firstOrNull { it.identity == identity } ?: return false
        pending.pendingOffsets.forEach { patch32(it, offset) }
        entries.remove(pending)
        return true
    }

    fun addScopeSymbol(scope: DebugScope, symbol: DebugSymbol) { scope.symbols += symbol }

    /** Serializes one C type in the compact STABS notation used by tcc_get_debug_info. */
    fun stabsType(type: DebugType, context: StabsTypeContext): String {
        fun next(): Int = ++context.nextId
        fun render(current: DebugType): String = when (current) {
            is DebugType.Base -> current.code.toString()
            is DebugType.Pointer -> "${next()}=*${render(current.target)}"
            is DebugType.ArrayType -> "${next()}=ar1;0;${current.upperBound};${render(current.element)}"
            is DebugType.Function -> "${next()}=f${render(current.result)}"
            is DebugType.Aggregate -> {
                val id = context.aggregateIds.getOrPut(current.identity) { next() }
                if (!context.definedAggregates.add(current.identity)) id.toString() else buildString {
                    append(current.name).append(":T").append(id).append('=').append(if (current.isUnion) 'u' else 's').append(current.byteSize)
                    current.members.forEach { member ->
                        append(member.name).append(':').append(render(member.type)).append(',').append(member.bitOffset).append(',')
                        append(if (member.bitSize > 0) member.bitSize else 0).append(';')
                    }
                    append(';')
                }
            }
            is DebugType.Enumeration -> {
                val id = context.aggregateIds.getOrPut(current.identity) { next() }
                if (!context.definedAggregates.add(current.identity)) id.toString() else buildString {
                    append(current.name).append(":T").append(id).append("=e")
                    current.values.forEach { (name, value) -> append(name).append(':').append(value).append(',') }
                    append(';')
                }
            }
        }
        return render(type)
    }

    /** Emits DWARF type DIEs using the abbreviation numbers from tccdbg.c. */
    fun emitDwarfType(type: DebugType, context: DwarfTypeContext): Int {
        fun ref(offset: Int) = offset - context.unitStart
        fun name(value: String) = writeStringReference(context.strings, context.section, value, context.refs.strings, pointerSize = context.pointerSize)
        fun emit(current: DebugType): Int = when (current) {
            is DebugType.Base -> context.baseTypes[current.code] ?: error("missing DWARF base type ${current.code}")
            is DebugType.Pointer -> {
                val target = emit(current.target)
                val offset = context.section.size
                writeData1(context.section, 7); writeData1(context.section, context.pointerSize)
                writeData4(context.section, ref(target)); offset
            }
            is DebugType.ArrayType -> {
                val element = emit(current.element)
                val offset = context.section.size
                writeData1(context.section, 8); writeData4(context.section, ref(element))
                val sibling = context.section.size; writeData4(context.section, 0)
                writeData1(context.section, 9); writeData4(context.section, ref(element)); writeUleb(context.section, current.upperBound.toLong())
                writeData1(context.section, 0); patch32(context.section, sibling, ref(context.section.size)); offset
            }
            is DebugType.Aggregate -> {
                context.typeOffsets[current.identity]?.let { return it }
                val offset = context.section.size
                context.typeOffsets[current.identity] = offset
                val hasMembers = current.members.isNotEmpty()
                writeData1(context.section, if (current.isUnion) if (hasMembers) 18 else 19 else if (hasMembers) 16 else 17)
                name(current.name); writeUleb(context.section, current.byteSize.toLong())
                writeUleb(context.section, context.file.toLong()); writeUleb(context.section, context.line.toLong())
                val sibling = if (hasMembers) context.section.size.also { writeData4(context.section, 0) } else -1
                current.members.forEach { member ->
                    val memberType = emit(member.type)
                    writeData1(context.section, if (member.bitSize > 0) 15 else 14); name(member.name)
                    writeUleb(context.section, context.file.toLong()); writeUleb(context.section, context.line.toLong())
                    val typeOffset = context.section.size; writeData4(context.section, ref(memberType))
                    if (member.bitSize > 0) { writeUleb(context.section, member.bitSize.toLong()); writeUleb(context.section, member.bitOffset.toLong()) }
                    context.strings.relocations.getOrPut(context.section.name) { mutableListOf() }
                        .add(Relocation(typeOffset, "R_DATA_32DW", context.refs.info))
                }
                if (hasMembers) { writeData1(context.section, 0); patch32(context.section, sibling, ref(context.section.size)) }
                offset
            }
            is DebugType.Enumeration -> {
                context.typeOffsets[current.identity]?.let { return it }
                val offset = context.section.size; context.typeOffsets[current.identity] = offset
                writeData1(context.section, 13); name(current.name); writeData1(context.section, if (current.unsigned) 7 else 5); writeData1(context.section, 4)
                writeData4(context.section, 0); writeUleb(context.section, context.file.toLong()); writeUleb(context.section, context.line.toLong())
                val sibling = context.section.size; writeData4(context.section, 0)
                current.values.forEach { (enumName, value) ->
                    writeData1(context.section, if (current.unsigned) 12 else 11); name(enumName)
                    if (current.unsigned) writeUleb(context.section, value) else writeSleb(context.section, value)
                }
                writeData1(context.section, 0); patch32(context.section, sibling, ref(context.section.size)); offset
            }
            is DebugType.Function -> {
                val result = emit(current.result); val offset = context.section.size
                writeData1(context.section, if (current.parameters.isEmpty()) 25 else 24)
                writeData4(context.section, ref(result))
                if (current.parameters.isNotEmpty()) {
                    val sibling = context.section.size; writeData4(context.section, 0)
                    current.parameters.forEach { parameter ->
                        val parameterType = emit(parameter)
                        writeData1(context.section, 26); writeData4(context.section, ref(parameterType))
                    }
                    writeData1(context.section, 0); patch32(context.section, sibling, ref(context.section.size))
                }
                offset
            }
        }
        return emit(type)
    }

    /** Serializes a collected lexical scope in the same order used by tcc_debug_finish. */
    fun finishDebugScope(
        state: DebugSections,
        scope: DebugScope,
        pointerSize: Int,
        functionAddress: Long,
        refs: DwarfSymbolRefs,
        parent: Boolean = false,
    ) {
        if (state.dwarfEnabled) {
            val info = state.sections.getValue(".debug_info")
            scope.symbols.asReversed().forEach { symbol ->
                val external = symbol.stabType == 0x20 // N_GSYM
                val static = symbol.stabType == 0x26 // N_STSYM
                val parameter = symbol.stabType == 0xa0 // N_PSYM
                writeData1(info, if (parameter) 6 else if (external) 3 else if (static) 4 else 5)
                writeStringReference(state, info, symbol.name, refs.strings, pointerSize = pointerSize)
                if (external || static) { writeUleb(info, symbol.file.toLong()); writeUleb(info, symbol.line.toLong()) }
                state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_32DW", refs.info)
                writeData4(info, symbol.typeOffset)
                if (external) writeData1(info, 1)
                if (external || static) {
                    writeData1(info, pointerSize + 1); writeData1(info, 0x03) // DW_OP_addr
                    if (static) state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_PTR", symbol.symbolIndex)
                    if (pointerSize == 4) writeData4(info, symbol.value.toInt()) else writeData8(info, symbol.value)
                } else {
                    val encoded = sleb128(symbol.value)
                    writeData1(info, encoded.size + 1); writeData1(info, 0x91) // DW_OP_fbreg
                    writeSleb(info, symbol.value)
                }
            }
            writeData1(info, if (scope.children.isEmpty()) 23 else 22)
            state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_PTR", refs.text)
            val start = functionAddress + scope.start
            val length = (scope.end - scope.start).toLong()
            if (pointerSize == 4) { writeData4(info, start.toInt()); writeData4(info, length.toInt()) }
            else { writeData8(info, start); writeData8(info, length) }
            scope.children.forEach { finishDebugScope(state, it, pointerSize, functionAddress, refs) }
            if (scope.children.isNotEmpty()) writeData1(info, 0)
        } else {
            scope.symbols.forEach { symbol ->
                putStabs(state, symbol.name, symbol.stabType, 0, 0, symbol.value)
            }
            putStabs(state, null, 0xc0, 0, 0, scope.start.toLong()) // N_LBRAC
            scope.children.forEach { finishDebugScope(state, it, pointerSize, functionAddress, refs) }
            putStabs(state, null, 0xe0, 0, 0, scope.end.toLong()) // N_RBRAC
        }
    }

    fun addDebugVariable(scope: DebugScope, name: String, stabType: Int, value: Long, typeOffset: Int, file: Int, line: Int) {
        scope.symbols += DebugSymbol(name, stabType, value, typeOffset = typeOffset, file = file, line = line)
    }

    fun beginDebugFunction(
        line: DwarfLineState,
        name: String,
        external: Boolean,
        sourceFile: Int,
        sourceLine: Int,
        address: Long,
        typeOffset: Int,
    ): DebugFunctionState = DebugFunctionState(name, external, sourceFile, sourceLine, address, typeOffset, line)

    /** Emits the function DIE and line markers after the function body has been generated. */
    fun finishDebugFunction(
        state: DebugSections,
        function: DebugFunctionState,
        endAddress: Long,
        pointerSize: Int,
        refs: DwarfSymbolRefs,
        scope: DebugScope? = null,
        backtrace: Boolean = false,
    ) {
        val line = function.lineState
        if (state.dwarfEnabled) {
            val info = state.sections.getValue(".debug_info")
            writeData1(info, if (function.external) 20 else 21)
            if (function.external) writeData1(info, 1)
            writeStringReference(state, info, function.name, refs.strings, pointerSize = pointerSize)
            writeUleb(info, function.sourceFile.toLong()); writeUleb(info, function.sourceLine.toLong())
            state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_PTR", refs.text)
            val length = endAddress - function.startAddress
            if (pointerSize == 4) { writeData4(info, function.startAddress.toInt()); writeData4(info, length.toInt()) }
            else { writeData8(info, function.startAddress); writeData8(info, length) }
            state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_32DW", refs.info)
            writeData4(info, function.typeOffset)
            writeData1(info, 0) // DW_AT_frame_base expression: DW_OP_call_frame_cfa
            writeData1(info, 1); writeData1(info, 0x9c)
            if (backtrace) {
                val payload = function.name.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                lineOperation(line, 0); lineOperationUleb(line, (payload.size + 1).toLong()); lineOperation(line, 0x80)
                payload.forEach { line.operations += it }
            }
            scope?.let { finishDebugScope(state, it, pointerSize, function.startAddress, refs) }
            if (scope != null) writeData1(info, 0)
        } else {
            putStabs(state, "${function.name}:${if (function.external) 'F' else 'f'}", N_FUN, 0, function.sourceLine, function.startAddress)
            scope?.let { finishDebugScope(state, it, pointerSize, function.startAddress, refs) }
            putStabs(state, null, N_FUN, 0, 0, endAddress - function.startAddress)
        }
    }

    fun markDebugPrologueEnd(state: DwarfLineState) { lineOperation(state, 10) }
    fun markDebugEpilogueBegin(state: DwarfLineState) { lineOperation(state, 11) }

    fun emitTypedef(state: DebugSections, name: String, type: DebugType, context: StabsTypeContext, dwarf: DwarfTypeContext? = null): Int {
        if (!state.dwarfEnabled) {
            val description = "$name:t${stabsType(type, context)}"
            putStabs(state, description, 0x80, 0, 0, 0)
            return context.nextId
        }
        val typeOffset = dwarf?.let { emitDwarfType(type, it) } ?: return -1
        val info = state.sections.getValue(".debug_info")
        writeData1(info, 10); writeStringReference(state, info, name, dwarf.refs.strings, pointerSize = dwarf.pointerSize)
        writeUleb(info, dwarf.file.toLong()); writeUleb(info, dwarf.line.toLong())
        state.relocations.getOrPut(info.name) { mutableListOf() } += Relocation(info.size, "R_DATA_32DW", dwarf.refs.info)
        writeData4(info, typeOffset - dwarf.unitStart)
        return typeOffset
    }

    fun beginCoverageBlock(
        state: CoverageState,
        enabled: Boolean,
        sourceFile: String,
        function: String,
        sourceLine: Int,
        instruction: Int,
        incrementCounter: (counterOffset: Int) -> Unit,
    ): Int {
        if (!enabled) return state.section.size
        if (state.lastFileName != sourceFile) {
            if (state.lastFunctionName != null) state.section.append(0)
            if (state.lastFileName != null) state.section.append(0)
            state.lastFileName = sourceFile
            appendCString(state.section, sourceFile)
        }
        if (state.lastFunctionName != function) {
            if (state.lastFunctionName != null) state.section.append(0)
            state.lastFunctionName = function
            appendCString(state.section, function)
            while (state.section.size % 8 != 0) state.section.append(0)
            writeData8(state.section, sourceLine.toLong())
        }
        val previousOffset = state.counterOffset
        if (instruction == state.instruction && sourceLine == state.line) {
            state.counterOffset = previousOffset
        } else {
            while (state.section.size % 8 != 0) state.section.append(0)
            state.counterOffset = state.section.size
            state.line = sourceLine
            writeData8(state.section, (sourceLine.toLong() shl 8) or 0xff)
            writeData8(state.section, 0)
            incrementCounter(state.counterOffset)
            state.instruction = instruction
        }
        return state.counterOffset
    }

    fun endCoverageBlock(state: CoverageState, sourceLine: Int, finalLine: Int = 0) {
        if (state.counterOffset == 0) return
        val slot = state.counterOffset
        val old = readData8(state.section, slot)
        val endLine = if (finalLine != 0) finalLine else sourceLine
        patch64(state.section, slot, (old and 0xfffffffffL) or (endLine.toLong() shl 36))
        state.counterOffset = 0
    }

    fun checkCoverageLine(state: CoverageState, sourceLine: Int, startBlock: Boolean, endBlock: () -> Unit, beginBlock: () -> Unit) {
        if (state.line == sourceLine) return
        if (state.line + 1 != sourceLine) {
            endBlock()
            if (startBlock) beginBlock()
        } else state.line = sourceLine
    }

    fun finishCoverageFile(state: CoverageState) {
        if (state.lastFunctionName != null) state.section.append(0)
        if (state.lastFileName != null) state.section.append(0)
    }

    private fun appendCString(section: DwarfSection, value: String) {
        section.append(value.toByteArray(Charsets.UTF_8)); section.append(0)
    }

    private fun readData8(section: DwarfSection, offset: Int): Long {
        var value = 0L
        repeat(8) { i -> value = value or ((section.bytes[offset + i].toLong() and 0xff) shl (i * 8)) }
        return value
    }

    fun writeData1(section: DwarfSection, value: Int) = section.append(value)
    fun writeData2(section: DwarfSection, value: Int) { writeData1(section, value); writeData1(section, value ushr 8) }
    fun writeData4(section: DwarfSection, value: Int) { writeData2(section, value); writeData2(section, value ushr 16) }
    fun writeData8(section: DwarfSection, value: Long) { writeData4(section, value.toInt()); writeData4(section, (value ushr 32).toInt()) }
    fun writeUleb(section: DwarfSection, value: Long) = section.append(uleb128(value))
    fun writeSleb(section: DwarfSection, value: Long) = section.append(sleb128(value))

    fun writeStringReference(
        state: DebugSections,
        output: DwarfSection,
        string: String,
        symbolIndex: Int,
        lineString: Boolean = false,
        pointerSize: Int = 8,
    ): Int {
        val offset = if (lineString) state.lineStrings.intern(string) else state.debugStrings.intern(string)
        state.relocations.getOrPut(output.name) { mutableListOf() } +=
            Relocation(output.size, "R_DATA_32DW", symbolIndex, if (pointerSize == 4) offset.toLong() else 0L)
        writeData4(output, if (pointerSize == 4) offset else 0)
        return offset
    }

    enum class EhTarget { I386, X86_64, ARM, ARM64, RISCV64 }
    data class EhFrameState(val section: DwarfSection, val startOffset: Int, val target: EhTarget)
    data class DwarfSymbolRefs(
        val info: Int, val abbrev: Int, val line: Int, val strings: Int,
        val lineStrings: Int, val text: Int,
    )
    data class DwarfUnitState(
        val version: Int,
        val pointerSize: Int,
        val infoStart: Int,
        val infoLengthOffset: Int,
        val highPcOffset: Int,
        val lineStart: Int,
        val lineLengthOffset: Int,
        val linePrologueLengthOffset: Int,
        val linePrologueStart: Int,
        val textStart: Long,
        val refs: DwarfSymbolRefs,
        val lineState: DwarfLineState,
    )

    /** Emits the target CIE and patches its length after alignment. */
    fun startEhFrame(unwindTables: Boolean, target: EhTarget): EhFrameState? {
        if (!unwindTables) return null
        val section = DwarfSection(".eh_frame", flags = 2)
        val start = section.size
        writeData4(section, 0); writeData4(section, 0)
        writeData1(section, if (target == EhTarget.RISCV64) 3 else 1)
        section.append(byteArrayOf('z'.code.toByte(), 'R'.code.toByte(), 0))
        when (target) {
            EhTarget.I386 -> { writeUleb(section, 1); writeSleb(section, -4); writeUleb(section, 8) }
            EhTarget.X86_64 -> { writeUleb(section, 1); writeSleb(section, -8); writeUleb(section, 16) }
            EhTarget.ARM -> { writeUleb(section, 2); writeSleb(section, -4); writeUleb(section, 14) }
            EhTarget.ARM64 -> { writeUleb(section, 4); writeSleb(section, -8); writeUleb(section, 30) }
            EhTarget.RISCV64 -> { writeUleb(section, 1); writeSleb(section, -4); writeUleb(section, 1) }
        }
        writeUleb(section, 1); writeData1(section, 0x1b)
        writeData1(section, 0x0c)
        val (stackRegister, offset) = when (target) {
            EhTarget.I386 -> 4L to 4L; EhTarget.X86_64 -> 7L to 8L; EhTarget.ARM -> 13L to 0L
            EhTarget.ARM64 -> 31L to 0L; EhTarget.RISCV64 -> 2L to 0L
        }
        writeUleb(section, stackRegister); writeUleb(section, offset)
        when (target) {
            EhTarget.I386 -> { writeData1(section, 0x88); writeUleb(section, 1) }
            EhTarget.X86_64 -> { writeData1(section, 0x90); writeUleb(section, 1) }
            else -> Unit
        }
        while ((section.size - start) and 3 != 0) writeData1(section, 0)
        patch32(section, start, section.size - start - 4)
        return EhFrameState(section, start, target)
    }

    /** Emits one target's FDE state machine and patches the record length. */
    fun emitEhFrameFde(
        frame: EhFrameState,
        functionOffset: Int,
        functionSize: Int,
        textSectionSymbol: Int,
        localStackSize: Int = 0,
        code: ByteArray = byteArrayOf(),
    ): Relocation {
        val section = frame.section
        val start = section.size
        writeData4(section, 0)
        writeData4(section, start - frame.startOffset + 4)
        val relocation = Relocation(section.size, when (frame.target) {
            EhTarget.I386 -> "R_386_PC32"; EhTarget.X86_64 -> "R_X86_64_PC32"; EhTarget.ARM -> "R_ARM_REL32"
            EhTarget.ARM64 -> "R_AARCH64_PREL32"; EhTarget.RISCV64 -> "R_RISCV_32_PCREL"
        }, textSectionSymbol)
        writeData4(section, functionOffset)
        writeData4(section, functionSize)
        writeData1(section, 0)
        when (frame.target) {
            EhTarget.I386 -> {
                writeData1(section, 0x41); writeData1(section, 0x0e); writeUleb(section, 8)
                writeData1(section, 0x85); writeUleb(section, 2)
                writeData1(section, 0x42); writeData1(section, 0x0d); writeUleb(section, 5)
                writeData1(section, 0x04); writeData4(section, functionSize - 5)
                writeData1(section, 0xc5); writeData1(section, 0x0c); writeUleb(section, 4); writeUleb(section, 4)
            }
            EhTarget.X86_64 -> {
                writeData1(section, 0x41); writeData1(section, 0x0e); writeUleb(section, 16)
                writeData1(section, 0x86); writeUleb(section, 2)
                writeData1(section, 0x43); writeData1(section, 0x0d); writeUleb(section, 6)
                writeData1(section, 0x04); writeData4(section, functionSize - 5)
                writeData1(section, 0x0c); writeUleb(section, 7); writeUleb(section, 8)
            }
            EhTarget.ARM -> {
                writeData1(section, 0x42); writeData1(section, 0x0e); writeUleb(section, 8)
                writeData1(section, 0x8e); writeUleb(section, 1)
                writeData1(section, 0x8b); writeUleb(section, 2)
                writeData1(section, 0x04); writeData4(section, functionSize / 2 - 5)
                writeData1(section, 0x0d); writeUleb(section, 11)
            }
            EhTarget.ARM64 -> {
                writeData1(section, 0x41); writeData1(section, 0x0e); writeUleb(section, 224)
                writeData1(section, 0x9d); writeUleb(section, 28); writeData1(section, 0x9e); writeUleb(section, 27)
                writeData1(section, 0x43); writeData1(section, 0x0e); writeUleb(section, (224 + localStackSize).toLong())
                writeData1(section, 0x04); writeData4(section, functionSize / 4 - 5)
                writeData1(section, 0xde); writeData1(section, 0xdd); writeData1(section, 0x0e); writeUleb(section, 0)
            }
            EhTarget.RISCV64 -> {
                writeData1(section, 0x44); writeData1(section, 0x0e); writeUleb(section, 16)
                writeData1(section, 0x48); writeData1(section, 0x81); writeUleb(section, 2); writeData1(section, 0x88); writeUleb(section, 4)
                writeData1(section, 0x48); writeData1(section, 0x0c); writeUleb(section, 8); writeUleb(section, 0)
                writeData1(section, 0x04)
                var bodySize = functionSize
                while (bodySize >= 4 && bodySize <= code.size && read32(code, bodySize - 4) != 0x00008067) bodySize -= 4
                writeData4(section, bodySize - 36)
                writeData1(section, 0x0c); writeUleb(section, 2); writeUleb(section, 16)
                writeData1(section, 0x44); writeData1(section, 0xc1); writeData1(section, 0x44); writeData1(section, 0xc8)
                writeData1(section, 0x44); writeData1(section, 0x0e); writeUleb(section, 0)
            }
        }
        while ((section.size - start) and 3 != 0) writeData1(section, 0)
        patch32(section, start, section.size - start - 4)
        return relocation
    }

    fun endEhFrame(frame: EhFrameState?) { if (frame != null) writeData4(frame.section, 0) }

    private fun read32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or (data[offset + 3].toInt() shl 24)

    private fun patch32(section: DwarfSection, offset: Int, value: Int) {
        repeat(4) { byte -> section.bytes[offset + byte] = (value ushr (byte * 8)).toByte() }
    }
}
