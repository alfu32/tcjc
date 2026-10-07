package org.tinycc.core.types

import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.expressions.Expression

enum class TargetArchitecture { I386, X86_64, ARM, ARM64, RISCV64, C67 }

data class TargetDataModel(
    val architecture: TargetArchitecture,
    val pointerBytes: Long,
    val shortBytes: Long,
    val intBytes: Long,
    val longBytes: Long,
    val longLongBytes: Long,
    val floatBytes: Long,
    val doubleBytes: Long,
    val longDoubleBytes: Long,
    val pointerAlignment: Long = pointerBytes,
    val maxAlignment: Long = pointerAlignment,
)

object TargetDataModels {
    val I386_SYSV = TargetDataModel(TargetArchitecture.I386, 4, 2, 4, 4, 8, 4, 8, 12, 4, 8)
    val X86_64_SYSV = TargetDataModel(TargetArchitecture.X86_64, 8, 2, 4, 8, 8, 4, 8, 16, 8, 16)
    val ARM_EABI = TargetDataModel(TargetArchitecture.ARM, 4, 2, 4, 4, 8, 4, 8, 8, 4, 8)
    val ARM64_AAPCS = TargetDataModel(TargetArchitecture.ARM64, 8, 2, 4, 8, 8, 4, 8, 16, 8, 16)
    val RISCV64 = TargetDataModel(TargetArchitecture.RISCV64, 8, 2, 4, 8, 8, 4, 8, 16, 8, 16)
    val C67_MODEL = TargetDataModel(TargetArchitecture.C67, 4, 2, 4, 4, 8, 4, 8, 8, 4, 8)
}

data class FieldLayout(
    val name: String?,
    val offset: Long,
    val size: Long,
    val alignment: Long,
    val bitOffset: Int? = null,
    val bitWidth: Int? = null,
)

data class RecordLayout(
    val size: Long,
    val alignment: Long,
    val fields: List<FieldLayout>,
)

data class AbiMetadata(
    val architecture: TargetArchitecture,
    val callingConvention: CallingConvention,
    val pointerBytes: Long,
    val stackAlignment: Long,
    val calleeSavedRegisters: Set<String>,
    val aggregateReturnInMemory: Boolean,
)

object AbiMetadataCatalog {
    val I386_SYSV = AbiMetadata(
        TargetArchitecture.I386,
        CallingConvention.CDECL,
        pointerBytes = 4,
        stackAlignment = 4,
        calleeSavedRegisters = setOf("ebx", "esi", "edi", "ebp"),
        aggregateReturnInMemory = true,
    )

    val X86_64_SYSV = AbiMetadata(
        TargetArchitecture.X86_64,
        CallingConvention.SYSV64,
        pointerBytes = 8,
        stackAlignment = 16,
        calleeSavedRegisters = setOf("rbx", "rbp", "r12", "r13", "r14", "r15"),
        aggregateReturnInMemory = false,
    )

    val X86_64_WIN64 = AbiMetadata(
        TargetArchitecture.X86_64,
        CallingConvention.WIN64,
        pointerBytes = 8,
        stackAlignment = 16,
        calleeSavedRegisters = setOf("rbx", "rbp", "rdi", "rsi", "r12", "r13", "r14", "r15"),
        aggregateReturnInMemory = false,
    )

    val ARM_EABI = AbiMetadata(
        TargetArchitecture.ARM,
        CallingConvention.AAPCS,
        pointerBytes = 4,
        stackAlignment = 8,
        calleeSavedRegisters = setOf("r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11", "sp", "lr"),
        aggregateReturnInMemory = false,
    )

    val ARM64_AAPCS = AbiMetadata(
        TargetArchitecture.ARM64,
        CallingConvention.AAPCS64,
        pointerBytes = 8,
        stackAlignment = 16,
        calleeSavedRegisters = (19..28).map { "x$it" }.toSet() + "x29",
        aggregateReturnInMemory = false,
    )

    val RISCV64 = AbiMetadata(
        TargetArchitecture.RISCV64,
        CallingConvention.RISCV64,
        pointerBytes = 8,
        stackAlignment = 16,
        calleeSavedRegisters = setOf("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11"),
        aggregateReturnInMemory = false,
    )

    val C67 = AbiMetadata(
        TargetArchitecture.C67,
        CallingConvention.C67,
        pointerBytes = 4,
        stackAlignment = 8,
        calleeSavedRegisters = setOf("a10", "a11", "a12", "a13", "a14", "a15"),
        aggregateReturnInMemory = true,
    )
}

