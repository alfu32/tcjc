package org.tinycc.core.symbols

import java.util.ArrayDeque
import java.util.EnumMap
import java.util.Collections
import java.util.IdentityHashMap
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
    private val history = ArrayList<Declaration>().also { it += declaration }
    private var tentativeFinalized = false
    private var hasTentativeDefinition = declaration is ObjectDeclaration &&
        (declaration as ObjectDeclaration).initializer == null &&
        declaration.attributes.storage != StorageClass.EXTERN &&
        scopeKind in setOf(ScopeKind.GLOBAL, ScopeKind.FILE)

    val type: CType
        get() = declaration.type

    val linkage: Linkage
        get() = declaration.attributes.linkage

    val declarations: List<Declaration>
        get() = history.toList()

    val isTentative: Boolean
        get() = hasTentativeDefinition && !tentativeFinalized

    val isDefined: Boolean
        get() = when (val value = declaration) {
            is FunctionDeclaration -> value.isDefinition
            is ObjectDeclaration -> value.initializer != null || tentativeFinalized
            else -> true
        }

    internal fun record(declaration: Declaration) {
        history += declaration
        this.declaration = declaration
        if (declaration is ObjectDeclaration) {
            if (declaration.initializer != null) {
                hasTentativeDefinition = false
                tentativeFinalized = false
            } else if (scopeKind in setOf(ScopeKind.GLOBAL, ScopeKind.FILE) &&
                declaration.attributes.storage != StorageClass.EXTERN
            ) {
                hasTentativeDefinition = true
            }
        }
    }

    internal fun finalizeTentative() {
        if (isTentative) tentativeFinalized = true
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

    val allSymbols: List<Symbol>
        get() {
            val seen = Collections.newSetFromMap(IdentityHashMap<Symbol, Boolean>())
            return scopes.flatMap { scope -> scope.symbols.values.flatMap { it.values } }
                .filter(seen::add)
        }

    fun enter(kind: ScopeKind): ScopeKind {
        scopes.addLast(Scope(kind))
        return kind
    }

    fun exit(): ScopeKind {
        check(scopes.size > 1) { "cannot exit the global scope" }
        val scope = scopes.removeLast()
        if (scope.kind == ScopeKind.FILE) scope.symbols.values.flatMap { it.values }.forEach(Symbol::finalizeTentative)
        return scope.kind
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
        validateDeclarationPosition(declaration, namespace, location)
        val existing = current.symbols.getValue(namespace)[name]
            ?: findLinkedExternal(name, declaration, namespace)
        if (existing == null) {
            val symbol = Symbol(name, namespace, current.kind, declaration.withLinkage(effectiveLinkage(declaration)))
            current.symbols.getValue(namespace)[name] = symbol
            return symbol
        }
        if (!canMerge(existing, declaration)) {
            diagnostics.error(location, "conflicting declaration of '$name'")
            return existing
        }
        val linkage = effectiveLinkage(declaration)
        if (existing.linkage != Linkage.NONE && linkage != Linkage.NONE && existing.linkage != linkage) {
            diagnostics.error(location, "conflicting linkage for '$name'")
            return existing
        }
        if (existing.isDefined && isDefinition(declaration)) {
            diagnostics.error(location, "redefinition of '$name'")
            return existing
        }
        val merged = declaration.withLinkage(if (linkage == Linkage.NONE) existing.linkage else linkage)
        if (isDefinition(declaration) || existing.declaration.attributes.storage == StorageClass.EXTERN || existing.isTentative) {
            existing.record(merged)
        } else {
            existing.record(existing.declaration)
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

    /** Commits file-scope tentative definitions as zero-initialized definitions. */
    fun finalizeFileScope(): List<Symbol> {
        val finalized = allSymbols.filter(Symbol::isTentative)
        finalized.forEach(Symbol::finalizeTentative)
        return finalized
    }

    fun tentativeDefinitions(): List<Symbol> = allSymbols.filter(Symbol::isTentative)

    private fun findLinkedExternal(name: String, declaration: Declaration, namespace: SymbolNamespace): Symbol? {
        if (namespace != SymbolNamespace.ORDINARY || declaration.attributes.storage != StorageClass.EXTERN) return null
        val iterator = scopes.descendingIterator()
        if (iterator.hasNext()) iterator.next()
        while (iterator.hasNext()) {
            val candidate = iterator.next().symbols.getValue(namespace)[name]
            if (candidate?.linkage == Linkage.EXTERNAL) return candidate
        }
        return null
    }

    private fun validateDeclarationPosition(
        declaration: Declaration,
        namespace: SymbolNamespace,
        location: SourceLocation,
    ) {
        if (namespace == SymbolNamespace.LABEL && currentScope != ScopeKind.FUNCTION) {
            diagnostics.error(location, "label declaration outside a function")
        }
        if (namespace == SymbolNamespace.MEMBER && currentScope != ScopeKind.RECORD) {
            diagnostics.error(location, "member declaration outside a record")
        }
        val storage = declaration.attributes.storage
        if (storage == StorageClass.TYPEDEF && namespace != SymbolNamespace.ORDINARY) {
            diagnostics.error(location, "typedef must be declared in the ordinary namespace")
        }
        if (storage == StorageClass.REGISTER && currentScope in setOf(ScopeKind.GLOBAL, ScopeKind.FILE)) {
            diagnostics.error(location, "automatic storage is not valid at file scope")
        }
        if (storage == StorageClass.THREAD_LOCAL && currentScope == ScopeKind.BLOCK &&
            declaration.attributes.storage != StorageClass.EXTERN && declaration.attributes.storage != StorageClass.STATIC
        ) {
            diagnostics.error(location, "thread-local block declarations require static or extern storage")
        }
    }

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
