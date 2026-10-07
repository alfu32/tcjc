package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
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
