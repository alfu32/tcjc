package org.tinycc.core.constants

import java.math.BigDecimal
import java.math.BigInteger
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.expressions.BinaryOperator
import org.tinycc.core.expressions.Expression
import org.tinycc.core.expressions.SizeOperand
import org.tinycc.core.semantics.ExpressionSemanticAnalyzer
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.TargetDataModels
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.TypeLayout

enum class RelocationKind { ABSOLUTE, PC_RELATIVE, GOT, PLT, TLS, SECTION_RELATIVE }

data class Relocation(val symbol: String, val addend: Long = 0, val kind: RelocationKind = RelocationKind.ABSOLUTE)

sealed interface ConstantValue {
    data class Integer(val value: BigInteger) : ConstantValue
    data class Floating(val value: BigDecimal) : ConstantValue
    data class Address(val relocation: Relocation) : ConstantValue
    data class Aggregate(val values: List<ConstantValue>) : ConstantValue
    data object Zero : ConstantValue
    data object NotConstant : ConstantValue
}

/** Evaluates expressions permitted in C constant-expression and relocation contexts. */
class ConstantEvaluator(
    private val diagnostics: DiagnosticEngine = DiagnosticEngine(),
    private val symbols: SymbolTable = SymbolTable(diagnostics),
    private val layout: TypeLayout = TypeLayout(TargetDataModels.X86_64_SYSV),
) {
    private val semanticAnalyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

    fun evaluate(expression: Expression): ConstantValue = when (expression) {
        is Expression.Integer -> ConstantValue.Integer(expression.value)
        is Expression.Character -> ConstantValue.Integer(BigInteger.valueOf(expression.value.toLong()))
        is Expression.Floating -> parseFloating(expression.raw)?.let(ConstantValue::Floating)
            ?: notConstant(expression, "invalid floating constant")
        is Expression.Name, is Expression.StringLiteral -> ConstantValue.NotConstant
        is Expression.Invalid, is Expression.Index, is Expression.Member -> ConstantValue.NotConstant
        is Expression.Call -> evaluateCall(expression)
        is Expression.CompoundLiteral -> ConstantValue.NotConstant
        is Expression.Assignment -> notConstant(expression, "assignment is not a constant expression")
        is Expression.Cast -> cast(expression.type, evaluate(expression.operand), expression)
        is Expression.SizeOf -> evaluateSizeOf(expression)
        is Expression.AlignOf -> evaluateAlignOf(expression)
        is Expression.TypeOf -> notConstant(expression, "typeof does not produce a runtime value")
        is Expression.GenericSelection -> evaluateGeneric(expression)
        is Expression.StatementExpression -> notConstant(expression, "statement expression is not a constant expression")
        is Expression.TypeOperand -> notConstant(expression, "type operand is not a constant expression")
        is Expression.LabelAddress -> notConstant(expression, "label address is not a link-time constant")
        is Expression.Unary -> evaluateUnary(expression)
        is Expression.Binary -> evaluateBinary(expression)
        is Expression.Conditional -> {
            when (val condition = evaluate(expression.condition)) {
                is ConstantValue.Integer -> if (condition.value != BigInteger.ZERO) evaluate(expression.whenTrue) else evaluate(expression.whenFalse)
                else -> notConstant(expression, "conditional expression is not constant")
            }
        }
    }

    fun evaluateInitializer(initializer: org.tinycc.core.expressions.Initializer, expected: CType): ConstantValue {
        return when (initializer) {
            is org.tinycc.core.expressions.Initializer.ExpressionValue -> {
                val value = evaluate(initializer.expression)
                if (value is ConstantValue.NotConstant) {
                    notConstant(initializer.expression, "initializer is not a constant expression")
                } else value
            }
            is org.tinycc.core.expressions.Initializer.ListValue -> {
                val aggregate = canonical(expected)
                if (aggregate is CType.Array || aggregate is CType.Record) {
                    val elementTypes = initializerElementTypes(expected, initializer.values.size)
                    if (elementTypes == null || initializer.values.size > elementTypes.size && aggregate !is CType.Array) {
                        diagnostics.error(SourceLocation(), "initializer list does not match aggregate type")
                        return ConstantValue.NotConstant
                    }
                    if (aggregate is CType.Array && aggregate.bound is ArrayBound.Constant && initializer.values.size > aggregate.bound.length) {
                        diagnostics.error(SourceLocation(), "too many initializers for array")
                        return ConstantValue.NotConstant
                    }
                    evaluateAggregateInitializer(initializer.values, expected, elementTypes!!)
                } else if (initializer.values.size == 1) {
                    evaluateInitializer(initializer.values.single(), expected)
                } else {
                    diagnostics.error(SourceLocation(), "initializer list does not match aggregate type")
                    ConstantValue.NotConstant
                }
            }
            is org.tinycc.core.expressions.Initializer.Designated -> {
                val target = designatedType(expected, initializer.designator)
                if (target == null) {
                    diagnostics.error(SourceLocation(), "designator does not match initializer type")
                    ConstantValue.NotConstant
                } else evaluateInitializer(initializer.value, target)
            }
        }
    }

    private fun evaluateAggregateInitializer(
        initializers: List<org.tinycc.core.expressions.Initializer>,
        expected: CType,
        elementTypes: List<CType>,
    ): ConstantValue {
        val values = MutableList<ConstantValue>(elementTypes.size) { ConstantValue.Zero }
        var next = 0
        for (initializer in initializers) {
            val designated = initializer as? org.tinycc.core.expressions.Initializer.Designated
            val candidateIndex = if (designated == null) next else designatedIndex(expected, designated.designator)
            if (candidateIndex == null || candidateIndex !in values.indices) {
                diagnostics.error(SourceLocation(), "designator does not match initializer type")
                return ConstantValue.NotConstant
            }
            val index = candidateIndex
            val targetType = if (designated == null) elementTypes.getOrNull(index) else designatedType(expected, designated.designator)
            if (targetType == null) {
                diagnostics.error(SourceLocation(), "designator does not match initializer type")
                return ConstantValue.NotConstant
            }
            val valueInitializer = designated?.value ?: initializer
            values[index] = evaluateInitializer(valueInitializer, targetType)
            next = index + 1
        }
        return ConstantValue.Aggregate(values)
    }

    private fun designatedIndex(type: CType, designator: org.tinycc.core.expressions.Designator): Int? = when (designator) {
        is org.tinycc.core.expressions.Designator.Field -> (canonical(type) as? CType.Record)?.fields?.indexOfFirst { it.name == designator.name }?.takeIf { it >= 0 }
        is org.tinycc.core.expressions.Designator.Index -> {
            val value = evaluate(designator.expression) as? ConstantValue.Integer ?: return null
            value.value.toInt()
        }
    }

    private fun designatedType(type: CType, designator: org.tinycc.core.expressions.Designator): CType? = when (designator) {
        is org.tinycc.core.expressions.Designator.Field -> (canonical(type) as? CType.Record)?.fields?.firstOrNull { it.name == designator.name }?.type
        is org.tinycc.core.expressions.Designator.Index -> (canonical(type) as? CType.Array)?.element
    }

    private fun evaluateUnary(expression: Expression.Unary): ConstantValue {
        if (expression.operator == org.tinycc.core.expressions.UnaryOperator.ADDRESS) return addressOf(expression.operand, expression)
        val value = evaluate(expression.operand)
        return when (expression.operator) {
            org.tinycc.core.expressions.UnaryOperator.PLUS -> value
            org.tinycc.core.expressions.UnaryOperator.MINUS -> integer(value)?.let { ConstantValue.Integer(it.negate()) }
                ?: floating(value)?.let { ConstantValue.Floating(it.negate()) }
                ?: notConstant(expression, "unary operand is not constant")
            org.tinycc.core.expressions.UnaryOperator.LOGICAL_NOT -> integer(value)?.let { bool(it == BigInteger.ZERO) }
                ?: floating(value)?.let { bool(it.compareTo(BigDecimal.ZERO) == 0) }
                ?: notConstant(expression, "unary operand is not constant")
            org.tinycc.core.expressions.UnaryOperator.BITWISE_NOT -> integer(value)?.let { ConstantValue.Integer(it.not()) } ?: notConstant(expression, "unary operand is not constant")
            org.tinycc.core.expressions.UnaryOperator.DEREFERENCE,
            org.tinycc.core.expressions.UnaryOperator.PRE_INCREMENT,
            org.tinycc.core.expressions.UnaryOperator.PRE_DECREMENT,
            org.tinycc.core.expressions.UnaryOperator.POST_INCREMENT,
            org.tinycc.core.expressions.UnaryOperator.POST_DECREMENT,
            -> notConstant(expression, "operator is not permitted in a constant expression")
            org.tinycc.core.expressions.UnaryOperator.ADDRESS -> error("unreachable")
        }
    }

    private fun evaluateBinary(expression: Expression.Binary): ConstantValue {
        val left = evaluate(expression.left)
        if (expression.operator == BinaryOperator.LOGICAL_AND) {
            val leftInteger = integer(left)
            if (leftInteger != null && leftInteger == BigInteger.ZERO) return bool(false)
        }
        if (expression.operator == BinaryOperator.LOGICAL_OR) {
            val leftInteger = integer(left)
            if (leftInteger != null && leftInteger != BigInteger.ZERO) return bool(true)
        }
        val right = evaluate(expression.right)
        if (expression.operator == BinaryOperator.COMMA) return right
        if (left is ConstantValue.Address || right is ConstantValue.Address) return evaluateAddressBinary(expression, left, right)
        val leftInteger = integer(left)
        val rightInteger = integer(right)
        if (leftInteger != null && rightInteger != null) return evaluateIntegerBinary(expression.operator, leftInteger, rightInteger)
        val leftFloat = floating(left)
        val rightFloat = floating(right)
        if (leftFloat != null || rightFloat != null) {
            val leftNumber = leftFloat ?: leftInteger?.toBigDecimal()
            val rightNumber = rightFloat ?: rightInteger?.toBigDecimal()
            if (leftNumber != null && rightNumber != null) return evaluateFloatingBinary(expression.operator, leftNumber, rightNumber)
        }
        return notConstant(expression, "binary operands are not constant")
    }

    private fun evaluateIntegerBinary(operator: BinaryOperator, left: BigInteger, right: BigInteger): ConstantValue = when (operator) {
        BinaryOperator.ADD -> integer(left + right)
        BinaryOperator.SUBTRACT -> integer(left - right)
        BinaryOperator.MULTIPLY -> integer(left * right)
        BinaryOperator.DIVIDE -> if (right == BigInteger.ZERO) ConstantValue.NotConstant else integer(left / right)
        BinaryOperator.REMAINDER -> if (right == BigInteger.ZERO) ConstantValue.NotConstant else integer(left % right)
        BinaryOperator.SHIFT_LEFT -> integer(left.shiftLeft(right.toInt()))
        BinaryOperator.SHIFT_RIGHT -> integer(left.shiftRight(right.toInt()))
        BinaryOperator.BITWISE_AND -> integer(left and right)
        BinaryOperator.BITWISE_XOR -> integer(left xor right)
        BinaryOperator.BITWISE_OR -> integer(left or right)
        BinaryOperator.LOGICAL_AND -> bool(left != BigInteger.ZERO && right != BigInteger.ZERO)
        BinaryOperator.LOGICAL_OR -> bool(left != BigInteger.ZERO || right != BigInteger.ZERO)
        BinaryOperator.LESS -> bool(left < right)
        BinaryOperator.LESS_EQUAL -> bool(left <= right)
        BinaryOperator.GREATER -> bool(left > right)
        BinaryOperator.GREATER_EQUAL -> bool(left >= right)
        BinaryOperator.EQUAL -> bool(left == right)
        BinaryOperator.NOT_EQUAL -> bool(left != right)
        BinaryOperator.COMMA -> integer(right)
    }

    private fun evaluateFloatingBinary(operator: BinaryOperator, left: BigDecimal, right: BigDecimal): ConstantValue = when (operator) {
        BinaryOperator.ADD -> floating(left + right)
        BinaryOperator.SUBTRACT -> floating(left - right)
        BinaryOperator.MULTIPLY -> floating(left * right)
        BinaryOperator.DIVIDE -> if (right.compareTo(BigDecimal.ZERO) == 0) ConstantValue.NotConstant else floating(left.divide(right, 18, java.math.RoundingMode.HALF_EVEN))
        BinaryOperator.LESS -> bool(left < right)
        BinaryOperator.LESS_EQUAL -> bool(left <= right)
        BinaryOperator.GREATER -> bool(left > right)
        BinaryOperator.GREATER_EQUAL -> bool(left >= right)
        BinaryOperator.EQUAL -> bool(left.compareTo(right) == 0)
        BinaryOperator.NOT_EQUAL -> bool(left.compareTo(right) != 0)
        else -> ConstantValue.NotConstant
    }

    private fun evaluateAddressBinary(expression: Expression.Binary, left: ConstantValue, right: ConstantValue): ConstantValue {
        val leftAddress = left as? ConstantValue.Address
        val rightAddress = right as? ConstantValue.Address
        val leftInteger = integer(left)
        val rightInteger = integer(right)
        val elementSize = pointerElementSize(expression.left)
        return when {
            leftAddress != null && rightInteger != null && expression.operator == BinaryOperator.ADD ->
                ConstantValue.Address(leftAddress.relocation.copy(addend = leftAddress.relocation.addend + rightInteger.toLong() * elementSize))
            leftAddress != null && rightInteger != null && expression.operator == BinaryOperator.SUBTRACT ->
                ConstantValue.Address(leftAddress.relocation.copy(addend = leftAddress.relocation.addend - rightInteger.toLong() * elementSize))
            rightAddress != null && leftInteger != null && expression.operator == BinaryOperator.ADD ->
                ConstantValue.Address(rightAddress.relocation.copy(addend = rightAddress.relocation.addend + leftInteger.toLong() * pointerElementSize(expression.right)))
            leftAddress != null && rightAddress != null && expression.operator == BinaryOperator.SUBTRACT && leftAddress.relocation.symbol == rightAddress.relocation.symbol ->
                ConstantValue.Integer(BigInteger.valueOf(leftAddress.relocation.addend - rightAddress.relocation.addend))
            leftAddress != null && rightAddress != null && expression.operator in setOf(BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL) ->
                bool((leftAddress.relocation == rightAddress.relocation) == (expression.operator == BinaryOperator.EQUAL))
            else -> notConstant(expression, "invalid relocation expression")
        }
    }

    private fun evaluateSizeOf(expression: Expression.SizeOf): ConstantValue {
        val type = when (val operand = expression.operand) {
            is SizeOperand.Type -> operand.value
            is SizeOperand.Expression -> semanticAnalyzer.analyze(operand.value).type
        }
        return layout.sizeOf(type)?.let { integer(BigInteger.valueOf(it)) }
            ?: notConstant(expression, "sizeof operand has no compile-time size")
    }

    private fun evaluateAlignOf(expression: Expression.AlignOf): ConstantValue {
        val type = when (val operand = expression.operand) {
            is SizeOperand.Type -> operand.value
            is SizeOperand.Expression -> semanticAnalyzer.analyze(operand.value).type
        }
        return layout.alignmentOf(type)?.let { integer(BigInteger.valueOf(it)) }
            ?: notConstant(expression, "alignof operand has no compile-time alignment")
    }

    private fun evaluateGeneric(expression: Expression.GenericSelection): ConstantValue {
        val controllingType = semanticAnalyzer.analyze(expression.controlling).type
        val selected = expression.associations.firstOrNull { it.type != null && CTypes.compatible(it.type, controllingType) }
            ?: expression.associations.firstOrNull { it.type == null }
        return selected?.let { evaluate(it.expression) }
            ?: notConstant(expression, "_Generic has no matching association")
    }

    private fun evaluateCall(expression: Expression.Call): ConstantValue {
        val name = (expression.callee as? Expression.Name)?.identifier ?: return ConstantValue.NotConstant
        return when (name) {
            "__builtin_constant_p" -> {
                if (expression.arguments.size != 1) notConstant(expression, "$name expects one argument")
                else bool(evaluate(expression.arguments.single()) !is ConstantValue.NotConstant)
            }
            "__builtin_choose_expr" -> {
                if (expression.arguments.size != 3) notConstant(expression, "$name expects three arguments")
                else when (val condition = evaluate(expression.arguments[0])) {
                    is ConstantValue.Integer -> evaluate(expression.arguments[if (condition.value.signum() != 0) 1 else 2])
                    else -> notConstant(expression, "$name requires an integer constant condition")
                }
            }
            "__builtin_types_compatible_p" -> {
                if (expression.arguments.size != 2) notConstant(expression, "$name expects two type names")
                else {
                    val left = expression.arguments[0] as? Expression.TypeOperand
                    val right = expression.arguments[1] as? Expression.TypeOperand
                    if (left == null || right == null) notConstant(expression, "$name requires two type names")
                    else bool(CTypes.compatible(left.type, right.type))
                }
            }
            "__builtin_offsetof" -> {
                val record = (expression.arguments.getOrNull(0) as? Expression.TypeOperand)?.type?.let(::canonical)
                val fieldName = (expression.arguments.getOrNull(1) as? Expression.Name)?.identifier
                val layoutField = (record as? CType.Record)?.let { recordType ->
                    layout.recordLayout(recordType)?.fields?.firstOrNull { it.name == fieldName }
                }
                layoutField?.let { integer(BigInteger.valueOf(it.offset)) }
                    ?: notConstant(expression, "$name requires a known record field")
            }
            else -> ConstantValue.NotConstant
        }
    }

    private fun initializerElementTypes(type: CType, count: Int): List<CType>? = when (val canonical = canonical(type)) {
        is CType.Array -> when (canonical.bound) {
            is ArrayBound.Constant -> List(canonical.bound.length.toInt()) { canonical.element }
            ArrayBound.Flexible, ArrayBound.Unspecified, is ArrayBound.Variable -> List(count) { canonical.element }
        }
        is CType.Record -> canonical.fields.map { it.type }
        else -> null
    }

    private fun cast(type: CType, value: ConstantValue, expression: Expression): ConstantValue {
        if (value is ConstantValue.NotConstant) return value
        if (canonical(type) is CType.Pointer && value is ConstantValue.Address) return value
        val primitive = canonical(type) as? CType.Primitive ?: return value
        return when (primitive.kind) {
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE, PrimitiveKind.LONG_DOUBLE ->
                floating(valueToDouble(value).toBigDecimal())
            else -> integer(valueToBigInteger(value))
        }
    }

    private fun addressOf(expression: Expression, outer: Expression): ConstantValue = when (expression) {
        is Expression.Name -> if (symbols.lookup(expression.identifier) != null) {
            ConstantValue.Address(Relocation(expression.identifier))
        } else notConstant(outer, "address refers to an unknown symbol")
        else -> notConstant(outer, "address is not relocatable")
    }

    private fun parseFloating(raw: String): BigDecimal? {
        val normalized = raw.removeSuffix("f").removeSuffix("F").removeSuffix("l").removeSuffix("L")
        return normalized.toBigDecimalOrNull() ?: runCatching {
            BigDecimal.valueOf(java.lang.Double.parseDouble(normalized))
        }.getOrNull()
    }

    private fun pointerElementSize(expression: Expression): Long {
        val type = semanticAnalyzer.analyze(expression).type
        val pointer = canonical(type) as? CType.Pointer ?: return 1
        return layout.sizeOf(pointer.pointee) ?: 1
    }

    private fun integer(value: ConstantValue): BigInteger? = (value as? ConstantValue.Integer)?.value

    private fun floating(value: ConstantValue): BigDecimal? = (value as? ConstantValue.Floating)?.value

    private fun integer(value: BigInteger): ConstantValue.Integer = ConstantValue.Integer(value)

    private fun floating(value: BigDecimal): ConstantValue.Floating = ConstantValue.Floating(value)

    private fun bool(value: Boolean): ConstantValue.Integer = integer(if (value) BigInteger.ONE else BigInteger.ZERO)

    private fun valueToBigInteger(value: ConstantValue): BigInteger = integer(value) ?: BigInteger.ZERO

    private fun valueToDouble(value: ConstantValue): Double = floating(value)?.toDouble() ?: valueToBigInteger(value).toDouble()

    private fun canonical(type: CType): CType = when (type) {
        is CType.Typedef -> canonical(type.target)
        is CType.Qualified -> canonical(type.base)
        else -> type
    }

    private fun notConstant(expression: Expression, message: String): ConstantValue {
        diagnostics.error(expression.span.start, message)
        return ConstantValue.NotConstant
    }
}
