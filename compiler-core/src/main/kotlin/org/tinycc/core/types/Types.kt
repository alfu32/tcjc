package org.tinycc.core.types

enum class PrimitiveKind {
    VOID,
    BOOL,
    CHAR,
    SIGNED_CHAR,
    UNSIGNED_CHAR,
    SHORT,
    UNSIGNED_SHORT,
    INT,
    UNSIGNED_INT,
    LONG,
    UNSIGNED_LONG,
    LONG_LONG,
    UNSIGNED_LONG_LONG,
    FLOAT,
    DOUBLE,
    LONG_DOUBLE,
    FLOAT_COMPLEX,
    DOUBLE_COMPLEX,
    LONG_DOUBLE_COMPLEX,
}

enum class RecordKind { STRUCT, UNION }

enum class CallingConvention {
    CDECL,
    STDCALL,
    FASTCALL,
    THISCALL,
    SYSV64,
    WIN64,
    AAPCS,
    AAPCS64,
    RISCV64,
    C67,
}

data class TypeAttributes(
    val aligned: Long? = null,
    val packed: Boolean = false,
    val addressSpace: Int? = null,
    val vectorBytes: Long? = null,
    val mode: String? = null,
    val mayAlias: Boolean = false,
    val transparentUnion: Boolean = false,
    val deprecated: String? = null,
    val nonnull: Boolean = false,
)

data class TypeQualifiers(
    val isConst: Boolean = false,
    val isVolatile: Boolean = false,
    val isRestrict: Boolean = false,
    val isAtomic: Boolean = false,
) {
    fun plus(other: TypeQualifiers): TypeQualifiers = TypeQualifiers(
        isConst || other.isConst,
        isVolatile || other.isVolatile,
        isRestrict || other.isRestrict,
        isAtomic || other.isAtomic,
    )
}

sealed interface ArrayBound {
    data class Constant(val length: Long) : ArrayBound {
        init {
            require(length >= 0) { "array length must not be negative" }
        }
    }

    data class Variable(val expression: String) : ArrayBound {
        init {
            require(expression.isNotBlank()) { "VLA expression must not be blank" }
        }
    }

    data object Unspecified : ArrayBound
    data object Flexible : ArrayBound
}

sealed interface CType {
    data class Primitive(val kind: PrimitiveKind) : CType

    data class Qualified(
        val base: CType,
        val qualifiers: TypeQualifiers,
        val attributes: TypeAttributes = TypeAttributes(),
    ) : CType

    data class Pointer(val pointee: CType, val qualifiers: TypeQualifiers = TypeQualifiers()) : CType

    data class Array(
        val element: CType,
        val bound: ArrayBound,
        val qualifiers: TypeQualifiers = TypeQualifiers(),
        val isStaticParameter: Boolean = false,
    ) : CType

    data class Parameter(val name: String?, val type: CType)

    data class Function(
        val returnType: CType,
        val parameters: List<Parameter>,
        val variadic: Boolean = false,
        val oldStyle: Boolean = false,
        val callingConvention: CallingConvention = CallingConvention.CDECL,
        val attributes: TypeAttributes = TypeAttributes(),
    ) : CType

    class Record(
        val kind: RecordKind,
        val tag: String?,
        fields: List<Field> = emptyList(),
        val packed: Boolean = false,
        val alignment: Long? = null,
        val attributes: TypeAttributes = TypeAttributes(),
    ) : CType {
        var fields: List<Field> = fields
            private set

        private var completed: Boolean = fields.isNotEmpty()

        val isComplete: Boolean
            get() = completed

        fun completeWith(newFields: List<Field>) {
            check(!isComplete) { "record is already complete" }
            fields = newFields.toList()
            completed = true
        }

        override fun toString(): String = "${kind.name.lowercase()} ${tag ?: "<anonymous>"}"
    }

    class Enumeration(
        val tag: String?,
        constants: List<EnumConstant> = emptyList(),
        val underlying: PrimitiveKind = PrimitiveKind.INT,
    ) : CType {
        var constants: List<EnumConstant> = constants
            private set

        private var completed: Boolean = constants.isNotEmpty()

        val isComplete: Boolean
            get() = completed

        fun completeWith(newConstants: List<EnumConstant>) {
            check(!isComplete) { "enum is already complete" }
            constants = newConstants.toList()
            completed = true
        }

        override fun toString(): String = "enum ${tag ?: "<anonymous>"}"
    }

