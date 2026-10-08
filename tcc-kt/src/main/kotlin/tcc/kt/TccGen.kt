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
    const val VT_LLOCAL = 0x0031
    const val VT_LOCAL = 0x0032
    const val VT_JMP = 0x0034
    const val VT_JMPI = 0x0035
    const val VT_LVAL = 0x0100
    const val VT_BYTE = 1
    const val VT_SHORT = 2
    const val VT_INT = 3
    const val VT_LLONG = 4
    const val VT_PTR = 5
    const val VT_QLONG = 13
    const val VT_FLOAT = 8
    const val VT_DOUBLE = 9
    const val VT_LDOUBLE = 10
    const val VT_BOOL = 11
    const val VT_QFLOAT = 14
    const val VT_UNSIGNED = 0x0010
    const val VT_FUNC = 6
    const val VT_STRUCT = 7
    const val VT_BTYPE = 0x000f
    const val VT_VOID = 0
    const val VT_STATIC = 0x00004000
    const val VT_EXTERN = 0x00002000
    const val VT_INLINE = 0x00010000
    const val VT_ARRAY = 0x0040
    const val VT_TLS = 0x00020000
    const val VT_ASM_FUNC = VT_VOID or (5 shl VT_STRUCT_SHIFT)
    const val FUNC_OLD = 2
    const val FUNC_CDECL = 0
    const val FUNC_STDCALL = 3
    const val ST_PE_EXPORT = 0x10
    const val ST_PE_IMPORT = 0x20
    const val ST_PE_STDCALL = 0x40
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
        var section: String? = null,
        var aliasTarget: Int = 0,
        var mode: Int = 0,
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
        val saveRegister: (Int) -> Unit = {},
        val temporaryTypeSize: (Int) -> Pair<Int, Int> = { 0 to 1 },
    )
    data class SymbolEmissionHooks(
        val tokenName: (Int) -> String = { it.toString() },
        val debugExternalSymbol: (Sym, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    )
    data class AttributeDefinition(
        val symbol: SymbolAttributes = SymbolAttributes(),
        val function: FunctionAttributes = FunctionAttributes(),
        var section: String? = null,
        var aliasTarget: Int = 0,
        var assemblyLabel: Int = 0,
        var mode: Int = 0,
    )
    data class TypePatchHooks(
        val compatible: (CType, CType) -> Boolean,
        val error: (String) -> Unit = {},
        val warning: (String) -> Unit = {},
        val tokenName: (Int) -> String = { it.toString() },
    )
    data class RuntimeState(
        val values: MutableList<Value> = mutableListOf(),
        var codeIndex: Int = 0,
        var noCodeWanted: Int = 0,
        var debugModes: Int = 0,
        val hooks: RuntimeHooks = RuntimeHooks(),
        var registerClasses: IntArray = intArrayOf(),
        val temporaryLocals: MutableList<TemporaryLocal> = mutableListOf(),
        var localIndex: Int = 0,
    )
    data class TemporaryLocal(var location: Int, var size: Int, var alignment: Int)
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
        var currentTextSection: TccElf.ElfSection? = null,
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
        var assemblyLabel: Int = 0,
        var value: Int = 0,
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

    fun pushSymbol(state: RuntimeState, type: CType, symbol: Sym) {
        setValueConstant(state, type, VT_CONST or VT_SYM, 0, symbol)
    }

    fun getSymbolReference(
        compiler: CompilerState,
        token: Int,
        type: CType,
        target: TccElf.ElfSection?,
        value: Long,
        size: Long,
        elfState: TccElf.ElfState,
        generator: GeneratorState,
        hooks: SymbolEmissionHooks = SymbolEmissionHooks(),
    ): Sym {
        val staticType = type.copy(type = type.type or VT_STATIC)
        val symbol = symbolPush(compiler, token, staticType, VT_CONST or VT_SYM, 0)
        putExternalSymbol(symbol, target, value, size, generator, elfState, hooks = hooks)
        return symbol
    }

    fun pushSectionReference(
        state: RuntimeState,
        compiler: CompilerState,
        type: CType,
        token: Int,
        target: TccElf.ElfSection?,
        value: Long,
        size: Long,
        elfState: TccElf.ElfState,
        generator: GeneratorState,
        hooks: SymbolEmissionHooks = SymbolEmissionHooks(),
    ) = pushSymbol(state, type, getSymbolReference(compiler, token, type, target, value, size, elfState, generator, hooks))

    fun externalGlobalSymbol(
        state: CompilerState,
        token: Int,
        type: CType,
        elfState: TccElf.ElfState? = null,
        peTarget: Boolean = false,
    ): Sym {
        val symbol = symbolFind(state, token)
        if (symbol == null) {
            val forward = globalIdentifierPush(state, token, type.type or VT_EXTERN, 0)
            forward.type.reference = type.reference
            return forward
        }
        if (isAsmSymbol(symbol)) {
            symbol.type.type = type.type or (symbol.type.type and VT_EXTERN)
            symbol.type.reference = type.reference
            if (elfState != null) updateStorage(symbol, elfState, peTarget)
        }
        return symbol
    }

    fun externalHelperSymbol(
        state: CompilerState,
        token: Int,
        elfState: TccElf.ElfState? = null,
        peTarget: Boolean = false,
    ): Sym = externalGlobalSymbol(state, token, CType(VT_ASM_FUNC), elfState, peTarget)

    fun pushHelperFunction(
        state: RuntimeState,
        compiler: CompilerState,
        token: Int,
        oldFunctionType: CType,
        elfState: TccElf.ElfState? = null,
        peTarget: Boolean = false,
    ) = pushSymbol(state, oldFunctionType, externalHelperSymbol(compiler, token, elfState, peTarget))

    private fun isAsmSymbol(symbol: Sym): Boolean =
        symbol.type.type and (VT_BTYPE or VT_STRUCT_MASK) == VT_ASM_FUNC

    fun mergeSymbolAttributes(target: SymbolAttributes, incoming: SymbolAttributes) {
        if (incoming.aligned != 0 && target.aligned == 0) target.aligned = incoming.aligned
        target.packed = target.packed || incoming.packed
        target.weak = target.weak || incoming.weak
        target.noDebug = target.noDebug || incoming.noDebug
        if (incoming.visibility != 0 && (target.visibility == 0 || target.visibility > incoming.visibility)) {
            target.visibility = incoming.visibility
        }
        target.dllExport = target.dllExport || incoming.dllExport
        target.noDecorate = target.noDecorate || incoming.noDecorate
        target.dllImport = target.dllImport || incoming.dllImport
        if (incoming.section != null) target.section = incoming.section
        if (incoming.aliasTarget != 0) target.aliasTarget = incoming.aliasTarget
        if (incoming.mode != 0) target.mode = incoming.mode
    }

    fun mergeFunctionAttributes(target: FunctionAttributes, incoming: FunctionAttributes) {
        if (incoming.callingConvention != 0 && target.callingConvention == 0) target.callingConvention = incoming.callingConvention
        if (incoming.functionType != 0 && target.functionType == 0) target.functionType = incoming.functionType
        if (incoming.argumentCount != 0 && target.argumentCount == 0) target.argumentCount = incoming.argumentCount
        target.noReturn = target.noReturn || incoming.noReturn
        target.constructor = target.constructor || incoming.constructor
        target.destructor = target.destructor || incoming.destructor
        target.alwaysInline = target.alwaysInline || incoming.alwaysInline
    }

    fun mergeAttributes(target: AttributeDefinition, incoming: AttributeDefinition) {
        mergeSymbolAttributes(target.symbol, incoming.symbol)
        mergeFunctionAttributes(target.function, incoming.function)
        if (incoming.section != null) target.section = incoming.section
        if (incoming.aliasTarget != 0) target.aliasTarget = incoming.aliasTarget
        if (incoming.assemblyLabel != 0) target.assemblyLabel = incoming.assemblyLabel
        if (incoming.mode != 0) target.mode = incoming.mode
    }

    fun patchSymbolType(symbol: Sym, incoming: CType, hooks: TypePatchHooks) {
        if (incoming.type and VT_EXTERN == 0 || symbol.type.type and VT_STRUCT_MASK == VT_ENUM_VAL) {
            if (symbol.type.type and VT_EXTERN == 0) hooks.error("redefinition of '${hooks.tokenName(symbol.token)}'")
            symbol.type.type = symbol.type.type and VT_EXTERN.inv()
        }
        if (isAsmSymbol(symbol)) {
            symbol.type.type = incoming.type and (symbol.type.type or VT_STATIC.inv())
            symbol.type.reference = incoming.reference
            if (incoming.type and VT_BTYPE != VT_FUNC && incoming.type and VT_ARRAY == 0) symbol.register = symbol.register or VT_LVAL
        }
        if (!hooks.compatible(symbol.type, incoming)) {
            hooks.error("incompatible types for redefinition of '${hooks.tokenName(symbol.token)}'")
            return
        }
        if (symbol.type.type and VT_BTYPE == VT_FUNC) {
            val currentFunction = symbol.type.reference ?: return
            val incomingFunction = incoming.reference ?: return
            val staticPrototype = symbol.type.type and VT_STATIC
            val oldFunctionKind = currentFunction.function.functionType
            val newFunctionKind = incomingFunction.function.functionType
            if (incoming.type and VT_STATIC != 0 && staticPrototype == 0 &&
                (incoming.type or symbol.type.type) and VT_INLINE == 0) {
                hooks.warning("static storage ignored for redefinition of '${hooks.tokenName(symbol.token)}'")
            }
            var patchedStatic = staticPrototype
            if ((incoming.type or symbol.type.type) and VT_INLINE != 0 &&
                ((incoming.type xor symbol.type.type) and VT_INLINE == 0 || (incoming.type or symbol.type.type) and VT_STATIC != 0)) {
                patchedStatic = patchedStatic or VT_INLINE
            }
            if (incoming.type and VT_EXTERN == 0) {
                val oldAttributes = currentFunction.function.copy()
                symbol.type.type = (incoming.type and (VT_STATIC or VT_INLINE).inv()) or patchedStatic
                if (oldFunctionKind != FUNC_OLD) incomingFunction.function.functionType = oldFunctionKind
                symbol.type.reference = incomingFunction
                mergeFunctionAttributes(incomingFunction.function, oldAttributes)
            } else {
                symbol.type.type = (symbol.type.type and VT_INLINE.inv()) or patchedStatic
                if (oldFunctionKind == FUNC_OLD && newFunctionKind != FUNC_OLD) symbol.type.reference = incomingFunction
            }
        } else {
            if (symbol.type.type and VT_ARRAY != 0 && (symbol.type.reference?.value ?: -1) >= 0) {
                symbol.type.reference?.value = incoming.reference?.value ?: return
            }
            if ((incoming.type xor symbol.type.type) and VT_STATIC != 0) {
                hooks.warning("storage mismatch for redefinition of '${hooks.tokenName(symbol.token)}'")
            }
        }
    }

    fun patchSymbolStorage(
        symbol: Sym,
        attributes: AttributeDefinition,
        type: CType?,
        elfState: TccElf.ElfState,
        peTarget: Boolean = false,
        hooks: TypePatchHooks,
    ) {
        if (type != null) patchSymbolType(symbol, type, hooks)
        if (peTarget && symbol.attributes.dllImport != attributes.symbol.dllImport) {
            hooks.error("incompatible dll linkage for redefinition of '${hooks.tokenName(symbol.token)}'")
        }
        mergeSymbolAttributes(symbol.attributes, attributes.symbol)
        if (attributes.assemblyLabel != 0) symbol.assemblyLabel = attributes.assemblyLabel
        updateStorage(symbol, elfState, peTarget)
    }

    fun copySymbolToStack(symbol: Sym, state: CompilerState, local: Boolean): Sym {
        val copy = copySymbol(symbol)
        if (local) {
            copy.previous = state.localStack
            state.localStack = copy
        } else {
            copy.previous = state.globalStack
            state.globalStack = copy
        }
        if ((copy.token and SYM_STRUCT.inv()) < SYM_FIRST_ANOM) linkSymbol(state, copy, true)
        return copy
    }

    private fun copySymbol(source: Sym): Sym = Sym(
        token = source.token,
        register = source.register,
        attributes = source.attributes.copy(),
        number = source.number,
        enumValue = source.enumValue,
        type = source.type.copy(),
        next = source.next,
        previous = source.previous,
        previousToken = source.previousToken,
        scope = source.scope,
        jumpNext = source.jumpNext,
        jumpIndex = source.jumpIndex,
        assemblyLabel = source.assemblyLabel,
        value = source.value,
        function = source.function.copy(),
    )

    fun moveReferencedTypesToGlobal(state: CompilerState, root: Sym) {
        val baseType = root.type.type and VT_BTYPE
        if (baseType != VT_PTR && baseType != VT_FUNC && baseType != VT_STRUCT && root.type.type and VT_STRUCT_MASK != VT_ENUM_VAL) return
        var typeSymbol = root.type.reference
        var foundNonPointer = false
        while (typeSymbol != null) {
            var previous: Sym? = null
            var candidate = state.localStack
            while (candidate != null && candidate !== typeSymbol) {
                previous = candidate
                candidate = candidate.previous
            }
            if (candidate != null) {
                if (previous == null) state.localStack = candidate.previous else previous.previous = candidate.previous
                candidate.previous = state.globalStack
                state.globalStack = candidate
                if (foundNonPointer || baseType == VT_PTR || baseType == VT_FUNC) {
                    moveReferencedTypesToGlobal(state, candidate)
                } else if ((candidate.token and SYM_STRUCT.inv()) < SYM_FIRST_ANOM) {
                    candidate.token = candidate.token or SYM_FIELD
                    val localCopy = copySymbolToStack(candidate, state, local = true)
                    localCopy.token = localCopy.token and SYM_FIELD.inv()
                }
                if (baseType != VT_PTR) foundNonPointer = true
            }
            if (!foundNonPointer) break
            typeSymbol = typeSymbol.next
        }
    }

    fun externalSymbol(
        state: CompilerState,
        token: Int,
        type: CType,
        register: Int,
        attributes: AttributeDefinition,
        elfState: TccElf.ElfState,
        peTarget: Boolean = false,
        hooks: TypePatchHooks,
    ): Sym {
        var symbol = symbolFind(state, token)
        while (symbol != null && symbol.scope != 0) symbol = symbol.previousToken
        if (symbol == null) {
            symbol = globalIdentifierPush(state, token, type.type, 0)
            symbol.register = symbol.register or register
            symbol.attributes = attributes.symbol.copy()
            symbol.assemblyLabel = attributes.assemblyLabel
            symbol.type.reference = type.reference
        } else {
            patchSymbolStorage(symbol, attributes, type, elfState, peTarget, hooks)
        }
        if (state.localStack != null) {
            moveReferencedTypesToGlobal(state, symbol)
            copySymbolToStack(symbol, state, local = true)
        }
        return symbol
    }

    fun pushLongLong(state: RuntimeState, value: Long) =
        setValue(state, CType(VT_LLONG), VT_CONST, value)

    fun pushPointerSized(state: RuntimeState, value: Long, sizeType: Int) =
        setValue(state, CType(sizeType), VT_CONST, value)

    fun setIntegerValue(state: RuntimeState, register: Int, value: Int) =
        setValue(state, CType(VT_INT), register, value.toLong())

    fun duplicateTopValue(state: RuntimeState) {
        val value = state.values.lastOrNull() ?: run { state.hooks.error("value stack underflow"); return }
        pushValue(state, value)
    }

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

    fun elfSymbol(symbol: Sym, elfState: TccElf.ElfState): TccElf.ElfSymbol? {
        if (symbol.number == 0) return null
        return elfState.symbolTable?.symbols?.getOrNull(symbol.number)
    }

    fun updateStorage(symbol: Sym, elfState: TccElf.ElfState, peTarget: Boolean = false) {
        val elfSymbol = elfSymbol(symbol, elfState) ?: return
        if (symbol.attributes.visibility != 0) {
            elfSymbol.other = (elfSymbol.other and 3.inv()) or symbol.attributes.visibility
        }
        val binding = when {
            symbol.type.type and (VT_STATIC or VT_INLINE) != 0 -> TccElf.STB_LOCAL
            symbol.attributes.weak -> TccElf.STB_WEAK
            else -> TccElf.STB_GLOBAL
        }
        if (binding != elfSymbol.info ushr 4) elfSymbol.info = (binding shl 4) or (elfSymbol.info and 0x0f)
        if (peTarget) {
            if (symbol.attributes.dllImport) elfSymbol.other = elfSymbol.other or ST_PE_IMPORT
            if (symbol.attributes.dllExport) elfSymbol.other = elfSymbol.other or ST_PE_EXPORT
        }
    }

    fun putExternalSymbol(
        symbol: Sym,
        sectionIndex: Int,
        value: Long,
        size: Long,
        canAddUnderscore: Boolean,
        elfState: TccElf.ElfState,
        leadingUnderscore: Boolean = false,
        peTarget: Boolean = false,
        pointerSize: Int = elfState.wordSize,
        hooks: SymbolEmissionHooks = SymbolEmissionHooks(),
    ): Int {
        val table = elfState.symbolTable ?: return 0
        if (symbol.number == 0) {
            var addUnderscore = canAddUnderscore
            val typeBits = symbol.type.type
            val type = when {
                typeBits and VT_BTYPE == VT_FUNC -> TccElf.STT_FUNC
                typeBits and VT_BTYPE == VT_VOID -> if (typeBits and (VT_BTYPE or VT_STRUCT_MASK) == VT_ASM_FUNC) TccElf.STT_FUNC else TccElf.STT_NOTYPE
                typeBits and VT_TLS != 0 -> TccElf.STT_TLS
                else -> TccElf.STT_OBJECT
            }
            val binding = if (typeBits and (VT_STATIC or VT_INLINE) != 0) TccElf.STB_LOCAL else TccElf.STB_GLOBAL
            var other = 0
            var name = hooks.tokenName(symbol.token)
            if (peTarget && type == TccElf.STT_FUNC && symbol.type.reference != null) {
                val functionType = requireNotNull(symbol.type.reference)
                if (functionType.attributes.noDecorate) addUnderscore = false
                if (functionType.function.callingConvention == FUNC_STDCALL && addUnderscore) {
                    name = "_${name}@${functionType.function.argumentCount * pointerSize}"
                    other = other or ST_PE_STDCALL
                    addUnderscore = false
                }
            }
            if (symbol.assemblyLabel != 0) {
                name = hooks.tokenName(symbol.assemblyLabel)
                addUnderscore = false
            }
            if (leadingUnderscore && addUnderscore) name = "_${name.take(254)}"
            symbol.number = TccElf.setElfSymbol(
                elfState, table, value, size, (binding shl 4) or type, other, sectionIndex, name,
            )
            hooks.debugExternalSymbol(symbol, sectionIndex, binding, type)
        } else {
            elfSymbol(symbol, elfState)?.let {
                it.value = value
                it.size = size
                it.sectionIndex = sectionIndex
            }
        }
        updateStorage(symbol, elfState, peTarget)
        return symbol.number
    }

    fun putExternalSymbol(
        symbol: Sym,
        target: TccElf.ElfSection?,
        value: Long,
        size: Long,
        generator: GeneratorState,
        elfState: TccElf.ElfState,
        leadingUnderscore: Boolean = false,
        peTarget: Boolean = false,
        hooks: SymbolEmissionHooks = SymbolEmissionHooks(),
    ): Int {
        if (generator.noCodeWanted != 0 &&
            (generator.noCodeWanted > 0 || target === generator.currentTextSection)) return 0
        return putExternalSymbol(
            symbol, target?.index ?: TccElf.SHN_UNDEF, value, size, true, elfState,
            leadingUnderscore, peTarget, elfState.wordSize, hooks,
        )
    }

    fun generateRelocation(
        symbol: Sym?,
        target: TccElf.ElfSection,
        offset: Long,
        type: Int,
        addend: Long,
        generator: GeneratorState,
        elfState: TccElf.ElfState,
        leadingUnderscore: Boolean = false,
        peTarget: Boolean = false,
        hooks: SymbolEmissionHooks = SymbolEmissionHooks(),
    ): TccElf.ElfRelocation? {
        if (generator.noCodeWanted != 0 && target === generator.currentTextSection) return null
        var symbolIndex = 0
        if (symbol != null) {
            if (symbol.number == 0) {
                putExternalSymbol(symbol, null, 0, 0, generator, elfState, leadingUnderscore, peTarget, hooks)
                if (symbol.scope != 0 && symbol.type.type and (VT_STATIC or VT_EXTERN) == (VT_STATIC or VT_EXTERN)) {
                    var global: Sym = symbol
                    while (true) {
                        val previous = global.previousToken ?: break
                        global = previous
                    }
                    global.number = symbol.number
                }
            }
            symbolIndex = symbol.number
        }
        val table = elfState.symbolTable ?: return null
        return TccElf.putElfRelocation(elfState, table, target, offset, type, symbolIndex, addend)
    }

    const val CODE_OFF_BIT = 0x20000000
    const val RC_INT = 1
    const val RC_FLOAT = 2
    const val RC_ST0 = 4
    const val RC_IRET = 8
    const val RC_FRET = 16
    const val RC_IRE2 = 32
    const val RC_FRE2 = 64
    const val VALUE_STACK_SIZE = 1024

    data class TypeTarget(
        val pointerSize: Int,
        val integerReturnRegister: Int,
        val floatingReturnRegister: Int,
        val x86_64: Boolean = false,
        val riscv64: Boolean = false,
        val x87StackReturnRegister: Int = -1,
        val integerSecondReturnRegister: Int? = null,
        val floatingSecondReturnRegister: Int? = null,
        val registerClasses: IntArray = intArrayOf(),
        val integerClass: Int = RC_INT,
        val floatingClass: Int = RC_FLOAT,
        val stackFloatClass: Int = RC_ST0,
        val integerReturnClass: Int = RC_IRET,
        val floatingReturnClass: Int = RC_FRET,
        val integerSecondReturnClass: Int = RC_IRE2,
        val floatingSecondReturnClass: Int = RC_FRE2,
    )

    fun isFloat(type: Int): Boolean = when (type and VT_BTYPE) {
        VT_FLOAT, VT_DOUBLE, VT_LDOUBLE, VT_QFLOAT -> true
        else -> false
    }

    fun isIntegerBaseType(baseType: Int): Boolean = baseType in setOf(VT_BYTE, VT_BOOL, VT_SHORT, VT_INT, VT_LLONG)

    fun baseTypeSize(baseType: Int, target: TypeTarget): Int = when (baseType) {
        VT_BYTE, VT_BOOL -> 1
        VT_SHORT -> 2
        VT_INT -> 4
        VT_LLONG -> 8
        VT_PTR -> target.pointerSize
        else -> 0
    }

    fun returnRegister(type: Int, target: TypeTarget): Int {
        if (!isFloat(type)) return target.integerReturnRegister
        val baseType = type and VT_BTYPE
        if (target.x86_64 && baseType == VT_LDOUBLE) return target.x87StackReturnRegister
        if (target.riscv64 && baseType == VT_LDOUBLE) return target.integerReturnRegister
        return target.floatingReturnRegister
    }

    fun secondReturnRegister(type: Int, target: TypeTarget): Int {
        return when (type and VT_BTYPE) {
            VT_LLONG -> if (target.pointerSize == 4) target.integerSecondReturnRegister ?: VT_CONST else VT_CONST
            VT_QLONG -> if (target.x86_64) target.integerSecondReturnRegister ?: VT_CONST else VT_CONST
            VT_QFLOAT -> if (target.x86_64) target.floatingSecondReturnRegister ?: VT_CONST else VT_CONST
            VT_LDOUBLE -> if (target.riscv64) target.integerSecondReturnRegister ?: VT_CONST else VT_CONST
            else -> VT_CONST
        }
    }

    fun putReturnRegisters(value: Value, type: Int, target: TypeTarget) {
        value.register = returnRegister(type, target)
        value.secondRegister = secondReturnRegister(type, target)
    }

    fun returnRegisterClass(type: Int, target: TypeTarget): Int = target.registerClasses
        .getOrElse(returnRegister(type, target)) { 0 } and (target.floatingClass or target.integerClass).inv()

    fun registerClassForType(type: Int, target: TypeTarget): Int {
        if (!isFloat(type)) return target.integerClass
        val baseType = type and VT_BTYPE
        if (target.x86_64 && baseType == VT_LDOUBLE) return target.stackFloatClass
        if (target.x86_64 && baseType == VT_QFLOAT) return target.floatingReturnClass
        if (target.riscv64 && baseType == VT_LDOUBLE) return target.integerClass
        return target.floatingClass
    }

    fun secondRegisterClass(type: Int, registerClass: Int, target: TypeTarget): Int {
        if (secondReturnRegister(type, target) == VT_CONST) return 0
        if (registerClass == target.integerReturnClass && target.integerSecondReturnRegister != null) return target.integerSecondReturnClass
        if (registerClass == target.floatingReturnClass && target.floatingSecondReturnRegister != null) return target.floatingSecondReturnClass
        return if (registerClass and target.floatingClass != 0) target.floatingClass else target.integerClass
    }

    fun saveRegisters(state: RuntimeState, depth: Int) {
        val end = (state.values.size - depth).coerceAtMost(state.values.size)
        for (index in 0 until end.coerceAtLeast(0)) saveRegister(state, state.values[index].register)
    }

    fun saveRegister(state: RuntimeState, register: Int) = saveRegisterUpStack(state, register, 0)

    fun saveRegisterUpStack(state: RuntimeState, rawRegister: Int, depth: Int) {
        val register = rawRegister and VT_VALMASK
        if (register >= VT_CONST || state.noCodeWanted != 0) return
        var slot: Int? = null
        var second = VT_CONST
        val end = (state.values.size - depth).coerceAtMost(state.values.size)
        for (index in 0 until end.coerceAtLeast(0)) {
            val value = state.values[index]
            if ((value.register and VT_VALMASK != register) && value.secondRegister != register) continue
            if (slot == null) {
                var baseType = value.type.type and VT_BTYPE
                if (baseType == VT_VOID) continue
                if (value.register and VT_LVAL != 0 || baseType == VT_FUNC) baseType = VT_PTR
                val (size, alignment) = state.hooks.temporaryTypeSize(baseType)
                slot = temporaryLocal(state, size, alignment).first
                second = temporaryLocalIndex(state, slot)
                state.hooks.saveRegister(register)
                if (value.secondRegister < VT_CONST && secondReturnRegister(baseType, TypeTarget(4, 0, 0)) != VT_CONST) {
                    state.hooks.saveRegister(value.secondRegister)
                }
            }
            if (value.register and VT_LVAL != 0) {
                value.register = (value.register and (VT_VALMASK or 0x8000).inv()) or VT_LLOCAL
            } else {
                value.register = VT_LVAL or VT_LOCAL
                value.type.type = value.type.type and VT_ARRAY.inv()
            }
            value.symbol = null
            value.secondRegister = second
            value.constant = slot.toLong()
        }
    }

    fun getRegister(state: RuntimeState, registerClass: Int): Int {
        for (register in state.registerClasses.indices) {
            if (state.registerClasses[register] and registerClass == 0) continue
            if (state.noCodeWanted != 0 || state.values.none {
                    (it.register and VT_VALMASK) == register || it.secondRegister == register
                }) return register
        }
        for (value in state.values) {
            val second = value.secondRegister
            if (second < VT_CONST && second in state.registerClasses.indices && state.registerClasses[second] and registerClass != 0) {
                saveRegister(state, second)
                return second
            }
            val first = value.register and VT_VALMASK
            if (first < VT_CONST && first in state.registerClasses.indices && state.registerClasses[first] and registerClass != 0) {
                saveRegister(state, first)
                return first
            }
        }
        return -1
    }

    fun getRegisterEx(state: RuntimeState, registerClass: Int, secondaryClass: Int): Int {
        for (register in state.registerClasses.indices) {
            if (state.registerClasses[register] and secondaryClass == 0) continue
            val uses = state.values.count { (it.register and VT_VALMASK) == register || it.secondRegister == register }
            if (uses <= 1) return register
        }
        return getRegister(state, registerClass)
    }

    fun temporaryLocal(state: RuntimeState, size: Int, alignment: Int): Pair<Int, Int> {
        val used = state.values.mapNotNull { value ->
            val register = value.register and VT_VALMASK
            if (register == VT_LOCAL || register == VT_LLOCAL) (value.secondRegister - (VT_CONST + 1)).takeIf { it >= 0 }
            else null
        }.toSet()
        state.temporaryLocals.forEachIndexed { index, temp ->
            if (index !in used && temp.size >= size && temp.alignment >= alignment) return temp.location to (VT_CONST + 1 + index)
        }
        val index = state.temporaryLocals.size
        state.localIndex = (state.localIndex - size) and -alignment
        if (index < 32) {
            state.temporaryLocals += TemporaryLocal(state.localIndex, size, alignment)
            return state.localIndex to (VT_CONST + 1 + index)
        }
        return state.localIndex to VT_CONST
    }

    private fun temporaryLocalIndex(state: RuntimeState, location: Int): Int =
        state.temporaryLocals.indexOfFirst { it.location == location }.let { if (it < 0) VT_CONST else VT_CONST + 1 + it }

    const val VT_CONST = 0x0040
    const val VT_SYM = 0x0200
}
