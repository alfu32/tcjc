package org.tinycc.backends.x86

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempFile
import org.tinycc.core.ir.IrRegister

/** Encodes the register/immediate subset used by the backend smoke and parity fixtures. */
class X86MachineCodeEncoder(private val mode: X86Mode) {
    private data class ZeroOperandEncoding(val bytes: List<Int>, val mode: X86Mode? = null)

    private val zeroOperandEncodings = mapOf(
        X86Opcode.RET to ZeroOperandEncoding(listOf(0xC3)),
        X86Opcode.SYSCALL to ZeroOperandEncoding(listOf(0x0F, 0x05), X86Mode.X86_64),
        X86Opcode.UD2 to ZeroOperandEncoding(listOf(0x0F, 0x0B)),
        X86Opcode.NOP to ZeroOperandEncoding(listOf(0x90)),
        X86Opcode.PAUSE to ZeroOperandEncoding(listOf(0xF3, 0x90)),
        X86Opcode.FWAIT to ZeroOperandEncoding(listOf(0x9B)),
        X86Opcode.CLC to ZeroOperandEncoding(listOf(0xF8)),
        X86Opcode.CLD to ZeroOperandEncoding(listOf(0xFC)),
        X86Opcode.CLI to ZeroOperandEncoding(listOf(0xFA)),
        X86Opcode.CLTS to ZeroOperandEncoding(listOf(0x0F, 0x06)),
        X86Opcode.CMC to ZeroOperandEncoding(listOf(0xF5)),
        X86Opcode.STC to ZeroOperandEncoding(listOf(0xF9)),
        X86Opcode.STD to ZeroOperandEncoding(listOf(0xFD)),
        X86Opcode.STI to ZeroOperandEncoding(listOf(0xFB)),
        X86Opcode.HLT to ZeroOperandEncoding(listOf(0xF4)),
        X86Opcode.INT3 to ZeroOperandEncoding(listOf(0xCC)),
        X86Opcode.INTO to ZeroOperandEncoding(listOf(0xCE), X86Mode.I386),
        X86Opcode.CPUID to ZeroOperandEncoding(listOf(0x0F, 0xA2)),
        X86Opcode.RDTSC to ZeroOperandEncoding(listOf(0x0F, 0x31)),
        X86Opcode.RDMSR to ZeroOperandEncoding(listOf(0x0F, 0x32)),
        X86Opcode.WRMSR to ZeroOperandEncoding(listOf(0x0F, 0x30)),
        X86Opcode.RDPMC to ZeroOperandEncoding(listOf(0x0F, 0x33)),
        X86Opcode.INVD to ZeroOperandEncoding(listOf(0x0F, 0x08)),
        X86Opcode.WBINVD to ZeroOperandEncoding(listOf(0x0F, 0x09)),
        X86Opcode.SYSRET to ZeroOperandEncoding(listOf(0x0F, 0x07), X86Mode.X86_64),
        X86Opcode.MFENCE to ZeroOperandEncoding(listOf(0x0F, 0xAE, 0xF0), X86Mode.X86_64),
        X86Opcode.LFENCE to ZeroOperandEncoding(listOf(0x0F, 0xAE, 0xE8), X86Mode.X86_64),
        X86Opcode.SFENCE to ZeroOperandEncoding(listOf(0x0F, 0xAE, 0xF8), X86Mode.X86_64),
        X86Opcode.ENDBR32 to ZeroOperandEncoding(listOf(0xF3, 0x0F, 0x1E, 0xFB), X86Mode.I386),
        X86Opcode.ENDBR64 to ZeroOperandEncoding(listOf(0xF3, 0x0F, 0x1E, 0xFA), X86Mode.X86_64),
    )

    fun encode(instructions: List<X86Instruction>): ByteArray {
        val output = ArrayList<Byte>()
        instructions.forEach { instruction -> encodeInstruction(instruction, output) }
        return output.toByteArray()
    }

    private fun encodeInstruction(instruction: X86Instruction, output: MutableList<Byte>) {
        if (instruction.operands.isEmpty()) {
            val encoding = zeroOperandEncodings[instruction.opcode]
            if (encoding != null) {
                require(encoding.mode == null || encoding.mode == mode) {
                    "${instruction.opcode} is unavailable in $mode"
                }
                output += encoding.bytes.map(Int::toByte)
                return
            }
        }
        when (instruction.opcode) {
            X86Opcode.MOV -> encodeMov(instruction.operands, output)
            X86Opcode.ADD -> encodeBinary(instruction.operands, output, 0x01)
            X86Opcode.SUB -> encodeBinary(instruction.operands, output, 0x29)
            X86Opcode.PUSH -> encodeStackRegister(instruction.operands, output, push = true)
            X86Opcode.POP -> encodeStackRegister(instruction.operands, output, push = false)
            else -> error("machine-code encoder does not support ${instruction.opcode}")
        }
    }

