package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.runtime.CompilerRuntime
import org.tinycc.runtime.RuntimeCheckKind
import org.tinycc.runtime.RuntimeDiagnostic
import org.tinycc.runtime.RuntimeDebugInfo
import org.tinycc.runtime.RuntimeInstrumentation
import org.tinycc.runtime.RuntimeInstrumentationConfig
import org.tinycc.runtime.KotlinRuntimeLinker
import org.tinycc.runtime.RuntimeLinkMode
import org.tinycc.runtime.RuntimeLinkOptions
import org.tinycc.runtime.RuntimeObject
import org.tinycc.runtime.RuntimeProfileEvent
import org.tinycc.runtime.RuntimeSanitizerException
import org.tinycc.runtime.RuntimeSourceLocation

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

    @Test
    fun reportsBoundsFailuresWithDebugLocationsBacktracesAndProfileEvents() {
        val location = RuntimeSourceLocation("sample.c", 12, 4, "main")
        val diagnostics = mutableListOf<RuntimeDiagnostic>()
        val events = mutableListOf<RuntimeProfileEvent>()
        val config = RuntimeInstrumentationConfig(
            maxBacktraceFrames = 8,
            debugInfo = RuntimeDebugInfo("sample", mapOf("main" to location)),
            diagnosticSink = diagnostics::add,
            profiler = events::add,
        )

        val error = assertFailsWith<RuntimeSanitizerException> {
            RuntimeInstrumentation.scoped(config) {
                RuntimeInstrumentation.traceFunction("main") {
                    CompilerRuntime.boundsCheck(2, 2, location)
                }
            }
        }

        assertEquals(RuntimeCheckKind.BOUNDS, error.diagnostic.kind)
        assertEquals(location, error.diagnostic.location)
        assertTrue(error.diagnostic.backtrace.isNotEmpty())
        assertEquals(listOf(error.diagnostic), diagnostics)
        assertTrue(events.any { it is RuntimeProfileEvent.FunctionEntered && it.location == location })
        assertTrue(events.any { it is RuntimeProfileEvent.FunctionExited && it.location == location })
        assertTrue(events.any { it is RuntimeProfileEvent.DiagnosticRaised })
        assertTrue(error.message!!.contains("sample.c:12:4 in main"))
    }

    @Test
    fun disablesBacktracesForSanitizerFriendlyMachineReadableReports() {
        val diagnostics = mutableListOf<RuntimeDiagnostic>()
        val location = RuntimeSourceLocation("stack.c", 3)
        val error = assertFailsWith<RuntimeSanitizerException> {
            RuntimeInstrumentation.scoped(
                RuntimeInstrumentationConfig(captureBacktrace = false, diagnosticSink = diagnostics::add),
            ) {
                CompilerRuntime.stackProbe(-1, location)
            }
        }
        assertEquals(RuntimeCheckKind.STACK, error.diagnostic.kind)
        assertTrue(error.diagnostic.backtrace.isEmpty())
        assertEquals("stack.c:3:1", error.diagnostic.location.toString())
        assertEquals(error.diagnostic, diagnostics.single())
    }
}