    data class Typedef(val name: String, val target: CType) : CType

    data object Error : CType
}

data class Field(
    val name: String?,
    val type: CType,
    val bitWidth: Int? = null,
    val attributes: TypeAttributes = TypeAttributes(),
)

data class EnumConstant(val name: String, val value: Long)

object CTypes {
    val void = CType.Primitive(PrimitiveKind.VOID)
    val bool = CType.Primitive(PrimitiveKind.BOOL)
    val char = CType.Primitive(PrimitiveKind.CHAR)
    val int = CType.Primitive(PrimitiveKind.INT)
    val unsignedInt = CType.Primitive(PrimitiveKind.UNSIGNED_INT)
    val long = CType.Primitive(PrimitiveKind.LONG)
    val unsignedLong = CType.Primitive(PrimitiveKind.UNSIGNED_LONG)
    val float = CType.Primitive(PrimitiveKind.FLOAT)
    val double = CType.Primitive(PrimitiveKind.DOUBLE)

    val signedChar = CType.Primitive(PrimitiveKind.SIGNED_CHAR)
    val unsignedChar = CType.Primitive(PrimitiveKind.UNSIGNED_CHAR)
    val short = CType.Primitive(PrimitiveKind.SHORT)
    val unsignedShort = CType.Primitive(PrimitiveKind.UNSIGNED_SHORT)
    val unsignedLongLong = CType.Primitive(PrimitiveKind.UNSIGNED_LONG_LONG)
    val longLong = CType.Primitive(PrimitiveKind.LONG_LONG)
    val longDouble = CType.Primitive(PrimitiveKind.LONG_DOUBLE)
    val floatComplex = CType.Primitive(PrimitiveKind.FLOAT_COMPLEX)
    val doubleComplex = CType.Primitive(PrimitiveKind.DOUBLE_COMPLEX)
    val longDoubleComplex = CType.Primitive(PrimitiveKind.LONG_DOUBLE_COMPLEX)

    fun annotated(base: CType, attributes: TypeAttributes): CType = when (base) {
        is CType.Qualified -> base.copy(attributes = base.attributes.merge(attributes))
        else -> CType.Qualified(base, TypeQualifiers(), attributes)
    }

    fun qualified(base: CType, qualifiers: TypeQualifiers): CType = when (base) {
        is CType.Qualified -> CType.Qualified(base.base, base.qualifiers.plus(qualifiers), base.attributes)
        else -> CType.Qualified(base, qualifiers)
    }

    fun pointer(to: CType, qualifiers: TypeQualifiers = TypeQualifiers()): CType = CType.Pointer(to, qualifiers)

    fun arrayOf(element: CType, length: Long): CType = CType.Array(element, ArrayBound.Constant(length))

    fun arrayOf(
        element: CType,
        length: Long,
        qualifiers: TypeQualifiers = TypeQualifiers(),
        isStaticParameter: Boolean = false,
    ): CType = CType.Array(element, ArrayBound.Constant(length), qualifiers, isStaticParameter)

    fun flexibleArrayOf(element: CType): CType = CType.Array(element, ArrayBound.Flexible)

    fun variableArrayOf(element: CType, expression: String): CType =
        CType.Array(element, ArrayBound.Variable(expression))

    fun function(
        returnType: CType,
        parameters: List<CType>,
        variadic: Boolean = false,
        oldStyle: Boolean = false,
        callingConvention: CallingConvention = CallingConvention.CDECL,
        attributes: TypeAttributes = TypeAttributes(),
    ): CType = CType.Function(
        returnType,
        parameters.map { CType.Parameter(null, it) },
        variadic,
        oldStyle,
        callingConvention,
        attributes,
    )

    fun functionOf(
        returnType: CType,
        parameters: List<CType.Parameter>,
        variadic: Boolean = false,
        oldStyle: Boolean = false,
        callingConvention: CallingConvention = CallingConvention.CDECL,
        attributes: TypeAttributes = TypeAttributes(),
    ): CType = CType.Function(returnType, parameters.map { it.copy(type = adjustParameter(it.type)) }, variadic, oldStyle, callingConvention, attributes)

