package tcc.kt

/** Parser, symbol stack, and expression generation routines ported from tccgen.c. */
object TccGen {
    const val SYM_STRUCT = 0x40000000
    const val SYM_FIELD = 0x20000000
    const val SYM_FIRST_ANOM = 0x10000000
    const val LABEL_DEFINED = 0
    const val LABEL_FORWARD = 1
    const val LABEL_DECLARED = 2
    const val LABEL_GONE = 3
    const val VT_STRUCT_SHIFT = 14
    const val VT_STRUCT_MASK = 3 shl VT_STRUCT_SHIFT
    const val VT_ENUM_VAL = 3 shl VT_STRUCT_SHIFT

    data class CType(var type: Int = 0, var reference: Sym? = null)
    data class SymbolAttributes(
        var aligned: Int = 0,
        var packed: Boolean = false,
        var weak: Boolean = false,
        var visibility: Int = 0,
        var dllExport: Boolean = false,
        var noDecorate: Boolean = false,
        var dllImport: Boolean = false,
        var addressTaken: Boolean = false,
        var noDebug: Boolean = false,
    )
    data class FunctionAttributes(
        var callingConvention: Int = 0,
        var functionType: Int = 0,
        var noReturn: Boolean = false,
        var constructor: Boolean = false,
        var destructor: Boolean = false,
        var argumentCount: Int = 0,
        var alwaysInline: Boolean = false,
    )
    class Sym(
        var token: Int = 0,
        var register: Int = 0,
        var attributes: SymbolAttributes = SymbolAttributes(),
        var number: Int = 0,
        var enumValue: Long = 0,
        var type: CType = CType(),
        var next: Sym? = null,
        var previous: Sym? = null,
        var previousToken: Sym? = null,
        var scope: Int = 0,
        var jumpNext: Int = 0,
        var jumpIndex: Int = 0,
        var function: FunctionAttributes = FunctionAttributes(),
    )
    class IdentifierSlot(var identifier: Sym? = null, var structure: Sym? = null, var label: Sym? = null)
    data class CompilerState(
        var globalStack: Sym? = null,
        var localStack: Sym? = null,
        var defineStack: Sym? = null,
        var globalLabelStack: Sym? = null,
        var localLabelStack: Sym? = null,
        var localScope: Int = 0,
        val identifiers: MutableList<IdentifierSlot> = mutableListOf(),
        val identifierTokenBase: Int = 0,
        val error: (String) -> Unit = {},
        val warning: (String) -> Unit = {},
    )

    fun symbolPush2(top: Sym?, token: Int, type: Int, number: Int): Sym =
        Sym(token = token, number = number, type = CType(type), previous = top)

    fun symbolFind2(top: Sym?, token: Int): Sym? {
        var symbol = top
        while (symbol != null) {
            if (symbol.token == token) return symbol
            symbol = symbol.previous
        }
        return null
    }

    fun symbolFind(state: CompilerState, token: Int, structure: Boolean = false): Sym? =
        identifierSlot(state, token and SYM_STRUCT.inv())?.let { if (structure) it.structure else it.identifier }

    private fun identifierSlot(state: CompilerState, token: Int): IdentifierSlot? =
        state.identifiers.getOrNull(token - state.identifierTokenBase)

    private fun linkSymbol(state: CompilerState, symbol: Sym, add: Boolean) {
        val slot = identifierSlot(state, symbol.token and SYM_STRUCT.inv())
            ?: error("identifier token is outside the token table: ${symbol.token}")
        if (symbol.token and SYM_STRUCT != 0) {
            if (add) { symbol.previousToken = slot.structure; slot.structure = symbol }
            else slot.structure = symbol.previousToken
        } else {
            if (add) { symbol.previousToken = slot.identifier; slot.identifier = symbol }
            else slot.identifier = symbol.previousToken
        }
        if (add) symbol.scope = state.localScope
    }

    private fun symbolScope(symbol: Sym): Int =
        if (symbol.type.type and VT_STRUCT_MASK == VT_ENUM_VAL) symbol.type.reference?.scope ?: 0 else symbol.scope

