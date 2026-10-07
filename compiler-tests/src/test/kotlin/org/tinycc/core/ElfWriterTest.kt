package org.tinycc.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.elf.ElfDebugLine
import org.tinycc.backends.elf.ElfMachine
import org.tinycc.backends.elf.ElfObjectDescription
import org.tinycc.backends.elf.ElfObjectWriter
import org.tinycc.backends.elf.ElfRelocationSpec
import org.tinycc.backends.elf.ElfSectionFlags
import org.tinycc.backends.elf.ElfSectionSpec
import org.tinycc.backends.elf.ElfSectionType
import org.tinycc.backends.elf.ElfStabEntry
import org.tinycc.backends.elf.ElfSymbolSpec
import org.tinycc.backends.elf.ElfSymbolType

class ElfWriterTest {
    @Test
    fun writesDeterministicElf64SectionsSymbolsRelocationsTlsAndDebug() {
        val description = ElfObjectDescription(
            machine = ElfMachine.X86_64,
            sections = listOf(
                ElfSectionSpec(".text", ElfSectionType.PROGBITS, ElfSectionFlags.ALLOC or ElfSectionFlags.EXECINSTR, 16, byteArrayOf(0xC3.toByte())),
                ElfSectionSpec(".tdata", ElfSectionType.PROGBITS, ElfSectionFlags.ALLOC or ElfSectionFlags.WRITE or ElfSectionFlags.TLS, 8, byteArrayOf(1, 2, 3, 4)),
            ),
            symbols = listOf(
                ElfSymbolSpec("entry", type = ElfSymbolType.FUNC, section = ".text", value = 0),
                ElfSymbolSpec("tlsValue", type = ElfSymbolType.TLS, section = ".tdata", size = 4),
            ),
            relocations = listOf(ElfRelocationSpec(".text", 0, 2, "entry")),
            debugLines = listOf(ElfDebugLine("main.c", 4, 2, 0)),
            stabs = listOf(ElfStabEntry("entry", 4, 0)),
        )
        val writer = ElfObjectWriter()
        val first = writer.write(description)
        val second = writer.write(description)
        val sections = sectionNames(first)

        assertTrue(first.contentEquals(second))
        assertEquals(0x7F.toByte(), first[0])
        assertEquals(1, u16(first, 16))
        assertEquals(62, u16(first, 18))
        assertTrue(sections.containsAll(listOf(".text", ".tdata", ".rela.text", ".symtab", ".strtab", ".shstrtab", ".debug_line", ".stab")))
    }

    private fun sectionNames(bytes: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sectionHeaderOffset = buffer.getLong(40).toInt()
        val sectionCount = buffer.getShort(60).toInt() and 0xFFFF
        val stringIndex = buffer.getShort(62).toInt() and 0xFFFF
        val stringHeader = sectionHeaderOffset + stringIndex * 64
        val stringOffset = buffer.getLong(stringHeader + 24).toInt()
        val stringSize = buffer.getLong(stringHeader + 32).toInt()
        val strings = bytes.copyOfRange(stringOffset, stringOffset + stringSize)
        return (1 until sectionCount).map { index ->
            val header = sectionHeaderOffset + index * 64
            val nameOffset = buffer.getInt(header).toInt()
            val end = (nameOffset until strings.size).first { strings[it] == 0.toByte() }
            strings.copyOfRange(nameOffset, end).decodeToString()
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt() and 0xFFFF
}
