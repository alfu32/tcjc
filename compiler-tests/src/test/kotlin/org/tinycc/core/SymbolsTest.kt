package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.symbols.ScopeKind
import org.tinycc.core.symbols.SymbolNamespace
import org.tinycc.core.symbols.SymbolTable
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.DeclarationAttributes
import org.tinycc.core.types.FunctionDeclaration
import org.tinycc.core.types.Linkage
import org.tinycc.core.types.ObjectDeclaration
import org.tinycc.core.types.RecordDeclaration
import org.tinycc.core.types.StorageClass
import org.tinycc.core.types.CType
import org.tinycc.core.types.RecordKind

class SymbolsTest {
    @Test
    fun mergesCompatibleDeclarationsAndDerivesLinkage() {
        val symbols = SymbolTable()
        val declaration = ObjectDeclaration("value", CTypes.int)
        val extern = ObjectDeclaration(
            "value",
            CTypes.int,
            DeclarationAttributes(storage = StorageClass.EXTERN),
        )

        val first = symbols.declare(extern)
        val merged = symbols.declare(declaration.copy(initializer = "42"))

        assertSame(first, merged)
        assertEquals(Linkage.EXTERNAL, merged?.linkage)
        assertTrue(merged?.isDefined == true)
    }

    @Test
    fun scopesShadowNamesButKeepTagsAndOrdinaryIdentifiersSeparate() {
        val symbols = SymbolTable()
        val global = symbols.declare(ObjectDeclaration("item", CTypes.int))
        val record = CType.Record(RecordKind.STRUCT, "item")
        val tag = symbols.declare(
            RecordDeclaration("item", record),
            namespace = SymbolNamespace.TAG,
        )
        symbols.enter(ScopeKind.BLOCK)
        val local = symbols.declare(ObjectDeclaration("item", CTypes.double))

        assertSame(local, symbols.lookup("item"))
        assertSame(tag, symbols.lookup("item", SymbolNamespace.TAG))
        assertNotSame(global, local)
        assertEquals(ScopeKind.BLOCK, symbols.exit())
        assertSame(global, symbols.lookup("item"))
    }

    @Test
    fun reportsIncompatibleRedeclarationsAndPreservesExistingBinding() {
        val diagnostics = DiagnosticEngine()
        val symbols = SymbolTable(diagnostics)
        val original = symbols.declare(ObjectDeclaration("value", CTypes.int))
        val result = symbols.declare(ObjectDeclaration("value", CTypes.pointer(CTypes.int)))

        assertSame(original, result)
        assertTrue(diagnostics.render().contains("conflicting declaration"))
    }

    @Test
    fun keepsFunctionDefinitionMetadata() {
        val symbols = SymbolTable()
        val function = CTypes.function(CTypes.int, emptyList()) as CType.Function
        val declaration = FunctionDeclaration("run", function, isDefinition = true)
        val symbol = symbols.declare(declaration)!!

        assertTrue(symbol.isDefined)
        assertEquals(Linkage.EXTERNAL, symbol.linkage)
    }
}
