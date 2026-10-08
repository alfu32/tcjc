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
    data class ElfRelocation(var offset: Long, var symbolIndex: Int, var type: Int, var addend: Long = 0)
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
    const val NO_GOTPLT_ENTRY = 0
    const val BUILD_GOT_ONLY = 1
    const val AUTO_GOTPLT_ENTRY = 2
    const val ALWAYS_GOTPLT_ENTRY = 3

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

    fun relocateSymbols(
        state: ElfState,
        table: ElfSection,
        resolveUndefined: Int,
        dynamicLookup: (String) -> Long? = { null },
        loadedLibraryLookup: (String) -> Long? = { null },
        noStandardLibraries: Boolean = false,
        leadingUnderscore: Boolean = false,
        peTarget: Boolean = false,
        unresolved: (String) -> Unit = {},
    ) {
        val strings = state.symbolTable?.link ?: table.link ?: return
        table.symbols.drop(1).forEach { symbol ->
            val sectionIndex = symbol.sectionIndex
            if (sectionIndex == SHN_UNDEF) {
                if (resolveUndefined == 2) return@forEach
                val name = elfString(strings, symbol.nameOffset)
                if (resolveUndefined != 0 && !peTarget) {
                    val undecorated = if (leadingUnderscore) name.drop(1) else name
                    val address = (if (noStandardLibraries) null else dynamicLookup(undecorated))
                        ?: loadedLibraryLookup(undecorated)
                    if (address != null) { symbol.value = address; return@forEach }
                } else if (resolveUndefined == 0 && state.dynamicSymbolTable?.let { findElfSymbol(it, name) != 0 } == true) {
                    return@forEach
                }
                if (name == "_fp_hw") return@forEach
                if (symbolBind(symbol.info) == STB_WEAK) symbol.value = 0
                else unresolved("unresolved reference to '$name'")
            } else if (sectionIndex < 0xff00 && sectionIndex in state.sections.indices) {
                symbol.value += state.sections[sectionIndex]?.address ?: 0L
            }
        }
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

    fun buildGot(state: ElfState, symbolTable: ElfSection): Int {
        val got = state.namedSections[".got"] ?: newSection(state, ".got", SHT_PROGBITS, SHF_ALLOC or SHF_WRITE).also {
            it.entrySize = 4
            state.namedSections[".got"] = it
            sectionAdd(it, 3 * state.wordSize, 1)
        }
        return setGlobalSymbol(state, symbolTable, "_GLOBAL_OFFSET_TABLE_", got, 0)
    }

    fun putGotEntry(
        state: ElfState,
        symbolTable: ElfSection,
        dynamicSymbols: ElfSection?,
        symbolIndex: Int,
        dynamicRelocationType: Int,
        jumpSlotType: Int,
        relativeType: Int,
        createPltEntry: (Long, SymbolAttributes) -> Long,
    ): SymbolAttributes {
        val got = state.namedSections[".got"] ?: error("GOT has not been created")
        val needsPlt = dynamicRelocationType == jumpSlotType
        val attributes = requireNotNull(getSymbolAttributes(state, symbolIndex, true))
        if (if (needsPlt) attributes.pltOffset != 0L else attributes.gotOffset != 0L) return attributes
        var relocationTarget = got
        if (needsPlt) relocationTarget = state.namedSections[".plt"] ?: newSection(state, ".plt", SHT_PROGBITS, SHF_ALLOC or SHF_EXECINSTR).also {
            it.entrySize = 4
            state.namedSections[".plt"] = it
        }
        val gotOffset = got.dataOffset.toLong()
        sectionAdd(got, state.wordSize, 1)
        val symbol = symbolTable.symbols[symbolIndex]
        val name = elfString(requireNotNull(symbolTable.link), symbol.nameOffset)
        if (dynamicSymbols != null) {
            if (symbolBind(symbol.info) == STB_LOCAL) {
                putElfRelocation(state, dynamicSymbols, got, gotOffset, relativeType, symbolIndex)
            } else {
                if (attributes.dynamicIndex == 0) attributes.dynamicIndex = setElfSymbol(
                    state, dynamicSymbols, symbol.value, symbol.size, symbol.info, 0, symbol.sectionIndex, name,
                )
                putElfRelocation(state, dynamicSymbols, relocationTarget, gotOffset, dynamicRelocationType, attributes.dynamicIndex)
            }
        } else putElfRelocation(state, symbolTable, got, gotOffset, dynamicRelocationType, symbolIndex)
        if (needsPlt) {
            attributes.pltOffset = createPltEntry(gotOffset, attributes)
            val plt = state.namedSections.getValue(".plt")
            val pltName = name.take(195) + "@plt"
            attributes.pltSymbol = putElfSymbol(symbolTable, attributes.pltOffset, 0, (STB_GLOBAL shl 4) or STT_FUNC, 0, plt.index, pltName)
        } else attributes.gotOffset = gotOffset
        return attributes
    }

    fun buildGotEntries(
        state: ElfState,
        symbolTable: ElfSection,
        dynamicSymbols: ElfSection?,
        initialGotSymbol: Int,
        jumpSlotType: Int,
        globalDataType: Int,
        relativeType: Int,
        outputDynamic: Boolean,
        positionIndependentDllPlt: Boolean,
        outputExecutable: Boolean,
        armTarget: Boolean = false,
        classifyGotPlt: (Int) -> Int,
        classifyCodeRelocation: (Int) -> Int,
        forceLocalPcRelative: (Int, ElfSymbol) -> Int? = { _, _ -> null },
        createPltEntry: (Long, SymbolAttributes) -> Long,
        reportError: (String) -> Unit = {},
    ): Int {
        var gotSymbol = initialGotSymbol
        repeat(2) { pass ->
            state.sections.drop(1).filterNotNull().forEach { relocationSection ->
                if (relocationSection.type != SHT_REL && relocationSection.type != SHT_RELA) return@forEach
                if (relocationSection.link !== symbolTable) return@forEach
                relocationSection.relocations.forEach { relocation ->
                    val originalType = relocation.type
                    val category = classifyGotPlt(originalType)
                    if (category == -1) { reportError("Unknown relocation type for got: $originalType"); return@forEach }
                    if (category == NO_GOTPLT_ENTRY) return@forEach
                    val symbol = symbolTable.symbols.getOrNull(relocation.symbolIndex) ?: return@forEach
                    var forceJumpSlot = false
                    if (category == AUTO_GOTPLT_ENTRY) {
                        when (symbol.sectionIndex) {
                            SHN_UNDEF -> {
                                if (!positionIndependentDllPlt && outputDynamic) return@forEach
                                if (dynamicSymbols != null) {
                                    val dynIndex = getSymbolAttributes(state, relocation.symbolIndex, false)?.dynamicIndex ?: 0
                                    val dynamicSymbol = dynamicSymbols.symbols.getOrNull(dynIndex)
                                    if (dynIndex != 0 && dynamicSymbol != null &&
                                        ((dynamicSymbol.info and 0x0f) == STT_FUNC ||
                                            ((dynamicSymbol.info and 0x0f) == STT_NOTYPE && (symbol.info and 0x0f) == STT_FUNC))) {
                                        forceJumpSlot = true
                                    }
                                }
                            }
                            SHN_ABS -> if (symbol.value == 0L || (!armTarget && state.wordSize != 8)) return@forEach
                            else -> return@forEach
                        }
                    }
                    val localPcType = forceLocalPcRelative(originalType, symbol)
                    if (localPcType != null) {
                        if (pass == 0) relocation.type = localPcType
                        return@forEach
                    }
                    val relocationClass = classifyCodeRelocation(originalType)
                    if (relocationClass == -1) { reportError("Unknown relocation type: $originalType"); return@forEach }
                    val dynamicRelocationType = if (forceJumpSlot || relocationClass != 0) {
                        if (pass != 0) return@forEach
                        jumpSlotType
                    } else {
                        if (pass != 1) return@forEach
                        globalDataType
                    }
                    if (state.namedSections[".got"] == null) gotSymbol = buildGot(state, symbolTable)
                    if (category == BUILD_GOT_ONLY) return@forEach
                    val attributes = putGotEntry(
                        state, symbolTable, dynamicSymbols, relocation.symbolIndex, dynamicRelocationType,
                        jumpSlotType, relativeType, createPltEntry,
                    )
                    if (dynamicRelocationType == jumpSlotType) {
                        relocation.symbolIndex = attributes.pltSymbol
                        relocation.type = originalType
                    }
                }
            }
        }
        val plt = state.namedSections[".plt"]
        val got = state.namedSections[".got"]
        if (plt?.relocation != null && got != null) plt.relocation!!.sectionInfo = got.index
        if (gotSymbol != 0) symbolTable.symbols.getOrNull(gotSymbol)?.size = got?.dataOffset?.toLong() ?: 0L
        return gotSymbol
    }

    fun prepareDynamicRelocations(
        state: ElfState,
        relocationSection: ElfSection,
        symbols: ElfSection,
        outputDll: Boolean,
        absoluteDynamicTypes: Set<Int>,
        pcRelativeTypes: Set<Int>,
        hiddenLocalReplacements: Map<Int, Int> = emptyMap(),
        undefinedAbsoluteType: Int? = null,
        relativeType: Int = 0,
    ): Int {
        var count = 0
        relocationSection.relocations.forEach { relocation ->
            val symbol = symbols.symbols.getOrNull(relocation.symbolIndex) ?: return@forEach
            val dynamicIndex = getSymbolAttributes(state, relocation.symbolIndex, false)?.dynamicIndex ?: 0
            val type = relocation.type
            if (undefinedAbsoluteType == type && dynamicIndex == 0 && symbol.sectionIndex == SHN_UNDEF) {
                relocation.type = relativeType
                return@forEach
            }
            if (type in absoluteDynamicTypes) {
                count++
                return@forEach
            }
            val replacement = hiddenLocalReplacements[type]
            if (replacement != null && symbol.sectionIndex != SHN_UNDEF && (symbol.other and 3) == STV_HIDDEN) {
                relocation.type = replacement
                return@forEach
            }
            if (type in pcRelativeTypes && outputDll && dynamicIndex != 0) count++
        }
        return count
    }

    fun fillGotEntry(state: ElfState, got: ElfSection, symbols: ElfSection, relocation: ElfRelocation) {
        val symbol = symbols.symbols.getOrNull(relocation.symbolIndex) ?: return
        val offset = getSymbolAttributes(state, relocation.symbolIndex, false)?.gotOffset ?: return
        if (offset == 0L) return
        reserveSection(got, (offset + state.wordSize).toInt())
        writeWord(got.data, offset.toInt(), symbol.value, state.wordSize)
    }

    fun fillGot(
        state: ElfState,
        symbols: ElfSection,
        supportedRelocationTypes: Set<Int>,
    ) {
        val got = state.namedSections[".got"] ?: return
        state.sections.drop(1).filterNotNull().forEach { section ->
            if ((section.type != SHT_REL && section.type != SHT_RELA) || section.link !== symbols) return@forEach
            section.relocations.forEach { relocation ->
                if (relocation.type in supportedRelocationTypes) fillGotEntry(state, got, symbols, relocation)
            }
        }
    }

    fun fillLocalGotEntries(state: ElfState, symbols: ElfSection, relativeType: Int, error: (String) -> Unit = {}) {
        val got = state.namedSections[".got"] ?: return
        val relocations = got.relocation ?: return
        relocations.relocations.forEach { relocation ->
            if (relocation.type != relativeType) return@forEach
            val symbol = symbols.symbols.getOrNull(relocation.symbolIndex) ?: return@forEach
            val attributes = getSymbolAttributes(state, relocation.symbolIndex, false) ?: return@forEach
            val offset = attributes.gotOffset
            if (offset != relocation.offset - got.address) error("fill_local_got_entries: huh?")
            relocation.symbolIndex = 0
            if (relocations.type == SHT_RELA) relocation.addend = symbol.value
            else writeWord(got.data, offset.toInt(), symbol.value, 4)
        }
    }

    fun reserveSection(section: ElfSection, size: Int) {
        if (section.type == SHT_NOBITS) {
            section.dataOffset = maxOf(section.dataOffset, size)
            section.allocatedSize = maxOf(section.allocatedSize, size)
            return
        }
        while (section.data.size < size) section.data += 0
        section.dataOffset = maxOf(section.dataOffset, size)
        section.allocatedSize = maxOf(section.allocatedSize, section.data.size)
    }

    fun relocateSection(
        state: ElfState,
        target: ElfSection,
        relocationSection: ElfSection,
        symbols: ElfSection,
        dwarfSectionIndices: IntRange = IntRange.EMPTY,
        rela: Boolean = state.wordSize == 8,
        dynamicOutput: Boolean = false,
        applyRelocation: (ElfRelocation, MutableList<Byte>, Long, Long) -> Unit,
    ) {
        if (target.type == SHT_NOBITS) return
        relocationSection.relocations.forEach { relocation ->
            val symbol = symbols.symbols.getOrNull(relocation.symbolIndex) ?: return@forEach
            val symbolSectionIndex = symbol.sectionIndex
            val symbolValue = symbol.value + if (rela) relocation.addend else 0L
            if (target.index in dwarfSectionIndices && symbolSectionIndex in dwarfSectionIndices) {
                val offset = relocation.offset.toInt()
                if (offset in 0..(target.data.size - 4)) {
                    val relative = symbolValue - (state.sections[symbolSectionIndex]?.address ?: 0L)
                    addInt32(target.data, offset, relative.toInt())
                }
                return@forEach
            }
            applyRelocation(relocation, target.data, target.address + relocation.offset, symbolValue)
        }
        if (relocationSection.flags and SHF_ALLOC != 0) {
            state.dynamicSymbolTable?.let { relocationSection.link = it }
            if (dynamicOutput) {
                relocationSection.dataOffset = relocationSection.relocations.size * relocationSection.entrySize
                if (state.wordSize == 8 && target.name == ".stab") relocationSection.dataOffset = 0
            }
        }
    }

    fun relocateSections(
        state: ElfState,
        symbols: ElfSection,
        gotSection: ElfSection? = null,
        staticLink: Boolean = false,
        memoryOutput: Boolean = false,
        dynamicOutput: Boolean = false,
        dwarfSectionIndices: IntRange = IntRange.EMPTY,
        applyRelocation: (ElfRelocation, MutableList<Byte>, Long, Long) -> Unit,
    ) {
        state.sections.drop(1).filterNotNull().forEach { relocationSection ->
            if (relocationSection.type != SHT_REL && relocationSection.type != SHT_RELA) return@forEach
            val target = state.sections.getOrNull(relocationSection.sectionInfo) ?: return@forEach
            if (target === gotSection && !staticLink && !memoryOutput) return@forEach
            relocateSection(state, target, relocationSection, symbols, dwarfSectionIndices,
                relocationSection.type == SHT_RELA, dynamicOutput, applyRelocation)
            if (relocationSection.flags and SHF_ALLOC != 0) {
                relocationSection.relocations.forEach { it.offset += target.address }
            }
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

    fun gnuHash(name: String): Int {
        var hash = 5381
        name.toByteArray(Charsets.UTF_8).forEach { byte -> hash = hash * 33 + (byte.toInt() and 0xff) }
        return hash
    }

    fun createGnuHash(state: ElfState, dynamicSymbols: ElfSection): ElfSection {
        val definitions = dynamicSymbols.symbols.count { it.sectionIndex != SHN_UNDEF }
        val symbolCount = dynamicSymbols.symbols.size
        val buckets = definitions / 4 + 1
        val symbolOffset = symbolCount - definitions
        val shift = if (state.wordSize == 8) 6 else 5
        var bloomSize = 1
        while (definitions >= bloomSize * (1 shl (shift - 3))) bloomSize *= 2
        val section = newSection(state, ".gnu.hash", 0x6ffffff6, SHF_ALLOC)
        section.link = dynamicSymbols.hash?.link
        val totalBytes = 16 + state.wordSize * bloomSize + buckets * 4 + definitions * 4
        sectionAdd(section, totalBytes, 1)
        writeInt32(section.data, 0, buckets)
        writeInt32(section.data, 4, symbolOffset)
        writeInt32(section.data, 8, bloomSize)
        writeInt32(section.data, 12, shift)
        return section
    }

    /** Reorders defined dynamic symbols into GNU hash bucket order and fills bloom, bucket, and chain data. */
    fun updateGnuHash(state: ElfState, gnuHash: ElfSection, dynamicSymbols: ElfSection): IntArray {
        val bucketCount = readInt32(gnuHash.data, 0)
        val symbolOffset = readInt32(gnuHash.data, 4)
        val bloomSize = readInt32(gnuHash.data, 8)
        val bloomShift = readInt32(gnuHash.data, 12)
        val oldSymbols = dynamicSymbols.symbols.toList()
        val strings = requireNotNull(dynamicSymbols.link)
        val oldToNew = IntArray(oldSymbols.size)
        val ordered = mutableListOf<ElfSymbol>()
        oldSymbols.forEachIndexed { index, symbol ->
            if (symbol.sectionIndex == SHN_UNDEF) {
                oldToNew[index] = ordered.size
                ordered += symbol
            }
        }
        val defined = oldSymbols.indices.filter { oldSymbols[it].sectionIndex != SHN_UNDEF }
        val hashes = defined.associateWith { gnuHash(elfString(strings, oldSymbols[it].nameOffset)) }
        val bloomStart = 16
        val bucketsStart = bloomStart + bloomSize * state.wordSize
        val chainsStart = bucketsStart + bucketCount * 4
        for (bucket in 0 until bucketCount) {
            val members = defined.filter { hashes.getValue(it).toUInt() % bucketCount.toUInt() == bucket.toUInt() }
            if (members.isEmpty()) continue
            writeInt32(gnuHash.data, bucketsStart + bucket * 4, ordered.size)
            members.forEachIndexed { memberIndex, oldIndex ->
                val hash = hashes.getValue(oldIndex)
                oldToNew[oldIndex] = ordered.size
                ordered += oldSymbols[oldIndex]
                val chainIndex = ordered.lastIndex - symbolOffset
                var chainValue = hash and -2
                if (memberIndex == members.lastIndex) chainValue = chainValue or 1
                writeInt32(gnuHash.data, chainsStart + chainIndex * 4, chainValue)
                val bits = state.wordSize * 8
                val bloomIndex = ((hash.toUInt() / bits.toUInt()) % bloomSize.toUInt()).toInt()
                val firstBit = (hash.toUInt() % bits.toUInt()).toInt()
                val secondBit = (hash ushr bloomShift) % bits
                val wordOffset = bloomStart + bloomIndex * state.wordSize
                val bloom = if (state.wordSize == 8) readInt64(gnuHash.data, wordOffset) else readInt32(gnuHash.data, wordOffset).toLong()
                val mask = (1L shl firstBit) or (1L shl secondBit)
                if (state.wordSize == 8) writeInt64(gnuHash.data, wordOffset, bloom or mask)
                else writeInt32(gnuHash.data, wordOffset, (bloom or mask).toInt())
            }
        }
        dynamicSymbols.symbols.clear(); dynamicSymbols.symbols.addAll(ordered)
        updateRelocationSymbolIndices(state, dynamicSymbols, oldToNew, 0)
        rebuildHash(dynamicSymbols)
        return oldToNew
    }

    fun updateRelocationSymbolIndices(state: ElfState, table: ElfSection, oldToNew: IntArray, firstSymbol: Int) {
        state.sections.drop(1).filterNotNull().forEach { relocationSection ->
            if ((relocationSection.type != SHT_REL && relocationSection.type != SHT_RELA) || relocationSection.link !== table) return@forEach
            relocationSection.relocations.forEach { relocation ->
                val local = relocation.symbolIndex - firstSymbol
                if (local in oldToNew.indices) relocation.symbolIndex = oldToNew[local]
            }
        }
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
    private fun addInt32(output: MutableList<Byte>, offset: Int, value: Int) {
        writeInt32(output, offset, readInt32(output, offset) + value)
    }
    private fun readInt64(input: List<Byte>, offset: Int): Long {
        var value = 0L
        repeat(8) { shift -> value = value or ((input[offset + shift].toLong() and 0xff) shl (shift * 8)) }
        return value
    }
    private fun writeInt64(output: MutableList<Byte>, offset: Int, value: Long) {
        repeat(8) { shift -> output[offset + shift] = (value ushr (shift * 8)).toByte() }
    }
    private fun writeWord(output: MutableList<Byte>, offset: Int, value: Long, wordSize: Int) {
        if (wordSize == 8) writeInt64(output, offset, value) else writeInt32(output, offset, value.toInt())
    }
}
