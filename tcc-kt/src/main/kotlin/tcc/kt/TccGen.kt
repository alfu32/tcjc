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
    const val VT_VALMASK = 0x003f
    const val VT_CMP = 0x0033
    const val VT_JMP = 0x0034
    const val VT_JMPI = 0x0035
    const val VT_LVAL = 0x0100
    const val VT_BYTE = 1
    const val VT_SHORT = 2
    const val VT_INT = 3
    const val VT_LLONG = 4
    const val VT_PTR = 5
    const val VT_FLOAT = 8
    const val VT_DOUBLE = 9
    const val VT_LDOUBLE = 10
    const val VT_BOOL = 11
    const val VT_QFLOAT = 14
    const val VT_UNSIGNED = 0x0010
    const val VT_FUNC = 6
    const val FUNC_OLD = 2
    const val FUNC_CDECL = 0
    const val PARSE_FLAG_PREPROCESS = 0x0001
    const val PARSE_FLAG_TOKEN_NUMBER = 0x0002
    const val PARSE_FLAG_TOKEN_STRING = 0x0040
    const val DATA_ONLY_WANTED = Int.MIN_VALUE
    const val FIRST_ANONYMOUS_SYMBOL = SYM_FIRST_ANOM
    const val PRECEDENCE_PARSER = true

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
    data class Value(
        var type: CType = CType(),
        var register: Int = 0,
        var secondRegister: Int = VT_CONST,
        var constant: Long = 0,
        var trueJump: Int = 0,
        var falseJump: Int = 0,
        var symbol: Sym? = null,
        var compareOperator: Int = 0,
        var compareRegister: Int = 0,
    )
    data class RuntimeHooks(
        val error: (String) -> Unit = {},
        val warning: (String) -> Unit = {},
        val defineJump: (Int, Int) -> Unit = { _, _ -> },
        val codeOn: () -> Unit = {},
        val codeOff: () -> Unit = {},
        val coverageBlockBegin: (Int) -> Unit = {},
        val loadCompare: (Int) -> Unit = {},
        val outputOpcode: (Int) -> Unit = {},
        val tokenName: (Int) -> String = { it.toString() },
    )
    data class RuntimeState(
        val values: MutableList<Value> = mutableListOf(),
        var codeIndex: Int = 0,
        var noCodeWanted: Int = 0,
        var debugModes: Int = 0,
        val hooks: RuntimeHooks = RuntimeHooks(),
    )
    data class GeneratorState(
        var returnSymbol: Int = 0,
        var anonymousSymbol: Int = 0,
        var instructionIndex: Int = 0,
        var localIndex: Int = 0,
        var debugModes: Int = 0,
        var noCodeWanted: Int = 0,
        var globalExpression: Int = 0,
        var functionReturnType: CType = CType(),
        var functionVariadic: Boolean = false,
        var functionReturnStorage: Int = 0,
        var intType: CType = CType(),
        var characterType: CType = CType(),
        var characterPointerType: CType = CType(),
        var oldFunctionType: CType = CType(),
        var functionStart: Int = -1,
        var functionName: String = "",
        var currentScope: Int = 0,
        var switchDepth: Int = 0,
        var temporaryLocalCount: Int = 0,
    )
    data class LifecycleHooks(
        val debugStart: () -> Unit = {},
        val coverageStart: () -> Unit = {},
        val architectureInit: () -> Unit = {},
        val nextToken: () -> Unit = {},
        val parseDeclarations: (Int) -> Unit = {},
        val generateInlineFunctions: () -> Unit = {},
        val checkValueStack: () -> Unit = {},
        val unwindEnd: () -> Unit = {},
        val debugEnd: () -> Unit = {},
        val coverageEnd: () -> Unit = {},
        val freeInlineFunctions: () -> Unit = {},
        val freeDefines: () -> Unit = {},
        val freeStringBuffer: () -> Unit = {},
        val clearStackData: () -> Unit = {},
        val endSwitch: () -> Unit = {},
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

    fun gsym(state: RuntimeState, label: Int) {
        if (label != 0) {
            state.hooks.defineJump(label, state.codeIndex)
            state.hooks.codeOn()
            state.noCodeWanted = state.noCodeWanted and CODE_OFF_BIT.inv()
        }
    }

    fun gind(state: RuntimeState): Int {
        val index = state.codeIndex
        state.hooks.codeOn()
        state.noCodeWanted = state.noCodeWanted and CODE_OFF_BIT.inv()
        if (state.debugModes != 0) state.hooks.coverageBlockBegin(index)
        return index
    }

    fun jumpAddressSuppress(state: RuntimeState, label: Int, jumpAddress: (Int) -> Unit) {
        jumpAddress(label)
        state.noCodeWanted = state.noCodeWanted or CODE_OFF_BIT
        state.hooks.codeOff()
    }

    fun jumpSuppress(state: RuntimeState, jump: () -> Int): Int {
        val label = jump()
        state.noCodeWanted = state.noCodeWanted or CODE_OFF_BIT
        state.hooks.codeOff()
        return label
    }

    fun ieeeFinite(value: Double): Int {
        val highWord = (java.lang.Double.doubleToRawLongBits(value) ushr 32).toInt()
        return (((highWord or 0x800fffff.toInt()).toUInt() + 1u) shr 31).toInt()
    }

    fun testLvalue(state: RuntimeState) {
        val value = state.values.lastOrNull() ?: error("value stack is empty")
        if (value.register and VT_LVAL == 0) state.hooks.error("lvalue expected")
    }

    fun checkValueStack(state: RuntimeState) {
        if (state.values.isNotEmpty()) state.hooks.error("internal compiler error: vstack leak (${state.values.size})")
    }

    private fun checkCompare(state: RuntimeState) {
        val value = state.values.lastOrNull() ?: return
        if (value.register == VT_CMP && state.noCodeWanted and CODE_OFF_BIT.inv() == 0) state.hooks.loadCompare(RC_INT)
    }

    fun setValue(state: RuntimeState, type: CType, register: Int, constant: Long) =
        setValueConstant(state, type, register, constant, null)

    fun setValueConstant(state: RuntimeState, type: CType, register: Int, constant: Long, symbol: Sym?) {
        if (state.values.size >= VALUE_STACK_SIZE) {
            state.hooks.error("memory full (vstack)")
            return
        }
        checkCompare(state)
        state.values += Value(type.copy(), register, VT_CONST, constant, symbol = symbol)
    }

    fun pushInteger(state: RuntimeState, value: Int) =
        setValue(state, CType(VT_INT), VT_CONST, value.toLong())

    fun pushValue(state: RuntimeState, value: Value) {
        if (state.values.size >= VALUE_STACK_SIZE) {
            state.hooks.error("memory full (vstack)")
            return
        }
        checkCompare(state)
        state.values += value.copy(type = value.type.copy())
    }

    fun swapValues(state: RuntimeState) {
        checkCompare(state)
        if (state.values.size < 2) { state.hooks.error("value stack underflow"); return }
        val top = state.values.lastIndex
        val previous = state.values[top - 1]
        state.values[top - 1] = state.values[top]
        state.values[top] = previous
    }

    fun popValue(state: RuntimeState, x86FloatingStack: Boolean = false, floatingStackRegister: Int = -1) {
        val value = state.values.lastOrNull() ?: run { state.hooks.error("value stack underflow"); return }
        when {
            x86FloatingStack && value.register and VT_VALMASK == floatingStackRegister -> state.hooks.outputOpcode(0xd8dd)
            value.register == VT_CMP -> { gsym(state, value.trueJump); gsym(state, value.falseJump) }
        }
        state.values.removeAt(state.values.lastIndex)
    }

    fun rotateValueToTop(state: RuntimeState, position: Int) {
        val count = position - 1
        if (count < 1) return
        checkCompare(state)
        if (count >= state.values.size) { state.hooks.error("value stack underflow"); return }
        val index = state.values.lastIndex - count
        val value = state.values.removeAt(index)
        state.values += value
    }

    fun rotateTopValueDown(state: RuntimeState, position: Int) {
        val count = position - 1
        if (count < 1) return
        checkCompare(state)
        if (count >= state.values.size) { state.hooks.error("value stack underflow"); return }
        val value = state.values.removeAt(state.values.lastIndex)
        state.values.add(state.values.size - count, value)
    }

    fun reverseValues(state: RuntimeState, count: Int) {
        checkCompare(state)
        if (count < 0 || count > state.values.size) { state.hooks.error("value stack underflow"); return }
        val start = state.values.size - count
        for (offset in 0 until count / 2) {
            val left = start + offset
            val right = state.values.lastIndex - offset
            val value = state.values[left]
            state.values[left] = state.values[right]
            state.values[right] = value
        }
    }

    fun setCompareValue(state: RuntimeState, operator: Int) {
        val value = state.values.lastOrNull() ?: error("value stack is empty")
        value.register = VT_CMP
        value.compareOperator = operator
        value.falseJump = 0
        value.trueJump = 0
    }

    fun initializeGenerator(
        state: GeneratorState,
        charIsUnsigned: Boolean,
        makePointer: (CType) -> Unit,
        pushFunctionTypeSymbol: (Int, CType, Int, Int) -> Sym,
        initializePrecedence: () -> Unit = {},
        initializeString: () -> Unit = {},
    ) {
        val intType = CType(VT_INT)
        val charType = CType(VT_BYTE or if (charIsUnsigned) VT_UNSIGNED else 0)
        val charPointer = charType.copy()
        makePointer(charPointer)
        val oldFunctionType = CType(VT_FUNC)
        oldFunctionType.reference = pushFunctionTypeSymbol(SYM_FIELD, intType, 0, 0).also {
            it.function.callingConvention = FUNC_CDECL
            it.function.functionType = FUNC_OLD
        }
        if (PRECEDENCE_PARSER) initializePrecedence()
        initializeString()
        state.intType = intType
        state.characterType = charType
        state.characterPointerType = charPointer
        state.oldFunctionType = oldFunctionType
    }

    fun compileTranslationUnit(
        state: GeneratorState,
        runtime: RuntimeState,
        debugEnabled: Boolean,
        testCoverageEnabled: Boolean,
        architectureInitializationNeeded: Boolean,
        hooks: LifecycleHooks = LifecycleHooks(),
    ): Int {
        state.functionName = ""
        state.functionStart = -1
        state.anonymousSymbol = FIRST_ANONYMOUS_SYMBOL
        state.noCodeWanted = DATA_ONLY_WANTED
        state.debugModes = (if (debugEnabled) 1 else 0) or (if (testCoverageEnabled) 2 else 0)
        state.globalExpression = 0
        runtime.noCodeWanted = DATA_ONLY_WANTED
        runtime.debugModes = state.debugModes
        hooks.debugStart()
        hooks.coverageStart()
        if (architectureInitializationNeeded) hooks.architectureInit()
        hooks.nextToken()
        hooks.parseDeclarations(PARSE_FLAG_PREPROCESS or PARSE_FLAG_TOKEN_NUMBER or PARSE_FLAG_TOKEN_STRING)
        hooks.generateInlineFunctions()
        hooks.checkValueStack()
        hooks.unwindEnd()
        hooks.debugEnd()
        hooks.coverageEnd()
        return 0
    }

    fun finishGenerator(
        state: GeneratorState,
        compiler: CompilerState,
        hooks: LifecycleHooks = LifecycleHooks(),
    ) {
        hooks.debugEnd()
        hooks.freeInlineFunctions()
        compiler.globalStack = null
        compiler.localStack = null
        compiler.defineStack = null
        hooks.freeDefines()
        hooks.freeStringBuffer()
        hooks.clearStackData()
        while (state.switchDepth > 0) { hooks.endSwitch(); state.switchDepth-- }
        state.currentScope = 0
        state.temporaryLocalCount = 0
        compiler.globalLabelStack = null
        compiler.localLabelStack = null
    }

    const val CODE_OFF_BIT = 0x20000000
    const val RC_INT = 1
    const val VALUE_STACK_SIZE = 1024

    const val VT_CONST = 0x0040
    const val VT_SYM = 0x0200
}