    private fun encodeMov(operands: List<X86Operand>, output: MutableList<Byte>) {
        require(operands.size == 2) { "mov requires two operands" }
        val destination = physicalRegister(operands[0])
        when (val source = operands[1]) {
            is X86Operand.Immediate -> {
                if (mode == X86Mode.X86_64) {
                    rex(output, w = true, register = destination.number)
                    output += (0xB8 + (destination.number and 7)).toByte()
                    appendLong(output, source.value)
                } else {
                    require(destination.bits == 32) { "i386 immediate mov requires a 32-bit register" }
                    output += (0xB8 + destination.number).toByte()
                    appendInt(output, source.value.toInt())
                }
            }
            is X86Operand.Register -> {
                val sourceRegister = physicalRegister(source)
                rex(output, w = mode == X86Mode.X86_64, register = sourceRegister.number, base = destination.number)
                output += 0x89.toByte()
                output += modRm(3, sourceRegister.number, destination.number)
            }
            else -> error("encoder only supports register and immediate mov sources")
        }
    }

    private fun encodeBinary(operands: List<X86Operand>, output: MutableList<Byte>, opcode: Int) {
        require(operands.size == 2) { "binary operation requires two operands" }
        val destination = physicalRegister(operands[0])
        val source = physicalRegister(operands[1])
        rex(output, w = mode == X86Mode.X86_64, register = source.number, base = destination.number)
        output += opcode.toByte()
        output += modRm(3, source.number, destination.number)
    }

    private fun encodeStackRegister(operands: List<X86Operand>, output: MutableList<Byte>, push: Boolean) {
        require(operands.size == 1) { "stack register operation requires one operand" }
        val register = physicalRegister(operands.single())
        if (mode == X86Mode.X86_64 && register.number >= 8) output += 0x41.toByte()
        output += ((if (push) 0x50 else 0x58) + (register.number and 7)).toByte()
    }

    private fun physicalRegister(operand: X86Operand): IrRegister = when (operand) {
        is X86Operand.Register -> when (val reference = operand.value) {
            is X86RegisterRef.Physical -> reference.value
            is X86RegisterRef.Virtual -> error("machine-code encoding requires allocated registers")
        }
        else -> error("expected register operand, got $operand")
    }

    private fun rex(
        output: MutableList<Byte>,
        w: Boolean = false,
        register: Int = 0,
        base: Int = 0,
    ) {
        if (mode != X86Mode.X86_64) return
        val value = 0x40 or (if (w) 8 else 0) or (if (register >= 8) 4 else 0) or (if (base >= 8) 1 else 0)
        if (value != 0x40) output += value.toByte()
    }

    private fun modRm(mode: Int, register: Int, base: Int): Byte =
        ((mode shl 6) or ((register and 7) shl 3) or (base and 7)).toByte()

    private fun appendInt(output: MutableList<Byte>, value: Int) {
        repeat(4) { index -> output += (value ushr (index * 8)).toByte() }
    }

    private fun appendLong(output: MutableList<Byte>, value: Long) {
        repeat(8) { index -> output += (value ushr (index * 8)).toByte() }
    }
}

/** Minimal ELF64 linker image for executing a Kotlin-generated x86_64 code smoke fixture. */
object X86LinuxElf64 {
    private const val HEADER_SIZE = 64
    private const val PROGRAM_HEADER_SIZE = 56
    private const val BASE_ADDRESS = 0x400000L

    fun image(code: ByteArray): ByteArray {
        val codeOffset = HEADER_SIZE + PROGRAM_HEADER_SIZE
        val totalSize = codeOffset + code.size
        val header = ByteBuffer.allocate(codeOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        header.putShort(2)
        header.putShort(62)
        header.putInt(1)
        header.putLong(BASE_ADDRESS + codeOffset)
        header.putLong(HEADER_SIZE.toLong())
        header.putLong(0)
        header.putInt(0)
        header.putShort(HEADER_SIZE.toShort())
        header.putShort(PROGRAM_HEADER_SIZE.toShort())
        header.putShort(1)
        header.putShort(0)
        header.putShort(0)
        header.putShort(0)
        header.putInt(1)
        header.putInt(5)
        header.putLong(0)
        header.putLong(BASE_ADDRESS)
        header.putLong(BASE_ADDRESS)
        header.putLong(totalSize.toLong())
        header.putLong(totalSize.toLong())
        header.putLong(0x1000)
        return header.array() + code
    }

    fun writeExecutable(code: ByteArray, path: Path): Path {
        Files.write(path, image(code))
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
        return path
    }

    fun runExitCode(exitCode: Int): Int {
        val instructions = listOf(
            X86Instruction(X86Opcode.MOV, listOf(physical("rax"), X86Operand.Immediate(60))),
            X86Instruction(X86Opcode.MOV, listOf(physical("rdi"), X86Operand.Immediate(exitCode.toLong()))),
            X86Instruction(X86Opcode.SYSCALL),
        )
        val code = X86MachineCodeEncoder(X86Mode.X86_64).encode(instructions)
        val path = createTempFile(prefix = "tcjc-x86-smoke-", suffix = ".elf")
        writeExecutable(code, path)
        return try {
            ProcessBuilder(path.toString()).inheritIO().start().waitFor()
        } finally {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private fun physical(name: String): X86Operand.Register = X86Operand.Register(
        X86RegisterRef.Physical(X86Registers.bank(X86Mode.X86_64).find(name)!!),
    )
}
