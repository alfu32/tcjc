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
}

enum class RecordKind { STRUCT, UNION }

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

    data class Qualified(val base: CType, val qualifiers: TypeQualifiers) : CType

    data class Pointer(val pointee: CType, val qualifiers: TypeQualifiers = TypeQualifiers()) : CType

    data class Array(val element: CType, val bound: ArrayBound) : CType

    data class Parameter(val name: String?, val type: CType)

    data class Function(
        val returnType: CType,
        val parameters: List<Parameter>,
        val variadic: Boolean = false,
        val oldStyle: Boolean = false,
    ) : CType

    class Record(
        val kind: RecordKind,
        val tag: String?,
        fields: List<Field> = emptyList(),
        val packed: Boolean = false,
    ) : CType {
        var fields: List<Field> = fields
            private set

        val isComplete: Boolean
            get() = fields.isNotEmpty()

        fun completeWith(newFields: List<Field>) {
            check(!isComplete) { "record is already complete" }
            fields = newFields.toList()
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

        val isComplete: Boolean
            get() = constants.isNotEmpty()

        fun completeWith(newConstants: List<EnumConstant>) {
            check(!isComplete) { "enum is already complete" }
            constants = newConstants.toList()
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

    fun qualified(base: CType, qualifiers: TypeQualifiers): CType = when (base) {
        is CType.Qualified -> CType.Qualified(base.base, base.qualifiers.plus(qualifiers))
        else -> CType.Qualified(base, qualifiers)
    }

    fun pointer(to: CType, qualifiers: TypeQualifiers = TypeQualifiers()): CType = CType.Pointer(to, qualifiers)

    fun arrayOf(element: CType, length: Long): CType = CType.Array(element, ArrayBound.Constant(length))

    fun variableArrayOf(element: CType, expression: String): CType =
        CType.Array(element, ArrayBound.Variable(expression))

    fun function(
        returnType: CType,
        parameters: List<CType>,
        variadic: Boolean = false,
    ): CType = CType.Function(returnType, parameters.map { CType.Parameter(null, it) }, variadic)

    fun typedef(name: String, target: CType): CType = CType.Typedef(name, target)

    fun unalias(type: CType): CType = when (type) {
        is CType.Typedef -> unalias(type.target)
        else -> type
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
            a is CType.Pointer && b is CType.Pointer -> compatible(a.pointee, b.pointee)
            a is CType.Array && b is CType.Array -> compatible(a.element, b.element) && compatibleBounds(a.bound, b.bound)
            a is CType.Function && b is CType.Function ->
                compatible(a.returnType, b.returnType) && a.variadic == b.variadic &&
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
}