    fun symbolPush(state: CompilerState, token: Int, type: CType, register: Int, number: Int): Sym {
        val top = if (state.localStack != null) state.localStack else state.globalStack
        val symbol = symbolPush2(top, token, type.type, number)
        symbol.type.reference = type.reference
        symbol.register = register
        if ((token and SYM_STRUCT.inv()) < SYM_FIRST_ANOM) {
            linkSymbol(state, symbol, true)
            if (symbol.previousToken?.let { symbolScope(it) == state.localScope } == true) {
                state.error("redeclaration of '${token}'")
            }
        }
        if (state.localStack != null) state.localStack = symbol else state.globalStack = symbol
        return symbol
    }

    fun globalIdentifierPush(state: CompilerState, token: Int, type: Int, number: Int): Sym {
        val symbol = symbolPush2(state.globalStack, token, type, number)
        symbol.register = VT_CONST or VT_SYM
        if (token < SYM_FIRST_ANOM) {
            val slot = identifierSlot(state, token) ?: error("identifier token is outside the token table: $token")
            var previous = slot.identifier
            while (previous != null && previous.scope != 0) previous = previous.previousToken
            symbol.previousToken = previous
            if (previous == null) slot.identifier = symbol else previous.previousToken = symbol
        }
        state.globalStack = symbol
        return symbol
    }

    fun symbolPop(state: CompilerState, local: Boolean, boundary: Sym? = null, keep: Boolean = false) {
        var symbol = if (local) state.localStack else state.globalStack
        while (symbol !== boundary) {
            val current = symbol ?: error("symbol stack boundary is not reachable")
            val previous = current.previous
            if ((current.token and SYM_STRUCT.inv()) < SYM_FIRST_ANOM) linkSymbol(state, current, false)
            symbol = previous
        }
        if (!keep) {
            if (local) state.localStack = boundary else state.globalStack = boundary
        }
    }

    fun labelFind(state: CompilerState, token: Int): Sym? =
        identifierSlot(state, token)?.label

    fun labelPush(state: CompilerState, local: Boolean, token: Int, flags: Int): Sym {
        val top = if (local) state.localLabelStack else state.globalLabelStack
        val symbol = symbolPush2(top, token, 0, 0)
        symbol.register = flags
        val slot = identifierSlot(state, token) ?: error("label token is outside the token table: $token")
        if (local) {
            symbol.previousToken = slot.label
            slot.label = symbol
        } else {
            var previous = slot.label
            while (previous?.previousToken != null) previous = previous.previousToken
            symbol.previousToken = previous
            if (previous == null) slot.label = symbol else previous.previousToken = symbol
        }
        if (local) state.localLabelStack = symbol else state.globalLabelStack = symbol
        return symbol
    }

    fun labelPop(
        state: CompilerState,
        local: Boolean,
        boundary: Sym? = null,
        keep: Boolean = false,
        putExternSymbol: (Sym, Long, Int) -> Unit = { _, _, _ -> },
    ) {
        var symbol = if (local) state.localLabelStack else state.globalLabelStack
        while (symbol !== boundary) {
            val current = symbol ?: error("label stack boundary is not reachable")
            when (current.register) {
                LABEL_DECLARED -> state.warning("label '${current.token}' declared but not used")
                LABEL_FORWARD -> state.error("label '${current.token}' used but not defined")
                else -> if (current.number != 0) putExternSymbol(current, current.jumpIndex.toLong(), 1)
            }
            if (current.register != LABEL_GONE) {
                val slot = identifierSlot(state, current.token) ?: error("label token is outside the token table")
                slot.label = current.previousToken
            }
            if (keep) current.register = LABEL_GONE
            symbol = current.previous
        }
        if (!keep) {
            if (local) state.localLabelStack = boundary else state.globalLabelStack = boundary
        }
    }

    const val VT_CONST = 0x0040
    const val VT_SYM = 0x0200
}
