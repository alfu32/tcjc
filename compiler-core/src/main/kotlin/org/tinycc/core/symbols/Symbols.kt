package org.tinycc.core.symbols

import java.util.ArrayDeque
import java.util.EnumMap
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation
import org.tinycc.core.types.CType
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.Declaration
import org.tinycc.core.types.FunctionDeclaration
import org.tinycc.core.types.Linkage
import org.tinycc.core.types.ObjectDeclaration
import org.tinycc.core.types.StorageClass

enum class SymbolNamespace { ORDINARY, TAG, LABEL, MEMBER }

enum class ScopeKind { GLOBAL, FILE, FUNCTION, BLOCK, PROTOTYPE, RECORD }

class Symbol(
    val name: String,
    val namespace: SymbolNamespace,
    val scopeKind: ScopeKind,
    var declaration: Declaration,
) {
    val type: CType
        get() = declaration.type

    val linkage: Linkage
        get() = declaration.attributes.linkage

    val isDefined: Boolean
        get() = when (val value = declaration) {
            is FunctionDeclaration -> value.isDefinition
            is ObjectDeclaration -> value.initializer != null
            else -> true
        }
}

/** Scoped C namespaces with compatible redeclaration and shadowing rules. */
class SymbolTable(private val diagnostics: DiagnosticEngine = DiagnosticEngine()) {
    private val scopes = ArrayDeque<Scope>()

    init {
        scopes.addLast(Scope(ScopeKind.GLOBAL))
    }

    val depth: Int
        get() = scopes.size - 1

    val currentScope: ScopeKind
        get() = scopes.peekLast().kind

    fun enter(kind: ScopeKind): ScopeKind {
        scopes.addLast(Scope(kind))
        return kind
    }

    fun exit(): ScopeKind {
        check(scopes.size > 1) { "cannot exit the global scope" }
        return scopes.removeLast().kind
    }

    fun <T> withScope(kind: ScopeKind, action: () -> T): T {
        enter(kind)
        return try {
            action()
        } finally {
            exit()
        }
    }

    fun declare(
        declaration: Declaration,
        namespace: SymbolNamespace = SymbolNamespace.ORDINARY,
        location: SourceLocation = SourceLocation(),
    ): Symbol? {
        val name = declaration.name
        if (name == null) {
            diagnostics.error(location, "declaration requires a name")
            return null
        }
        return declare(name, declaration, namespace, location)
    }

    fun declare(
        name: String,
        declaration: Declaration,
        namespace: SymbolNamespace = SymbolNamespace.ORDINARY,
        location: SourceLocation = SourceLocation(),
    ): Symbol {
        require(name.isNotBlank()) { "symbol name must not be blank" }
        val current = scopes.peekLast()
        val existing = current.symbols.getValue(namespace)[name]
        if (existing == null) {
            val symbol = Symbol(name, namespace, current.kind, declaration.withLinkage(effectiveLinkage(declaration)))
            current.symbols.getValue(namespace)[name] = symbol
            return symbol
        }
        if (!canMerge(existing, declaration)) {
            diagnostics.error(location, "conflicting declaration of '$name'")
            return existing
        }
        if (existing.isDefined && isDefinition(declaration)) {
            diagnostics.error(location, "redefinition of '$name'")
            return existing
        }
        if (isDefinition(declaration) || existing.declaration.attributes.storage == StorageClass.EXTERN) {
            existing.declaration = declaration.withLinkage(effectiveLinkage(declaration))
        }
        return existing
    }

    fun lookup(name: String, namespace: SymbolNamespace = SymbolNamespace.ORDINARY): Symbol? {
        val iterator = scopes.descendingIterator()
        while (iterator.hasNext()) {
            scopesScope(iterator.next()).symbols.getValue(namespace)[name]?.let { return it }
        }
        return null
    }

    fun lookupCurrent(name: String, namespace: SymbolNamespace = SymbolNamespace.ORDINARY): Symbol? =
        scopes.peekLast().symbols.getValue(namespace)[name]

    private fun canMerge(existing: Symbol, declaration: Declaration): Boolean =
        CTypes.compatible(existing.type, declaration.type) &&
            !(existing.declaration.attributes.storage == StorageClass.TYPEDEF &&
                declaration.attributes.storage != StorageClass.TYPEDEF)

    private fun isDefinition(declaration: Declaration): Boolean = when (declaration) {
        is FunctionDeclaration -> declaration.isDefinition
        is ObjectDeclaration -> declaration.initializer != null
        else -> true
    }

    private fun effectiveLinkage(declaration: Declaration): Linkage {
        if (declaration.attributes.linkage != Linkage.NONE) return declaration.attributes.linkage
        return when {
            declaration.attributes.storage == StorageClass.STATIC -> Linkage.INTERNAL
            declaration.attributes.storage == StorageClass.EXTERN -> Linkage.EXTERNAL
            currentScope == ScopeKind.GLOBAL || currentScope == ScopeKind.FILE -> Linkage.EXTERNAL
            else -> Linkage.NONE
        }
    }

    private fun Declaration.withLinkage(linkage: Linkage): Declaration {
        val attributes = attributes.copy(linkage = linkage)
        return when (this) {
            is ObjectDeclaration -> copy(attributes = attributes)
            is FunctionDeclaration -> copy(attributes = attributes)
            is org.tinycc.core.types.TypedefDeclaration -> copy(attributes = attributes)
            is org.tinycc.core.types.RecordDeclaration -> copy(attributes = attributes)
            is org.tinycc.core.types.EnumDeclaration -> copy(attributes = attributes)
        }
    }

    private fun scopesScope(scope: Scope): Scope = scope

    private class Scope(val kind: ScopeKind) {
        val symbols = EnumMap<SymbolNamespace, MutableMap<String, Symbol>>(SymbolNamespace::class.java).apply {
            SymbolNamespace.values().forEach { put(it, LinkedHashMap()) }
        }
    }
}
