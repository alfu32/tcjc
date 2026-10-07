package org.tinycc.core.functions

import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.expressions.ExpressionParser
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.semantics.ExpressionSemanticAnalyzer
import org.tinycc.core.symbols.ScopeKind
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.ObjectDeclaration
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.statements.Statement

class FunctionSemanticValidator(
    private val diagnostics: DiagnosticEngine,
    private val symbols: SymbolTable = SymbolTable(diagnostics),
) {
    fun validate(function: ParsedFunction): Boolean {
        if (function.body == null) return true
        var valid = true
        symbols.withScope(ScopeKind.FUNCTION) {
            function.parameters.forEach { parameter ->
                if (parameter.name != null && parameter.type !== CType.Error) symbols.declare(ObjectDeclaration(parameter.name, parameter.type))
            }
            val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)
            walk(function.body, function.declaration.type.returnType, analyzer) { valid = false }
        }
        return valid && !diagnostics.hasErrors
    }

    private fun walk(
        statement: Statement,
        returnType: CType,
        analyzer: ExpressionSemanticAnalyzer,
        invalid: () -> Unit,
    ) {
        when (statement) {
            is Statement.Return -> {
                val isVoid = CTypes.unalias(returnType) == CType.Primitive(PrimitiveKind.VOID)
                if (isVoid && statement.expression != null) {
                    diagnostics.error(statement.span.start, "void function must not return a value")
                    invalid()
                } else if (!isVoid && statement.expression == null) {
                    diagnostics.error(statement.span.start, "non-void function must return a value")
                    invalid()
                } else statement.expression?.let { analyzer.analyze(it) }
            }
            is Statement.Compound -> symbols.withScope(ScopeKind.BLOCK) {
                statement.statements.forEach { child ->
                    if (child is Statement.DeclarationStatement) {
                        symbols.declare(child.declaration, location = child.span.start)
                    }
                    walk(child, returnType, analyzer, invalid)
                }
            }
            is Statement.If -> {
                analyzer.analyze(statement.condition)
                walk(statement.thenBranch, returnType, analyzer, invalid)
                statement.elseBranch?.let { walk(it, returnType, analyzer, invalid) }
            }
            is Statement.While -> {
                analyzer.analyze(statement.condition)
                walk(statement.body, returnType, analyzer, invalid)
            }
            is Statement.DoWhile -> {
                walk(statement.body, returnType, analyzer, invalid)
                analyzer.analyze(statement.condition)
            }
            is Statement.For -> symbols.withScope(ScopeKind.BLOCK) {
                statement.initializer?.let { initializer ->
                    if (initializer is Statement.DeclarationStatement) {
                        symbols.declare(initializer.declaration, location = initializer.span.start)
                    }
                    walk(initializer, returnType, analyzer, invalid)
                }
                statement.condition?.let { analyzer.analyze(it) }
                statement.update?.let { analyzer.analyze(it) }
                walk(statement.body, returnType, analyzer, invalid)
            }
            is Statement.Switch -> {
                analyzer.analyze(statement.condition)
                walk(statement.body, returnType, analyzer, invalid)
            }
            is Statement.Case -> statement.statements.forEach { walk(it, returnType, analyzer, invalid) }
            is Statement.Default -> statement.statements.forEach { walk(it, returnType, analyzer, invalid) }
            is Statement.Label -> walk(statement.statement, returnType, analyzer, invalid)
            is Statement.ExpressionStatement -> analyzer.analyze(statement.expression)
            else -> Unit
        }
    }
}
