package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.types.AbiMetadataCatalog
import org.tinycc.core.types.ArrayBound
import org.tinycc.core.types.CTypes
import org.tinycc.core.types.CType
import org.tinycc.core.types.Field
import org.tinycc.core.types.RecordKind
import org.tinycc.core.types.TargetDataModels
import org.tinycc.core.types.TypeCompatibilityChecker
import org.tinycc.core.types.TypeAttributes
import org.tinycc.core.types.TypeLayout
import org.tinycc.core.types.TypeRules
import org.tinycc.core.types.TypeUse

class LayoutTest {
    @Test
    fun computesSysvRecordUnionAndArrayLayout() {
        val layout = TypeLayout(TargetDataModels.X86_64_SYSV)
        val record = CType.Record(RecordKind.STRUCT, "Pair")
        record.completeWith(listOf(Field("tag", CTypes.char), Field("value", CTypes.int)))
        val union = CType.Record(RecordKind.UNION, "Value")
        union.completeWith(listOf(Field("integer", CTypes.int), Field("pointer", CTypes.pointer(CTypes.int))))

        val recordLayout = layout.recordLayout(record)!!
        val unionLayout = layout.recordLayout(union)!!

        assertEquals(8, recordLayout.size)
        assertEquals(0, recordLayout.fields[0].offset)
        assertEquals(4, recordLayout.fields[1].offset)
        assertEquals(8, unionLayout.size)
        assertEquals(12, layout.sizeOf(CTypes.arrayOf(CTypes.int, 3)))
        assertNull(layout.sizeOf(CTypes.variableArrayOf(CTypes.int, "n")))
    }

    @Test
    fun exposesStableAbiMetadataAndCompatibilityDiagnostics() {
        val diagnostics = DiagnosticEngine()
        val checker = TypeCompatibilityChecker(diagnostics)

        assertEquals(16, AbiMetadataCatalog.X86_64_SYSV.stackAlignment)
        assertTrue(checker.requireCompatible(CTypes.int, CTypes.int))
        assertTrue(!checker.requireCompatible(CTypes.int, CTypes.pointer(CTypes.int), context = "assignment"))
        assertTrue(!checker.requireComplete(CType.Array(CTypes.int, ArrayBound.Unspecified), context = "parameter"))
        assertTrue(diagnostics.render().contains("incompatible assignment"))
        assertTrue(diagnostics.render().contains("parameter is incomplete"))
    }

    @Test
    fun laysOutBitFieldsFlexibleArraysVectorsAndAllTargetAbis() {
        val layout = TypeLayout(TargetDataModels.X86_64_SYSV)
        val bits = CType.Record(RecordKind.STRUCT, "Bits")
        bits.completeWith(
            listOf(
                Field("first", CTypes.unsignedInt, bitWidth = 3),
                Field("second", CTypes.unsignedInt, bitWidth = 5),
                Field("value", CTypes.int),
            ),
        )
        val flexible = CType.Record(RecordKind.STRUCT, "Buffer")
        flexible.completeWith(listOf(Field("length", CTypes.int), Field("data", CTypes.flexibleArrayOf(CTypes.char))))
        val vector = CTypes.annotated(CTypes.float, TypeAttributes(vectorBytes = 16, aligned = 16))

        val bitLayout = layout.recordLayout(bits)!!
        val flexibleLayout = layout.recordLayout(flexible)!!

        assertEquals(0, bitLayout.fields[0].offset)
        assertEquals(0, bitLayout.fields[0].bitOffset)
        assertEquals(3, bitLayout.fields[1].bitOffset)
        assertEquals(4, bitLayout.fields[2].offset)
        assertEquals(4, flexibleLayout.size)
        assertEquals(16, layout.sizeOf(vector))
        assertEquals(16, layout.alignmentOf(vector))
        assertEquals(16, layout.sizeOf(CTypes.doubleComplex))
        assertEquals(8, layout.alignmentOf(CTypes.doubleComplex))
        assertEquals(TargetDataModels.I386_SYSV.architecture, AbiMetadataCatalog.I386_SYSV.architecture)
        assertEquals(TargetDataModels.ARM_EABI.pointerBytes, AbiMetadataCatalog.ARM_EABI.pointerBytes)
        assertEquals(16, AbiMetadataCatalog.ARM64_AAPCS.stackAlignment)
        assertEquals(16, AbiMetadataCatalog.RISCV64.stackAlignment)
        assertEquals(TargetDataModels.C67_MODEL.pointerBytes, AbiMetadataCatalog.C67.pointerBytes)
    }

    @Test
    fun rejectsInvalidBitFieldTypesAndNamedZeroWidthFields() {
        val diagnostics = DiagnosticEngine()
        val rules = TypeRules(diagnostics)
        val invalid = CType.Record(RecordKind.STRUCT, "InvalidBits")
        invalid.completeWith(
            listOf(
                Field("fraction", CTypes.float, bitWidth = 3),
                Field("namedZero", CTypes.int, bitWidth = 0),
            ),
        )

        assertTrue(!rules.validate(invalid, TypeUse.OBJECT))
        assertTrue(diagnostics.render().contains("bit-field type"))
        assertTrue(diagnostics.render().contains("zero-width"))
    }
}
