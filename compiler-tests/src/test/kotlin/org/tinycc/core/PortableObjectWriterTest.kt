package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.portable.CoffExport
import org.tinycc.backends.portable.CoffImport
import org.tinycc.backends.portable.CoffMachine
import org.tinycc.backends.portable.CoffSection
import org.tinycc.backends.portable.CoffSymbol
import org.tinycc.backends.portable.MachCpu
import org.tinycc.backends.portable.MachOObject
import org.tinycc.backends.portable.MachOWriter
import org.tinycc.backends.portable.PeCoffObject
import org.tinycc.backends.portable.PeCoffWriter
import org.tinycc.backends.portable.WindowsRuntimePolicy

class PortableObjectWriterTest {
    @Test
    fun writesPeCoffWithImportExportAndSymbols() {
        val bytes = PeCoffWriter().write(
            PeCoffObject(
                CoffMachine.X86_64,
                listOf(CoffSection(".text", byteArrayOf(0xC3.toByte()), 0x60000020)),
                listOf(CoffSymbol("entry")),
                imports = listOf(CoffImport("kernel32.dll", "ExitProcess")),
                exports = listOf(CoffExport("entry", 1, "entry")),
            ),
        )
        assertEquals(0x64, bytes[0].toInt() and 0xFF)
        assertEquals(0x86, bytes[1].toInt() and 0xFF)
        assertTrue(bytes.decodeToString().contains("kernel32.dll"))
        assertTrue(bytes.decodeToString().contains("entry"))
    }

    @Test
    fun writesMachO64HeaderAndStablePayload() {
        val objectFile = MachOObject(MachCpu.ARM64, listOf(CoffSection("__text", byteArrayOf(1, 2), 0)), listOf())
        val first = MachOWriter().write(objectFile)
        val second = MachOWriter().write(objectFile)
        assertTrue(first.contentEquals(second))
        assertEquals(0xCF, first[0].toInt() and 0xFF)
        assertEquals(0xFA, first[1].toInt() and 0xFF)
        assertTrue(first.decodeToString().contains("section:__text"))
    }

    @Test
    fun exposesWindowsRuntimeIntegrationPolicy() {
        val policy = WindowsRuntimePolicy()
        assertEquals("DllMainCRTStartup", policy.dllEntryPoint)
        assertTrue(policy.usesSeh)
        assertEquals(".lib", policy.importLibraryExtension)
    }
}
