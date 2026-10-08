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
    const val STV_DEFAULT = 0
    const val STV_INTERNAL = 1
    const val STV_HIDDEN = 2
    const val STV_PROTECTED = 3
    const val ST_ASM_SET = 0x80

    data class ElfSymbol(
        val nameOffset: Int,
        var value: Long,
        var size: Long,
        var info: Int,
        var other: Int,
        var sectionIndex: Int,
    )
    data class ElfRelocation(val offset: Long, var symbolIndex: Int, val type: Int, val addend: Long = 0)
    data class SymbolAttributes(
        var gotOffset: Long = 0,
        var pltOffset: Long = 0,
        var pltSymbol: Int = 0,
        var dynamicIndex: Int = 0,
        var linkerSymbol: Boolean = false,
        var thumbStub: Boolean = false,
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
        val relocations: MutableList<ElfRelocation> = mutableListOf(),
        var sectionInfo: Int = 0,
    ) {
        val size: Int get() = dataOffset
    }

    data class ElfState(
        val wordSize: Int = 8,
        val sections: MutableList<ElfSection?> = mutableListOf(null),
        val privateSections: MutableList<ElfSection> = mutableListOf(),
        var dynamicSymbolTable: ElfSection? = null,
        var symbolTable: ElfSection? = null,
        val namedSections: MutableMap<String, ElfSection> = mutableMapOf(),
        val symbolTables: MutableMap<String, SymbolTablePair> = mutableMapOf(),
        val symbolAttributes: MutableList<SymbolAttributes> = mutableListOf(),
        val fileSectionMarks: MutableList<Pair<ElfSection, Int>> = mutableListOf(),
        var fileSymbolMark: Int = 0,
        var fileStringMark: Int = 0,
        val totalOutput: MutableList<Long> = MutableList(4) { 0L },
    )

    data class SymbolTablePair(val symbols: ElfSection, val strings: ElfSection, val hash: ElfSection)

    fun initializeElfSections(state: ElfState, peTarget: Boolean = false, boundsChecking: Boolean = false) {
        state.namedSections[".text"] = newSection(state, ".text", SHT_PROGBITS, SHF_ALLOC or SHF_EXECINSTR)
        state.namedSections[".data"] = newSection(state, ".data", SHT_PROGBITS, SHF_ALLOC or SHF_WRITE)
        val readOnlyDataName = if (peTarget) ".rdata" else ".data.ro"
        state.namedSections[readOnlyDataName] = newSection(state, readOnlyDataName, SHT_PROGBITS, SHF_ALLOC)
        state.namedSections[".bss"] = newSection(state, ".bss", SHT_NOBITS, SHF_ALLOC or SHF_WRITE)
        state.namedSections[".common"] = newSection(state, ".common", SHT_NOBITS, SHF_PRIVATE).also { it.index = SHN_COMMON }
        val mainTable = newSymbolTable(state, ".symtab", SHT_SYMTAB, 0, ".strtab", ".hashtab", SHF_PRIVATE)
        state.symbolTables[mainTable.symbols.name] = mainTable
        state.symbolTable = mainTable.symbols
        val dynamicTable = newSymbolTable(
            state, ".dynsymtab", SHT_SYMTAB, SHF_PRIVATE or SHF_DYNSYM,
            ".dynstrtab", ".dynhashtab", SHF_PRIVATE,
        )
        state.symbolTables[dynamicTable.symbols.name] = dynamicTable
        state.dynamicSymbolTable = dynamicTable.symbols
        if (boundsChecking) {
            state.namedSections[".bounds"] = newSection(state, ".bounds", SHT_PROGBITS, SHF_ALLOC)
            state.namedSections[".lbounds"] = newSection(state, ".lbounds", SHT_PROGBITS, SHF_ALLOC)
        }
    }

    /** Saves section offsets and suspends the main symbol hash during one input file. */
    fun beginInputFile(state: ElfState) {
        state.fileSectionMarks.clear()
        state.sections.drop(1).filterNotNull().forEach { section ->
            section.offset = section.dataOffset.toLong()
            state.fileSectionMarks += section to section.dataOffset
        }
        val symbols = state.symbolTable ?: return
        state.fileSymbolMark = symbols.dataOffset / symbols.entrySize
        state.fileStringMark = requireNotNull(symbols.link).dataOffset
        symbols.relocation = symbols.hash
        symbols.hash = null
    }

    /** Merges symbols emitted for one source file and remaps its relocation indices. */
    fun endInputFile(
        state: ElfState,
        outputObject: Boolean,
        peTarget: Boolean = false,
        reportDuplicate: (String) -> Unit = {},
    ): IntArray {
        val table = state.symbolTable ?: return IntArray(0)
        val strings = requireNotNull(table.link)
        val firstSymbol = state.fileSymbolMark
        val newSymbols = table.symbols.drop(firstSymbol).map { it to elfString(strings, it.nameOffset) }
        val newCount = newSymbols.size
        truncate(table, firstSymbol * table.entrySize)
        truncate(strings, state.fileStringMark)
        while (table.symbols.size > firstSymbol) table.symbols.removeAt(table.symbols.lastIndex)
        table.hash = table.relocation
        table.relocation = null
        val translation = IntArray(newCount)
        newSymbols.forEachIndexed { i, (symbol, name) ->
            var info = symbol.info
            if (symbol.sectionIndex == SHN_UNDEF) {
                var binding = symbolBind(info)
                val type = info and 0x0f
                if (binding == STB_LOCAL) binding = STB_GLOBAL
                var adjustedType = type
                if (!peTarget && outputObject && binding == STB_GLOBAL && type != STT_TLS) adjustedType = STT_NOTYPE
                info = (binding shl 4) or adjustedType
            }
            translation[i] = setElfSymbol(state, table, symbol.value, symbol.size, info, symbol.other, symbol.sectionIndex, name, reportDuplicate)
        }
        state.sections.drop(1).filterNotNull().forEach { relocationSection ->
            if (relocationSection.type != SHT_REL && relocationSection.type != SHT_RELA) return@forEach
            if (relocationSection.link !== table) return@forEach
            relocationSection.relocations.forEach { relocation ->
                val localIndex = relocation.symbolIndex - firstSymbol
                if (localIndex >= 0 && localIndex < translation.size) relocation.symbolIndex = translation[localIndex]
            }
        }
        for (i in 0 until minOf(4, state.sections.size - 1)) {
            val section = state.sections[i + 1] ?: continue
            state.totalOutput[i] += section.dataOffset - section.offset.toInt()
        }
        state.fileSectionMarks.clear()
        return translation
    }

    private fun truncate(section: ElfSection, offset: Int) {
        require(offset in 0..section.dataOffset)
        if (section.type != SHT_NOBITS) while (section.data.size > offset) section.data.removeAt(section.data.lastIndex)
        section.dataOffset = offset
    }

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
        val offset = section.dataOffset
        text.toByteArray(Charsets.UTF_8).forEach { section.data += it }
        section.data += 0
        section.dataOffset = section.data.size
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

    /** Merges an object symbol with an earlier global/weak definition. */
    fun setElfSymbol(
        state: ElfState,
        table: ElfSection,
        value: Long,
        size: Long,
        info: Int,
        other: Int,
        sectionIndex: Int,
        name: String,
        reportDuplicate: (String) -> Unit = {},
    ): Int {
        val binding = symbolBind(info)
        val type = info and 0x0f
        val visibility = other and 3
        if (binding == STB_LOCAL) return putElfSymbol(table, value, size, info, other, sectionIndex, name)
        val index = findElfSymbol(table, name)
        if (index == 0) return putElfSymbol(table, value, size, info, other, sectionIndex, name)
        val existing = table.symbols[index]
        if (existing.value == value && existing.size == size && existing.info == info &&
            existing.other == other && existing.sectionIndex == sectionIndex) return index
        if (existing.sectionIndex == SHN_UNDEF) {
            existing.other = other
            existing.info = (binding shl 4) or type
            existing.sectionIndex = sectionIndex
            existing.value = value
            existing.size = size
            return index
        }
        val oldBinding = symbolBind(existing.info)
        val oldVisibility = existing.other and 3
        val mergedVisibility = when {
            oldVisibility == STV_DEFAULT -> visibility
            visibility == STV_DEFAULT -> oldVisibility
            else -> minOf(oldVisibility, visibility)
        }
        existing.other = (existing.other and 3.inv()) or mergedVisibility
        when {
            sectionIndex == SHN_UNDEF -> Unit
            binding == STB_GLOBAL && oldBinding == STB_WEAK -> patchSymbol(existing, value, size, binding, type, sectionIndex)
            binding == STB_WEAK && oldBinding == STB_GLOBAL -> Unit
            binding == STB_WEAK && oldBinding == STB_WEAK -> Unit
            visibility == STV_HIDDEN || visibility == STV_INTERNAL -> Unit
            table.flags and SHF_DYNSYM != 0 -> Unit
            !isBss(state, sectionIndex) && isBss(state, existing.sectionIndex) -> patchSymbol(existing, value, size, binding, type, sectionIndex)
            isBss(state, sectionIndex) -> Unit
            existing.other and ST_ASM_SET != 0 -> patchSymbol(existing, value, size, binding, type, sectionIndex)
            else -> reportDuplicate("link symbol '$name' defined twice")
        }
        return index
    }

    private fun patchSymbol(symbol: ElfSymbol, value: Long, size: Long, binding: Int, type: Int, sectionIndex: Int) {
        symbol.info = (binding shl 4) or type
        symbol.sectionIndex = sectionIndex
        symbol.value = value
        symbol.size = size
    }

    private fun isBss(state: ElfState, sectionIndex: Int): Boolean =
        sectionIndex == SHN_COMMON || (sectionIndex >= 0 && sectionIndex < state.sections.size && state.sections[sectionIndex]?.type == SHT_NOBITS)

    fun symbolAddress(
        state: ElfState,
        name: String,
        errorOnMissing: Boolean = false,
        forceUnderscore: Boolean = false,
        leadingUnderscore: Boolean = false,
        peTarget: Boolean = false,
        reportMissing: (String) -> Unit = {},
    ): Long? {
        val symbols = state.symbolTable ?: return null
        val lookupName = if (forceUnderscore && leadingUnderscore && !(peTarget && '@' in name)) "_$name" else name
        val index = findElfSymbol(symbols, lookupName)
        val symbol = symbols.symbols.getOrNull(index)
        if (index == 0 || symbol == null || symbol.sectionIndex == SHN_UNDEF) {
            if (errorOnMissing) reportMissing("$lookupName not defined")
            return null
        }
        return symbol.value
    }

    fun listElfSymbols(state: ElfState, callback: (String, Long) -> Unit) {
        val table = state.symbolTable ?: return
        val strings = table.link ?: return
        table.symbols.forEach { symbol ->
            if (symbol.value != 0L && symbolBind(symbol.info) == STB_GLOBAL && (symbol.other and 3) == STV_DEFAULT) {
                callback(elfString(strings, symbol.nameOffset), symbol.value)
            }
        }
    }

    fun setGlobalSymbol(state: ElfState, table: ElfSection, name: String?, section: ElfSection?, offset: Long): Int {
        val sectionIndex = when {
            section != null -> section.index
            offset != 0L || name == null -> SHN_ABS
            else -> SHN_UNDEF
        }
        val value = if (section != null && offset == -1L) section.dataOffset.toLong() else offset
        val binding = if (name == null) STB_LOCAL else STB_GLOBAL
        return setElfSymbol(state, table, value, 0, (binding shl 4) or STT_NOTYPE, 0, sectionIndex, name ?: "")
    }

    fun addSymbol(
        state: ElfState,
        name: String,
        value: Long,
        leadingUnderscore: Boolean = false,
        peTarget: Boolean = false,
        addPeImport: (String, Long) -> Unit = { _, _ -> },
    ): Int {
        if (peTarget) {
            addPeImport(name, value)
            return 0
        }
        val decorated = if (leadingUnderscore) "_$name" else name
        val table = state.symbolTable ?: return 0
        return setGlobalSymbol(state, table, decorated, null, value)
    }

    fun putElfRelocation(
        state: ElfState,
        symbolTable: ElfSection,
        target: ElfSection,
        offset: Long,
        type: Int,
        symbolIndex: Int,
        addend: Long = 0,
        rela: Boolean = state.wordSize == 8,
    ): ElfRelocation {
        var relocationSection = target.relocation
        if (relocationSection == null) {
            val prefix = if (rela) ".rela" else ".rel"
            relocationSection = newSection(state, "$prefix${target.name}", if (rela) SHT_RELA else SHT_REL, symbolTable.flags)
            relocationSection.entrySize = if (state.wordSize == 8) if (rela) 24 else 16 else if (rela) 12 else 8
            relocationSection.link = symbolTable
            relocationSection.sectionInfo = target.index
            target.relocation = relocationSection
        }
        if (!rela && addend != 0L) error("non-zero addend on REL architecture")
        return ElfRelocation(offset, symbolIndex, type, addend).also {
            relocationSection.relocations += it
            sectionAdd(relocationSection, relocationSection.entrySize, 1)
        }
    }

    fun getSymbolAttributes(state: ElfState, index: Int, allocate: Boolean): SymbolAttributes? {
        if (index < state.symbolAttributes.size) return state.symbolAttributes[index]
        if (!allocate) return null
        var capacity = 1
        while (index >= capacity) capacity *= 2
        while (state.symbolAttributes.size < capacity) state.symbolAttributes += SymbolAttributes()
        return state.symbolAttributes[index]
    }

    /** Places all local symbols before global and weak symbols and fixes relocation references. */
    fun sortSymbols(state: ElfState, table: ElfSection): IntArray {
        val old = table.symbols.toList()
        val localIndices = old.indices.filter { symbolBind(old[it].info) == STB_LOCAL }
        val globalIndices = old.indices.filter { symbolBind(old[it].info) != STB_LOCAL }
        val order = localIndices + globalIndices
        val oldToNew = IntArray(old.size)
        order.forEachIndexed { newIndex, oldIndex -> oldToNew[oldIndex] = newIndex }
        table.symbols.clear()
        order.forEach { table.symbols += old[it] }
        table.sectionInfo = localIndices.size
        state.sections.drop(1).filterNotNull().forEach { relocationSection ->
            if ((relocationSection.type == SHT_REL || relocationSection.type == SHT_RELA) && relocationSection.link === table) {
                relocationSection.relocations.forEach { relocation ->
                    if (relocation.symbolIndex in oldToNew.indices) relocation.symbolIndex = oldToNew[relocation.symbolIndex]
                }
            }
        }
        return oldToNew
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

    fun deleteElfState(state: ElfState) {
        state.sections.drop(1).filterNotNull().forEach(::freeSection)
        state.privateSections.forEach(::freeSection)
        state.sections.clear(); state.sections += null
        state.privateSections.clear()
        state.namedSections.clear()
        state.symbolTables.clear()
        state.symbolAttributes.clear()
        state.fileSectionMarks.clear()
        state.dynamicSymbolTable = null
        state.symbolTable = null
        state.fileSymbolMark = 0
        state.fileStringMark = 0
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