class TypeLayout(private val model: TargetDataModel) {
    fun sizeOf(type: CType): Long? = when (val unaliased = CTypes.unalias(type)) {
        is CType.Primitive -> primitiveSize(unaliased.kind)
        is CType.Qualified -> unaliased.attributes.vectorBytes ?: sizeOf(unaliased.base)
        is CType.Pointer -> model.pointerBytes
        is CType.Array -> when (val bound = unaliased.bound) {
            is ArrayBound.Constant -> sizeOf(unaliased.element)?.times(bound.length)
            else -> null
        }
        is CType.Function -> null
        is CType.Record -> recordLayout(unaliased)?.size
        is CType.Enumeration -> model.intBytes
        CType.Error -> null
        is CType.Typedef -> error("unalias must remove typedef wrappers")
    }

    fun alignmentOf(type: CType): Long? = when (val unaliased = CTypes.unalias(type)) {
        is CType.Primitive -> primitiveAlignment(unaliased.kind)
        is CType.Qualified -> maxOf(
            alignmentOf(unaliased.base) ?: 1L,
            unaliased.attributes.aligned ?: 1L,
            unaliased.attributes.vectorBytes ?: 1L,
        )
        is CType.Pointer -> model.pointerAlignment
        is CType.Array -> alignmentOf(unaliased.element)
        is CType.Function -> null
        is CType.Record -> recordLayout(unaliased)?.alignment
        is CType.Enumeration -> model.intBytes
        CType.Error -> null
        is CType.Typedef -> error("unalias must remove typedef wrappers")
    }

    fun recordLayout(record: CType.Record): RecordLayout? {
        if (!record.isComplete) return null
        val fieldLayouts = ArrayList<FieldLayout>(record.fields.size)
        var offset = 0L
        var alignment = 1L
        var unionSize = 0L
        var bitStorageOffset: Long? = null
        var bitStorageSize = 0L
        var bitStorageBits = 0
        var bitCursor = 0

        fun flushBitStorage() {
            bitStorageOffset = null
            bitStorageSize = 0
            bitStorageBits = 0
            bitCursor = 0
        }

        record.fields.forEach { field ->
            val isFlexibleTail = field === record.fields.lastOrNull() &&
                CTypes.unalias(field.type) is CType.Array &&
                (CTypes.unalias(field.type) as CType.Array).bound == ArrayBound.Flexible
            val size = sizeOf(field.type) ?: if (isFlexibleTail) 0L else return null
            val fieldAlignment = if (record.packed || record.attributes.packed || field.attributes.packed) 1L
            else field.attributes.aligned ?: alignmentOf(field.type) ?: return null
            alignment = maxOf(alignment, fieldAlignment)

            if (field.bitWidth != null) {
                val width = field.bitWidth
                if (width < 0 || width > size * 8) return null
                if (record.kind == RecordKind.UNION) {
                    fieldLayouts += FieldLayout(field.name, 0, size, fieldAlignment, 0, width)
                    unionSize = maxOf(unionSize, size)
                    return@forEach
                }
                if (width == 0) {
                    flushBitStorage()
                    offset = alignUp(offset, fieldAlignment)
                    fieldLayouts += FieldLayout(field.name, offset, 0, fieldAlignment, 0, width)
                    return@forEach
                }
                val bits = (size * 8).toInt()
                if (bitStorageOffset == null || bitStorageBits != bits || bitCursor + width > bits) {
                    flushBitStorage()
                    val storageOffset = alignUp(offset, fieldAlignment)
                    bitStorageOffset = storageOffset
                    bitStorageSize = size
                    bitStorageBits = bits
                    offset = storageOffset + bitStorageSize
                }
                val storageOffset = bitStorageOffset ?: return@forEach
                fieldLayouts += FieldLayout(field.name, storageOffset, bitStorageSize, fieldAlignment, bitCursor, width)
                bitCursor += width
                if (bitCursor == bitStorageBits) flushBitStorage()
                return@forEach
            }
            flushBitStorage()
            val fieldOffset = if (record.kind == RecordKind.UNION) 0 else alignUp(offset, fieldAlignment)
            fieldLayouts += FieldLayout(field.name, fieldOffset, size, fieldAlignment)
            if (record.kind == RecordKind.UNION) unionSize = maxOf(unionSize, size) else offset = fieldOffset + size
        }
        flushBitStorage()
        val size = if (record.kind == RecordKind.UNION) unionSize else offset
        val recordAlignment = when {
            record.packed || record.attributes.packed -> 1L
            record.alignment != null -> record.alignment.coerceAtLeast(1).coerceAtMost(model.maxAlignment)
            record.attributes.aligned != null -> record.attributes.aligned.coerceAtLeast(1).coerceAtMost(model.maxAlignment)
            else -> alignment.coerceAtMost(model.maxAlignment)
        }
        return RecordLayout(alignUp(size, recordAlignment), recordAlignment, fieldLayouts)
    }