    fun typedef(name: String, target: CType): CType = CType.Typedef(name, target)

    fun unalias(type: CType): CType {
        var current = type
        val seen = HashSet<CType>()
        while (current is CType.Typedef && seen.add(current)) current = current.target
        return if (current is CType.Typedef) CType.Error else current
    }

    fun isVariablyModified(type: CType): Boolean = when (val unaliased = unalias(type)) {
        is CType.Array -> unaliased.bound is ArrayBound.Variable || isVariablyModified(unaliased.element)
        is CType.Pointer -> isVariablyModified(unaliased.pointee)
        is CType.Qualified -> isVariablyModified(unaliased.base)
        is CType.Function -> isVariablyModified(unaliased.returnType) || unaliased.parameters.any { isVariablyModified(it.type) }
        else -> false
    }

    fun isComplete(type: CType): Boolean = when (val unaliased = unalias(type)) {
        is CType.Primitive -> unaliased.kind != PrimitiveKind.VOID
        is CType.Qualified -> isComplete(unaliased.base)
        is CType.Pointer -> true
        is CType.Array -> when (unaliased.bound) {
            ArrayBound.Unspecified, ArrayBound.Flexible -> false
            else -> isComplete(unaliased.element)
        }
        is CType.Function -> false
        is CType.Record -> unaliased.isComplete
        is CType.Enumeration -> unaliased.isComplete
        CType.Error -> false
        is CType.Typedef -> error("unalias must remove typedef wrappers")
    }

    fun compatible(left: CType, right: CType): Boolean {
        val a = unalias(left)
        val b = unalias(right)
        return when {
            a is CType.Qualified && b is CType.Qualified -> compatible(a.base, b.base)
            a is CType.Qualified -> compatible(a.base, b)
            b is CType.Qualified -> compatible(a, b.base)
            a is CType.Primitive && b is CType.Primitive -> a.kind == b.kind
            a is CType.Pointer && b is CType.Pointer ->
                qualifiersCompatible(a.qualifiers, b.qualifiers) && compatible(a.pointee, b.pointee)
            a is CType.Array && b is CType.Array ->
                qualifiersCompatible(a.qualifiers, b.qualifiers) && compatible(a.element, b.element) && compatibleBounds(a.bound, b.bound)
            a is CType.Function && b is CType.Function ->
                compatible(a.returnType, b.returnType) &&
                    a.callingConvention == b.callingConvention &&
                    a.variadic == b.variadic &&
                    (a.oldStyle || b.oldStyle || a.parameters.size == b.parameters.size &&
                        a.parameters.zip(b.parameters).all { compatible(it.first.type, it.second.type) })
            a is CType.Record && b is CType.Record -> a === b
            a is CType.Enumeration && b is CType.Enumeration -> a === b
            a === CType.Error || b === CType.Error -> true
            else -> false
        }
    }

    private fun compatibleBounds(left: ArrayBound, right: ArrayBound): Boolean = when {
        left is ArrayBound.Constant && right is ArrayBound.Constant -> left.length == right.length
        left is ArrayBound.Variable || right is ArrayBound.Variable -> true
        left is ArrayBound.Unspecified || right is ArrayBound.Unspecified -> true
        left is ArrayBound.Flexible || right is ArrayBound.Flexible -> true
        else -> false
    }

    private fun qualifiersCompatible(left: TypeQualifiers, right: TypeQualifiers): Boolean =
        left == right

    private fun adjustParameter(type: CType): CType = when (val unaliased = unalias(type)) {
        is CType.Array -> pointer(unaliased.element)
        is CType.Function -> pointer(unaliased)
        else -> type
    }
}

private fun TypeAttributes.merge(other: TypeAttributes): TypeAttributes = TypeAttributes(
    aligned = other.aligned ?: aligned,
    packed = packed || other.packed,
    addressSpace = other.addressSpace ?: addressSpace,
    vectorBytes = other.vectorBytes ?: vectorBytes,
    mode = other.mode ?: mode,
    mayAlias = mayAlias || other.mayAlias,
    transparentUnion = transparentUnion || other.transparentUnion,
    deprecated = other.deprecated ?: deprecated,
    nonnull = nonnull || other.nonnull,
)
