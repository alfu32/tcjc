package org.tinycc.core.statements

import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.expressions.Expression
import org.tinycc.core.types.ObjectDeclaration

sealed interface Statement {
    val span: SourceSpan

    data class Empty(override val span: SourceSpan) : Statement
    data class ExpressionStatement(val expression: Expression, override val span: SourceSpan) : Statement
    data class DeclarationStatement(val declaration: ObjectDeclaration, override val span: SourceSpan) : Statement
    data class InlineAssembly(
        val template: String,
        val outputs: List<AsmOperand>,
        val inputs: List<AsmOperand>,
        val clobbers: List<String>,
        val isVolatile: Boolean,
        override val span: SourceSpan,
    ) : Statement
    data class Compound(val statements: List<Statement>, override val span: SourceSpan) : Statement
    data class If(
        val condition: Expression,
        val thenBranch: Statement,
        val elseBranch: Statement?,
        override val span: SourceSpan,
    ) : Statement
    data class While(val condition: Expression, val body: Statement, override val span: SourceSpan) : Statement
    data class DoWhile(val body: Statement, val condition: Expression, override val span: SourceSpan) : Statement
    data class For(
        val initializer: Statement?,
        val condition: Expression?,
        val update: Expression?,
        val body: Statement,
        override val span: SourceSpan,
    ) : Statement
    data class Switch(val condition: Expression, val body: Statement, override val span: SourceSpan) : Statement
    data class Case(val value: Expression, val statements: List<Statement>, override val span: SourceSpan) : Statement
    data class Default(val statements: List<Statement>, override val span: SourceSpan) : Statement
    data class Break(override val span: SourceSpan) : Statement
    data class Continue(override val span: SourceSpan) : Statement
    data class Return(val expression: Expression?, override val span: SourceSpan) : Statement
    data class Goto(val label: String, override val span: SourceSpan) : Statement
    data class Label(val label: String, val statement: Statement, override val span: SourceSpan) : Statement
    data class Invalid(override val span: SourceSpan) : Statement
}

data class AsmOperand(val constraint: String, val expression: Expression?)
