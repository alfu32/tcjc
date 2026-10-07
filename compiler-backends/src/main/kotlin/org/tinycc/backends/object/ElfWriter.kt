package org.tinycc.backends.elf

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ElfMachine(val value: Int) { I386(3), X86_64(62), ARM(40), AARCH64(183), RISCV(243), C67(0x99) }

enum class ElfClass(val value: Int) { ELF32(1), ELF64(2) }

enum class ElfSectionType(val value: Int) { NULL(0), PROGBITS(1), SYMTAB(2), STRTAB(3), RELA(4), NOBITS(8), DYNAMIC(6), DYNSYM(11) }

enum class ElfSymbolBinding(val value: Int) { LOCAL(0), GLOBAL(1), WEAK(2) }

enum class ElfSymbolType(val value: Int) { NOTYPE(0), OBJECT(1), FUNC(2), SECTION(3), TLS(6) }

enum class ElfDynamicTag(val value: Long) { NULL(0), NEEDED(1), PLTGOT(3), STRTAB(5), SYMTAB(6), RELA(7), RELASZ(8), RELAENT(9) }

object ElfSectionFlags {
    const val WRITE: Long = 0x1
    const val ALLOC: Long = 0x2
    const val EXECINSTR: Long = 0x4
    const val TLS: Long = 0x400
}

data class ElfSectionSpec(
    val name: String,
    val type: ElfSectionType,
    val flags: Long = 0,
    val alignment: Long = 1,
    val data: ByteArray = ByteArray(0),
    val entrySize: Long = 0,
)

data class ElfSymbolSpec(
    val name: String,
    val binding: ElfSymbolBinding = ElfSymbolBinding.GLOBAL,
    val type: ElfSymbolType = ElfSymbolType.NOTYPE,
    val section: String? = null,
    val value: Long = 0,
    val size: Long = 0,
)

data class ElfRelocationSpec(
    val section: String,
    val offset: Long,
    val type: Int,
    val symbol: String,
    val addend: Long = 0,
)

data class ElfDebugLine(
    val file: String,
    val line: Int,
    val column: Int,
    val address: Long,
)

data class ElfStabEntry(
    val name: String,
    val line: Int,
    val address: Long,
)

data class ElfDynamicEntry(val tag: ElfDynamicTag, val value: Long)

data class ElfStartupPolicy(
    val entrySymbol: String = "_start",
    val stackAlignment: Int = 16,
    val usesTls: Boolean = false,
    val requiresDynamicLoader: Boolean = false,
)

data class ElfObjectDescription(
    val machine: ElfMachine,
    val sections: List<ElfSectionSpec>,
    val symbols: List<ElfSymbolSpec> = emptyList(),
    val relocations: List<ElfRelocationSpec> = emptyList(),
    val debugLines: List<ElfDebugLine> = emptyList(),
    val stabs: List<ElfStabEntry> = emptyList(),
    val dynamicEntries: List<ElfDynamicEntry> = emptyList(),
    val startup: ElfStartupPolicy = ElfStartupPolicy(),
    val elfClass: ElfClass = ElfClass.ELF64,
)

