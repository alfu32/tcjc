package org.tinycc.core.types

import java.util.Collections
import java.util.IdentityHashMap
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.SourceLocation

enum class TypeUse {
    OBJECT,
    PARAMETER,
    RETURN,
    FIELD,
    ARRAY_ELEMENT,
    FUNCTION,
    TYPEDEF,
}

/** Validates the C constraints that are independent of a target ABI. */
class TypeRules(private val diagnostics: DiagnosticEngine = DiagnosticEngine()) {
    fun validate(type: CType, use: TypeUse, location: SourceLocation = SourceLocation()): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<CType, Boolean>())
        return validateType(type, use, location, seen)
    }

    fun validateDeclaration(
        declaration: Declaration,
        location: SourceLocation = SourceLocation(),
    ): Boolean {
        val use = when (declaration) {
            is FunctionDeclaration -> TypeUse.FUNCTION
            is TypedefDeclaration -> TypeUse.TYPEDEF
            is RecordDeclaration, is EnumDeclaration -> TypeUse.TYPEDEF
            is ObjectDeclaration -> TypeUse.OBJECT
        }
        val validType = validate(declaration.type, use, location)
        val attributes = declaration.attributes
        val validAlignment = attributes.alignment == null || validAlignment(attributes.alignment)
        if (!validAlignment) diagnostics.error(location, "invalid declaration alignment")
        if (attributes.addressSpace != null && attributes.addressSpace < 0) {
            diagnostics.error(location, "address space must not be negative")
            return false
        }
        return validType && validAlignment
    }

    private fun validateType(
        type: CType,
        use: TypeUse,
        location: SourceLocation,
        seen: MutableSet<CType>,
    ): Boolean {
        if (!seen.add(type)) return true
        val valid = when (val unaliased = CTypes.unalias(type)) {
            CType.Error -> false
            is CType.Primitive -> validatePrimitive(unaliased, use, location)
            is CType.Qualified -> validateAttributes(unaliased.attributes, location) &&
                validateType(unaliased.base, use, location, seen)
            is CType.Pointer -> validateQualifiers(unaliased.qualifiers, location) &&
                validateType(unaliased.pointee, TypeUse.ARRAY_ELEMENT, location, seen)
            is CType.Array -> validateArray(unaliased, use, location, seen)
            is CType.Function -> validateFunction(unaliased, location, seen)
            is CType.Record -> validateRecord(unaliased, location, seen)
            is CType.Enumeration -> validateEnum(unaliased, location)
            is CType.Typedef -> validateType(unaliased.target, use, location, seen)
        }
        if (!valid && !diagnostics.hasErrors) diagnostics.error(location, "invalid C type $type")
        return valid
    }

    private fun validatePrimitive(type: CType.Primitive, use: TypeUse, location: SourceLocation): Boolean {
        if (type.kind == PrimitiveKind.VOID && use in setOf(TypeUse.OBJECT, TypeUse.FIELD, TypeUse.ARRAY_ELEMENT, TypeUse.PARAMETER)) {
            diagnostics.error(location, "void is not valid in this type position")
            return false
        }
        return true
    }

    private fun validateArray(
        type: CType.Array,
        use: TypeUse,
        location: SourceLocation,
        seen: MutableSet<CType>,
    ): Boolean {
        if (type.isStaticParameter && use != TypeUse.PARAMETER) {
            diagnostics.error(location, "static array bounds are only valid on parameters")
            return false
        }
        if (type.bound is ArrayBound.Flexible && use !in setOf(TypeUse.FIELD, TypeUse.PARAMETER)) {
            diagnostics.error(location, "flexible array is only valid as a field or parameter")
            return false
        }
        return validateType(type.element, TypeUse.ARRAY_ELEMENT, location, seen)
    }

    private fun validateFunction(
        type: CType.Function,
        location: SourceLocation,
        seen: MutableSet<CType>,
    ): Boolean {
        val returnType = CTypes.unalias(type.returnType)
        if (returnType is CType.Array || returnType is CType.Function) {
            diagnostics.error(location, "function cannot return an array or function")
            return false
        }
        if (!validateType(type.returnType, TypeUse.RETURN, location, seen)) return false
        if (type.variadic && type.parameters.isEmpty()) {
            diagnostics.error(location, "variadic function requires a named parameter")
            return false
        }
        val isVoidParameterList = type.parameters.size == 1 &&
            itIsVoid(type.parameters.single().type) && type.parameters.single().name == null
        if (isVoidParameterList) return true
        if (type.parameters.any { itIsVoid(it.type) }) {
            diagnostics.error(location, "void parameter must be the only parameter")
            return false
        }
        return type.parameters.all { validateType(it.type, TypeUse.PARAMETER, location, seen) }
    }

    private fun itIsVoid(type: CType): Boolean =
        (CTypes.unalias(type) as? CType.Primitive)?.kind == PrimitiveKind.VOID

    private fun validateRecord(
        type: CType.Record,
        location: SourceLocation,
        seen: MutableSet<CType>,
    ): Boolean {
        if (!validateAttributes(type.attributes, location)) return false
        val names = HashSet<String>()
        type.fields.forEachIndexed { index, field ->
            if (field.name != null && !names.add(field.name)) {
                diagnostics.error(location, "duplicate field '${field.name}'")
                return@forEachIndexed
            }
            val bound = (CTypes.unalias(field.type) as? CType.Array)?.bound
            if (bound is ArrayBound.Flexible && index != type.fields.lastIndex) {
                diagnostics.error(location, "flexible array must be the last field")
            }
            validateType(field.type, TypeUse.FIELD, location, seen)
        }
        return !diagnostics.hasErrors
    }

    private fun validateEnum(type: CType.Enumeration, location: SourceLocation): Boolean {
        val names = HashSet<String>()
        type.constants.forEach { constant ->
            if (!names.add(constant.name)) diagnostics.error(location, "duplicate enumerator '${constant.name}'")
        }
        return !diagnostics.hasErrors
    }

    private fun validateQualifiers(qualifiers: TypeQualifiers, location: SourceLocation): Boolean {
        if (qualifiers.isRestrict) {
            // Restrict is checked against the pointer level by the declarator builder;
            // retaining this hook keeps diagnostics centralized for later parser stages.
        }
        return true
    }

    private fun validateAttributes(attributes: TypeAttributes, location: SourceLocation): Boolean {
        if (attributes.aligned != null && !validAlignment(attributes.aligned)) {
            diagnostics.error(location, "invalid type alignment")
            return false
        }
        if (attributes.vectorBytes != null && (attributes.vectorBytes <= 0 || attributes.vectorBytes and (attributes.vectorBytes - 1) != 0L)) {
            diagnostics.error(location, "vector size must be a positive power of two")
            return false
        }
        return true
    }

    private fun validAlignment(value: Long): Boolean = value > 0 && value and (value - 1) == 0L
}
