package org.tinycc.core.ir

import java.security.MessageDigest

/** Emits a stable, human-readable assembly contract consumed by target backends. */
fun interface IrAssemblyEmitter {
    fun emit(module: IrModule): String
}

class CanonicalAssemblyEmitter : IrAssemblyEmitter {
    override fun emit(module: IrModule): String = buildString {
        appendLine("; tcjc canonical assembly v1")
        appendLine(".module ${quote(module.name)}")
        module.globals.sortedBy { it.symbol.name }.forEach { global ->
            append(".global ").append(global.symbol.format()).append(" ")
            append(global.symbol.type.format()).append(" ")
            append(if (global.mutable) "mutable" else "const")
            global.initializer?.let { append(" = ").append(it.format()) }
            appendLine()
        }
        module.functions.sortedBy { it.symbol.name }.forEach { function ->
            append(".function ").append(function.symbol.format()).appendLine()
            function.parameters.forEachIndexed { index, parameter ->
                append("  .param %arg").append(index).append(" ").append(parameter.type.format()).appendLine()
            }
            function.blocks.forEach { block ->
                append("  ").append(block.name).appendLine(":")
                block.instructions.forEach { instruction ->
                    append("    ").append(instruction.format()).appendLine()
                }
                block.terminator?.let { terminator ->
                    append("    ").append(terminator.format()).appendLine()
                }
            }
            appendLine(".endfunction")
        }
    }
}

enum class IrObjectFormat { ELF, PE_COFF, MACH_O, RAW }

data class ObjectTarget(
    val format: IrObjectFormat,
    val architecture: IrArchitecture,
    val bits: Int,
    val littleEndian: Boolean,
) {
    init {
        require(bits in setOf(32, 64)) { "object width must be 32 or 64 bits" }
    }
}

data class EmittedObject(
    val target: ObjectTarget,
    val bytes: ByteArray,
    val assemblyDigest: String,
)

fun interface IrObjectEmitter {
    fun emit(module: IrModule, target: ObjectTarget): EmittedObject
}

/** Produces a deterministic object manifest until a target-specific writer supplies native bytes. */
class DeterministicObjectEmitter(
    private val assemblyEmitter: IrAssemblyEmitter = CanonicalAssemblyEmitter(),
) : IrObjectEmitter {
    override fun emit(module: IrModule, target: ObjectTarget): EmittedObject {
        IrVerifier().verifyOrThrow(module)
        val assembly = assemblyEmitter.emit(module)
        val digest = sha256(assembly.encodeToByteArray())
        val manifest = buildString {
            appendLine("TCJC-OBJECT-V1")
            appendLine("format=${target.format}")
            appendLine("architecture=${target.architecture}")
            appendLine("bits=${target.bits}")
            appendLine("endianness=${if (target.littleEndian) "little" else "big"}")
            appendLine("module=${module.name}")
            appendLine("assembly-sha256=$digest")
            module.globals.sortedBy { it.symbol.name }.forEach { appendLine("global=${it.symbol.name}") }
            module.functions.sortedBy { it.symbol.name }.forEach { appendLine("function=${it.symbol.name}") }
            module.relocations.sortedWith(compareBy<IrRelocation> { it.section }.thenBy { it.offset }).forEach {
                appendLine("relocation=${it.section}:${it.offset}:${it.width}:${it.kind}:${it.symbol.name}:${it.addend}")
            }
        }
        return EmittedObject(target, manifest.encodeToByteArray(), digest)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

data class DifferentialReport(
    val matches: Boolean,
    val expectedSize: Int,
    val actualSize: Int,
    val firstDifference: Int?,
)

object ArtifactDifferential {
    fun compare(expected: ByteArray, actual: ByteArray): DifferentialReport {
        val common = minOf(expected.size, actual.size)
        val difference = (0 until common).firstOrNull { expected[it] != actual[it] }
            ?: if (expected.size == actual.size) null else common
        return DifferentialReport(difference == null, expected.size, actual.size, difference)
    }

    fun compareText(expected: String, actual: String): DifferentialReport =
        compare(expected.encodeToByteArray(), actual.encodeToByteArray())
}

private fun IrSymbol.format(): String = "@${name.escape()} : ${type.format()}"

private fun IrValue.format(): String = when (this) {
    is IrValue.Local -> "%${id}${name?.let { "<$it>" } ?: ""}"
    is IrValue.Parameter -> "%arg$index"
    is IrValue.IntegerConstant -> value.toString()
    is IrValue.FloatingConstant -> raw
    is IrValue.NullPointer -> "null"
    is IrValue.Undef -> "undef"
    is IrValue.SymbolAddress -> "@${symbol.name}"
}

private fun IrInstruction.format(): String = when (this) {
    is IrInstruction.Alloca -> "${result.format()} = alloca ${allocatedType.format()}, ${count.type.format()} ${count.format()}, align $alignment"
    is IrInstruction.Load -> "${result.format()} = load ${loadedType.format()}, ${address.type.format()} ${address.format()}, align $alignment"
    is IrInstruction.Store -> "store ${value.type.format()} ${value.format()}, ${address.type.format()} ${address.format()}, align $alignment"
    is IrInstruction.Binary -> "${result.format()} = ${operation.name.lowercase()} ${left.type.format()} ${left.format()}, ${right.format()}"
    is IrInstruction.Compare -> "${result.format()} = compare ${condition.name.lowercase()} ${left.type.format()} ${left.format()}, ${right.format()}"
    is IrInstruction.Cast -> "${result.format()} = ${kind.name.lowercase()} ${value.type.format()} ${value.format()} to ${targetType.format()}"
    is IrInstruction.GetElementPointer -> "${result.format()} = gep ${base.type.format()} ${base.format()}${indices.joinToString(prefix = ", ") { "${it.type.format()} ${it.format()}" }}"
    is IrInstruction.Call -> {
        val prefix = result?.let { "${it.format()} = " } ?: ""
        prefix + "call ${functionType.returnType.format()} ${callee.format()}(" +
            arguments.joinToString(", ") { "${it.type.format()} ${it.format()}" } + ")"
    }
}

private fun IrTerminator.format(): String = when (this) {
    is IrTerminator.Jump -> "jump $target"
    is IrTerminator.Branch -> "branch ${condition.type.format()} ${condition.format()}, $trueTarget, $falseTarget"
    is IrTerminator.Switch -> "switch ${value.type.format()} ${value.format()}, default $defaultTarget [" +
        cases.joinToString(", ") { "${it.value.value} -> ${it.target}" } + "]"
    is IrTerminator.Return -> value?.let { "return ${it.type.format()} ${it.format()}" } ?: "return void"
    is IrTerminator.Unreachable -> "unreachable"
}

private fun IrType.format(): String = when (this) {
    IrType.Void -> "void"
    is IrType.Integer -> "i$bits${if (signed) "s" else "u"}"
    is IrType.Floating -> "f$bits"
    is IrType.Pointer -> "ptr(${pointee.format()})"
    is IrType.Aggregate -> name?.let { "%$it" } ?: "{" + fields.joinToString(", ") { it.format() } + "}"
    is IrType.Function -> "fn(${parameters.joinToString(", ") { it.format() }}):${returnType.format()}${if (variadic) ",..." else ""}"
}

private fun IrValue.Local.format(): String = "%${id}${name?.let { "<$it>" } ?: ""}"

private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private fun quote(value: String): String = "\"${value.escape()}\""
