package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.constants.ConstantEvaluator
import org.tinycc.core.constants.ConstantValue
import org.tinycc.core.expressions.Expression
import org.tinycc.core.expressions.ExpressionParser
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.semantics.ExpressionSemanticAnalyzer
import org.tinycc.core.semantics.ValueCategory
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.FunctionDeclaration
import org.tinycc.core.types.ObjectDeclaration
import org.tinycc.core.types.Field
import org.tinycc.core.types.RecordKind
import org.tinycc.core.types.TypeQualifiers

class ExpressionSemanticsTest {
    @Test
    fun appliesArithmeticPointerDecayLvaluesAndCalls() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        symbols.declare(ObjectDeclaration("pointer", CTypes.pointer(CTypes.int)))
        symbols.declare(FunctionDeclaration("run", CTypes.function(CTypes.int, listOf(CTypes.int)) as CType.Function))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val arithmetic = analyzer.analyze(ExpressionParser(Lexer("value + 2").tokenize()).parse())
        val dereference = analyzer.analyze(ExpressionParser(Lexer("*pointer").tokenize()).parse())
        val call = analyzer.analyze(ExpressionParser(Lexer("run(value)").tokenize()).parse())

        assertEquals(CTypes.int, arithmetic.type)
        assertEquals(ValueCategory.LVALUE, dereference.category)
        assertEquals(CTypes.int, call.type)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun diagnosesInvalidAssignmentsAndUnknownMembers() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val assignment = analyzer.analyze(ExpressionParser(Lexer("3 = value").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("value.missing").tokenize()).parse())

