package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.x86.X86Instruction
import org.tinycc.backends.x86.X86LinuxElf64
import org.tinycc.backends.x86.X86MachineCodeEncoder
import org.tinycc.backends.x86.X86Mode
import org.tinycc.backends.x86.X86Opcode
import org.tinycc.backends.x86.X86Operand
import org.tinycc.backends.x86.X86RegisterRef
import org.tinycc.backends.x86.X86Registers

class X86MachineCodeTest {
    @Test
    fun encodesHistoricalZeroOperandInstructionsForBothModes() {
        val x64 = X86MachineCodeEncoder(X86Mode.X86_64)
        val i386 = X86MachineCodeEncoder(X86Mode.I386)
        val cases = listOf(
            X86Opcode.NOP to byteArrayOf(0x90.toByte()),
            X86Opcode.PAUSE to byteArrayOf(0xF3.toByte(), 0x90.toByte()),
            X86Opcode.CLC to byteArrayOf(0xF8.toByte()),
            X86Opcode.CLD to byteArrayOf(0xFC.toByte()),
            X86Opcode.CMC to byteArrayOf(0xF5.toByte()),
            X86Opcode.STC to byteArrayOf(0xF9.toByte()),
            X86Opcode.STD to byteArrayOf(0xFD.toByte()),
            X86Opcode.HLT to byteArrayOf(0xF4.toByte()),
            X86Opcode.INT3 to byteArrayOf(0xCC.toByte()),
            X86Opcode.UD2 to byteArrayOf(0x0F, 0x0B),
            X86Opcode.CPUID to byteArrayOf(0x0F, 0xA2.toByte()),
            X86Opcode.RDTSC to byteArrayOf(0x0F, 0x31),
            X86Opcode.ENDBR32 to byteArrayOf(0xF3.toByte(), 0x0F, 0x1E, 0xFB.toByte()),
        )

        cases.forEach { (opcode, bytes) ->
            assertContentEquals(bytes, i386.encode(listOf(X86Instruction(opcode))), "i386 $opcode")
            if (opcode != X86Opcode.ENDBR32) {
                assertContentEquals(bytes, x64.encode(listOf(X86Instruction(opcode))), "x86_64 $opcode")
            }
        }
        listOf(
            X86Opcode.MFENCE to byteArrayOf(0x0F, 0xAE.toByte(), 0xF0.toByte()),
            X86Opcode.LFENCE to byteArrayOf(0x0F, 0xAE.toByte(), 0xE8.toByte()),
            X86Opcode.SFENCE to byteArrayOf(0x0F, 0xAE.toByte(), 0xF8.toByte()),
        ).forEach { (opcode, bytes) ->
            assertContentEquals(bytes, x64.encode(listOf(X86Instruction(opcode))), "x86_64 $opcode")
            assertFailsWith<IllegalArgumentException> { i386.encode(listOf(X86Instruction(opcode))) }
        }
        assertContentEquals(byteArrayOf(0xCE.toByte()), i386.encode(listOf(X86Instruction(X86Opcode.INTO))))
        assertFailsWith<IllegalArgumentException> { x64.encode(listOf(X86Instruction(X86Opcode.INTO))) }
        assertContentEquals(
            byteArrayOf(0xF3.toByte(), 0x0F, 0x1E, 0xFA.toByte()),
            x64.encode(listOf(X86Instruction(X86Opcode.ENDBR64))),
        )
    }

    @Test
    fun encodesKnownX8664ImmediateExitSequence() {
        val registers = X86Registers.bank(X86Mode.X86_64)
        val physical = { name: String ->
            X86Operand.Register(X86RegisterRef.Physical(registers.find(name)!!))
        }
        val code = X86MachineCodeEncoder(X86Mode.X86_64).encode(
            listOf(
                X86Instruction(X86Opcode.MOV, listOf(physical("rax"), X86Operand.Immediate(60))),
                X86Instruction(X86Opcode.MOV, listOf(physical("rdi"), X86Operand.Immediate(7))),
                X86Instruction(X86Opcode.SYSCALL),
            ),
        )

        assertContentEquals(
            byteArrayOf(
                0x48, 0xB8.toByte(), 60, 0, 0, 0, 0, 0, 0, 0,
                0x48, 0xBF.toByte(), 7, 0, 0, 0, 0, 0, 0, 0,
                0x0F, 0x05,
            ),
            code,
        )
    }

    @Test
    fun constructsRunnableElf64ImageWithoutExternalToolchain() {
        val image = X86LinuxElf64.image(byteArrayOf(0xC3.toByte()))

        assertEquals(0x7F.toByte(), image[0])
        assertEquals('E'.code.toByte(), image[1])
        assertEquals('L'.code.toByte(), image[2])
        assertEquals('F'.code.toByte(), image[3])
        assertEquals(64 + 56 + 1, image.size)
        assertTrue(X86LinuxElf64.runExitCode(7) == 7)
    }
}
