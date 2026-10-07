package org.tinycc.core.ir

import java.math.BigInteger

/** Reports structural and type errors before an IR module reaches a target backend. */
class IrVerifier {
    fun verify(module: IrModule): List<IrVerificationError> {
        val errors = ArrayList<IrVerificationError>()
        val symbols = HashSet<String>()
        module.globals.forEach { global ->
            if (!symbols.add(global.symbol.name)) errors += error("duplicate symbol '${global.symbol.name}'", global.debugLocation)
            if (global.alignment <= 0) errors += error("global '${global.symbol.name}' has invalid alignment", global.debugLocation)
            global.initializer?.let { value ->
                if (!sameType(value.type, global.symbol.type)) {
                    errors += error("initializer for '${global.symbol.name}' has type ${value.type}, expected ${global.symbol.type}", global.debugLocation)
                }
            }
        }
        module.functions.forEach { function ->
            if (!symbols.add(function.symbol.name)) errors += error("duplicate symbol '${function.symbol.name}'", function.debugLocation)
            verifyFunction(function, errors)
        }
        module.relocations.forEach { relocation ->
            if (relocation.symbol.name !in symbols) {
                errors += error("relocation refers to unknown symbol '${relocation.symbol.name}'", relocation.debugLocation)
            }
        }
        return errors
    }

    fun verifyOrThrow(module: IrModule) {
        val errors = verify(module)
        check(errors.isEmpty()) { errors.joinToString("\n") { it.message } }
    }

    private fun verifyFunction(function: IrFunction, errors: MutableList<IrVerificationError>) {
        val functionType = function.symbol.type as? IrType.Function
        if (functionType == null) {
            errors += error("function '${function.symbol.name}' does not have a function type", function.debugLocation)
            return
        }
        if (functionType.parameters.size != function.parameters.size ||
            functionType.parameters.zip(function.parameters).any { (expected, actual) -> !sameType(expected, actual.type) }
        ) {
            errors += error("function '${function.symbol.name}' parameter metadata does not match its symbol type", function.debugLocation)
        }

        val blocks = function.blocks.associateBy { it.name }
        if (blocks.size != function.blocks.size) errors += error("function '${function.symbol.name}' has duplicate basic blocks", function.debugLocation)
        function.blocks.forEach { block ->
            block.instructions.forEach { instruction -> verifyInstruction(instruction, errors) }
            val terminator = block.terminator
            if (terminator == null) {
                errors += error("basic block '${block.name}' has no terminator", block.debugLocation ?: function.debugLocation)
            } else {
                verifyTerminator(terminator, functionType.returnType, blocks, errors)
            }
        }
    }

    private fun verifyInstruction(instruction: IrInstruction, errors: MutableList<IrVerificationError>) {
        when (instruction) {
            is IrInstruction.Alloca -> {
                if (!sameType(instruction.result.type, IrType.Pointer(instruction.allocatedType))) {
                    errors += error("alloca result is not a pointer to its allocated type", instruction.debugLocation)
                }
                requireInteger(instruction.count, "alloca count", instruction.debugLocation, errors)
                requirePositiveAlignment(instruction.alignment, "alloca", instruction.debugLocation, errors)
            }
            is IrInstruction.Load -> {
                requirePointerTo(instruction.address, instruction.loadedType, "load address", instruction.debugLocation, errors)
                if (!sameType(instruction.result.type, instruction.loadedType)) {
                    errors += error("load result type does not match loaded type", instruction.debugLocation)
                }
                requirePositiveAlignment(instruction.alignment, "load", instruction.debugLocation, errors)
            }
            is IrInstruction.Store -> {
                requirePointerTo(instruction.address, instruction.value.type, "store address", instruction.debugLocation, errors)
                requirePositiveAlignment(instruction.alignment, "store", instruction.debugLocation, errors)
            }
            is IrInstruction.Binary -> {
                if (!sameType(instruction.left.type, instruction.right.type) || !sameType(instruction.result.type, instruction.left.type)) {
                    errors += error("binary operation operands and result must have one type", instruction.debugLocation)
                }
            }
            is IrInstruction.Compare -> {
                if (!sameType(instruction.left.type, instruction.right.type)) {
                    errors += error("comparison operands must have one type", instruction.debugLocation)
                }
                if (!sameType(instruction.result.type, IrTypes.i1)) {
                    errors += error("comparison result must be i1", instruction.debugLocation)
                }
            }
            is IrInstruction.Cast -> if (!sameType(instruction.result.type, instruction.targetType)) {
                errors += error("cast result does not match its target type", instruction.debugLocation)
            }
            is IrInstruction.GetElementPointer -> {
                if (instruction.base.type !is IrType.Pointer) {
                    errors += error("getelementptr base must be a pointer", instruction.debugLocation)
                }
                instruction.indices.forEach { index -> requireInteger(index, "getelementptr index", instruction.debugLocation, errors) }
                if (instruction.result.type !is IrType.Pointer) {
                    errors += error("getelementptr result must be a pointer", instruction.debugLocation)
                }
            }
            is IrInstruction.Call -> {
                val calleeType = instruction.callee.type
                val expectedCalleeType = IrType.Pointer(instruction.functionType)
                if (!sameType(calleeType, instruction.functionType) && !sameType(calleeType, expectedCalleeType)) {
                    errors += error("call callee type does not match its function type", instruction.debugLocation)
                }
                if ((!instruction.functionType.variadic && instruction.arguments.size != instruction.functionType.parameters.size) ||
                    (instruction.functionType.variadic && instruction.arguments.size < instruction.functionType.parameters.size)
                ) {
                    errors += error("call argument count does not match function type", instruction.debugLocation)
                }
                instruction.functionType.parameters.zip(instruction.arguments).forEach { (expected, actual) ->
                    if (!sameType(expected, actual.type)) errors += error("call argument type does not match parameter type", instruction.debugLocation)
                }
                val returnType = instruction.functionType.returnType
                if (returnType == IrType.Void && instruction.result != null) {
                    errors += error("void call must not produce a result", instruction.debugLocation)
                } else if (returnType != IrType.Void && !sameType(instruction.result?.type, returnType)) {
                    errors += error("call result type does not match return type", instruction.debugLocation)
                }
            }
        }
    }

