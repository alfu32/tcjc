package org.tinycc.core.functions

import org.tinycc.core.diagnostics.SourceSpan
import org.tinycc.core.statements.Statement
import org.tinycc.core.types.CType
import org.tinycc.core.types.FunctionDeclaration

data class ParameterDeclaration(val name: String?, val type: CType, val span: SourceSpan)

data class ParsedFunction(
    val declaration: FunctionDeclaration,
    val parameters: List<ParameterDeclaration>,
    val body: Statement.Compound?,
    val span: SourceSpan,
)
