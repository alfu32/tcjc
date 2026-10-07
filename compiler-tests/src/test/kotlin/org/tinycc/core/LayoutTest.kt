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
import org.tinycc.core.types.TypeLayout

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
}
