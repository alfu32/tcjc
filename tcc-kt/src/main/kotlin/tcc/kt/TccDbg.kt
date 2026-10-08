package tcc.kt

/** STABS, DWARF, and exception-frame support mechanically ported from tccdbg.c. */
object TccDbg {
    const val DWARF_LINE_BASE = -5
    const val DWARF_LINE_RANGE = 14
    const val DWARF_OPCODE_BASE = 13
    const val N_STR_HASH = 256
    const val N_FUN = 0x24
    const val N_SLINE = 0x44
    const val N_SO = 0x64
    const val N_BINCL = 0x82
    const val N_EINCL = 0xa2

    data class DefaultType(val type: Int, val size: Int, val encoding: Int, val stabs: String)
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
    data class DwarfLineState(
        val directories: MutableList<String> = mutableListOf(),
        val files: MutableList<DwarfFile> = mutableListOf(DwarfFile("", 0), DwarfFile("", 0)),
        val operations: MutableList<Byte> = mutableListOf(),
        var currentFile: Int = 1,
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
