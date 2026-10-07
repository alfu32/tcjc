package org.tinycc.core

import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.tinycc.backends.x86.X86Instruction
import org.tinycc.backends.x86.X86LinuxElf64
import org.tinycc.backends.x86.X86MachineCodeEncoder
import org.tinycc.backends.x86.X86MachineBlock
import org.tinycc.backends.x86.X86MachineFunction
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
    fun encodesRegisterBasedAndAbsoluteMemoryOperands() {
        val x64 = X86MachineCodeEncoder(X86Mode.X86_64)
        val r = { name: String -> physical(X86Mode.X86_64, name) }
        val encoded = x64.encode(
            listOf(
                X86Instruction(X86Opcode.MOV, listOf(r("rax"), r("rbx"))),
                X86Instruction(X86Opcode.ADD, listOf(r("rax"), r("rbx"))),
                X86Instruction(X86Opcode.SUB, listOf(r("rax"), r("rbx"))),
                X86Instruction(X86Opcode.MOV, listOf(X86Operand.Memory(r("rbp").value, -8), r("rax"))),
                X86Instruction(X86Opcode.MOV, listOf(r("rax"), X86Operand.Memory(r("rbp").value, -8))),
                X86Instruction(X86Opcode.ADD, listOf(X86Operand.Memory(r("rsp").value, 16), r("rbx"))),
                X86Instruction(X86Opcode.ADD, listOf(r("rax"), X86Operand.Memory(r("r12").value, 128))),
                X86Instruction(X86Opcode.SUB, listOf(X86Operand.Memory(r("r13").value), r("r8"))),
                X86Instruction(X86Opcode.MOV, listOf(X86Operand.Memory(r("rbp").value, -8), X86Operand.Immediate(1))),
                X86Instruction(X86Opcode.MOV, listOf(r("rax"), X86Operand.Memory(displacement = 0x12345678))),
            ),
        )
        assertContentEquals(
            byteArrayOf(
                0x48, 0x89.toByte(), 0xD8.toByte(),
                0x48, 0x01, 0xD8.toByte(),
                0x48, 0x29, 0xD8.toByte(),
                0x48, 0x89.toByte(), 0x45, 0xF8.toByte(),
                0x48, 0x8B.toByte(), 0x45, 0xF8.toByte(),
                0x48, 0x01, 0x5C, 0x24, 0x10,
                0x49, 0x03, 0x84.toByte(), 0x24, 0x80.toByte(), 0, 0, 0,
                0x4D, 0x29, 0x45, 0,
                0x48, 0xC7.toByte(), 0x45, 0xF8.toByte(), 1, 0, 0, 0,
                0x48, 0x8B.toByte(), 0x04, 0x25, 0x78, 0x56, 0x34, 0x12,
            ),
            encoded,
        )

        val i386 = X86MachineCodeEncoder(X86Mode.I386)
        val eax = physical(X86Mode.I386, "eax")
        val ebp = physical(X86Mode.I386, "ebp")
        assertContentEquals(
            byteArrayOf(0x8B.toByte(), 0x45, 0xFC.toByte()),
            i386.encode(listOf(X86Instruction(X86Opcode.MOV, listOf(eax, X86Operand.Memory(ebp.value, -4))))),
        )
    }

    @Test
    fun encodesLeaForI386AndExtendedX8664AddressRegisters() {
        val x64 = X86MachineCodeEncoder(X86Mode.X86_64)
        val r64 = { name: String -> physical(X86Mode.X86_64, name) }
        assertContentEquals(
            byteArrayOf(
                0x48, 0x8D.toByte(), 0x45, 0xF8.toByte(),
                0x4D, 0x8D.toByte(), 0x84.toByte(), 0x24, 0x80.toByte(), 0, 0, 0,
            ),
            x64.encode(
                listOf(
                    X86Instruction(X86Opcode.LEA, listOf(r64("rax"), X86Operand.Memory(r64("rbp").value, -8))),
                    X86Instruction(X86Opcode.LEA, listOf(r64("r8"), X86Operand.Memory(r64("r12").value, 128))),
                ),
            ),
        )

        val i386 = X86MachineCodeEncoder(X86Mode.I386)
        val eax = physical(X86Mode.I386, "eax")
        val ebp = physical(X86Mode.I386, "ebp")
        assertContentEquals(
            byteArrayOf(0x8D.toByte(), 0x45, 0xFC.toByte()),
            i386.encode(listOf(X86Instruction(X86Opcode.LEA, listOf(eax, X86Operand.Memory(ebp.value, -4))))),
        )
        assertFailsWith<IllegalArgumentException> {
            i386.encode(listOf(X86Instruction(X86Opcode.LEA, listOf(r64("rax"), X86Operand.Memory()))))
        }
    }

    @Test
    fun encodesTestRegisterAndFullWidthImmediateForms() {
        val x64 = X86MachineCodeEncoder(X86Mode.X86_64)
        val r64 = { name: String -> physical(X86Mode.X86_64, name) }
        assertContentEquals(
            byteArrayOf(
                0x48, 0x85.toByte(), 0xD8.toByte(),
                0x4C, 0x85.toByte(), 0x45, 0xF8.toByte(),
                0x48, 0xF7.toByte(), 0xC0.toByte(), 0x78, 0x56, 0x34, 0x12,
                0x49, 0xF7.toByte(), 0x44, 0x24, 8, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            ),
            x64.encode(
                listOf(
                    X86Instruction(X86Opcode.TEST, listOf(r64("rax"), r64("rbx"))),
                    X86Instruction(X86Opcode.TEST, listOf(X86Operand.Memory(r64("rbp").value, -8), r64("r8"))),
                    X86Instruction(X86Opcode.TEST, listOf(r64("rax"), X86Operand.Immediate(0x12345678))),
                    X86Instruction(
                        X86Opcode.TEST,
                        listOf(X86Operand.Memory(r64("r12").value, 8), X86Operand.Immediate(-1)),
                    ),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            x64.encode(listOf(X86Instruction(X86Opcode.TEST, listOf(r64("rax"), X86Operand.Immediate(0x80000000L)))))
        }

        val i386 = X86MachineCodeEncoder(X86Mode.I386)
        val eax = physical(X86Mode.I386, "eax")
        assertContentEquals(
            byteArrayOf(0xF7.toByte(), 0xC0.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            i386.encode(listOf(X86Instruction(X86Opcode.TEST, listOf(eax, X86Operand.Immediate(0xFFFF_FFFFL))))),
        )
    }

    @Test
    fun encodesIntegerArithmeticAndComparisonImmediates() {
        val x64 = X86MachineCodeEncoder(X86Mode.X86_64)
        val r = { name: String -> physical(X86Mode.X86_64, name) }
        assertContentEquals(
            byteArrayOf(
                0x48, 0x83.toByte(), 0xC0.toByte(), 5,
                0x48, 0x83.toByte(), 0xF8.toByte(), 0,
                0x48, 0x81.toByte(), 0x65, 0xF8.toByte(), 0x78, 0x56, 0x34, 0x12,
                0x49, 0x81.toByte(), 0xC9.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0x49, 0x83.toByte(), 0x74, 0x24, 8, 0xFF.toByte(),
            ),
            x64.encode(
                listOf(
                    X86Instruction(X86Opcode.ADD, listOf(r("rax"), X86Operand.Immediate(5))),
                    X86Instruction(X86Opcode.CMP, listOf(r("rax"), X86Operand.Immediate(0))),
                    X86Instruction(
                        X86Opcode.AND,
                        listOf(X86Operand.Memory(r("rbp").value, -8), X86Operand.Immediate(0x12345678)),
                    ),
                    X86Instruction(X86Opcode.OR, listOf(r("r9"), X86Operand.Immediate(-129))),
                    X86Instruction(
                        X86Opcode.XOR,
                        listOf(X86Operand.Memory(r("r12").value, 8), X86Operand.Immediate(-1)),
                    ),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            x64.encode(listOf(X86Instruction(X86Opcode.SUB, listOf(r("rax"), X86Operand.Immediate(0x80000000L)))))
        }

        val i386 = X86MachineCodeEncoder(X86Mode.I386)
        val eax = physical(X86Mode.I386, "eax")
        assertContentEquals(
            byteArrayOf(0x81.toByte(), 0xE8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            i386.encode(listOf(X86Instruction(X86Opcode.SUB, listOf(eax, X86Operand.Immediate(0xFFFF_FFFFL))))),
        )
    }

    @Test
    fun resolvesForwardAndBackwardRelativeBranches() {
        val encoder = X86MachineCodeEncoder(X86Mode.X86_64)
        assertContentEquals(
            byteArrayOf(
                0x0F, 0x85.toByte(), 5, 0, 0, 0,
                0xE9.toByte(), 0xF5.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            ),
            encoder.encode(
                listOf(
                    X86Instruction(X86Opcode.LABEL, listOf(X86Operand.Label("entry"))),
                    X86Instruction(X86Opcode.JNE, listOf(X86Operand.Label("exit"))),
                    X86Instruction(X86Opcode.JMP, listOf(X86Operand.Label("entry"))),
                    X86Instruction(X86Opcode.LABEL, listOf(X86Operand.Label("exit"))),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> {
            encoder.encode(listOf(X86Instruction(X86Opcode.JMP, listOf(X86Operand.Label("missing")))))
        }
        assertFailsWith<IllegalArgumentException> {
            encoder.encode(
                listOf(
                    X86Instruction(X86Opcode.LABEL, listOf(X86Operand.Label("same"))),
                    X86Instruction(X86Opcode.LABEL, listOf(X86Operand.Label("same"))),
                ),
            )
        }

        val selectedFunction = X86MachineFunction(
            "branching",
            X86Mode.X86_64,
            listOf(
                X86MachineBlock(
                    "entry",
                    listOf(
                        X86Instruction(X86Opcode.JNE, listOf(X86Operand.Label("exit"))),
                        X86Instruction(X86Opcode.JMP, listOf(X86Operand.Label("entry"))),
                    ),
                ),
                X86MachineBlock("exit", listOf(X86Instruction(X86Opcode.RET))),
            ),
            emptySet(),
        )
        assertContentEquals(
            byteArrayOf(
                0x0F, 0x85.toByte(), 5, 0, 0, 0,
                0xE9.toByte(), 0xF5.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xC3.toByte(),
            ),
            encoder.encode(selectedFunction),
        )
    }

    @Test
    fun encodesEveryNearConditionalBranchConditionForBothModes() {
        val conditions = listOf(
            X86Opcode.JO to 0x0,
            X86Opcode.JNO to 0x1,
            X86Opcode.JB to 0x2,
            X86Opcode.JAE to 0x3,
            X86Opcode.JE to 0x4,
            X86Opcode.JNE to 0x5,
            X86Opcode.JBE to 0x6,
            X86Opcode.JA to 0x7,
            X86Opcode.JS to 0x8,
            X86Opcode.JNS to 0x9,
            X86Opcode.JP to 0xA,
            X86Opcode.JNP to 0xB,
            X86Opcode.JL to 0xC,
            X86Opcode.JGE to 0xD,
            X86Opcode.JLE to 0xE,
            X86Opcode.JG to 0xF,
        )
        listOf(X86Mode.I386, X86Mode.X86_64).forEach { mode ->
            val encoder = X86MachineCodeEncoder(mode)
            conditions.forEach { (opcode, condition) ->
                assertContentEquals(
                    byteArrayOf(0x0F, (0x80 + condition).toByte(), 0, 0, 0, 0),
                    encoder.encode(
                        listOf(
                            X86Instruction(opcode, listOf(X86Operand.Label("next"))),
                            X86Instruction(X86Opcode.LABEL, listOf(X86Operand.Label("next"))),
                        ),
                    ),
                    "$mode $opcode",
                )
            }
        }
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

    private fun physical(mode: X86Mode, name: String): X86Operand.Register = X86Operand.Register(
        X86RegisterRef.Physical(X86Registers.bank(mode).find(name)!!),
    )
}
