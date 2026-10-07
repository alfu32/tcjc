package org.tinycc.core.preprocessor

sealed interface PreprocessorPragma {
    data class Pack(val action: PackAction, val alignment: Int? = null) : PreprocessorPragma

    data class Library(val name: String) : PreprocessorPragma

    data class Option(val value: String) : PreprocessorPragma

    data class Comment(val kind: String, val value: String) : PreprocessorPragma
}

enum class PackAction {
    SET,
    RESET,
    PUSH,
    PUSH_SET,
    POP,
}
