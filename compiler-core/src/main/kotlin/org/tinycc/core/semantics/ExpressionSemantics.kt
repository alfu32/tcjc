package org.tinycc.core.semantics

import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.expressions.BinaryOperator
import org.tinycc.core.expressions.Expression
import org.tinycc.core.expressions.Initializer
import org.tinycc.core.expressions.SizeOperand
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.PrimitiveKind

enum class ValueCategory { LVALUE, PRVALUE, FUNCTION_DESIGNATOR, INVALID }

data class TypedExpression(
    val expression: Expression,
    val type: CType,
    val category: ValueCategory,
)

/** Applies C expression conversions and validates lvalue/operator constraints. */
class ExpressionSemanticAnalyzer(
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val symbols: SymbolTable = SymbolTable(diagnostics),
) {
    fun analyze(expression: Expression): TypedExpression = when (expression) {
        is Expression.Name -> analyzeName(expression)
        is Expression.Integer -> typed(expression, CTypes.int)
        is Expression.Floating -> typed(expression, CTypes.double)
        is Expression.Character -> typed(expression, CTypes.int)
        is Expression.StringLiteral -> typed(expression, CTypes.arrayOf(CTypes.char, expression.value.length.toLong() + 1), ValueCategory.LVALUE)
        is Expression.Invalid -> typed(expression, CType.Error, ValueCategory.INVALID)
        is Expression.Unary -> analyzeUnary(expression)
        is Expression.Binary -> analyzeBinary(expression)
        is Expression.Conditional -> analyzeConditional(expression)
        is Expression.Assignment -> analyzeAssignment(expression)
        is Expression.Call -> analyzeCall(expression)
        is Expression.Index -> analyzeIndex(expression)
        is Expression.Member -> analyzeMember(expression)
        is Expression.Cast -> typed(expression, expression.type)
        is Expression.SizeOf -> typed(expression, CTypes.unsignedLong)
        is Expression.CompoundLiteral -> typed(expression, expression.type, ValueCategory.LVALUE)
    }

    fun analyzeInitializer(initializer: Initializer, expected: CType): Boolean = when (initializer) {
        is Initializer.ExpressionValue -> {
            val actual = analyze(initializer.expression)
            requireCompatible(expected, decay(actual), initializer.expression.span.start, "initializer")
        }
        is Initializer.ListValue -> {
            val element = when (val type = canonical(expected)) {
                is CType.Array -> type.element
                is CType.Record -> null
                else -> null
            }
            if (element == null) {
                diagnostics.error(SourceLocation(), "initializer list requires an aggregate type")
                false
            } else initializer.values.all { analyzeInitializer(it, element) }
        }
    }

    private fun analyzeName(expression: Expression.Name): TypedExpression {
        val symbol = symbols.lookup(expression.identifier)
        if (symbol == null) {
            error(expression, "use of undeclared identifier '${expression.identifier}'")
            return typed(expression, CType.Error, ValueCategory.INVALID)
        }
        val category = if (canonical(symbol.type) is CType.Function) ValueCategory.FUNCTION_DESIGNATOR else ValueCategory.LVALUE
        return typed(expression, symbol.type, category)
    }

    private fun analyzeUnary(expression: Expression.Unary): TypedExpression {
        val operand = analyze(expression.operand)
        val canonical = canonical(decay(operand))
        return when (expression.operator) {
            org.tinycc.core.expressions.UnaryOperator.ADDRESS -> {
                if (operand.category !in setOf(ValueCategory.LVALUE, ValueCategory.FUNCTION_DESIGNATOR)) error(expression, "address-of requires an lvalue")
                typed(expression, CTypes.pointer(operand.type))
            }
            org.tinycc.core.expressions.UnaryOperator.DEREFERENCE -> {
                val pointee = (canonical as? CType.Pointer)?.pointee
                if (pointee == null) {
                    error(expression, "cannot dereference non-pointer type")
                    typed(expression, CType.Error, ValueCategory.INVALID)
                } else typed(expression, pointee, ValueCategory.LVALUE)
            }
            org.tinycc.core.expressions.UnaryOperator.LOGICAL_NOT -> typed(expression, CTypes.int)
            org.tinycc.core.expressions.UnaryOperator.BITWISE_NOT,
            org.tinycc.core.expressions.UnaryOperator.PLUS,
            org.tinycc.core.expressions.UnaryOperator.MINUS,
            -> if (isArithmetic(canonical)) typed(expression, canonical) else invalid(expression, "unary operator requires an arithmetic operand")
            org.tinycc.core.expressions.UnaryOperator.PRE_INCREMENT,
            org.tinycc.core.expressions.UnaryOperator.PRE_DECREMENT,
            -> if (operand.category == ValueCategory.LVALUE && isArithmetic(canonical)) typed(expression, operand.type) else invalid(expression, "increment/decrement requires an arithmetic lvalue")
        }
    }

    private fun analyzeBinary(expression: Expression.Binary): TypedExpression {
        val left = analyze(expression.left)
        val right = analyze(expression.right)
        val leftType = canonical(decay(left))
        val rightType = canonical(decay(right))
        return when (expression.operator) {
            BinaryOperator.COMMA -> typed(expression, rightType)
            BinaryOperator.LOGICAL_AND, BinaryOperator.LOGICAL_OR,
            BinaryOperator.LESS, BinaryOperator.LESS_EQUAL, BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL,
            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL,
            -> if (comparable(leftType, rightType)) typed(expression, CTypes.int) else invalid(expression, "incompatible comparison operands")
            BinaryOperator.ADD -> pointerArithmetic(expression, leftType, rightType, subtract = false)
            BinaryOperator.SUBTRACT -> pointerArithmetic(expression, leftType, rightType, subtract = true)
            BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE, BinaryOperator.REMAINDER,
            BinaryOperator.SHIFT_LEFT, BinaryOperator.SHIFT_RIGHT,
            BinaryOperator.BITWISE_AND, BinaryOperator.BITWISE_XOR, BinaryOperator.BITWISE_OR,
            -> if (isArithmetic(leftType) && isArithmetic(rightType)) typed(expression, commonArithmetic(leftType, rightType)) else invalid(expression, "binary operator requires arithmetic operands")
        }
    }

    private fun pointerArithmetic(expression: Expression, left: CType, right: CType, subtract: Boolean): TypedExpression {
        val leftPointer = left as? CType.Pointer
        val rightPointer = right as? CType.Pointer
        val leftInteger = isInteger(left)
        val rightInteger = isInteger(right)
        return when {
            isArithmetic(left) && isArithmetic(right) -> typed(expression, commonArithmetic(left, right))
            leftPointer != null && rightInteger -> typed(expression, left)
            !subtract && leftInteger && rightPointer != null -> typed(expression, right)
            subtract && leftPointer != null && rightPointer != null && CTypes.compatible(leftPointer.pointee, rightPointer.pointee) -> typed(expression, CTypes.long)
            else -> invalid(expression, "invalid pointer arithmetic")
        }
    }

    private fun analyzeConditional(expression: Expression.Conditional): TypedExpression {
        analyze(expression.condition)
        val whenTrue = analyze(expression.whenTrue)
        val whenFalse = analyze(expression.whenFalse)
        val trueType = canonical(decay(whenTrue))
        val falseType = canonical(decay(whenFalse))
        return if (CTypes.compatible(trueType, falseType)) typed(expression, trueType)
        else if (isArithmetic(trueType) && isArithmetic(falseType)) typed(expression, commonArithmetic(trueType, falseType))
        else invalid(expression, "conditional operands have incompatible types")
    }

    private fun analyzeAssignment(expression: Expression.Assignment): TypedExpression {
        val target = analyze(expression.target)
        val value = analyze(expression.value)
        if (target.category != ValueCategory.LVALUE) return invalid(expression, "assignment target is not an lvalue")
        if (!requireCompatible(target.type, decay(value), expression.value.span.start, "assignment")) return typed(expression, target.type)
        return typed(expression, target.type)
    }

    private fun analyzeCall(expression: Expression.Call): TypedExpression {
        val callee = canonical(decay(analyze(expression.callee)))
        val function = when (callee) {
            is CType.Function -> callee
            is CType.Pointer -> canonical(callee.pointee) as? CType.Function
            else -> null
        }
        if (function == null) return invalid(expression, "called expression is not a function")
        if (!function.variadic && expression.arguments.size != function.parameters.size) {
            error(expression, "function expects ${function.parameters.size} argument(s)")
        } else if (expression.arguments.size < function.parameters.size) {
            error(expression, "function expects at least ${function.parameters.size} argument(s)")
        }
        expression.arguments.forEachIndexed { index, argument ->
            val actual = analyze(argument)
            function.parameters.getOrNull(index)?.let { requireCompatible(it.type, decay(actual), argument.span.start, "argument") }
        }
        return typed(expression, function.returnType)
    }

    private fun analyzeIndex(expression: Expression.Index): TypedExpression {
        val base = canonical(decay(analyze(expression.array)))
        val index = canonical(decay(analyze(expression.index)))
        val element = when (base) {
            is CType.Pointer -> base.pointee
            is CType.Array -> base.element
            else -> null
        }
        if (element == null || !isInteger(index)) return invalid(expression, "indexing requires a pointer or array and integer index")
        return typed(expression, element, ValueCategory.LVALUE)
    }

    private fun analyzeMember(expression: Expression.Member): TypedExpression {
        var receiver = canonical(analyze(expression.receiver).type)
        if (expression.throughPointer) receiver = (receiver as? CType.Pointer)?.pointee?.let(::canonical) ?: CType.Error
        val record = receiver as? CType.Record
        val field = record?.fields?.firstOrNull { it.name == expression.name }
        if (field == null) return invalid(expression, "unknown member '${expression.name}'")
        return typed(expression, field.type, ValueCategory.LVALUE)
    }

    private fun decay(value: TypedExpression): CType = when (val type = canonical(value.type)) {
        is CType.Array -> CTypes.pointer(type.element)
        is CType.Function -> CTypes.pointer(type)
        else -> type
    }

    private fun commonArithmetic(left: CType, right: CType): CType {
        if (left is CType.Primitive && right is CType.Primitive) {
            if (left.kind == PrimitiveKind.LONG_DOUBLE || right.kind == PrimitiveKind.LONG_DOUBLE) return CType.Primitive(PrimitiveKind.LONG_DOUBLE)
            if (left.kind == PrimitiveKind.DOUBLE || right.kind == PrimitiveKind.DOUBLE) return CTypes.double
            if (left.kind == PrimitiveKind.FLOAT || right.kind == PrimitiveKind.FLOAT) return CTypes.float
            val rank = maxOf(rank(left.kind), rank(right.kind))
            val unsigned = isUnsigned(left.kind) || isUnsigned(right.kind)
            return CType.Primitive(if (unsigned) unsignedKind(rank) else signedKind(rank))
        }
        return CTypes.int
    }

    private fun comparable(left: CType, right: CType): Boolean =
        CTypes.compatible(left, right) || isArithmetic(left) && isArithmetic(right) ||
            left is CType.Pointer && right is CType.Pointer

    private fun isArithmetic(type: CType): Boolean = when (val value = canonical(type)) {
        is CType.Primitive -> value.kind != PrimitiveKind.VOID
        else -> false
    }

    private fun isInteger(type: CType): Boolean = when (val value = canonical(type)) {
        is CType.Primitive -> value.kind in setOf(
            PrimitiveKind.BOOL, PrimitiveKind.CHAR, PrimitiveKind.SIGNED_CHAR, PrimitiveKind.UNSIGNED_CHAR,
            PrimitiveKind.SHORT, PrimitiveKind.UNSIGNED_SHORT, PrimitiveKind.INT, PrimitiveKind.UNSIGNED_INT,
            PrimitiveKind.LONG, PrimitiveKind.UNSIGNED_LONG, PrimitiveKind.LONG_LONG, PrimitiveKind.UNSIGNED_LONG_LONG,
        )
        else -> false
    }

    private fun isUnsigned(kind: PrimitiveKind): Boolean = kind.name.startsWith("UNSIGNED")

    private fun rank(kind: PrimitiveKind): Int = when (kind) {
        PrimitiveKind.BOOL, PrimitiveKind.CHAR, PrimitiveKind.SIGNED_CHAR, PrimitiveKind.UNSIGNED_CHAR -> 1
        PrimitiveKind.SHORT, PrimitiveKind.UNSIGNED_SHORT -> 2
        PrimitiveKind.INT, PrimitiveKind.UNSIGNED_INT -> 3
        PrimitiveKind.LONG, PrimitiveKind.UNSIGNED_LONG -> 4
        PrimitiveKind.LONG_LONG, PrimitiveKind.UNSIGNED_LONG_LONG -> 5
        else -> 6
    }

    private fun signedKind(rank: Int): PrimitiveKind = when (rank) {
        1 -> PrimitiveKind.SIGNED_CHAR
        2 -> PrimitiveKind.SHORT
        3 -> PrimitiveKind.INT
        4 -> PrimitiveKind.LONG
        else -> PrimitiveKind.LONG_LONG
    }

    private fun unsignedKind(rank: Int): PrimitiveKind = when (rank) {
        1 -> PrimitiveKind.UNSIGNED_CHAR
        2 -> PrimitiveKind.UNSIGNED_SHORT
        3 -> PrimitiveKind.UNSIGNED_INT
        4 -> PrimitiveKind.UNSIGNED_LONG
        else -> PrimitiveKind.UNSIGNED_LONG_LONG
    }

    private fun canonical(type: CType): CType = when (type) {
        is CType.Typedef -> canonical(type.target)
        is CType.Qualified -> canonical(type.base)
        else -> type
    }

    private fun requireCompatible(expected: CType, actual: CType, location: SourceLocation, context: String): Boolean {
        if (CTypes.compatible(expected, actual)) return true
        diagnostics.error(location, "incompatible $context: expected $expected, got $actual")
        return false
    }

    private fun <T : Expression> typed(expression: T, type: CType, category: ValueCategory = ValueCategory.PRVALUE) =
        TypedExpression(expression, type, category)

    private fun invalid(expression: Expression, message: String): TypedExpression {
        error(expression, message)
        return typed(expression, CType.Error, ValueCategory.INVALID)
    }

    private fun error(expression: Expression, message: String) = diagnostics.error(expression.span.start, message)
}
