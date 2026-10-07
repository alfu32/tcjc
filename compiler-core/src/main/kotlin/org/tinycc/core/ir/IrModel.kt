package org.tinycc.core.ir

import java.math.BigInteger
import org.tinycc.core.diagnostics.SourceSpan

sealed interface IrType {
    data object Void : IrType

    data class Integer(val bits: Int, val signed: Boolean) : IrType {
        init {
            require(bits > 0) { "integer width must be positive" }
        }
    }

    data class Floating(val bits: Int) : IrType {
        init {
            require(bits in setOf(16, 32, 64, 80, 128)) { "unsupported floating-point width: $bits" }
        }
    }

    data class Pointer(val pointee: IrType, val addressSpace: Int = 0) : IrType {
        init {
            require(addressSpace >= 0) { "address space must not be negative" }
        }
    }

    data class Aggregate(
        val name: String?,
        val fields: List<IrType>,
        val packed: Boolean = false,
    ) : IrType

    data class Function(
        val returnType: IrType,
        val parameters: List<IrType>,
        val variadic: Boolean = false,
    ) : IrType
}

object IrTypes {
    val i1 = IrType.Integer(1, signed = false)
    val i8 = IrType.Integer(8, signed = false)
    val i32 = IrType.Integer(32, signed = true)
    val i64 = IrType.Integer(64, signed = true)
    val f32 = IrType.Floating(32)
    val f64 = IrType.Floating(64)

    fun pointer(to: IrType, addressSpace: Int = 0): IrType.Pointer = IrType.Pointer(to, addressSpace)
}

data class IrDebugLocation(
    val span: SourceSpan,
    val function: String? = null,
)

sealed interface IrValue {
    val type: IrType

    data class Local(
        val id: Int,
        override val type: IrType,
        val name: String? = null,
    ) : IrValue {
        init {
            require(id >= 0) { "local value id must not be negative" }
        }
    }

    data class Parameter(
        val index: Int,
        override val type: IrType,
        val name: String? = null,
    ) : IrValue {
        init {
            require(index >= 0) { "parameter index must not be negative" }
        }
    }

    data class IntegerConstant(
        val value: BigInteger,
        val bits: Int,
        val signed: Boolean,
    ) : IrValue {
        override val type: IrType = IrType.Integer(bits, signed)
    }

    data class FloatingConstant(
        val raw: String,
        val bits: Int,
        val value: Double? = null,
    ) : IrValue {
        override val type: IrType = IrType.Floating(bits)
    }

    data class NullPointer(val pointee: IrType, val addressSpace: Int = 0) : IrValue {
        override val type: IrType = IrType.Pointer(pointee, addressSpace)
    }

    data class Undef(override val type: IrType) : IrValue

    data class SymbolAddress(
        val symbol: IrSymbol,
        val addressSpace: Int = 0,
    ) : IrValue {
        override val type: IrType = IrType.Pointer(symbol.type, addressSpace)
    }
}

enum class IrLinkage { EXTERNAL, INTERNAL, PRIVATE }

data class IrSymbol(
    val name: String,
    val type: IrType,
    val linkage: IrLinkage = IrLinkage.EXTERNAL,
    val section: String? = null,
    val threadLocal: Boolean = false,
)

data class IrGlobal(
    val symbol: IrSymbol,
    val initializer: IrValue? = null,
    val mutable: Boolean = true,
    val alignment: Int = 1,
    val debugLocation: IrDebugLocation? = null,
)

enum class IrBinaryOp {
    ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER,
    SHIFT_LEFT, SHIFT_RIGHT,
    BITWISE_AND, BITWISE_OR, BITWISE_XOR,
}

enum class IrCompareCondition {
    EQUAL, NOT_EQUAL,
    SIGNED_LESS, SIGNED_LESS_EQUAL, SIGNED_GREATER, SIGNED_GREATER_EQUAL,
    UNSIGNED_LESS, UNSIGNED_LESS_EQUAL, UNSIGNED_GREATER, UNSIGNED_GREATER_EQUAL,
    FLOAT_ORDERED_LESS, FLOAT_ORDERED_LESS_EQUAL, FLOAT_ORDERED_GREATER, FLOAT_ORDERED_GREATER_EQUAL,
}

