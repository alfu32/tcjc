package org.tinycc.core

import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.tinycc.core.collections.ByteSink
import org.tinycc.core.collections.DynamicArray
import org.tinycc.core.collections.ObjectArena
import org.tinycc.core.collections.OpenAddressHashTable
import org.tinycc.core.collections.StringInterner
import org.tinycc.core.diagnostics.CollectingDiagnosticSink
import org.tinycc.core.diagnostics.DiagnosticEngine
import org.tinycc.core.diagnostics.IncludeStack
import org.tinycc.core.diagnostics.LineMap
import org.tinycc.core.diagnostics.SourceLocation

class CoreUtilitiesTest {
    @Test
    fun dynamicArrayGrowsAndPreservesOrder() {
        val values = DynamicArray<Int>(1)
        values.add(1)
        values.add(3)
        values.add(1, 2)

        assertEquals(listOf(1, 2, 3), values.toList())
        assertEquals(2, values.removeAt(1))
        assertEquals(listOf(1, 3), values.toList())
    }

    @Test
    fun byteSinkWritesAndPatchesLittleEndianValues() {
        val bytes = ByteSink(1)
        bytes.appendIntLE(0x12345678)
        bytes.appendLongLE(0x0102030405060708L)
        bytes.patchIntLE(0, 0xAABBCCDD.toInt())

        assertContentEquals(
            byteArrayOf(
                0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(),
                0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
            ),
            bytes.toByteArray(),
        )
    }

    @Test
    fun hashTableUpdatesRemovesAndRehashes() {
        val table = OpenAddressHashTable<Int, String>(4)
        repeat(100) { table.put(it, "value-$it") }
        assertEquals(100, table.size)
        assertEquals("value-50", table.put(50, "updated"))
        assertEquals(100, table.size)
        assertEquals("updated", table[50])
        assertEquals("value-3", table.remove(3))
        assertFalse(table.containsKey(3))
        assertEquals(99, table.size)
    }

    @Test
    fun internerReturnsCanonicalInstances() {
        val interner = StringInterner()
        val first = interner.intern("identifier-${String(charArrayOf('x'))}")
        val second = interner.intern("identifier-x")

        assertSame(first, second)
        assertEquals(1, interner.size)
    }

    @Test
    fun arenaTracksResetAndCloseLifetimes() {
        val arena = ObjectArena()
        val value = arena.allocate { StringBuilder("value") }
        assertEquals("value", value.toString())
        arena.reset()
        arena.close()
        assertTrue(runCatching { arena.allocate { Any() } }.isFailure)
    }

    @Test
    fun lineMapAndDiagnosticsUseStableLocations() {
        val path = Path.of("sample.c").toAbsolutePath()
        val location = LineMap("first\nsecond\nthird").locationAt(path, 7)
        val sink = CollectingDiagnosticSink()
        val diagnostics = DiagnosticEngine(sink)
        diagnostics.error(location, "unexpected token")

        assertEquals(2, location.line)
        assertEquals(2, location.column)
        assertEquals("${path}:2:2: error: unexpected token", diagnostics.render())
        assertEquals(1, sink.diagnostics().size)
    }

    @Test
    fun includeStackIsScopedAndIncludedInDiagnosticTrace() {
        val stack = IncludeStack()
        val root = Path.of("root.c").toAbsolutePath()
        val child = Path.of("child.h").toAbsolutePath()
        val location = SourceLocation(root, line = 4, column = 3)
        val diagnostic = DiagnosticEngine()

        stack.withFrame(root) {
            stack.withFrame(child, location) {
                assertTrue(stack.contains(root))
                assertTrue(stack.contains(child))
                diagnostic.error(location, "bad include", stack.snapshot())
            }
            assertEquals(1, stack.depth)
        }

        assertEquals(0, stack.depth)
        assertTrue(diagnostic.render().contains("included from $root"))
        assertTrue(diagnostic.render().contains("included from $child:4:3"))
    }
}
