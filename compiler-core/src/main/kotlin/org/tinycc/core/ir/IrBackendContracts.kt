package org.tinycc.core.ir

import java.io.ByteArrayOutputStream

enum class IrArchitecture { I386, X86_64, ARM, ARM64, RISCV64, C67 }

enum class IrRegisterClass { INTEGER, FLOAT, VECTOR, FLAGS }

data class IrRegister(
    val name: String,
    val number: Int,
    val registerClass: IrRegisterClass,
    val bits: Int,
    val callerSaved: Boolean,
) {
    init {
        require(name.isNotBlank()) { "register name must not be blank" }
        require(number >= 0) { "register number must not be negative" }
        require(bits > 0) { "register width must be positive" }
    }
}

data class IrRegisterBank(
    val architecture: IrArchitecture,
    val registers: List<IrRegister>,
) {
    init {
        require(registers.map { it.name }.toSet().size == registers.size) { "register names must be unique" }
        require(registers.map { it.number }.toSet().size == registers.size) { "register numbers must be unique" }
    }

    fun find(name: String): IrRegister? = registers.firstOrNull { it.name == name }

    fun byClass(registerClass: IrRegisterClass): List<IrRegister> = registers.filter { it.registerClass == registerClass }
}

data class StackSlot(
    val id: Int,
    val size: Int,
    val alignment: Int,
    val offset: Long,
)

data class StackFrame(
    val slots: List<StackSlot>,
    val reservedBytes: Long,
    val alignment: Int,
) {
    val size: Long
        get() = alignUp(reservedBytes, alignment.toLong())
}

class StackFrameBuilder(
    private val alignment: Int,
    initialOffset: Long = 0,
) {
    private val slots = ArrayList<StackSlot>()
    private var cursor = initialOffset

    init {
        require(alignment > 0) { "stack alignment must be positive" }
        require(initialOffset >= 0) { "initial stack offset must not be negative" }
    }

    fun allocate(size: Int, requestedAlignment: Int = alignment): StackSlot {
        require(size > 0) { "stack slot size must be positive" }
        require(requestedAlignment > 0) { "stack slot alignment must be positive" }
        cursor = alignUp(cursor, requestedAlignment.toLong())
        val slot = StackSlot(slots.size, size, requestedAlignment, cursor)
        slots += slot
        cursor += size
        return slot
    }

    fun finish(): StackFrame = StackFrame(slots.toList(), cursor, alignment)
}

sealed interface AbiLocation {
    data class Register(val value: IrRegister) : AbiLocation
    data class Stack(val slot: StackSlot) : AbiLocation
}

data class ArgumentAssignment(
    val index: Int,
    val type: IrType,
    val location: AbiLocation,
)

data class CallingConventionDescriptor(
    val name: String,
    val architecture: IrArchitecture,
    val pointerBits: Int,
    val stackAlignment: Int,
    val shadowSpace: Int = 0,
    val integerArgumentRegisters: List<IrRegister> = emptyList(),
    val floatingArgumentRegisters: List<IrRegister> = emptyList(),
    val integerReturnRegisters: List<IrRegister> = emptyList(),
    val floatingReturnRegisters: List<IrRegister> = emptyList(),
    val calleeSavedRegisters: List<IrRegister> = emptyList(),
) {
    init {
        require(pointerBits > 0) { "pointer width must be positive" }
        require(stackAlignment > 0) { "stack alignment must be positive" }
        require(shadowSpace >= 0) { "shadow space must not be negative" }
    }
}

class CallingConventionPlanner(private val convention: CallingConventionDescriptor) {
    fun assignArguments(types: List<IrType>): List<ArgumentAssignment> {
        val frame = StackFrameBuilder(convention.stackAlignment, convention.shadowSpace.toLong())
        var integerIndex = 0
        var floatingIndex = 0
        return types.mapIndexed { index, type ->
            val register = if (type.isFloatingPoint() && floatingIndex < convention.floatingArgumentRegisters.size) {
                convention.floatingArgumentRegisters[floatingIndex++]
            } else if (!type.isFloatingPoint() && integerIndex < convention.integerArgumentRegisters.size) {
                convention.integerArgumentRegisters[integerIndex++]
            } else null
            ArgumentAssignment(index, type, register?.let(AbiLocation::Register) ?: AbiLocation.Stack(
                frame.allocate(type.storageSize(convention.pointerBits), type.storageAlignment(convention.pointerBits)),
            ))
        }
    }

    fun returnLocation(type: IrType): AbiLocation? {
        if (type == IrType.Void) return null
        val registers = if (type.isFloatingPoint()) convention.floatingReturnRegisters else convention.integerReturnRegisters
        return registers.firstOrNull()?.let(AbiLocation::Register)
    }
}

enum class IrSectionKind { TEXT, RODATA, DATA, BSS, TLS, DEBUG, CUSTOM }

data class IrSectionFlags(
    val allocatable: Boolean,
    val writable: Boolean,
    val executable: Boolean,
)

data class SectionAllocation(
    val section: String,
    val offset: Long,
    val size: Int,
)

data class SectionSnapshot(
    val name: String,
    val kind: IrSectionKind,
    val flags: IrSectionFlags,
    val size: Long,
    val contents: ByteArray,
)