enum class IrCastKind { BITCAST, INTEGER_EXTEND, INTEGER_TRUNCATE, SIGN_EXTEND, ZERO_EXTEND, FLOAT_EXTEND, FLOAT_TRUNCATE, INT_TO_FLOAT, FLOAT_TO_INT }

enum class IrCallingConvention { C, FAST, TARGET }

enum class IrAtomicOperation { EXCHANGE, ADD, SUBTRACT, AND, OR, XOR }

enum class IrMemoryOrder { RELAXED, ACQUIRE, RELEASE, ACQ_REL, SEQ_CST }

sealed interface IrInstruction {
    val result: IrValue.Local?
    val debugLocation: IrDebugLocation?

    data class Alloca(
        override val result: IrValue.Local,
        val allocatedType: IrType,
        val count: IrValue,
        val alignment: Int = 1,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class Load(
        override val result: IrValue.Local,
        val address: IrValue,
        val loadedType: IrType,
        val alignment: Int = 1,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class Store(
        val value: IrValue,
        val address: IrValue,
        val alignment: Int = 1,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction {
        override val result: IrValue.Local? = null
    }

    data class Binary(
        override val result: IrValue.Local,
        val operation: IrBinaryOp,
        val left: IrValue,
        val right: IrValue,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class Compare(
        override val result: IrValue.Local,
        val condition: IrCompareCondition,
        val left: IrValue,
        val right: IrValue,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class Cast(
        override val result: IrValue.Local,
        val kind: IrCastKind,
        val value: IrValue,
        val targetType: IrType,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class GetElementPointer(
        override val result: IrValue.Local,
        val base: IrValue,
        val indices: List<IrValue>,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class Call(
        override val result: IrValue.Local?,
        val callee: IrValue,
        val functionType: IrType.Function,
        val arguments: List<IrValue>,
        val callingConvention: IrCallingConvention = IrCallingConvention.C,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class AtomicRmw(
        override val result: IrValue.Local,
        val operation: IrAtomicOperation,
        val address: IrValue,
        val value: IrValue,
        val memoryOrder: IrMemoryOrder = IrMemoryOrder.SEQ_CST,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction

    data class CompareExchange(
        override val result: IrValue.Local,
        val address: IrValue,
        val expected: IrValue,
        val replacement: IrValue,
        val memoryOrder: IrMemoryOrder = IrMemoryOrder.SEQ_CST,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrInstruction
}

sealed interface IrTerminator {
    val debugLocation: IrDebugLocation?

    data class Jump(val target: String, override val debugLocation: IrDebugLocation? = null) : IrTerminator

    data class Branch(
        val condition: IrValue,
        val trueTarget: String,
        val falseTarget: String,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrTerminator

    data class Switch(
        val value: IrValue,
        val defaultTarget: String,
        val cases: List<Case>,
        override val debugLocation: IrDebugLocation? = null,
    ) : IrTerminator {
        data class Case(val value: IrValue.IntegerConstant, val target: String)
    }

    data class Return(val value: IrValue? = null, override val debugLocation: IrDebugLocation? = null) : IrTerminator

    data class Unreachable(override val debugLocation: IrDebugLocation? = null) : IrTerminator
}

data class IrBasicBlock(
    val name: String,
    val instructions: List<IrInstruction>,
    val terminator: IrTerminator?,
    val debugLocation: IrDebugLocation? = null,
)

data class IrParameter(val name: String?, val type: IrType)

data class IrFunction(
    val symbol: IrSymbol,
    val parameters: List<IrParameter>,
    val blocks: List<IrBasicBlock>,
    val debugLocation: IrDebugLocation? = null,
)

enum class IrRelocationKind { ABSOLUTE, PC_RELATIVE, GOT, PLT, TLS }

data class IrRelocation(
    val section: String,
    val offset: Long,
    val width: Int,
    val kind: IrRelocationKind,
    val symbol: IrSymbol,
    val addend: Long = 0,
    val debugLocation: IrDebugLocation? = null,
) {
    init {
        require(offset >= 0) { "relocation offset must not be negative" }
        require(width in setOf(1, 2, 4, 8, 16)) { "unsupported relocation width: $width" }
    }
}

data class IrModule(
    val name: String,
    val globals: List<IrGlobal> = emptyList(),
    val functions: List<IrFunction> = emptyList(),
    val relocations: List<IrRelocation> = emptyList(),
)
