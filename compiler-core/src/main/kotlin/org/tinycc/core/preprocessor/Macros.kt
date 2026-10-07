package org.tinycc.core.preprocessor

import java.util.ArrayDeque

data class MacroDefinition(
    val name: String,
    val parameters: List<String>?,
    val replacement: String,
    val variadic: Boolean = false,
)

/** Ordered macro table so diagnostics and snapshots remain deterministic. */
class MacroTable {
    private val definitions = LinkedHashMap<String, MacroDefinition>()
    private val saved = HashMap<String, ArrayDeque<SavedMacro>>()

    fun define(definition: MacroDefinition): MacroDefinition? = definitions.put(definition.name, definition)

    fun define(name: String, replacement: String): MacroDefinition? =
        define(MacroDefinition(name, null, replacement))

    fun undef(name: String): MacroDefinition? = definitions.remove(name)

    fun push(name: String) {
        saved.getOrPut(name) { ArrayDeque() }.addLast(SavedMacro(definitions[name]))
    }

    fun pop(name: String): Boolean {
        val stack = saved[name] ?: return false
        val previous = stack.pollLast()
        if (stack.isEmpty()) saved.remove(name)
        if (previous == null) return false
        if (previous.definition == null) definitions.remove(name) else definitions[name] = previous.definition
        return true
    }

    operator fun get(name: String): MacroDefinition? = definitions[name]

    operator fun contains(name: String): Boolean = definitions.containsKey(name)

    fun snapshot(): List<MacroDefinition> = definitions.values.toList()

    private data class SavedMacro(val definition: MacroDefinition?)
}