class ElfObjectWriter {
    fun write(description: ElfObjectDescription): ByteArray {
        require(description.elfClass == ElfClass.ELF64) { "ELF32 emission is reserved for the 32-bit writer" }
        require(description.startup.stackAlignment > 0) { "startup stack alignment must be positive" }
        val builds = description.sections.map { it.toBuild() }.toMutableList()
        if (description.dynamicEntries.isNotEmpty() && builds.none { it.name == ".dynamic" }) {
            builds += SectionBuild(
                ".dynamic",
                ElfSectionType.DYNAMIC,
                ElfSectionFlags.ALLOC or ElfSectionFlags.WRITE,
                8,
                dynamicBytes(description.dynamicEntries),
                linkName = ".dynstr",
                entrySize = 16,
            )
        }
        if (description.debugLines.isNotEmpty() && builds.none { it.name == ".debug_line" }) {
            builds += SectionBuild(".debug_line", ElfSectionType.PROGBITS, data = debugLineBytes(description.debugLines))
        }
        if (description.stabs.isNotEmpty() && builds.none { it.name == ".stab" }) {
            builds += SectionBuild(".stab", ElfSectionType.PROGBITS, data = stabBytes(description.stabs))
        }
        val relocationsBySection = description.relocations.groupBy { it.section }.toSortedMap()
        relocationsBySection.forEach { (section, relocations) ->
            require(builds.any { it.name == section }) { "relocation references unknown section '$section'" }
            builds += SectionBuild(".rela$section", ElfSectionType.RELA, alignment = 8, entrySize = 24, linkName = ".symtab", infoName = section)
        }
        val sortedSymbols = description.symbols.sortedWith(compareBy<ElfSymbolSpec> { it.binding.value }.thenBy { it.name })
        val symbolStrings = StringTable(sortedSymbols.map { it.name })
        builds += SectionBuild(".symtab", ElfSectionType.SYMTAB, alignment = 8, entrySize = 24, linkName = ".strtab")
        builds += SectionBuild(".strtab", ElfSectionType.STRTAB, data = symbolStrings.bytes)
        val sectionStrings = StringTable(builds.map { it.name } + ".shstrtab")
        builds += SectionBuild(".shstrtab", ElfSectionType.STRTAB, data = sectionStrings.bytes)

        val indexed = builds.mapIndexed { index, build -> build.apply { sectionIndex = index + 1 } }
        val indexByName = indexed.associateBy { it.name }
        indexed.forEach { section ->
            section.nameOffset = sectionStrings.offsets.getValue(section.name)
            section.linkIndex = section.linkName?.let { indexByName[it]?.sectionIndex ?: 0 } ?: 0
            section.infoIndex = section.infoName?.let { indexByName[it]?.sectionIndex ?: 0 } ?: 0
        }
        indexByName[".symtab"]?.data = symbolBytes(sortedSymbols, symbolStrings, indexByName)
        relocationsBySection.forEach { (section, relocations) ->
            indexByName[".rela$section"]?.data = relocationBytes(relocations, sortedSymbols)
        }

        var cursor = 64L
        indexed.forEach { section ->
            cursor = alignUp(cursor, section.alignment)
            section.fileOffset = cursor
            section.size = section.data.size.toLong()
            if (section.type != ElfSectionType.NOBITS) cursor += section.size
        }
        val sectionHeaderOffset = alignUp(cursor, 8)
        val sectionCount = indexed.size + 1
        val total = sectionHeaderOffset + sectionCount * 64L
        require(total <= Int.MAX_VALUE) { "ELF object is too large" }
        val bytes = ByteArray(total.toInt())
        indexed.forEach { section ->
            if (section.type != ElfSectionType.NOBITS) section.data.copyInto(bytes, section.fileOffset.toInt())
        }
        writeHeader(bytes, description.machine, sectionHeaderOffset, sectionCount, indexByName.getValue(".shstrtab").sectionIndex)
        indexed.forEach { section -> writeSectionHeader(bytes, section, sectionHeaderOffset.toInt() + section.sectionIndex * 64) }
        return bytes
    }

    private fun symbolBytes(symbols: List<ElfSymbolSpec>, strings: StringTable, sections: Map<String, SectionBuild>): ByteArray {
        val output = ByteArray(24 * (symbols.size + 1))
        symbols.forEachIndexed { index, symbol ->
            val offset = (index + 1) * 24
            put32(output, offset, strings.offsets.getValue(symbol.name))
            output[offset + 4] = ((symbol.binding.value shl 4) or symbol.type.value).toByte()
            put16(output, offset + 6, symbol.section?.let { sections.getValue(it).sectionIndex } ?: 0)
            put64(output, offset + 8, symbol.value)
            put64(output, offset + 16, symbol.size)
        }
        return output
    }

