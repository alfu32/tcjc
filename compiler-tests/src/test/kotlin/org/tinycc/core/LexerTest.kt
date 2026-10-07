package org.tinycc.core

import java.nio.charset.StandardCharsets
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.io.SourceDecoder
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.lexer.LexerOptions
import org.tinycc.core.lexer.LiteralValue
import org.tinycc.core.lexer.TccTokenIds
import org.tinycc.core.lexer.TokenKind

class LexerTest {
    @Test
    fun decodesUtf8BomAndNormalizesNewlines() {
        val source = SourceDecoder.decode("\uFEFFint x;\r\nint y;\r".toByteArray(StandardCharsets.UTF_8))

        assertTrue(source.hadUtf8Bom)
        assertEquals("int x;\nint y;\n", source.text)
    }

    @Test
    fun lexesKeywordsOperatorsLiteralsAndLocations() {
        val diagnostics = DiagnosticEngine()
        val tokens = Lexer(
            "int main() { // comment\n return 0x2a + '\\n' + \"ok\\x21\"; }",
            diagnostics = diagnostics,
        ).tokenize()

        assertContentEquals(
            listOf(
                TokenKind.INT, TokenKind.IDENTIFIER, TokenKind.LEFT_PAREN, TokenKind.RIGHT_PAREN,
                TokenKind.LEFT_BRACE, TokenKind.RETURN, TokenKind.INTEGER_LITERAL, TokenKind.PLUS,
                TokenKind.CHARACTER_LITERAL, TokenKind.PLUS, TokenKind.STRING_LITERAL,
                TokenKind.SEMICOLON, TokenKind.RIGHT_BRACE, TokenKind.EOF,
            ),
            tokens.map { it.kind },
        )
        assertEquals(2, tokens[5].span.start.line)
        assertEquals(42, (tokens[6].literal as LiteralValue.Integer).value.toInt())
        assertEquals('\n'.code, (tokens[8].literal as LiteralValue.Character).value)
        assertEquals("ok!", (tokens[10].literal as LiteralValue.StringValue).value)
        assertEquals(0, diagnostics.errorCount)
    }

    @Test
    fun reportsMalformedCommentsNumbersAndEscapesWithoutStopping() {
        val diagnostics = DiagnosticEngine()
        val tokens = Lexer("/* unclosed", diagnostics = diagnostics).tokenize()
        assertEquals(TokenKind.EOF, tokens.single().kind)
        assertTrue(diagnostics.render().contains("unexpected end of file in comment"))

        val literalDiagnostics = DiagnosticEngine()
        val literalTokens = Lexer("1abc '\\q'", diagnostics = literalDiagnostics).tokenize()
        assertEquals(TokenKind.INVALID, literalTokens.first().kind)
        assertTrue(literalDiagnostics.render().contains("invalid number"))
        assertTrue(literalDiagnostics.render().contains("unknown escape sequence"))
    }

    @Test
    fun preservesHistoricalTinyCcTokenIdsAndLiteralClasses() {
        val tokens = Lexer("if int __attribute__ ++ -> ... && 7u 8L 9LL 1.0f 2.0L 'a' L\"x\"").tokenize()

        assertEquals(256, tokens[0].tccId)
        assertEquals(299, tokens[1].tccId)
        assertEquals(312, tokens[2].tccId)
        assertEquals(0x82, tokens[3].tccId)
        assertEquals(0xA0, tokens[4].tccId)
        assertEquals(0xA1, tokens[5].tccId)
        assertEquals(0x90, tokens[6].tccId)
        assertEquals(0xC3, tokens[7].tccId)
        assertEquals(0xC6, tokens[8].tccId)
        assertEquals(0xC4, tokens[9].tccId)
        assertEquals(0xCA, tokens[10].tccId)
        assertEquals(0xCC, tokens[11].tccId)
        assertEquals(0xC0, tokens[12].tccId)
        assertEquals(0xC9, tokens[13].tccId)
    }

    @Test
    fun preservesHistoricalPragmaAndRuntimeTokenOrder() {
        val tokens = Lexer(
            "__builtin_va_arg_types __atomic_store __atomic_nand_fetch pack comment option " +
                "memcpy __divdi3 __fixunsdfdi __fixxfdi alloca",
        ).tokenize()

        assertEquals(listOf(411, 412, 427, 428, 429, 434, 435, 438, 450, 451, 452), tokens.dropLast(1).map { it.tccId })
    }

    @Test
    fun selectsHistoricalI386TokenIdsAndTargetBuiltins() {
        val i386 = TccTokenIds.TargetProfile.I386
        val tokens = Lexer(
            "__builtin_va_arg_types __atomic_store __atomic_nand_fetch pack memcpy __divdi3 " +
                "__fixsfdi __fixdfdi __fixxfdi alloca",
            options = LexerOptions(tokenTarget = i386),
        ).tokenize()

        assertEquals(listOf(256, 411, 426, 427, 434, 437, 450, 451, 452, 453), tokens.dropLast(1).map { it.tccId })
        assertEquals(TokenKind.IDENTIFIER, tokens.first().kind)
    }

    @Test
    fun preservesWindowsX86TokenConditionals() {
        val x64 = Lexer(
            "__builtin_va_start pack __chkstk __tls_index",
            options = LexerOptions(tokenTarget = TccTokenIds.TargetProfile.X86_64_PE),
        ).tokenize()
        val i386 = Lexer(
            "pack alloca __chkstk __tls_index",
            options = LexerOptions(tokenTarget = TccTokenIds.TargetProfile.I386_PE),
        ).tokenize()

        assertEquals(listOf(411, 428, 453, 454), x64.dropLast(1).map { it.tccId })
        assertEquals(listOf(427, 453, 454, 455), i386.dropLast(1).map { it.tccId })
    }

    @Test
    fun lexesHexadecimalFloatingConstantsLineSplicesAndDigraphs() {
        val diagnostics = DiagnosticEngine()
        val tokens = Lexer("0x1.8p+1\\\nvalue <: 2 :> %:%:", diagnostics = diagnostics).tokenize()

        assertEquals(3.0, (tokens[0].literal as LiteralValue.Floating).value)
        assertEquals(TokenKind.IDENTIFIER, tokens[1].kind)
        assertEquals(2, tokens[1].span.start.line)
        assertEquals(TokenKind.LEFT_BRACKET, tokens[2].kind)
        assertEquals(TokenKind.RIGHT_BRACKET, tokens[4].kind)
        assertEquals(TokenKind.HASH_HASH, tokens[5].kind)
        assertEquals(0, diagnostics.errorCount)
    }
}
