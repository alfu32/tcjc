package org.tinycc.backends.x86

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempFile
import org.tinycc.core.ir.IrRegister

/** Encodes the explicitly supported scalar x86 machine-instruction subset. */
class X86MachineCodeEncoder(private val mode: X86Mode) {
    private data class ZeroOperandEncoding(val bytes: List<Int>, val mode: X86Mode? = null)
    private data class BranchFixup(val label: String, val displacementOffset: Int, val nextInstructionOffset: Int)

    private val conditionalBranchCodes = mapOf(
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

    /** Encodes selected basic blocks, materializing each block name as a branch target. */
    fun encode(function: X86MachineFunction): ByteArray = encode(
        function.blocks.flatMap { block ->
            listOf(X86Instruction(X86Opcode.LABEL, listOf(X86Operand.Label(block.name)))) + block.instructions
        },
    )

    fun encode(instructions: List<X86Instruction>): ByteArray {
        val output = ArrayList<Byte>()
        val labels = HashMap<String, Int>()
        val fixups = ArrayList<BranchFixup>()
        instructions.forEach { instruction -> encodeInstruction(instruction, output, labels, fixups) }
        fixups.forEach { fixup ->
            val target = labels[fixup.label] ?: error("undefined x86 code label: ${fixup.label}")
            val displacement = target.toLong() - fixup.nextInstructionOffset
            require(displacement in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "x86 branch target is outside rel32 range: ${fixup.label}"
            }
            val value = displacement.toInt()
            repeat(4) { index -> output[fixup.displacementOffset + index] = (value ushr (index * 8)).toByte() }
        }
        return output.toByteArray()
    }

    private fun encodeInstruction(
        instruction: X86Instruction,
        output: MutableList<Byte>,
        labels: MutableMap<String, Int>,
        fixups: MutableList<BranchFixup>,
    ) {
        if (instruction.opcode == X86Opcode.LABEL) {
            require(instruction.operands.size == 1 && instruction.operands.single() is X86Operand.Label) {
                "label marker requires exactly one label operand"
            }
            val name = (instruction.operands.single() as X86Operand.Label).name
            require(labels.putIfAbsent(name, output.size) == null) { "duplicate x86 code label: $name" }
            return
        }
        val conditionCode = conditionalBranchCodes[instruction.opcode]
        val directLocalCall = instruction.opcode == X86Opcode.CALL &&
            instruction.operands.singleOrNull() is X86Operand.Label
        if (instruction.opcode == X86Opcode.JMP || directLocalCall || conditionCode != null) {
            require(instruction.operands.size == 1 && instruction.operands.single() is X86Operand.Label) {
                "${instruction.opcode.name.lowercase()} requires one code-label operand"
            }
            val label = (instruction.operands.single() as X86Operand.Label).name
            when {
                instruction.opcode == X86Opcode.JMP -> output += 0xE9.toByte()
                directLocalCall -> output += 0xE8.toByte()
                else -> {
                output += 0x0F
                output += (0x80 + conditionCode!!).toByte()
                }
            }
            val displacementOffset = output.size
            repeat(4) { output += 0 }
            fixups += BranchFixup(label, displacementOffset, output.size)
            return
        }
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
            X86Opcode.LEA -> encodeLea(instruction.operands, output)
            X86Opcode.ADD -> encodeBinary(instruction.operands, output, 0x01, 0)
            X86Opcode.OR -> encodeBinary(instruction.operands, output, 0x09, 1)
            X86Opcode.AND -> encodeBinary(instruction.operands, output, 0x21, 4)
            X86Opcode.SUB -> encodeBinary(instruction.operands, output, 0x29, 5)
            X86Opcode.XOR -> encodeBinary(instruction.operands, output, 0x31, 6)
            X86Opcode.CMP -> encodeBinary(instruction.operands, output, 0x39, 7)
            X86Opcode.TEST -> encodeTest(instruction.operands, output)
            X86Opcode.IMUL -> encodeImul(instruction.operands, output)
            X86Opcode.SHL -> encodeShift(instruction.operands, output, 4)
            X86Opcode.SHR -> encodeShift(instruction.operands, output, 5)
            X86Opcode.SAR -> encodeShift(instruction.operands, output, 7)
            X86Opcode.CALL -> encodeIndirectCall(instruction.operands, output)
            X86Opcode.PUSH -> encodeStackOperand(instruction.operands, output, push = true)
            X86Opcode.POP -> encodeStackOperand(instruction.operands, output, push = false)
            else -> error("machine-code encoder does not support ${instruction.opcode}")
        }
    }

