package org.tinycc.backends.portable

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CoffMachine(val value: Int) { I386(0x14C), X86_64(0x8664), ARM64(0xAA64) }

data class CoffSection(
    val name: String,
    val data: ByteArray,
    val characteristics: Int,
)

data class CoffSymbol(
    val name: String,
    val value: Long = 0,
    val sectionNumber: Int = 1,
    val type: Int = 0x20,
    val storageClass: Int = 2,
)

data class CoffImport(val dll: String, val name: String, val ordinal: Int? = null)

data class CoffExport(val name: String, val ordinal: Int, val target: String)

data class PeCoffObject(
    val machine: CoffMachine,
    val sections: List<CoffSection>,
    val symbols: List<CoffSymbol> = emptyList(),
    val imports: List<CoffImport> = emptyList(),
    val exports: List<CoffExport> = emptyList(),
)

class PeCoffWriter {
    fun write(objectFile: PeCoffObject): ByteArray {
        val sections = objectFile.sections.toMutableList()
        if (objectFile.imports.isNotEmpty()) {
            sections += CoffSection(".idata$", objectFile.imports.sortedWith(compareBy<CoffImport> { it.dll }.thenBy { it.name })
                .joinToString("\n") { "${it.dll}:${it.name}:${it.ordinal ?: "name"}" }.encodeToByteArray(), 0x40000040)
        }
        if (objectFile.exports.isNotEmpty()) {
            sections += CoffSection(".edata$", objectFile.exports.sortedBy { it.ordinal }
                .joinToString("\n") { "${it.ordinal}:${it.name}:${it.target}" }.encodeToByteArray(), 0x40000040)
        }
        val headerSize = 20 + sections.size * 40
        var dataOffset = headerSize
        val sectionOffsets = sections.map { section ->
            dataOffset = align(dataOffset, 4)
            val result = dataOffset
            dataOffset += section.data.size
            result
        }
        val symbolOffset = align(dataOffset, 4)
        val stringTable = StringTable(objectFile.symbols.map { it.name })
        val total = symbolOffset + objectFile.symbols.size * 18 + stringTable.bytes.size
        val bytes = ByteArray(total)
        put16(bytes, 0, objectFile.machine.value)
        put16(bytes, 2, sections.size)
        put32(bytes, 4, 0)
        put32(bytes, 8, symbolOffset)
        put32(bytes, 12, objectFile.symbols.size)
        put16(bytes, 16, 0)
        put16(bytes, 18, if (objectFile.imports.isNotEmpty()) 0x0100 else 0)
        sections.forEachIndexed { index, section ->
            val offset = 20 + index * 40
            section.name.encodeToByteArray().copyInto(bytes, offset, 0, minOf(8, section.name.length))
            put32(bytes, offset + 8, section.data.size)
            put32(bytes, offset + 16, section.data.size)
            put32(bytes, offset + 20, sectionOffsets[index])
            put32(bytes, offset + 36, section.characteristics)
            section.data.copyInto(bytes, sectionOffsets[index])
        }
        objectFile.symbols.forEachIndexed { index, symbol ->
            val offset = symbolOffset + index * 18
            put32(bytes, offset, stringTable.offsets.getValue(symbol.name))
            put32(bytes, offset + 4, symbol.value.toInt())
            put16(bytes, offset + 8, symbol.sectionNumber)
            put16(bytes, offset + 10, symbol.type)
            bytes[offset + 12] = symbol.storageClass.toByte()
            bytes[offset + 13] = 0
        }
        stringTable.bytes.copyInto(bytes, symbolOffset + objectFile.symbols.size * 18)
        return bytes
    }

    private class StringTable(names: Collection<String>) {
        val offsets = LinkedHashMap<String, Int>()
        val bytes: ByteArray

        init {
            val stream = ByteArrayOutputStream()
            stream.write(byteArrayOf(4, 0, 0, 0))
            names.distinct().forEach { name ->
                offsets[name] = stream.size()
                stream.write(name.encodeToByteArray())
                stream.write(0)
            }
            val result = stream.toByteArray()
            put32(result, 0, result.size)
            bytes = result
        }
    }
}

enum class MachCpu(val value: Int) { X86_64(0x01000007), ARM64(0x0100000C) }

data class MachSymbol(val name: String, val value: Long = 0, val external: Boolean = true)

data class MachOObject(
    val cpu: MachCpu,
    val sections: List<CoffSection>,
    val symbols: List<MachSymbol> = emptyList(),
)

class MachOWriter {
    fun write(objectFile: MachOObject): ByteArray {
        val payload = buildString {
            objectFile.sections.sortedBy { it.name }.forEach { section ->
                append("section:").append(section.name).append(":").append(section.data.decodeToString()).append('\n')
            }
            objectFile.symbols.sortedBy { it.name }.forEach { symbol ->
                append("symbol:").append(symbol.name).append(":").append(symbol.value).append('\n')
            }
        }.encodeToByteArray()
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xFEEDFACF.toInt())
            .putInt(objectFile.cpu.value)
            .putInt(0)
            .putInt(1)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
        return header + payload
    }
}

data class WindowsRuntimePolicy(
    val dllEntryPoint: String = "DllMainCRTStartup",
    val usesSeh: Boolean = true,
    val importLibraryExtension: String = ".lib",
    val exportDirectiveExtension: String = ".def",
)

private fun align(value: Int, boundary: Int): Int = (value + boundary - 1) / boundary * boundary

private fun put16(bytes: ByteArray, offset: Int, value: Int) {
    ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
}

private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
}
