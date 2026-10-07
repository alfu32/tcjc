package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.runtime.CompilerRuntime
import org.tinycc.runtime.KotlinRuntimeLinker
import org.tinycc.runtime.RuntimeLinkMode
import org.tinycc.runtime.RuntimeLinkOptions
import org.tinycc.runtime.RuntimeObject

class RuntimeTest {
    @Test
    fun providesPureKotlinCompilerSupportArithmeticAndBounds() {
        assertEquals(5L, CompilerRuntime.adddi3(2, 3))
        assertEquals(2L, CompilerRuntime.udivdi3(4L, 2L))
        assertEquals(1L, CompilerRuntime.umoddi3(3L, 2L))
        CompilerRuntime.boundsCheck(1, 2)
        assertFailsWith<IllegalArgumentException> { CompilerRuntime.boundsCheck(2, 2) }
    }

    @Test
    fun linksStaticSharedPicAndCrossTargetImagesWithoutNativeDependencies() {
        val objectFile = RuntimeObject("libtcc1-kotlin", mapOf("__divdi3" to byteArrayOf(1, 2, 3)))
        val linker = KotlinRuntimeLinker()
        RuntimeLinkMode.values().forEach { mode ->
            val image = linker.link(listOf(objectFile), RuntimeLinkOptions(mode, "x86_64-linux", "runtime"))
            assertEquals(emptyList(), image.nativeDependencies)
            assertTrue(image.bytes.decodeToString().contains("mode=$mode"))
        }
    }

    @Test
    fun rejectsNativeRuntimeDependencies() {
        assertFailsWith<IllegalArgumentException> {
            KotlinRuntimeLinker().link(
                listOf(RuntimeObject("bad", emptyMap(), nativeDependencies = listOf("libtcc1.so"))),
                RuntimeLinkOptions(RuntimeLinkMode.SHARED, "x86_64-linux", "bad"),
            )
        }
    }
}