    private fun encodeIndirectCall(operands: List<X86Operand>, output: MutableList<Byte>) {
        require(operands.size == 1) { "indirect call requires one register or memory operand" }
        require(operands.single() is X86Operand.Register || operands.single() is X86Operand.Memory) {
            "indirect call requires a register or memory operand; external symbols need relocation support"
        }
        encodeRm(0xFF, 2, operands.single(), output)
    }

    private fun encodeShift(operands: List<X86Operand>, output: MutableList<Byte>, extension: Int) {
        require(operands.size == 2) { "shift requires a register or memory destination and a count" }
        val destination = operands[0]
        if (destination is X86Operand.Register) {
            val register = physicalRegister(destination)
            require(register.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER && register.bits == mode.bits) {
                "shift register width must match ${mode.bits}-bit target mode"
            }
        }
        require(destination is X86Operand.Register || destination is X86Operand.Memory) {
            "shift destination must be a register or memory operand"
        }
        when (val count = operands[1]) {
            is X86Operand.Immediate -> {
                require(count.value in 0L..255L) { "x86 shift immediate must fit 8 bits" }
                val value = count.value.toInt()
                encodeRm(if (value == 1) 0xD1 else 0xC1, extension, destination, output)
                if (value != 1) output += value.toByte()
            }
            is X86Operand.Register -> {
                val register = physicalRegister(count)
                require(register.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER &&
                    register.bits == mode.bits && register.number == 1
                ) { "variable x86 shift count must be held in CX/ECX/RCX (CL)" }
                encodeRm(0xD3, extension, destination, output)
            }
            else -> error("x86 shift count must be an immediate or CL register: $count")
        }
    }

    private fun encodeImul(operands: List<X86Operand>, output: MutableList<Byte>) {
        require(operands.size == 2) { "two-operand imul requires a register destination and source" }
        val destination = physicalRegister(operands[0])
        require(destination.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER && destination.bits == mode.bits) {
            "imul destination width must match ${mode.bits}-bit target mode"
        }
        when (val source = operands[1]) {
            is X86Operand.Register -> {
                val register = physicalRegister(source)
                require(register.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER && register.bits == mode.bits) {
                    "imul source width must match ${mode.bits}-bit target mode"
                }
                encodeRm(listOf(0x0F, 0xAF), destination.number, source, output)
            }
            is X86Operand.Memory -> encodeRm(listOf(0x0F, 0xAF), destination.number, source, output)
            is X86Operand.Immediate -> {
                val immediate = source.value
                require(immediate == immediate.toInt().toLong() ||
                    (mode == X86Mode.I386 && immediate in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL)
                ) { "imul immediate must fit the target's signed 32-bit encoding" }
                val compact = immediate in -128L..127L
                encodeRm(if (compact) 0x6B else 0x69, destination.number, operands[0], output)
                if (compact) output += immediate.toByte() else appendInt(output, immediate.toInt())
            }
            else -> error("unsupported imul source: $source")
        }
    }

    private fun encodeLea(operands: List<X86Operand>, output: MutableList<Byte>) {
        require(operands.size == 2) { "lea requires a register destination and memory source" }
        val destination = physicalRegister(operands[0])
        require(destination.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER && destination.bits == mode.bits) {
            "${mode.bits}-bit lea requires a ${mode.bits}-bit integer destination register"
        }
        require(operands[1] is X86Operand.Memory) { "lea source must be a memory address" }
        encodeRm(0x8D, destination.number, operands[1], output)
    }

    private fun encodeMov(operands: List<X86Operand>, output: MutableList<Byte>) {
        require(operands.size == 2) { "mov requires two operands" }
        when (val destination = operands[0]) {
            is X86Operand.Register -> {
                val destinationRegister = physicalRegister(destination)
                when (val source = operands[1]) {
                    is X86Operand.Immediate -> encodeImmediateMove(destinationRegister, source.value, output)
                    is X86Operand.Register -> encodeRm(0x89, physicalRegister(source).number, destination, output)
                    is X86Operand.Memory -> encodeRm(0x8B, destinationRegister.number, source, output)
                    else -> error("unsupported mov source: $source")
                }
            }
            is X86Operand.Memory -> when (val source = operands[1]) {
                is X86Operand.Register -> encodeRm(0x89, physicalRegister(source).number, destination, output)
                is X86Operand.Immediate -> encodeMemoryImmediate(destination, source.value, output)
                else -> error("unsupported mov source for memory destination: $source")
            }
            else -> error("unsupported mov destination: $destination")
        }
    }