class IrSection internal constructor(
    val name: String,
    val kind: IrSectionKind,
    val flags: IrSectionFlags,
) {
    private val contents = ByteArrayOutputStream()
    var size: Long = 0
        private set

    fun append(bytes: ByteArray, alignment: Int = 1): SectionAllocation {
        require(alignment > 0) { "section alignment must be positive" }
        val offset = alignUp(size, alignment.toLong())
        appendPadding(offset - size)
        contents.write(bytes)
        size = offset + bytes.size
        return SectionAllocation(name, offset, bytes.size)
    }

    fun reserve(bytes: Int, alignment: Int = 1): SectionAllocation {
        require(bytes >= 0) { "reserved section size must not be negative" }
        require(alignment > 0) { "section alignment must be positive" }
        val offset = alignUp(size, alignment.toLong())
        appendPadding(offset - size)
        size = offset + bytes
        return SectionAllocation(name, offset, bytes)
    }

    fun snapshot(): SectionSnapshot = SectionSnapshot(name, kind, flags, size, contents.toByteArray())

    private fun appendPadding(count: Long) {
        repeat(count.toInt()) { contents.write(0) }
    }
}

class IrSectionTable {
    private val sections = LinkedHashMap<String, IrSection>()

    fun getOrCreate(
        name: String,
        kind: IrSectionKind,
        flags: IrSectionFlags,
    ): IrSection {
        val existing = sections[name]
        if (existing != null) {
            require(existing.kind == kind && existing.flags == flags) { "section '$name' was declared with incompatible metadata" }
            return existing
        }
        return IrSection(name, kind, flags).also { sections[name] = it }
    }

    fun find(name: String): IrSection? = sections[name]

    fun snapshots(): List<SectionSnapshot> = sections.values.map { it.snapshot() }
}

data class RelocationContract(
    val kind: IrRelocationKind,
    val width: Int,
    val pcRelative: Boolean,
    val requiresExecutableSection: Boolean = false,
)

object IrRelocationContracts {
    private val contracts = listOf(
        RelocationContract(IrRelocationKind.ABSOLUTE, 1, pcRelative = false),
        RelocationContract(IrRelocationKind.ABSOLUTE, 2, pcRelative = false),
        RelocationContract(IrRelocationKind.ABSOLUTE, 4, pcRelative = false),
        RelocationContract(IrRelocationKind.ABSOLUTE, 8, pcRelative = false),
        RelocationContract(IrRelocationKind.PC_RELATIVE, 4, pcRelative = true, requiresExecutableSection = true),
        RelocationContract(IrRelocationKind.PC_RELATIVE, 8, pcRelative = true, requiresExecutableSection = true),
        RelocationContract(IrRelocationKind.GOT, 8, pcRelative = false),
        RelocationContract(IrRelocationKind.PLT, 4, pcRelative = true, requiresExecutableSection = true),
        RelocationContract(IrRelocationKind.TLS, 4, pcRelative = false),
        RelocationContract(IrRelocationKind.TLS, 8, pcRelative = false),
    )

    fun resolve(kind: IrRelocationKind, width: Int): RelocationContract? =
        contracts.firstOrNull { it.kind == kind && it.width == width }
}

class IrRelocationTable(private val sections: IrSectionTable) {
    private val relocations = ArrayList<IrRelocation>()

    fun add(
        sectionName: String,
        offset: Long,
        width: Int,
        kind: IrRelocationKind,
        symbol: IrSymbol,
        addend: Long = 0,
    ): IrRelocation {
        val section = sections.find(sectionName) ?: error("unknown relocation section '$sectionName'")
        val contract = IrRelocationContracts.resolve(kind, width)
            ?: error("no relocation contract for $kind/$width")
        require(offset >= 0 && offset + width <= section.size) { "relocation does not fit in section '$sectionName'" }
        require(!contract.requiresExecutableSection || section.flags.executable) {
            "$kind relocation requires an executable section"
        }
        return IrRelocation(sectionName, offset, width, kind, symbol, addend).also { relocations += it }
    }

    fun all(): List<IrRelocation> = relocations.toList()
}

private fun IrType.isFloatingPoint(): Boolean = this is IrType.Floating

private fun IrType.storageSize(pointerBits: Int): Int = when (this) {
    IrType.Void -> 0
    is IrType.Integer -> (bits + 7) / 8
    is IrType.Floating -> (bits + 7) / 8
    is IrType.Pointer -> pointerBits / 8
    is IrType.Aggregate -> fields.sumOf { it.storageSize(pointerBits) }.coerceAtLeast(1)
    is IrType.Function -> pointerBits / 8
}

private fun IrType.storageAlignment(pointerBits: Int): Int = when (this) {
    IrType.Void -> 1
    is IrType.Integer -> storageSize(pointerBits).coerceAtMost(8).coerceAtLeast(1)
    is IrType.Floating -> storageSize(pointerBits).coerceAtMost(16).coerceAtLeast(1)
    is IrType.Pointer, is IrType.Function -> (pointerBits / 8).coerceAtLeast(1)
    is IrType.Aggregate -> fields.maxOfOrNull { it.storageAlignment(pointerBits) } ?: 1
}

private fun alignUp(value: Long, alignment: Long): Long {
    val remainder = value % alignment
    return if (remainder == 0L) value else value + alignment - remainder
}