    private fun verifyTerminator(
        terminator: IrTerminator,
        returnType: IrType,
        blocks: Map<String, IrBasicBlock>,
        errors: MutableList<IrVerificationError>,
    ) {
        fun requireTarget(target: String) {
            if (target !in blocks) errors += error("terminator refers to unknown block '$target'", terminator.debugLocation)
        }
        when (terminator) {
            is IrTerminator.Jump -> requireTarget(terminator.target)
            is IrTerminator.Branch -> {
                if (!sameType(terminator.condition.type, IrTypes.i1)) errors += error("branch condition must be i1", terminator.debugLocation)
                requireTarget(terminator.trueTarget)
                requireTarget(terminator.falseTarget)
            }
            is IrTerminator.Switch -> {
                if (terminator.value.type !is IrType.Integer) errors += error("switch value must be an integer", terminator.debugLocation)
                requireTarget(terminator.defaultTarget)
                val caseValues = HashSet<BigInteger>()
                terminator.cases.forEach { case ->
                    if (!caseValues.add(case.value.value)) errors += error("switch contains duplicate case value ${case.value.value}", terminator.debugLocation)
                    requireTarget(case.target)
                }
            }
            is IrTerminator.Return -> {
                if (returnType == IrType.Void && terminator.value != null) errors += error("void function must return without a value", terminator.debugLocation)
                if (returnType != IrType.Void && (terminator.value == null || !sameType(terminator.value.type, returnType))) {
                    errors += error("return value does not match function return type", terminator.debugLocation)
                }
            }
            is IrTerminator.Unreachable -> Unit
        }
    }

    private fun requireInteger(value: IrValue, description: String, location: IrDebugLocation?, errors: MutableList<IrVerificationError>) {
        if (value.type !is IrType.Integer) errors += error("$description must be an integer", location)
    }

    private fun requirePointerTo(
        value: IrValue,
        pointee: IrType,
        description: String,
        location: IrDebugLocation?,
        errors: MutableList<IrVerificationError>,
    ) {
        val pointer = value.type as? IrType.Pointer
        if (pointer == null || !sameType(pointer.pointee, pointee)) errors += error("$description must point to $pointee", location)
    }

    private fun requirePositiveAlignment(value: Int, description: String, location: IrDebugLocation?, errors: MutableList<IrVerificationError>) {
        if (value <= 0) errors += error("$description alignment must be positive", location)
    }

    private fun sameType(left: IrType?, right: IrType?): Boolean = left == right

    private fun error(message: String, location: IrDebugLocation?) = IrVerificationError(message, location)
}

data class IrVerificationError(
    val message: String,
    val debugLocation: IrDebugLocation?,
)
