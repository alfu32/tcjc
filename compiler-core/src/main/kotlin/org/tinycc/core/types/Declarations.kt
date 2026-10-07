package org.tinycc.core.types

enum class StorageClass { AUTO, EXTERN, STATIC, REGISTER, TYPEDEF, THREAD_LOCAL }

enum class Linkage { NONE, INTERNAL, EXTERNAL }

data class DeclarationAttributes(
    val storage: StorageClass = StorageClass.AUTO,
    val linkage: Linkage = Linkage.NONE,
    val isInline: Boolean = false,
    val isWeak: Boolean = false,
    val section: String? = null,
    val visibility: Visibility = Visibility.DEFAULT,
    val alias: String? = null,
    val asmName: String? = null,
    val alignment: Long? = null,
    val isPacked: Boolean = false,
    val isDeprecated: Boolean = false,
    val isUsed: Boolean = false,
    val isNoReturn: Boolean = false,
    val callingConvention: CallingConvention = CallingConvention.CDECL,
    val addressSpace: Int? = null,
)

enum class Visibility { DEFAULT, HIDDEN, PROTECTED, INTERNAL }

sealed interface Declaration {
    val name: String?
    val type: CType
    val attributes: DeclarationAttributes
}

data class ObjectDeclaration(
    override val name: String?,
    override val type: CType,
    override val attributes: DeclarationAttributes = DeclarationAttributes(),
    val initializer: String? = null,
) : Declaration

data class FunctionDeclaration(
    override val name: String,
    override val type: CType.Function,
    override val attributes: DeclarationAttributes = DeclarationAttributes(),
    val isDefinition: Boolean = false,
) : Declaration

data class TypedefDeclaration(
    override val name: String,
    override val type: CType.Typedef,
    override val attributes: DeclarationAttributes = DeclarationAttributes(storage = StorageClass.TYPEDEF),
) : Declaration

data class RecordDeclaration(
    override val name: String?,
    override val type: CType.Record,
    override val attributes: DeclarationAttributes = DeclarationAttributes(),
) : Declaration

data class EnumDeclaration(
    override val name: String?,
    override val type: CType.Enumeration,
    override val attributes: DeclarationAttributes = DeclarationAttributes(),
) : Declaration