    private fun encodeBinary(operands: List<X86Operand>, output: MutableList<Byte>, opcode: Int, extension: Int) {
        require(operands.size == 2) { "binary operation requires two operands" }
        when (val destination = operands[0]) {
            is X86Operand.Register -> {
                val register = physicalRegister(destination)
                when (val source = operands[1]) {
                    is X86Operand.Register -> encodeRm(opcode, physicalRegister(source).number, destination, output)
                    is X86Operand.Memory -> encodeRm(opcode + 2, register.number, source, output)
                    is X86Operand.Immediate -> encodeArithmeticImmediate(destination, source.value, extension, output)
                    else -> error("unsupported binary source: $source")
                }
            }
            is X86Operand.Memory -> {
                when (val source = operands[1]) {
                    is X86Operand.Register -> encodeRm(opcode, physicalRegister(source).number, destination, output)
                    is X86Operand.Immediate -> encodeArithmeticImmediate(destination, source.value, extension, output)
                    else -> error("unsupported binary source: $source")
                }
            }
            else -> error("unsupported binary destination: $destination")
        }
    }

    private fun encodeArithmeticImmediate(
        destination: X86Operand,
        immediate: Long,
        extension: Int,
        output: MutableList<Byte>,
    ) {
        require(immediate == immediate.toInt().toLong() ||
            (mode == X86Mode.I386 && immediate in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL)
        ) { "x86 arithmetic immediate must fit the target's 32-bit encoding" }
        val useSignedByte = immediate in -128L..127L
        encodeRm(if (useSignedByte) 0x83 else 0x81, extension, destination, output)
        if (useSignedByte) output += immediate.toByte() else appendInt(output, immediate.toInt())
    }

    private fun encodeTest(operands: List<X86Operand>, output: MutableList<Byte>) {
        require(operands.size == 2) { "test requires two operands" }
        val destination = operands[0]
        val source = operands[1]
        when (source) {
            is X86Operand.Register -> {
                val register = physicalRegister(source)
                require(register.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER && register.bits == mode.bits) {
                    "test register width must match ${mode.bits}-bit target mode"
                }
                if (destination is X86Operand.Register) {
                    val destinationRegister = physicalRegister(destination)
                    require(destinationRegister.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER &&
                        destinationRegister.bits == mode.bits
                    ) { "test register width must match ${mode.bits}-bit target mode" }
                }
                require(destination is X86Operand.Register || destination is X86Operand.Memory) {
                    "test destination must be a register or memory operand"
                }
                encodeRm(0x85, register.number, destination, output)
            }
            is X86Operand.Immediate -> {
                val immediate = source.value
                val inRange = immediate == immediate.toInt().toLong() ||
                    (mode == X86Mode.I386 && immediate in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL)
                require(inRange) { "x86 test immediate must fit the target's 32-bit encoding" }
                if (destination is X86Operand.Register) {
                    val register = physicalRegister(destination)
                    require(register.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER && register.bits == mode.bits) {
                        "test register width must match ${mode.bits}-bit target mode"
                    }
                }
                require(destination is X86Operand.Register || destination is X86Operand.Memory) {
                    "test destination must be a register or memory operand"
                }
                encodeRm(0xF7, 0, destination, output)
                appendInt(output, immediate.toInt())
            }
            else -> error("test source must be a register or immediate: $source")
        }
    }

    private fun encodeImmediateMove(destination: IrRegister, immediate: Long, output: MutableList<Byte>) {
        if (mode == X86Mode.X86_64) {
            rex(output, w = true, base = destination.number)
            output += (0xB8 + (destination.number and 7)).toByte()
            appendLong(output, immediate)
        } else {
            require(destination.bits == 32) { "i386 immediate mov requires a 32-bit register" }
            output += (0xB8 + destination.number).toByte()
            appendInt(output, immediate.toInt())
        }
    }

    private fun encodeMemoryImmediate(destination: X86Operand.Memory, immediate: Long, output: MutableList<Byte>) {
        if (mode == X86Mode.X86_64) {
            require(immediate == immediate.toInt().toLong()) { "x86_64 memory mov immediate must fit signed 32 bits" }
        } else {
            require(immediate in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL) { "i386 memory mov immediate must fit 32 bits" }
        }
        encodeRm(0xC7, 0, destination, output)
        appendInt(output, immediate.toInt())
    }

