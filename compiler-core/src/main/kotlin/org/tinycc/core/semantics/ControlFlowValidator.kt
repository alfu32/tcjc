package org.tinycc.core.semantics

import java.util.ArrayDeque
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.statements.Statement

/** Checks function-local control-flow rules after parsing has recovered a statement tree. */
class ControlFlowValidator(private val diagnostics: DiagnosticEngine) {
    private val labels = LinkedHashMap<String, SourceSpan>()
    private val gotos = ArrayList<Statement.Goto>()
    private val switches = ArrayDeque<SwitchFrame>()
    private var loopDepth = 0

    fun validate(statement: Statement): Boolean {
        labels.clear()
        gotos.clear()
        switches.clear()
        loopDepth = 0
        walk(statement, reachable = true)
        gotos.forEach { jump ->
            if (jump.label !in labels) {
                diagnostics.error(jump.span.start, "use of undeclared label '${jump.label}'")
            }
        }
        return !diagnostics.hasErrors
    }

    private fun walk(statement: Statement, reachable: Boolean): Boolean = when (statement) {
        is Statement.Compound -> {
            var canContinue = reachable
            statement.statements.forEach { child ->
                if (!canContinue && child !is Statement.Label) {
                    diagnostics.warning(child.span.start, "unreachable statement")
                }
                val childReachability = if (child is Statement.Label) true else canContinue
                canContinue = walk(child, childReachability)
            }
            canContinue
        }
        is Statement.Empty,
        is Statement.ExpressionStatement,
        is Statement.DeclarationStatement,
        is Statement.Invalid,
        -> reachable
        is Statement.If -> {
            val thenCanContinue = walk(statement.thenBranch, reachable)
            val elseCanContinue = statement.elseBranch?.let { walk(it, reachable) }
            statement.elseBranch == null || thenCanContinue || elseCanContinue == true
        }
        is Statement.While -> {
            loopDepth++
            walk(statement.body, reachable)
            loopDepth--
            true
        }
        is Statement.DoWhile -> {
            loopDepth++
            val bodyCanContinue = walk(statement.body, reachable)
            loopDepth--
            bodyCanContinue || reachable
        }
        is Statement.For -> {
            statement.initializer?.let { walk(it, reachable) }
            loopDepth++
            walk(statement.body, reachable)
            loopDepth--
            true
        }
        is Statement.Switch -> {
            switches.addLast(SwitchFrame())
            walk(statement.body, reachable)
            switches.removeLast()
            true
        }
        is Statement.Case -> {
            if (switches.isEmpty()) diagnostics.error(statement.span.start, "case label not within a switch statement")
            walkSequence(statement.statements, reachable)
        }
        is Statement.Default -> {
            val frame = switches.peekLast()
            if (frame == null) {
                diagnostics.error(statement.span.start, "default label not within a switch statement")
            } else if (frame.hasDefault) {
                diagnostics.error(statement.span.start, "multiple default labels in one switch statement")
            } else {
                frame.hasDefault = true
            }
            walkSequence(statement.statements, reachable)
        }
        is Statement.Break -> {
            if (loopDepth == 0 && switches.isEmpty()) {
                diagnostics.error(statement.span.start, "break statement not within a loop or switch")
            }
            false
        }
        is Statement.Continue -> {
            if (loopDepth == 0) diagnostics.error(statement.span.start, "continue statement not within a loop")
            false
        }
        is Statement.Return -> false
        is Statement.Goto -> {
            gotos += statement
            false
        }
        is Statement.Label -> {
            if (labels.putIfAbsent(statement.label, statement.span) != null) {
                diagnostics.error(statement.span.start, "duplicate label '${statement.label}'")
            }
            walk(statement.statement, reachable)
        }
    }

    private fun walkSequence(statements: List<Statement>, reachable: Boolean): Boolean {
        var canContinue = reachable
        statements.forEach { child ->
            if (!canContinue && child !is Statement.Label) {
                diagnostics.warning(child.span.start, "unreachable statement")
            }
            canContinue = walk(child, if (child is Statement.Label) true else canContinue)
        }
        return canContinue
    }

    private class SwitchFrame(var hasDefault: Boolean = false)
}
