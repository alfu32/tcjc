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
        val relocations: MutableMap<String, MutableList<Relocation>> = mutableMapOf(),
        val debugStrings: StringPool = StringPool(),
        val lineStrings: StringPool = StringPool(),
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
            val offset = if (existing != null) existing.second + existing.first.length - value.length else data.size
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
        val bytes = text.toByteArray(Charsets.UTF_8)
        val offset = state.stabStrings.size
        bytes.forEach { state.stabStrings += it }
        state.stabStrings += 0
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
}
