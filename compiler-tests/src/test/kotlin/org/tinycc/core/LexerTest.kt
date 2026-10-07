package org.tinycc.core

import java.nio.charset.StandardCharsets
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.io.SourceDecoder
import org.tinycc.core.lexer.Lexer
import org.tinycc.core.lexer.LiteralValue
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
}
