package org.tinycc.core

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.constants.ConstantEvaluator
import org.tinycc.core.constants.ConstantValue
import org.tinycc.core.constants.RelocationKind
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.expressions.ExpressionParser
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.ObjectDeclaration

class ConstantEvaluationTest {
    @Test
    fun foldsIntegerFloatConditionalAndSizeofExpressions() {
        val evaluator = ConstantEvaluator()
        val integer = evaluator.evaluate(ExpressionParser(Lexer("2 + 3 * 4").tokenize()).parse())
        val floating = evaluator.evaluate(ExpressionParser(Lexer("1.5 + 2.5").tokenize()).parse())
        val conditional = evaluator.evaluate(ExpressionParser(Lexer("1 ? 8 : 9").tokenize()).parse())
        val size = evaluator.evaluate(ExpressionParser(Lexer("sizeof(int)").tokenize()).parse())

        assertEquals(BigInteger.valueOf(14), assertIs<ConstantValue.Integer>(integer).value)
        assertEquals("4.0", assertIs<ConstantValue.Floating>(floating).value.toPlainString())
        assertEquals(BigInteger.valueOf(8), assertIs<ConstantValue.Integer>(conditional).value)
        assertEquals(BigInteger.valueOf(4), assertIs<ConstantValue.Integer>(size).value)
    }

    @Test
    fun createsScaledRelocationsAndReportsNonConstantOperations() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("global", CTypes.int))
        val evaluator = ConstantEvaluator(diagnostics, symbols)
        val address = evaluator.evaluate(ExpressionParser(Lexer("&global + 2").tokenize()).parse())
        val invalid = evaluator.evaluate(ExpressionParser(Lexer("global + 1").tokenize()).parse())

        val relocation = assertIs<ConstantValue.Address>(address).relocation
        assertEquals("global", relocation.symbol)
        assertEquals(8, relocation.addend)
        assertEquals(RelocationKind.ABSOLUTE, relocation.kind)
        assertTrue(invalid is ConstantValue.NotConstant)
        assertTrue(diagnostics.hasErrors)
    }
}