    /** Encodes an instruction with an opcode-extension/register field and a register-or-memory r/m operand. */
    private fun encodeRm(
        opcode: Int,
        registerField: Int,
        operand: X86Operand,
        output: MutableList<Byte>,
        w: Boolean = mode == X86Mode.X86_64,
    ) = encodeRm(listOf(opcode), registerField, operand, output, w)

    private fun encodeRm(
        opcode: List<Int>,
        registerField: Int,
        operand: X86Operand,
        output: MutableList<Byte>,
        w: Boolean = mode == X86Mode.X86_64,
    ) {
        when (operand) {
            is X86Operand.Register -> {
                val base = physicalRegister(operand)
                rex(output, w = w, register = registerField, base = base.number)
                output += opcode.map(Int::toByte)
                output += modRm(3, registerField, base.number)
            }
            is X86Operand.Memory -> encodeMemoryRm(opcode, registerField, operand, output, w)
            else -> error("expected register or memory operand, got $operand")
        }
    }

    private fun encodeMemoryRm(
        opcode: List<Int>,
        registerField: Int,
        memory: X86Operand.Memory,
        output: MutableList<Byte>,
        w: Boolean,
    ) {
        require(memory.symbol == null && memory.relocation == X86RelocationSyntax.DIRECT) {
            "symbolic memory operands require relocation support"
        }
        val base = memory.base?.let { reference ->
            when (reference) {
                is X86RegisterRef.Physical -> reference.value
                is X86RegisterRef.Virtual -> error("machine-code encoding requires allocated base registers")
            }
        }
        val displacement = memory.displacement
        val needsSib = base == null || (base.number and 7) == 4
        val mod = when {
            base == null -> 0
            displacement == 0L && (base.number and 7) != 5 -> 0
            displacement in -128L..127L -> 1
            else -> 2
        }
        if (mode == X86Mode.I386) {
            require(base == null || base.bits == 32) { "i386 memory addressing requires 32-bit registers" }
        } else {
            require(base == null || base.bits == 64) { "x86_64 memory addressing requires 64-bit registers" }
        }
        rex(output, w = w, register = registerField, base = base?.number ?: 0)
        output += opcode.map(Int::toByte)
        val rm = if (needsSib) 4 else base!!.number
        output += modRm(mod, registerField, rm)
        if (needsSib) {
            val sibBase = base?.number?.and(7) ?: 5
            output += ((4 shl 3) or sibBase).toByte() // no index, scale 1
        }
        when (mod) {
            1 -> output += displacement.toByte()
            2 -> {
                require(displacement == displacement.toInt().toLong()) { "x86 displacement must fit signed 32 bits" }
                appendInt(output, displacement.toInt())
            }
            0 -> if (base == null) {
                val validAddress = if (mode == X86Mode.I386) {
                    displacement in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL
                } else {
                    displacement == displacement.toInt().toLong()
                }
                require(validAddress) { "absolute x86 address must fit the target address encoding" }
                appendInt(output, displacement.toInt())
            }
        }
    }

    private fun encodeStackOperand(operands: List<X86Operand>, output: MutableList<Byte>, push: Boolean) {
        require(operands.size == 1) { "stack operation requires one operand" }
        when (val operand = operands.single()) {
            is X86Operand.Register -> {
                val register = physicalRegister(operand)
                require(register.registerClass == org.tinycc.core.ir.IrRegisterClass.INTEGER &&
                    register.bits == mode.bits && register.number < if (mode == X86Mode.X86_64) 16 else 8
                ) { "stack register must be a target-width general-purpose register" }
                if (mode == X86Mode.X86_64 && register.number >= 8) output += 0x41.toByte()
                output += ((if (push) 0x50 else 0x58) + (register.number and 7)).toByte()
            }
            is X86Operand.Memory -> encodeRm(if (push) 0xFF else 0x8F, if (push) 6 else 0, operand, output, w = false)
            is X86Operand.Immediate -> {
                require(push) { "pop does not accept an immediate operand" }
                val immediate = operand.value
                val valid = if (mode == X86Mode.X86_64) {
                    immediate == immediate.toInt().toLong()
                } else {
                    immediate in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL
                }
                require(valid) { "push immediate must fit the target's sign-extended 32-bit encoding" }
                if (immediate in -128L..127L) {
                    output += 0x6A
                    output += immediate.toByte()
                } else {
                    output += 0x68
                    appendInt(output, immediate.toInt())
                }
            }
            else -> error("unsupported stack operand: $operand")
        }
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
