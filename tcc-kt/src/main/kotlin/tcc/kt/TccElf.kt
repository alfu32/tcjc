package tcc.kt

/** ELF section and symbol table routines ported from tccelf.c. */
object TccElf {
    const val SHT_NULL = 0
    const val SHT_PROGBITS = 1
    const val SHT_SYMTAB = 2
    const val SHT_STRTAB = 3
    const val SHT_RELA = 4
    const val SHT_HASH = 5
    const val SHT_DYNAMIC = 6
    const val SHT_NOBITS = 8
    const val SHT_REL = 9
    const val SHT_DYNSYM = 11
    const val SHF_WRITE = 1
    const val SHF_ALLOC = 2
    const val SHF_EXECINSTR = 4
    const val SHF_PRIVATE = -0x80000000
    const val SHF_DYNSYM = 0x40000000
    const val SHN_UNDEF = 0
    const val SHN_ABS = 0xfff1
    const val SHN_COMMON = 0xfff2
    const val STB_LOCAL = 0
    const val STB_GLOBAL = 1
    const val STB_WEAK = 2
    const val STT_NOTYPE = 0
    const val STT_OBJECT = 1
    const val STT_FUNC = 2
    const val STT_TLS = 6

    data class ElfSymbol(
        val nameOffset: Int,
        var value: Long,
        var size: Long,
        var info: Int,
        var other: Int,
        var sectionIndex: Int,
    )

    data class ElfSection(
        val name: String,
        var type: Int,
        var flags: Int,
        var index: Int = 0,
        var alignment: Int = 0,
        var entrySize: Int = 0,
        var offset: Long = 0,
        var address: Long = 0,
        var allocatedSize: Int = 0,
        var dataOffset: Int = 0,
        val data: MutableList<Byte> = mutableListOf(),
        var link: ElfSection? = null,
        var relocation: ElfSection? = null,
        var hash: ElfSection? = null,
        var hashedSymbols: Int = 0,
        val symbols: MutableList<ElfSymbol> = mutableListOf(),
        val stringOffsets: MutableMap<String, Int> = mutableMapOf(),
        val hashBuckets: MutableList<Int> = mutableListOf(0),
        val hashChains: MutableList<Int> = mutableListOf(0),
    ) {
        val size: Int get() = dataOffset
    }

    data class ElfState(
        val wordSize: Int = 8,
        val sections: MutableList<ElfSection?> = mutableListOf(null),
        val privateSections: MutableList<ElfSection> = mutableListOf(),
        val dynamicSymbolTable: ElfSection? = null,
        var symbolTable: ElfSection? = null,
    )

    data class SymbolTablePair(val symbols: ElfSection, val strings: ElfSection, val hash: ElfSection)

    fun newSection(state: ElfState, name: String, type: Int, flags: Int): ElfSection {
        val section = ElfSection(name, type, flags)
        section.alignment = when (type) {
            SHT_STRTAB -> 1
            0x6fffffff -> 2 // SHT_GNU_versym
            SHT_HASH, 0x6ffffff6, SHT_REL, SHT_RELA, SHT_DYNSYM, SHT_SYMTAB, SHT_DYNAMIC,
            0x6ffffffe, 0x6ffffffd -> state.wordSize
            else -> state.wordSize
        }
        if (flags and SHF_PRIVATE != 0) state.privateSections += section
        else {
            section.index = state.sections.size
            state.sections += section
        }
        return section
    }

    fun newSymbolTable(
        state: ElfState,
        symbolName: String,
        symbolType: Int,
        symbolFlags: Int,
        stringName: String,
        hashName: String,
        hashFlags: Int,
    ): SymbolTablePair {
        val symbols = newSection(state, symbolName, symbolType, symbolFlags)
        symbols.entrySize = if (state.wordSize == 8) 24 else 16
        val strings = newSection(state, stringName, SHT_STRTAB, symbolFlags)
        symbols.link = strings
        val hash = newSection(state, hashName, SHT_HASH, hashFlags)
        hash.entrySize = 4
        hash.link = symbols
        symbols.hash = hash
        initializeSymbolTable(symbols)
        return SymbolTablePair(symbols, strings, hash)
    }

