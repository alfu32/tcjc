package org.tinycc.core

import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.DeclarationAttributes
import org.tinycc.core.types.EnumConstant
import org.tinycc.core.types.FunctionDeclaration
import org.tinycc.core.types.PrimitiveKind
import org.tinycc.core.types.RecordKind
import org.tinycc.core.types.StorageClass
import org.tinycc.core.types.TypeQualifiers
import org.tinycc.core.types.TypeAttributes
import org.tinycc.core.types.TypeRules
import org.tinycc.core.types.TypeUse

class TypesTest {
    @Test
    fun buildsAndUnwrapsQualifiedCompositeTypes() {
        val constPointer = CTypes.pointer(
            CTypes.qualified(CTypes.int, TypeQualifiers(isConst = true)),
            TypeQualifiers(isRestrict = true),
        )
        val array = CTypes.arrayOf(constPointer, 4)
        val alias = CTypes.typedef("IntPointers", array)

        assertSame(array, CTypes.unalias(alias))
        assertTrue(CTypes.isComplete(alias))
        assertFalse(CTypes.isVariablyModified(alias))
        assertTrue(CTypes.compatible(alias, CTypes.arrayOf(constPointer, 4)))
    }

    @Test
    fun modelsVlasFunctionsRecordsAndEnums() {
        val vla = CTypes.variableArrayOf(CTypes.double, "count")
        val function = CTypes.function(CTypes.pointer(CTypes.int), listOf(vla), variadic = true)
        val record = CType.Record(RecordKind.STRUCT, "Point")
        record.completeWith(listOf(
            org.tinycc.core.types.Field("x", CTypes.int),
            org.tinycc.core.types.Field("y", CTypes.int),
        ))
        val enumeration = CType.Enumeration("Color")
        enumeration.completeWith(listOf(EnumConstant("RED", 0), EnumConstant("BLUE", 1)))

        assertTrue(CTypes.isVariablyModified(vla))
        assertTrue(CTypes.isVariablyModified(function))
        assertTrue(CTypes.isComplete(record))
        assertTrue(CTypes.isComplete(enumeration))
        assertFalse(CTypes.compatible(record, CType.Record(RecordKind.STRUCT, "Point")))
    }

    @Test
    fun declarationDescriptorsRetainStorageAndFunctionShape() {
        val functionType = CTypes.function(CTypes.int, listOf(CTypes.pointer(CTypes.char))) as CType.Function
        val declaration = FunctionDeclaration(
            name = "main",
            type = functionType,
            attributes = DeclarationAttributes(storage = StorageClass.EXTERN),
        )

        assertTrue(declaration.type.parameters.single().type is CType.Pointer)
        assertTrue(declaration.attributes.storage == StorageClass.EXTERN)
        assertTrue(PrimitiveKind.INT == (declaration.type.returnType as CType.Primitive).kind)
    }

    @Test
    fun coversDeclaratorAttributesParameterAdjustmentAndTypeConstraints() {
        val array = CTypes.arrayOf(
            CTypes.int,
            8,
            qualifiers = TypeQualifiers(isConst = true),
            isStaticParameter = true,
        )
        val function = CTypes.functionOf(
            CTypes.void,
            listOf(CType.Parameter("values", array)),
            callingConvention = org.tinycc.core.types.CallingConvention.SYSV64,
            attributes = TypeAttributes(aligned = 16, nonnull = true),
        ) as CType.Function

        assertTrue(function.parameters.single().type is CType.Pointer)
        assertTrue(function.parameters.single().type.let { it as CType.Pointer }.pointee is CType.Primitive)
        assertTrue(CTypes.unalias(CTypes.annotated(CTypes.int, TypeAttributes(packed = true))) is CType.Qualified)
        assertTrue(CTypes.compatible(function, function.copy(parameters = function.parameters)))

        val diagnostics = org.tinycc.core.diagnostics.DiagnosticEngine()
        val rules = TypeRules(diagnostics)
        val invalid = CType.Record(RecordKind.STRUCT, "Invalid")
        invalid.completeWith(listOf(
            org.tinycc.core.types.Field("tail", CType.Array(CTypes.int, ArrayBound.Flexible)),
            org.tinycc.core.types.Field("after", CTypes.int),
        ))
        assertTrue(!rules.validate(invalid, TypeUse.TYPEDEF))
        assertTrue(diagnostics.render().contains("flexible array must be the last field"))
    }
}