        assertTrue(assignment.type is CType.Error)
        assertTrue(diagnostics.render().contains("assignment target is not an lvalue"))
        assertTrue(diagnostics.render().contains("unknown member"))
    }

    @Test
    fun appliesCConversionsPointerRulesCompoundLvaluesAndGenericSelection() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        symbols.declare(ObjectDeclaration("pointer", CTypes.pointer(CTypes.int)))
        symbols.declare(ObjectDeclaration("readonly", CTypes.qualified(CTypes.int, TypeQualifiers(isConst = true))))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val postfix = analyzer.analyze(ExpressionParser(Lexer("value++").tokenize()).parse())
        val pointer = analyzer.analyze(ExpressionParser(Lexer("pointer + 2").tokenize()).parse())
        val compound = analyzer.analyze(ExpressionParser(Lexer("(int){1}").tokenize()).parse())
        val generic = analyzer.analyze(ExpressionParser(Lexer("_Generic(value, int: 1, default: 2)").tokenize()).parse())
        val statementExpression = analyzer.analyze(ExpressionParser(Lexer("({ value + 1; })").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("readonly = 2").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("1.0 % 2").tokenize()).parse())

        assertEquals(CTypes.int, postfix.type)
        assertEquals(CTypes.pointer(CTypes.int), pointer.type)
        assertEquals(ValueCategory.LVALUE, compound.category)
        assertEquals(CTypes.int, generic.type)
        assertEquals(CTypes.int, statementExpression.type)
        assertTrue(diagnostics.render().contains("not an lvalue"))
        assertTrue(diagnostics.render().contains("integer operands are required"))
    }

    @Test
    fun recognizesCoreGnuBuiltinExpressionExtensions() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)

        val constant = analyzer.analyze(ExpressionParser(Lexer("__builtin_constant_p(value)").tokenize()).parse())
        val expected = analyzer.analyze(ExpressionParser(Lexer("__builtin_expect(value, 1)").tokenize()).parse())
        val chosen = analyzer.analyze(ExpressionParser(Lexer("__builtin_choose_expr(1, value, 0)").tokenize()).parse())
        val address = analyzer.analyze(ExpressionParser(Lexer("__builtin_return_address(0)").tokenize()).parse())
        val unreachable = analyzer.analyze(ExpressionParser(Lexer("__builtin_unreachable()").tokenize()).parse())
        val typeCompatible = analyzer.analyze(ExpressionParser(Lexer("__builtin_types_compatible_p(int, int)").tokenize()).parse())
        val vaArg = analyzer.analyze(ExpressionParser(Lexer("__builtin_va_arg(value, long)").tokenize()).parse())
        val atomic = analyzer.analyze(ExpressionParser(Lexer("__atomic_fetch_add(&value, 1, 0)").tokenize()).parse())
        val labelAddress = analyzer.analyze(ExpressionParser(Lexer("&&done").tokenize()).parse())

        assertEquals(CTypes.int, constant.type)
        assertEquals(CTypes.int, expected.type)
        assertEquals(CTypes.int, chosen.type)
        assertEquals(CTypes.pointer(CTypes.void), address.type)
        assertEquals(CTypes.void, unreachable.type)
        assertEquals(CTypes.int, typeCompatible.type)
        assertEquals(CTypes.long, vaArg.type)
        assertEquals(CTypes.int, atomic.type)
        assertEquals(CTypes.pointer(CTypes.void), labelAddress.type)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun validatesDesignatedInitializersAndConstantPlacement() {
        val diagnostics = DiagnosticEngine()
        val evaluator = org.tinycc.core.constants.ConstantEvaluator(diagnostics)
        val initializer = ExpressionParser(Lexer("{ [2] = 4, 1 }").tokenize()).parseInitializer()
        val array = CTypes.arrayOf(CTypes.int, 4)
        val result = evaluator.evaluateInitializer(initializer, array)

        val values = kotlin.test.assertIs<org.tinycc.core.constants.ConstantValue.Aggregate>(result).values
        assertEquals(4, values.size)
        assertTrue(values[0] is org.tinycc.core.constants.ConstantValue.Zero)
        assertEquals(java.math.BigInteger.valueOf(4), kotlin.test.assertIs<org.tinycc.core.constants.ConstantValue.Integer>(values[2]).value)
        assertEquals(java.math.BigInteger.ONE, kotlin.test.assertIs<org.tinycc.core.constants.ConstantValue.Integer>(values[3]).value)

        val analyzer = ExpressionSemanticAnalyzer(diagnostics, SymbolTable(diagnostics))
        val compound = analyzer.analyze(ExpressionParser(Lexer("(int[3]){1, [2] = 3}").tokenize()).parse())
        assertEquals(CTypes.arrayOf(CTypes.int, 3), compound.type)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun resolvesBuiltinOffsetofAgainstRecordLayout() {
        val diagnostics = DiagnosticEngine()
        val span = SourceSpan(SourceLocation(), SourceLocation())
        val record = CType.Record(RecordKind.STRUCT, "Pair")
        record.completeWith(listOf(Field("tag", CTypes.char), Field("value", CTypes.int)))
        val call = Expression.Call(
            Expression.Name("__builtin_offsetof", span),
            listOf(Expression.TypeOperand(record, span), Expression.Name("value", span)),
            span,
        )
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, SymbolTable(diagnostics))
        val result = analyzer.analyze(call)
        val folded = ConstantEvaluator(diagnostics).evaluate(call)

        assertEquals(CTypes.unsignedLong, result.type)
        assertEquals(java.math.BigInteger.valueOf(4), kotlin.test.assertIs<ConstantValue.Integer>(folded).value)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun appliesComplexArithmeticPromotion() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("z", CTypes.doubleComplex))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)
        val result = analyzer.analyze(ExpressionParser(Lexer("z + 1.0").tokenize()).parse())

        assertEquals(CTypes.doubleComplex, result.type)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun callsThroughFunctionPointerTypeNames() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(
            ObjectDeclaration(
                "callback",
                CTypes.pointer(CTypes.function(CTypes.long, listOf(CTypes.int))),
            ),
        )
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)
        val result = analyzer.analyze(ExpressionParser(Lexer("callback(1)").tokenize()).parse())

        assertEquals(CTypes.long, result.type)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun assignsTinyCcSpecialFloatingConstantsFloatType() {
        val analyzer = ExpressionSemanticAnalyzer()
        for (literal in listOf("__nan__", "__snan__", "__inf__")) {
            val expression = ExpressionParser(Lexer(literal).tokenize()).parse()
            assertEquals(CTypes.float, analyzer.analyze(expression).type)
        }
    }

    @Test
    fun validatesAtomicBuiltinSignaturesAndPointerTargets() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        symbols.declare(ObjectDeclaration("value", CTypes.int))
        symbols.declare(ObjectDeclaration("expected", CTypes.int))
        symbols.declare(ObjectDeclaration("other", CTypes.long))
        val analyzer = ExpressionSemanticAnalyzer(diagnostics, symbols)
        val validLoad = analyzer.analyze(ExpressionParser(Lexer("__atomic_load(&value, 0)").tokenize()).parse())
        val validStore = analyzer.analyze(ExpressionParser(Lexer("__atomic_store(&value, 3, 0)").tokenize()).parse())
        val validCompare = analyzer.analyze(
            ExpressionParser(Lexer("__atomic_compare_exchange(&value, &expected, 3, 0, 5, 5)").tokenize()).parse(),
        )
        analyzer.analyze(ExpressionParser(Lexer("__atomic_load(&value)").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("__atomic_fetch_add(&value, 1.5, 0)").tokenize()).parse())
        analyzer.analyze(ExpressionParser(Lexer("__atomic_compare_exchange(&value, &other, 3, 0, 5, 5)").tokenize()).parse())

        assertEquals(CTypes.int, validLoad.type)
        assertEquals(CTypes.void, validStore.type)
        assertEquals(CTypes.int, validCompare.type)
        assertTrue(diagnostics.render().contains("expects 2 argument(s)"))
        assertTrue(diagnostics.render().contains("operand must be an integer"))
        assertTrue(diagnostics.render().contains("expected-value argument must point"))
    }
}