    fun initializeSymbolTable(symbols: ElfSection) {
        val strings = requireNotNull(symbols.link)
        putElfString(strings, "")
        repeat(symbols.entrySize) { symbols.data += 0 }
        symbols.dataOffset = symbols.entrySize
        val hash = requireNotNull(symbols.hash)
        hash.data.clear()
        hash.dataOffset = 0
        appendInt32(hash, 1) // bucket count
        appendInt32(hash, 1) // first available symbol index
        appendInt32(hash, 0)
        appendInt32(hash, 0)
        symbols.symbols += ElfSymbol(0, 0, 0, 0, 0, SHN_UNDEF)
    }

    fun putElfString(section: ElfSection, text: String): Int {
        section.stringOffsets[text]?.let { return it }
        val offset = section.dataOffset
        text.toByteArray(Charsets.UTF_8).forEach { section.data += it }
        section.data += 0
        section.dataOffset = section.data.size
        section.stringOffsets[text] = offset
        return offset
    }

    fun findSection(state: ElfState, name: String): ElfSection =
        state.sections.drop(1).firstOrNull { it?.name == name } ?: newSection(state, name, SHT_PROGBITS, SHF_ALLOC)

    fun sectionAdd(section: ElfSection, size: Int, alignment: Int): Int {
        require(size >= 0 && alignment > 0 && alignment and (alignment - 1) == 0)
        val offset = (section.dataOffset + alignment - 1) and -alignment
        val end = offset + size
        if (section.type != SHT_NOBITS) {
            while (section.data.size < end) section.data += 0
            section.allocatedSize = maxOf(section.allocatedSize, section.data.size)
        }
        section.dataOffset = end
        section.alignment = maxOf(section.alignment, alignment)
        if (section.type == SHT_NOBITS) section.allocatedSize = maxOf(section.allocatedSize, end)
        return offset
    }

    fun appendSection(section: ElfSection, bytes: ByteArray): Int {
        val offset = sectionAdd(section, bytes.size, 1)
        if (section.type != SHT_NOBITS) bytes.forEachIndexed { index, value -> section.data[offset + index] = value }
        return offset
    }

    fun elfHash(name: String): Int {
        var hash = 0
        name.toByteArray(Charsets.UTF_8).forEach { byte ->
            hash = (hash shl 4) + (byte.toInt() and 0xff)
            val high = hash and -0x10000000
            if (high != 0) hash = hash xor (high ushr 24)
            hash = hash and high.inv()
        }
        return hash
    }

    fun putElfSymbol(section: ElfSection, value: Long, size: Long, info: Int, other: Int, sectionIndex: Int, name: String?): Int {
        val nameOffset = if (name.isNullOrEmpty()) 0 else putElfString(requireNotNull(section.link), name)
        val index = section.symbols.size
        section.symbols += ElfSymbol(nameOffset, value, size, info, other, sectionIndex)
        sectionAdd(section, section.entrySize, 1)
        val hash = section.hash
        if (hash != null) {
            val bucketCount = readInt32(hash.data, 0).coerceAtLeast(1)
            val oldFirst = readInt32(hash.data, 8 + (elfHash(name ?: "") % bucketCount) * 4)
            if (symbolBind(info) != STB_LOCAL) {
                val bucketOffset = 8 + (elfHash(name ?: "") % bucketCount) * 4
                writeInt32(hash.data, bucketOffset, index)
                hash.hashChains += oldFirst
                hash.hashedSymbols++
                ensureHashChainWord(hash, index)
                writeInt32(hash.data, 8 + (bucketCount + index) * 4, oldFirst)
                writeInt32(hash.data, 4, readInt32(hash.data, 4) + 1)
                if (hash.hashedSymbols > 2 * bucketCount) rebuildHash(section)
            } else {
                hash.hashChains += 0
                ensureHashChainWord(hash, index)
                writeInt32(hash.data, 8 + (bucketCount + index) * 4, 0)
                writeInt32(hash.data, 4, readInt32(hash.data, 4) + 1)
            }
        }
        return index
    }

