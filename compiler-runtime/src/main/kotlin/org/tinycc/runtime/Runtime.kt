package org.tinycc.runtime

import java.math.BigInteger

/** Pure Kotlin replacements for the compiler support routines traditionally supplied by libtcc1. */
object CompilerRuntime {
    fun adddi3(left: Long, right: Long): Long = left + right
    fun subdi3(left: Long, right: Long): Long = left - right
    fun muldi3(left: Long, right: Long): Long = left * right
    fun divdi3(left: Long, right: Long): Long = left / right
    fun moddi3(left: Long, right: Long): Long = left % right
    fun udivdi3(left: Long, right: Long): Long = unsigned(left).divide(unsigned(right)).longValueExact()
    fun umoddi3(left: Long, right: Long): Long = unsigned(left).remainder(unsigned(right)).longValueExact()
    fun floatToInt(value: Double): Long = value.toLong()
    fun intToFloat(value: Long): Double = value.toDouble()

    fun boundsCheck(index: Long, length: Long) {
        require(index >= 0 && index < length) { "bounds check failed: index=$index length=$length" }
    }

    fun stackProbe(bytes: Long) {
        require(bytes >= 0) { "stack probe size must not be negative" }
    }

    private fun unsigned(value: Long): BigInteger = BigInteger.valueOf(value).and(BigInteger("ffffffffffffffff", 16))
}

enum class RuntimeLinkMode { STATIC, SHARED, PIC, CROSS_TARGET }

data class RuntimeObject(
    val name: String,
    val symbols: Map<String, ByteArray>,
    val nativeDependencies: List<String> = emptyList(),
)

data class RuntimeLinkOptions(
    val mode: RuntimeLinkMode,
    val target: String,
    val outputName: String,
)

data class LinkedRuntimeImage(
    val options: RuntimeLinkOptions,
    val symbols: Map<String, ByteArray>,
    val bytes: ByteArray,
    val nativeDependencies: List<String>,
)

class KotlinRuntimeLinker {
    fun link(objects: List<RuntimeObject>, options: RuntimeLinkOptions): LinkedRuntimeImage {
        val dependencies = objects.flatMap { it.nativeDependencies }.distinct().sorted()
        require(dependencies.isEmpty()) { "pure Kotlin runtime cannot depend on native artifacts: ${dependencies.joinToString()}" }
        val symbols = LinkedHashMap<String, ByteArray>()
        objects.sortedBy { it.name }.forEach { objectFile ->
            objectFile.symbols.toSortedMap().forEach { (name, bytes) ->
                require(symbols.putIfAbsent(name, bytes.copyOf()) == null) { "duplicate runtime symbol '$name'" }
            }
        }
        val manifest = buildString {
            appendLine("TCJC-KOTLIN-RUNTIME-V1")
            appendLine("mode=${options.mode}")
            appendLine("target=${options.target}")
            appendLine("output=${options.outputName}")
            symbols.forEach { (name, bytes) -> appendLine("symbol=$name:${bytes.size}") }
        }.encodeToByteArray()
        return LinkedRuntimeImage(options, symbols, manifest, dependencies)
    }
}
