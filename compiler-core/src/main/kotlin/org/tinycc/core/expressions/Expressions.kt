package org.tinycc.core.expressions

import java.math.BigInteger
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.statements.Statement
import org.tinycc.core.types.CType

sealed interface Expression {
    val span: SourceSpan

    data class Name(val identifier: String, override val span: SourceSpan) : Expression
    data class Integer(val value: BigInteger, override val span: SourceSpan) : Expression
    data class Floating(val raw: String, override val span: SourceSpan) : Expression
    data class Character(val value: Int, override val span: SourceSpan) : Expression
    data class StringLiteral(val value: String, val wide: Boolean, override val span: SourceSpan) : Expression

    data class Unary(val operator: UnaryOperator, val operand: Expression, override val span: SourceSpan) : Expression
    data class Binary(val operator: BinaryOperator, val left: Expression, val right: Expression, override val span: SourceSpan) : Expression
    data class Conditional(
        val condition: Expression,
        val whenTrue: Expression,
        val whenFalse: Expression,
        override val span: SourceSpan,
    ) : Expression

    data class Assignment(
        val operator: AssignmentOperator,
        val target: Expression,
        val value: Expression,
        override val span: SourceSpan,
    ) : Expression

    data class Call(val callee: Expression, val arguments: List<Expression>, override val span: SourceSpan) : Expression
    data class Index(val array: Expression, val index: Expression, override val span: SourceSpan) : Expression
    data class Member(val receiver: Expression, val name: String, val throughPointer: Boolean, override val span: SourceSpan) : Expression
    data class Cast(val type: CType, val operand: Expression, override val span: SourceSpan) : Expression
    data class SizeOf(val operand: SizeOperand, override val span: SourceSpan) : Expression
    data class AlignOf(val operand: SizeOperand, override val span: SourceSpan) : Expression
    data class TypeOf(val operand: SizeOperand, override val span: SourceSpan) : Expression
    data class GenericSelection(
        val controlling: Expression,
        val associations: List<GenericAssociation>,
        override val span: SourceSpan,
    ) : Expression
    data class StatementExpression(val body: Statement, override val span: SourceSpan) : Expression
    data class CompoundLiteral(val type: CType, val initializer: Initializer, override val span: SourceSpan) : Expression
    data class Invalid(override val span: SourceSpan) : Expression
}

data class GenericAssociation(val type: CType?, val expression: Expression)

sealed interface SizeOperand {
    data class Type(val value: CType) : SizeOperand
    data class Expression(val value: org.tinycc.core.expressions.Expression) : SizeOperand
}

sealed interface Initializer {
    data class ExpressionValue(val expression: Expression) : Initializer
    data class ListValue(val values: List<Initializer>) : Initializer
}

enum class UnaryOperator {
    PLUS,
    MINUS,
    LOGICAL_NOT,
    BITWISE_NOT,
    ADDRESS,
    DEREFERENCE,
    PRE_INCREMENT,
    PRE_DECREMENT,
    POST_INCREMENT,
    POST_DECREMENT,
}

enum class BinaryOperator {
    MULTIPLY, DIVIDE, REMAINDER,
    ADD, SUBTRACT,
    SHIFT_LEFT, SHIFT_RIGHT,
    LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    EQUAL, NOT_EQUAL,
    BITWISE_AND, BITWISE_XOR, BITWISE_OR,
    LOGICAL_AND, LOGICAL_OR,
    COMMA,
}

enum class AssignmentOperator { ASSIGN, ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, AND, OR, XOR, SHIFT_LEFT, SHIFT_RIGHT }