    private fun relocationBytes(relocations: List<ElfRelocationSpec>, symbols: List<ElfSymbolSpec>): ByteArray {
        val symbolIndexes = symbols.mapIndexed { index, symbol -> symbol.name to index + 1 }.toMap()
        val output = ByteArray(relocations.size * 24)
        relocations.sortedWith(compareBy<ElfRelocationSpec> { it.offset }.thenBy { it.symbol }).forEachIndexed { index, relocation ->
            val offset = index * 24
            put64(output, offset, relocation.offset)
            put64(output, offset + 8, (symbolIndexes.getValue(relocation.symbol).toLong() shl 32) or (relocation.type.toLong() and 0xFFFF_FFFFL))
            put64(output, offset + 16, relocation.addend)
        }
        return output
    }

    private fun dynamicBytes(entries: List<ElfDynamicEntry>): ByteArray {
        val output = ByteArray((entries.size + 1) * 16)
        entries.forEachIndexed { index, entry ->
            put64(output, index * 16, entry.tag.value)
            put64(output, index * 16 + 8, entry.value)
        }
        return output
    }

    private fun debugLineBytes(entries: List<ElfDebugLine>): ByteArray = buildString {
        appendLine("TCJC-DWARF-LINE-V1")
        entries.sortedWith(compareBy<ElfDebugLine> { it.address }.thenBy { it.file }).forEach { appendLine("${it.address}:${it.file}:${it.line}:${it.column}") }
    }.encodeToByteArray()

    private fun stabBytes(entries: List<ElfStabEntry>): ByteArray = buildString {
        appendLine("TCJC-STABS-V1")
        entries.sortedWith(compareBy<ElfStabEntry> { it.address }.thenBy { it.name }).forEach { appendLine("${it.address}:${it.name}:${it.line}") }
    }.encodeToByteArray()

    private fun writeHeader(bytes: ByteArray, machine: ElfMachine, sectionHeaderOffset: Long, sectionCount: Int, stringIndex: Int) {
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = ElfClass.ELF64.value.toByte()
        bytes[5] = 1
        bytes[6] = 1
        put16(bytes, 16, 1)
        put16(bytes, 18, machine.value)
        put32(bytes, 20, 1)
        put64(bytes, 40, sectionHeaderOffset)
        put16(bytes, 52, 64)
        put16(bytes, 58, 64)
        put16(bytes, 60, sectionCount)
        put16(bytes, 62, stringIndex)
    }

    private fun writeSectionHeader(bytes: ByteArray, section: SectionBuild, offset: Int) {
        put32(bytes, offset, section.nameOffset)
        put32(bytes, offset + 4, section.type.value)
        put64(bytes, offset + 8, section.flags)
        put64(bytes, offset + 24, section.fileOffset)
        put64(bytes, offset + 32, section.size)
        put32(bytes, offset + 40, section.linkName?.let { section.linkIndex } ?: 0)
        put32(bytes, offset + 44, section.infoName?.let { section.infoIndex } ?: 0)
        put64(bytes, offset + 48, section.alignment)
        put64(bytes, offset + 56, section.entrySize)
    }

    private fun ElfSectionSpec.toBuild() = SectionBuild(name, type, flags, alignment, data.copyOf(), entrySize = entrySize)

    private class SectionBuild(
        val name: String,
        val type: ElfSectionType,
        val flags: Long = 0,
        val alignment: Long = 1,
        var data: ByteArray = ByteArray(0),
        val linkName: String? = null,
        val infoName: String? = null,
        val entrySize: Long = 0,
    ) {
        var sectionIndex: Int = 0
        var nameOffset: Int = 0
        var fileOffset: Long = 0
        var size: Long = 0
        var linkIndex: Int = 0
        var infoIndex: Int = 0
    }

    private class StringTable(names: Collection<String>) {
        val offsets = LinkedHashMap<String, Int>()
        val bytes: ByteArray

        init {
            val output = ByteArrayOutputStream()
            output.write(0)
            names.distinct().forEach { name ->
                offsets[name] = output.size()
                output.write(name.encodeToByteArray())
                output.write(0)
            }
            bytes = output.toByteArray()
        }
    }

    private fun alignUp(value: Long, alignment: Long): Long {
        val safe = alignment.coerceAtLeast(1)
        val remainder = value % safe
        return if (remainder == 0L) value else value + safe - remainder
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun put64(bytes: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    }
}
