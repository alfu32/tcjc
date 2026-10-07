package org.tinycc.core.preprocessor

data class MacroDefinition(
    val name: String,
    val parameters: List<String>?,
    val replacement: String,
    val variadic: Boolean = false,
)

/** Ordered macro table so diagnostics and snapshots remain deterministic. */
class MacroTable {
    private val definitions = LinkedHashMap<String, MacroDefinition>()

    fun define(definition: MacroDefinition): MacroDefinition? = definitions.put(definition.name, definition)

    fun define(name: String, replacement: String): MacroDefinition? =
        define(MacroDefinition(name, null, replacement))

    fun undef(name: String): MacroDefinition? = definitions.remove(name)

    operator fun get(name: String): MacroDefinition? = definitions[name]

    operator fun contains(name: String): Boolean = definitions.containsKey(name)

    fun snapshot(): List<MacroDefinition> = definitions.values.toList()
}