    fun findElfSymbol(section: ElfSection, name: String): Int {
        val hash = section.hash ?: return 0
        val bucketCount = readInt32(hash.data, 0)
        if (bucketCount <= 0) return 0
        var symbolIndex = readInt32(hash.data, 8 + (elfHash(name) % bucketCount) * 4)
        while (symbolIndex != 0) {
            val symbol = section.symbols.getOrNull(symbolIndex) ?: return 0
            if (elfString(requireNotNull(section.link), symbol.nameOffset) == name) return symbolIndex
            symbolIndex = readInt32(hash.data, 8 + (bucketCount + symbolIndex) * 4)
        }
        return 0
    }

    fun symbolAddress(state: ElfState, name: String, reportMissing: (String) -> Unit = {}): Long? {
        val symbols = state.symbolTable ?: return null
        val index = findElfSymbol(symbols, name)
        val symbol = symbols.symbols.getOrNull(index)
        if (index == 0 || symbol == null || symbol.sectionIndex == SHN_UNDEF) {
            reportMissing("$name not defined")
            return null
        }
        return symbol.value
    }

    fun freeSection(section: ElfSection) {
        section.data.clear()
        section.dataOffset = 0
        section.allocatedSize = 0
        section.symbols.clear()
        section.hashBuckets.clear()
        section.hashChains.clear()
        section.stringOffsets.clear()
    }

    private fun symbolBind(info: Int): Int = info ushr 4
    private fun elfString(section: ElfSection, offset: Int): String {
        if (offset !in 0 until section.size) return ""
        var end = offset
        while (end < section.size && section.data[end].toInt() != 0) end++
        return ByteArray(end - offset) { section.data[offset + it] }.toString(Charsets.UTF_8)
    }
    private fun ensureHashChainWord(hash: ElfSection, symbolIndex: Int) {
        val needed = 8 + (readInt32(hash.data, 0) + symbolIndex + 1) * 4
        while (hash.data.size < needed) hash.data += 0
        hash.dataOffset = hash.data.size
    }
    private fun rebuildHash(symbols: ElfSection) {
        val hash = requireNotNull(symbols.hash)
        val oldCount = readInt32(hash.data, 4)
        val bucketCount = (readInt32(hash.data, 0) * 2).coerceAtLeast(1)
        hash.data.clear(); hash.dataOffset = 0
        appendInt32(hash, bucketCount)
        appendInt32(hash, oldCount)
        repeat(bucketCount + oldCount) { appendInt32(hash, 0) }
        hash.hashedSymbols = 0
        symbols.symbols.forEachIndexed { index, symbol ->
            if (index == 0 || symbolBind(symbol.info) == STB_LOCAL) return@forEachIndexed
            val name = elfString(requireNotNull(symbols.link), symbol.nameOffset)
            val bucket = elfHash(name) % bucketCount
            val bucketOffset = 8 + bucket * 4
            val previous = readInt32(hash.data, bucketOffset)
            writeInt32(hash.data, bucketOffset, index)
            writeInt32(hash.data, 8 + (bucketCount + index) * 4, previous)
            hash.hashedSymbols++
        }
    }
    private fun appendInt32(section: ElfSection, value: Int) {
        repeat(4) { shift -> section.data += (value ushr (shift * 8)).toByte() }
        section.dataOffset = section.data.size
    }
    private fun readInt32(input: List<Byte>, offset: Int): Int =
        (input[offset].toInt() and 0xff) or ((input[offset + 1].toInt() and 0xff) shl 8) or
            ((input[offset + 2].toInt() and 0xff) shl 16) or ((input[offset + 3].toInt() and 0xff) shl 24)
    private fun writeInt32(output: MutableList<Byte>, offset: Int, value: Int) {
        repeat(4) { shift -> output[offset + shift] = (value ushr (shift * 8)).toByte() }
    }
}
