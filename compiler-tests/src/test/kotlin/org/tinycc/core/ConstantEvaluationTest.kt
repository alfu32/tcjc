package org.tinycc.core

import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.constants.ConstantEvaluator
import org.tinycc.core.constants.ConstantValue
import org.tinycc.core.constants.RelocationKind
import org.tinycc.core.constants.SpecialFloatingKind
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

    @Test
    fun foldsShortCircuitMixedNumericAndAggregateInitializers() {
        val diagnostics = DiagnosticEngine()
        val evaluator = ConstantEvaluator(diagnostics)
        val shortCircuit = evaluator.evaluate(ExpressionParser(Lexer("0 && (1 / 0)").tokenize()).parse())
        val mixed = evaluator.evaluate(ExpressionParser(Lexer("0x1p2 + 1").tokenize()).parse())
        val initializer = ExpressionParser(Lexer("{ 1, 2, 3 }").tokenize()).parseInitializer()
        val aggregate = evaluator.evaluateInitializer(initializer, CTypes.arrayOf(CTypes.int, 4))

        assertEquals(BigInteger.ZERO, assertIs<ConstantValue.Integer>(shortCircuit).value)
        assertEquals("5", assertIs<ConstantValue.Floating>(mixed).value.stripTrailingZeros().toPlainString())
        val values = assertIs<ConstantValue.Aggregate>(aggregate).values
        assertEquals(4, values.size)
        assertEquals(BigInteger.ONE, assertIs<ConstantValue.Integer>(values[0]).value)
        assertTrue(values[3] is ConstantValue.Zero)
        assertTrue(!diagnostics.hasErrors)
    }

    @Test
    fun preservesTinyCcSpecialFloatingConstants() {
        val evaluator = ConstantEvaluator()
        val nan = evaluator.evaluate(ExpressionParser(Lexer("__nan__").tokenize()).parse())
        val signalingNan = evaluator.evaluate(ExpressionParser(Lexer("__snan__").tokenize()).parse())
        val negativeInfinity = evaluator.evaluate(ExpressionParser(Lexer("-__inf__").tokenize()).parse())
        val comparison = evaluator.evaluate(ExpressionParser(Lexer("__nan__ != __inf__").tokenize()).parse())
        val conditional = evaluator.evaluate(ExpressionParser(Lexer("__inf__ ? 3 : 4").tokenize()).parse())

        assertEquals(SpecialFloatingKind.NAN, assertIs<ConstantValue.SpecialFloating>(nan).kind)
        assertEquals(SpecialFloatingKind.SNAN, assertIs<ConstantValue.SpecialFloating>(signalingNan).kind)
        assertTrue(assertIs<ConstantValue.SpecialFloating>(negativeInfinity).negative)
        assertEquals(BigInteger.ONE, assertIs<ConstantValue.Integer>(comparison).value)
        assertEquals(BigInteger.valueOf(3), assertIs<ConstantValue.Integer>(conditional).value)
    }
}
