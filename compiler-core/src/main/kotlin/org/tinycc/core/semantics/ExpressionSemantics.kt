package org.tinycc.core.semantics

import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.constants.ConstantEvaluator
import org.tinycc.core.constants.ConstantValue
import org.tinycc.core.expressions.BinaryOperator
import org.tinycc.core.expressions.Expression
import org.tinycc.core.expressions.Initializer
import org.tinycc.core.expressions.SizeOperand
import org.tinycc.core.expressions.AssignmentOperator
import org.tinycc.core.statements.Statement
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.TargetDataModels
import org.tinycc.core.types.TypeLayout

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
    private val layout: TypeLayout = TypeLayout(TargetDataModels.X86_64_SYSV),
) {
    fun analyze(expression: Expression): TypedExpression = when (expression) {
        is Expression.Name -> analyzeName(expression)
        is Expression.Integer -> typed(expression, CTypes.int)
        is Expression.Floating -> typed(
            expression,
            if (expression.raw.uppercase() in setOf("NAN", "SNAN", "INF")) CTypes.float else CTypes.double,
        )
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
        is Expression.Cast -> {
            analyze(expression.operand)
            typed(expression, expression.type)
        }
        is Expression.SizeOf -> typed(expression, CTypes.unsignedLong)
        is Expression.AlignOf -> typed(expression, CTypes.unsignedLong)
        is Expression.TypeOf -> typed(expression, operandType(expression.operand))
        is Expression.GenericSelection -> analyzeGeneric(expression)
        is Expression.StatementExpression -> analyzeStatementExpression(expression)
        is Expression.TypeOperand -> typed(expression, expression.type)
        is Expression.LabelAddress -> typed(expression, CTypes.pointer(CTypes.void))
        is Expression.CompoundLiteral -> if (analyzeInitializer(expression.initializer, expression.type)) {
            typed(expression, expression.type, ValueCategory.LVALUE)
        } else {
            typed(expression, CType.Error, ValueCategory.INVALID)
        }
    }

    fun analyzeInitializer(initializer: Initializer, expected: CType): Boolean = when (initializer) {
        is Initializer.ExpressionValue -> {
            val actual = analyze(initializer.expression)
            requireCompatible(expected, decay(actual), initializer.expression.span.start, "initializer")
        }
        is Initializer.ListValue -> {
            when (val type = canonical(expected)) {
                is CType.Array -> {
                    val tooMany = type.bound is ArrayBound.Constant && initializer.values.size > type.bound.length
                    if (tooMany) diagnostics.error(SourceLocation(), "too many initializers for array")
                    initializer.values.take(type.bound.boundAsCount(initializer.values.size)).all {
                        analyzeInitializer(it, if (it is Initializer.Designated) expected else type.element)
                    } && !tooMany
                }
                is CType.Record -> {
                    if (initializer.values.size > type.fields.size) {
                        diagnostics.error(SourceLocation(), "too many initializers for record")
                        false
                    } else initializer.values.mapIndexed { index, value ->
                        analyzeInitializer(value, if (value is Initializer.Designated) expected else type.fields[index].type)
                    }.all { it }
                }
                else -> if (initializer.values.size == 1) {
                    analyzeInitializer(initializer.values.single(), expected)
                } else {
                    diagnostics.error(SourceLocation(), "initializer list requires an aggregate type")
                    false
                }
            }
        }
        is Initializer.Designated -> {
            val target = designatedType(expected, initializer.designator)
            if (target == null) {
                diagnostics.error(SourceLocation(), "designator does not match initializer type")
                false
            } else {
                val validIndex = when (val designator = initializer.designator) {
                    is org.tinycc.core.expressions.Designator.Field -> true
                    is org.tinycc.core.expressions.Designator.Index -> {
                        val index = analyze(designator.expression)
                        isInteger(canonical(decay(index)))
                    }
                }
                validIndex && analyzeInitializer(initializer.value, target)
            }
        }
    }

    private fun designatedType(type: CType, designator: org.tinycc.core.expressions.Designator): CType? = when (designator) {
        is org.tinycc.core.expressions.Designator.Field ->
            (canonical(type) as? CType.Record)?.fields?.firstOrNull { it.name == designator.name }?.type
        is org.tinycc.core.expressions.Designator.Index ->
            (canonical(type) as? CType.Array)?.element
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
                if (expression.operand is Expression.Member && isBitField(expression.operand)) {
                    error(expression, "address-of cannot be applied to a bit-field")
                }
                typed(expression, CTypes.pointer(operand.type))
            }
            org.tinycc.core.expressions.UnaryOperator.DEREFERENCE -> {
                val pointee = (canonical as? CType.Pointer)?.pointee
                if (pointee == null) {
                    error(expression, "cannot dereference non-pointer type")
                    typed(expression, CType.Error, ValueCategory.INVALID)
                } else typed(expression, pointee, ValueCategory.LVALUE)
            }
            org.tinycc.core.expressions.UnaryOperator.LOGICAL_NOT ->
                if (isScalar(canonical)) typed(expression, CTypes.int) else invalid(expression, "logical not requires a scalar operand")
            org.tinycc.core.expressions.UnaryOperator.BITWISE_NOT ->
                if (isInteger(canonical)) typed(expression, canonical) else invalid(expression, "bitwise not requires an integer operand")
            org.tinycc.core.expressions.UnaryOperator.PLUS,
            org.tinycc.core.expressions.UnaryOperator.MINUS,
            -> if (isArithmetic(canonical)) typed(expression, canonical) else invalid(expression, "unary operator requires an arithmetic operand")
            org.tinycc.core.expressions.UnaryOperator.PRE_INCREMENT,
            org.tinycc.core.expressions.UnaryOperator.PRE_DECREMENT,
            org.tinycc.core.expressions.UnaryOperator.POST_INCREMENT,
            org.tinycc.core.expressions.UnaryOperator.POST_DECREMENT,
            -> if (isModifiableLvalue(operand) && (isArithmetic(canonical) || canonical is CType.Pointer)) typed(expression, operand.type)
            else invalid(expression, "increment/decrement requires a modifiable arithmetic or pointer lvalue")
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
            -> if (isScalar(leftType) && isScalar(rightType)) typed(expression, CTypes.int) else invalid(expression, "logical operator requires scalar operands")
            BinaryOperator.LESS, BinaryOperator.LESS_EQUAL, BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL,
            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL,
            -> if (comparable(leftType, rightType)) typed(expression, CTypes.int) else invalid(expression, "incompatible comparison operands")
            BinaryOperator.ADD -> pointerArithmetic(expression, leftType, rightType, subtract = false)
            BinaryOperator.SUBTRACT -> pointerArithmetic(expression, leftType, rightType, subtract = true)
            BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE,
            -> if (isArithmetic(leftType) && isArithmetic(rightType)) typed(expression, commonArithmetic(leftType, rightType)) else invalid(expression, "binary operator requires arithmetic operands")
            BinaryOperator.REMAINDER,
            BinaryOperator.BITWISE_AND, BinaryOperator.BITWISE_XOR, BinaryOperator.BITWISE_OR,
            BinaryOperator.SHIFT_LEFT, BinaryOperator.SHIFT_RIGHT,
            -> if (isInteger(leftType) && isInteger(rightType)) typed(expression, commonArithmetic(leftType, rightType)) else invalid(expression, "integer operands are required")
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
        val condition = analyze(expression.condition)
        if (!isScalar(canonical(decay(condition)))) error(expression.condition, "conditional condition requires a scalar operand")
        val whenTrue = analyze(expression.whenTrue)
        val whenFalse = analyze(expression.whenFalse)
        val trueType = canonical(decay(whenTrue))
        val falseType = canonical(decay(whenFalse))
        return when {
            CTypes.compatible(trueType, falseType) -> typed(expression, trueType)
            isArithmetic(trueType) && isArithmetic(falseType) -> typed(expression, commonArithmetic(trueType, falseType))
            trueType is CType.Pointer && falseType is CType.Pointer && comparable(trueType, falseType) -> typed(expression, trueType)
            trueType is CType.Pointer && isNullPointerConstant(expression.whenFalse) -> typed(expression, trueType)
            falseType is CType.Pointer && isNullPointerConstant(expression.whenTrue) -> typed(expression, falseType)
            else -> invalid(expression, "conditional operands have incompatible types")
        }
    }

    private fun analyzeAssignment(expression: Expression.Assignment): TypedExpression {
        val target = analyze(expression.target)
        val value = analyze(expression.value)
        if (!isModifiableLvalue(target)) return invalid(expression, "assignment target is not an lvalue or is not modifiable")
        val actual = decay(value)
        val valid = if (expression.operator == AssignmentOperator.ASSIGN) {
            requireCompatible(target.type, actual, expression.value.span.start, "assignment")
        } else {
            val targetType = canonical(decay(target))
            val valueType = canonical(actual)
            val validOperator = when (expression.operator) {
                AssignmentOperator.ADD, AssignmentOperator.SUBTRACT ->
                    (isArithmetic(targetType) && isArithmetic(valueType)) || targetType is CType.Pointer && isInteger(valueType)
                AssignmentOperator.MULTIPLY, AssignmentOperator.DIVIDE -> isArithmetic(targetType) && isArithmetic(valueType)
                AssignmentOperator.REMAINDER, AssignmentOperator.AND, AssignmentOperator.OR,
                AssignmentOperator.XOR, AssignmentOperator.SHIFT_LEFT, AssignmentOperator.SHIFT_RIGHT ->
                    isInteger(targetType) && isInteger(valueType)
                AssignmentOperator.ASSIGN -> true
            }
            if (!validOperator) error(expression, "invalid operands for compound assignment")
            validOperator
        }
        if (!valid) return typed(expression, target.type)
        return typed(expression, target.type)
    }

    private fun analyzeCall(expression: Expression.Call): TypedExpression {
        val builtin = (expression.callee as? Expression.Name)?.identifier?.let {
            analyzeBuiltinCall(expression, it)
        }
        if (builtin != null) return builtin
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

    private fun analyzeBuiltinCall(expression: Expression.Call, name: String): TypedExpression? = when (name) {
        "__builtin_constant_p" -> {
            requireArgumentCount(expression, 1, name)
            expression.arguments.firstOrNull()?.let(::analyze)
            typed(expression, CTypes.int)
        }
        "__builtin_expect" -> {
            requireArgumentCount(expression, 2, name)
            val value = expression.arguments.firstOrNull()?.let(::analyze) ?: return invalid(expression, "$name requires a value")
            expression.arguments.getOrNull(1)?.let { expected ->
                if (!isScalar(canonical(decay(analyze(expected))))) error(expected, "$name expectation must be scalar")
            }
            typed(expression, value.type, value.category)
        }
        "__builtin_choose_expr" -> {
            requireArgumentCount(expression, 3, name)
            val condition = expression.arguments.firstOrNull()?.let { ConstantEvaluator(diagnostics, symbols).evaluate(it) }
            val selected = when (condition) {
                is ConstantValue.Integer -> if (condition.value.signum() != 0) expression.arguments.getOrNull(1) else expression.arguments.getOrNull(2)
                else -> null
            }
            if (selected == null) invalid(expression, "$name requires an integer constant condition")
            else {
                val result = analyze(selected)
                typed(expression, result.type, result.category)
            }
        }
        "__builtin_unreachable" -> {
            requireArgumentCount(expression, 0, name)
            typed(expression, CTypes.void)
        }
        "__builtin_types_compatible_p" -> {
            requireArgumentCount(expression, 2, name)
            val left = expression.arguments.getOrNull(0) as? Expression.TypeOperand
            val right = expression.arguments.getOrNull(1) as? Expression.TypeOperand
            if (left == null || right == null) invalid(expression, "$name requires two type names")
            else typed(expression, CTypes.int)
        }
        "__builtin_va_arg" -> {
            requireArgumentCount(expression, 2, name)
            expression.arguments.firstOrNull()?.let(::analyze)
            val result = expression.arguments.getOrNull(1) as? Expression.TypeOperand
            if (result == null) invalid(expression, "$name requires a type name") else typed(expression, result.type)
        }
        "__builtin_va_start", "__builtin_va_end", "__builtin_va_copy" -> typed(expression, CTypes.void)
        "__builtin_offsetof" -> {
            requireArgumentCount(expression, 2, name)
            val record = (expression.arguments.getOrNull(0) as? Expression.TypeOperand)?.type?.let(::canonical)
            val fieldName = (expression.arguments.getOrNull(1) as? Expression.Name)?.identifier
            val field = (record as? CType.Record)?.fields?.firstOrNull { it.name == fieldName }
            val fieldLayout = if (field != null) layout.recordLayout(record)?.fields?.firstOrNull { it.name == fieldName } else null
            if (field == null || fieldLayout == null) invalid(expression, "$name requires a known record field")
            else typed(expression, CTypes.unsignedLong)
        }
        "__builtin_frame_address", "__builtin_return_address" -> {
            requireArgumentCount(expression, 1, name)
            expression.arguments.firstOrNull()?.let { if (!isInteger(canonical(decay(analyze(it))))) error(it, "$name level must be an integer") }
            typed(expression, CTypes.pointer(CTypes.void))
        }
        "alloca", "__builtin_alloca" -> {
            requireArgumentCount(expression, 1, name)
            expression.arguments.firstOrNull()?.let { if (!isInteger(canonical(decay(analyze(it))))) error(it, "$name size must be an integer") }
            typed(expression, CTypes.pointer(CTypes.void))
        }
        else if (name.startsWith("__atomic_")) -> analyzeAtomicBuiltin(expression, name)
        else -> null
    }

    private fun analyzeAtomicBuiltin(expression: Expression.Call, name: String): TypedExpression {
        val signature = atomicBuiltinSignature(name) ?: return invalid(expression, "unknown atomic builtin '$name'")
        if (expression.arguments.size != signature.argumentCount) {
            return invalid(expression, "$name expects ${signature.argumentCount} argument(s)")
        }
        val arguments = expression.arguments.map(::analyze)
        val atomicPointer = canonical(decay(arguments[0])) as? CType.Pointer
            ?: return invalid(expression, "$name first argument must be a pointer")
        val valueType = canonical(atomicPointer.pointee)
        val valueSize = layout.sizeOf(valueType)
        if (valueSize == null || valueSize <= 0 || valueSize > 8 || valueSize.and(valueSize - 1) != 0L) {
            return invalid(expression, "$name atomic target must have a supported integer-sized representation")
        }

        when (signature.kind) {
            AtomicBuiltinKind.LOAD, AtomicBuiltinKind.EXCHANGE, AtomicBuiltinKind.STORE -> {
                if (signature.valueIndex != null) {
                    requireCompatible(valueType, decay(arguments[signature.valueIndex]), expression.arguments[signature.valueIndex].span.start, "$name value")
                }
                if (signature.orderIndex != null) validateAtomicOrder(expression, signature.orderIndex, arguments)
            }
            AtomicBuiltinKind.COMPARE_EXCHANGE -> {
                val expectedPointer = canonical(decay(arguments[1])) as? CType.Pointer
                if (expectedPointer == null || !CTypes.compatible(valueType, expectedPointer.pointee)) {
                    error(expression.arguments[1], "$name expected-value argument must point to the atomic value type")
                }
                requireCompatible(valueType, decay(arguments[2]), expression.arguments[2].span.start, "$name desired value")
                if (!isInteger(canonical(decay(arguments[3])))) error(expression.arguments[3], "$name weak flag must be an integer")
                validateAtomicOrder(expression, 4, arguments)
                validateAtomicOrder(expression, 5, arguments)
            }
            AtomicBuiltinKind.FETCH -> {
                if (!isInteger(valueType)) return invalid(expression, "$name requires an integer atomic target")
                if (!isInteger(canonical(decay(arguments[1])))) error(expression.arguments[1], "$name operand must be an integer")
                validateAtomicOrder(expression, 2, arguments)
            }
        }
        return typed(expression, if (signature.kind == AtomicBuiltinKind.STORE) CTypes.void else if (signature.kind == AtomicBuiltinKind.COMPARE_EXCHANGE) CTypes.int else valueType)
    }

    private fun validateAtomicOrder(expression: Expression.Call, index: Int, arguments: List<TypedExpression>) {
        if (!isInteger(canonical(decay(arguments[index])))) {
            error(expression.arguments[index], "atomic memory order must be an integer")
        }
    }

    private fun atomicBuiltinSignature(name: String): AtomicBuiltinSignature? = when {
        name == "__atomic_store" -> AtomicBuiltinSignature(AtomicBuiltinKind.STORE, 3, valueIndex = 1, orderIndex = 2)
        name == "__atomic_load" -> AtomicBuiltinSignature(AtomicBuiltinKind.LOAD, 2, orderIndex = 1)
        name == "__atomic_exchange" -> AtomicBuiltinSignature(AtomicBuiltinKind.EXCHANGE, 3, valueIndex = 1, orderIndex = 2)
        name == "__atomic_compare_exchange" -> AtomicBuiltinSignature(AtomicBuiltinKind.COMPARE_EXCHANGE, 6)
        name in ATOMIC_FETCH_BUILTINS -> AtomicBuiltinSignature(AtomicBuiltinKind.FETCH, 3)
        else -> null
    }

    private enum class AtomicBuiltinKind { STORE, LOAD, EXCHANGE, COMPARE_EXCHANGE, FETCH }

    private data class AtomicBuiltinSignature(
        val kind: AtomicBuiltinKind,
        val argumentCount: Int,
        val valueIndex: Int? = null,
        val orderIndex: Int? = null,
    )

    private companion object {
        val ATOMIC_FETCH_BUILTINS = setOf(
            "__atomic_fetch_add", "__atomic_fetch_sub", "__atomic_fetch_or", "__atomic_fetch_xor",
            "__atomic_fetch_and", "__atomic_fetch_nand", "__atomic_add_fetch", "__atomic_sub_fetch",
            "__atomic_or_fetch", "__atomic_xor_fetch", "__atomic_and_fetch", "__atomic_nand_fetch",
        )
    }

    private fun requireArgumentCount(expression: Expression.Call, expected: Int, name: String) {
        if (expression.arguments.size != expected) error(expression, "$name expects $expected argument(s)")
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

    private fun analyzeGeneric(expression: Expression.GenericSelection): TypedExpression {
        val controllingType = canonical(decay(analyze(expression.controlling)))
        val selected = expression.associations.firstOrNull { it.type != null && CTypes.compatible(it.type, controllingType) }
            ?: expression.associations.firstOrNull { it.type == null }
        if (selected == null) return invalid(expression, "_Generic has no matching association")
        val result = analyze(selected.expression)
        return typed(expression, result.type, result.category)
    }

    private fun analyzeStatementExpression(expression: Expression.StatementExpression): TypedExpression {
        val last = when (val body = expression.body) {
            is Statement.ExpressionStatement -> body.expression
            is Statement.Compound -> (body.statements.lastOrNull() as? Statement.ExpressionStatement)?.expression
            else -> null
        }
        return if (last == null) typed(expression, CTypes.void) else {
            val result = analyze(last)
            typed(expression, result.type, result.category)
        }
    }

    private fun operandType(operand: SizeOperand): CType = when (operand) {
        is SizeOperand.Type -> operand.value
        is SizeOperand.Expression -> analyze(operand.value).type
    }

    private fun decay(value: TypedExpression): CType = when (val type = canonical(value.type)) {
        is CType.Array -> CTypes.pointer(type.element)
        is CType.Function -> CTypes.pointer(type)
        else -> type
    }

    private fun commonArithmetic(left: CType, right: CType): CType {
        if (left is CType.Primitive && right is CType.Primitive) {
            if (left.kind == PrimitiveKind.LONG_DOUBLE_COMPLEX || right.kind == PrimitiveKind.LONG_DOUBLE_COMPLEX) return CTypes.longDoubleComplex
            if (left.kind == PrimitiveKind.DOUBLE_COMPLEX || right.kind == PrimitiveKind.DOUBLE_COMPLEX) return CTypes.doubleComplex
            if (left.kind == PrimitiveKind.FLOAT_COMPLEX || right.kind == PrimitiveKind.FLOAT_COMPLEX) return CTypes.floatComplex
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
            left is CType.Pointer && right is CType.Pointer && pointerCompatible(left, right)

    private fun isArithmetic(type: CType): Boolean = when (val value = canonical(type)) {
        is CType.Primitive -> value.kind != PrimitiveKind.VOID
        is CType.Enumeration -> true
        else -> false
    }

    private fun isInteger(type: CType): Boolean = when (val value = canonical(type)) {
        is CType.Primitive -> value.kind in setOf(
            PrimitiveKind.BOOL, PrimitiveKind.CHAR, PrimitiveKind.SIGNED_CHAR, PrimitiveKind.UNSIGNED_CHAR,
            PrimitiveKind.SHORT, PrimitiveKind.UNSIGNED_SHORT, PrimitiveKind.INT, PrimitiveKind.UNSIGNED_INT,
            PrimitiveKind.LONG, PrimitiveKind.UNSIGNED_LONG, PrimitiveKind.LONG_LONG, PrimitiveKind.UNSIGNED_LONG_LONG,
        )
        is CType.Enumeration -> true
        else -> false
    }

    private fun isScalar(type: CType): Boolean = isArithmetic(type) || canonical(type) is CType.Pointer

    private fun isModifiableLvalue(value: TypedExpression): Boolean =
        value.category == ValueCategory.LVALUE && canonical(value.type) !is CType.Array && !isConstQualified(value.type)

    private fun isConstQualified(type: CType): Boolean = when (type) {
        is CType.Qualified -> type.qualifiers.isConst || isConstQualified(type.base)
        is CType.Typedef -> isConstQualified(type.target)
        else -> false
    }

    private fun pointerCompatible(left: CType.Pointer, right: CType.Pointer): Boolean {
        if (CTypes.compatible(left.pointee, right.pointee)) return true
        val leftPointee = canonical(left.pointee)
        val rightPointee = canonical(right.pointee)
        return leftPointee is CType.Primitive && leftPointee.kind == PrimitiveKind.VOID && rightPointee !is CType.Function ||
            rightPointee is CType.Primitive && rightPointee.kind == PrimitiveKind.VOID && leftPointee !is CType.Function
    }

    private fun isNullPointerConstant(expression: Expression): Boolean =
        expression is Expression.Integer && expression.value.signum() == 0

    private fun ArrayBound.boundAsCount(initializerCount: Int): Int = when (this) {
        is ArrayBound.Constant -> length.toInt().coerceAtMost(initializerCount)
        ArrayBound.Flexible, ArrayBound.Unspecified, is ArrayBound.Variable -> initializerCount
    }

    private fun isBitField(expression: Expression.Member): Boolean {
        val receiver = canonical(analyze(expression.receiver).type)
        val record = if (expression.throughPointer) (receiver as? CType.Pointer)?.pointee else receiver
        return (canonical(record ?: CType.Error) as? CType.Record)?.fields?.firstOrNull { it.name == expression.name }?.bitWidth != null
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
        if (assignable(expected, actual)) return true
        diagnostics.error(location, "incompatible $context: expected $expected, got $actual")
        return false
    }

    private fun assignable(expected: CType, actual: CType): Boolean {
        if (CTypes.compatible(expected, actual)) return true
        val expectedType = canonical(expected)
        val actualType = canonical(actual)
        if (isArithmetic(expectedType) && isArithmetic(actualType)) return true
        if (expectedType is CType.Pointer && actualType is CType.Pointer) return pointerCompatible(expectedType, actualType)
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
