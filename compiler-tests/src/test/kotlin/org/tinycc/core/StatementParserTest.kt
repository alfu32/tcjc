package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.statements.Statement
import org.tinycc.core.statements.StatementParser

class StatementParserTest {
    @Test
    fun parsesBlocksLoopsSelectionsJumpsLabelsAndSwitches() {
        val source = """
            {
                int i = 0;
                while (i < 3) { if (i == 1) continue; i = i + 1; }
                switch (i) { case 3: goto done; default: break; }
                done: return i;
            }
        """.trimIndent()
        val diagnostics = DiagnosticEngine()
        val statement = StatementParser(Lexer(source, diagnostics = diagnostics).tokenize(), diagnostics).parse()
        val block = assertIs<Statement.Compound>(statement)

        assertIs<Statement.DeclarationStatement>(block.statements[0])
        assertIs<Statement.While>(block.statements[1])
        assertIs<Statement.Switch>(block.statements[2])
        assertIs<Statement.Label>(block.statements[3])
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun parsesForAndReportsMalformedStatements() {
        val diagnostics = DiagnosticEngine()
        val tokens = Lexer("for (int i = 0; i < 2; i = i + 1) ; return", diagnostics = diagnostics).tokenize()
        val statement = StatementParser(tokens, diagnostics).parse()

        assertTrue(statement is Statement.Compound || statement is Statement.For)
        assertTrue(diagnostics.hasErrors)
    }

    @Test
    fun parsesInlineAssemblyTemplatesConstraintsAndClobbers() {
        val diagnostics = DiagnosticEngine()
        val source = "{ int value; asm volatile(\"add %1, %0\" : \"+r\"(value) : \"r\"(1) : \"cc\", \"memory\"); }"
        val statement = StatementParser(Lexer(source, diagnostics = diagnostics).tokenize(), diagnostics).parse()
        val block = assertIs<Statement.Compound>(statement)
        val asm = assertIs<Statement.InlineAssembly>(block.statements[1])

        assertEquals("add %1, %0", asm.template)
        assertEquals(listOf("+r"), asm.outputs.map { it.constraint })
        assertEquals(listOf("r"), asm.inputs.map { it.constraint })
        assertEquals(listOf("cc", "memory"), asm.clobbers)
        assertTrue(asm.isVolatile)
        assertEquals(0, diagnostics.errorCount)
    }
}