    fun offsetOf(
        record: CType.Record,
        designator: Expression,
        evaluateIndex: (Expression) -> Long?,
    ): Long? {
        fun resolve(type: CType, path: Expression): Pair<CType, Long>? {
            return when (path) {
                is Expression.Name -> {
                    val current = CTypes.unalias(type) as? CType.Record ?: return null
                    val field = current.fields.firstOrNull { it.name == path.identifier } ?: return null
                    val fieldOffset = recordLayout(current)?.fields?.firstOrNull { it.name == path.identifier }?.offset ?: return null
                    field.type to fieldOffset
                }
                is Expression.Member -> {
                    if (path.throughPointer) return null
                    val (containerType, baseOffset) = resolve(type, path.receiver) ?: return null
                    val container = CTypes.unalias(containerType) as? CType.Record ?: return null
                    val field = container.fields.firstOrNull { it.name == path.name } ?: return null
                    val fieldOffset = recordLayout(container)?.fields?.firstOrNull { it.name == path.name }?.offset ?: return null
                    field.type to Math.addExact(baseOffset, fieldOffset)
                }
                is Expression.Index -> {
                    val (arrayType, baseOffset) = resolve(type, path.array) ?: return null
                    val element = when (val aggregate = CTypes.unalias(arrayType)) {
                        is CType.Array -> aggregate.element
                        is CType.Pointer -> aggregate.pointee
                        else -> return null
                    }
                    val index = evaluateIndex(path.index) ?: return null
                    val elementSize = sizeOf(element) ?: return null
                    element to Math.addExact(baseOffset, Math.multiplyExact(index, elementSize))
                }
                else -> null
            }
        }

        return runCatching { resolve(record, designator)?.second }.getOrNull()
    }

    private fun primitiveSize(kind: PrimitiveKind): Long = when (kind) {
        PrimitiveKind.VOID -> 0
        PrimitiveKind.BOOL, PrimitiveKind.CHAR, PrimitiveKind.SIGNED_CHAR, PrimitiveKind.UNSIGNED_CHAR -> 1
        PrimitiveKind.SHORT, PrimitiveKind.UNSIGNED_SHORT -> model.shortBytes
        PrimitiveKind.INT, PrimitiveKind.UNSIGNED_INT -> model.intBytes
        PrimitiveKind.LONG, PrimitiveKind.UNSIGNED_LONG -> model.longBytes
        PrimitiveKind.LONG_LONG, PrimitiveKind.UNSIGNED_LONG_LONG -> model.longLongBytes
        PrimitiveKind.FLOAT -> model.floatBytes
        PrimitiveKind.DOUBLE -> model.doubleBytes
        PrimitiveKind.LONG_DOUBLE -> model.longDoubleBytes
        PrimitiveKind.FLOAT_COMPLEX -> model.floatBytes * 2
        PrimitiveKind.DOUBLE_COMPLEX -> model.doubleBytes * 2
        PrimitiveKind.LONG_DOUBLE_COMPLEX -> model.longDoubleBytes * 2
    }

    private fun primitiveAlignment(kind: PrimitiveKind): Long = when (kind) {
        PrimitiveKind.FLOAT_COMPLEX -> model.floatBytes.coerceAtMost(model.maxAlignment)
        PrimitiveKind.DOUBLE_COMPLEX -> model.doubleBytes.coerceAtMost(model.maxAlignment)
        PrimitiveKind.LONG_DOUBLE_COMPLEX -> model.longDoubleBytes.coerceAtMost(model.maxAlignment)
        else -> primitiveSize(kind).coerceAtMost(model.maxAlignment)
    }.coerceAtLeast(1)

    private fun alignUp(value: Long, alignment: Long): Long =
        if (alignment <= 1) value else (value + alignment - 1) / alignment * alignment
}

class TypeCompatibilityChecker(private val diagnostics: DiagnosticEngine) {
    fun requireCompatible(
        expected: CType,
        actual: CType,
        location: SourceLocation = SourceLocation(),
        context: String = "types",
    ): Boolean {
        if (CTypes.compatible(expected, actual)) return true
        diagnostics.error(location, "incompatible $context: expected $expected, got $actual")
        return false
    }

    fun requireComplete(
        type: CType,
        location: SourceLocation = SourceLocation(),
        context: String = "type",
    ): Boolean {
        if (CTypes.isComplete(type)) return true
        diagnostics.error(location, "$context is incomplete")
        return false
    }
}
