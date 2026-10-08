package tcc.kt

private fun parseArmInteger(source: String): Int {
    val text = source.trim().replace("_", "")
    val sign = if (text.startsWith('-')) -1 else 1
    val digits = text.removePrefix("-").removePrefix("+")
    val radix = when {
        digits.startsWith("0x", true) -> 16
        digits.startsWith("0b", true) -> 2
        digits.length > 1 && digits.startsWith('0') -> 8
        else -> 10
    }
    val number = digits.removePrefix("0x").removePrefix("0X").removePrefix("0b").removePrefix("0B")
    return sign * number.toLong(radix).toInt()
}

/** ARM assembler operand decoding and instruction encoding from arm-asm.c. */
class ArmAsm(
    private val output: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val expect: (String) -> Unit = { throw IllegalArgumentException("expected $it") },
    private val error: (String) -> Unit = { throw IllegalArgumentException(it) },
    private val warning: (String) -> Unit = {},
    private val expression: (String) -> Expression = { Expression(parseArmInteger(it)) },
    private val relocation: (String, Int, String, Int) -> Unit = { _, _, _, _ -> },
) {
    enum class Kind { REG32, REGSET32, IMM8, IMM8N, IMM32, VREG32, VREG64 }
    data class Expression(val value: Int, val symbol: String? = null)
    data class Operand(
        var kind: Kind = Kind.IMM32, var register: Int = 0,
        var registerSet: Int = 0, var value: Expression = Expression(0),
    )

    fun g(value: Int) { if (!noCode()) output(value and 0xff) }
    fun genLe16(value: Int) { g(value); g(value ushr 8) }
    fun genLe32(value: Int) { g(value); g(value ushr 8); g(value ushr 16); g(value ushr 24) }
    fun genExpr32(value: Expression) { genLe32(value.value) }

    /** Reads ARM core, VFP, or register-list operands from AT&T-free ARM syntax. */
    fun parseOperand(source: String): Operand {
        val text = source.trim()
        if (text.startsWith('{') && text.endsWith('}')) {
            val names = text.substring(1, text.length - 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (names.isEmpty()) error("empty register list is not supported")
            var set = 0
            var previous = -1
            names.forEach { name ->
                val register = coreRegister(name)
                if (register < 0) expect("register")
                if (previous >= 0 && register < previous)
                    warning("registers will be processed in ascending order by hardware--but are not specified in ascending order here")
                set = set or (1 shl register)
                previous = register
            }
            return Operand(Kind.REGSET32, registerSet = set)
        }
        coreRegister(text).takeIf { it >= 0 }?.let { return Operand(Kind.REG32, register = it) }
        Regex("s([0-9]|[12][0-9]|3[01])").matchEntire(text)?.let {
            return Operand(Kind.VREG32, register = it.groupValues[1].toInt())
        }
        Regex("d([0-9]|1[0-5])").matchEntire(text)?.let {
            return Operand(Kind.VREG64, register = it.groupValues[1].toInt())
        }
        val constant = if (text.startsWith('#') || text.startsWith('$')) text.drop(1) else text
        val result = try { expression(constant) } catch (_: RuntimeException) { expect("operand"); return Operand() }
        if (result.symbol != null) expect("operand")
        val number = result.value
        val kind = when {
            number < 0 && number >= -255 -> Kind.IMM8N
            number in 0..255 -> Kind.IMM8
            else -> Kind.IMM32
        }
        return Operand(kind, value = result)
    }

    private fun coreRegister(name: String): Int = when (name.lowercase()) {
        "sp" -> 13
        "lr" -> 14
        "pc" -> 15
        else -> Regex("r([0-9]|1[0-5])").matchEntire(name.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    fun conditionCode(token: Int, firstConditionToken: Int): Int {
        if (token < firstConditionToken) { expect("condition-enabled instruction"); return 0 }
        return (token - firstConditionToken) and 15
    }

    fun emitOpcode(token: Int, firstConditionToken: Int, opcode: Int) =
        genLe32((conditionCode(token, firstConditionToken) shl 28) or opcode)

    fun emitUnconditionalOpcode(opcode: Int) = genLe32(opcode)

    /** Encodes a coprocessor data-processing or register-transfer instruction word. */
    fun emitCoprocessorOpcode(
        highNibble: Int, coprocessor: Int, opcode: Int, destination: Int,
        nOperand: Int, mOperand: Int, opcode2: Int, interProcessorTransfer: Boolean,
    ) {
        var word = 0x0e000000
        if (interProcessorTransfer) word = word or (1 shl 4)
        word = word or (opcode shl 20) or (nOperand shl 16) or (destination shl 12)
        word = word or (coprocessor shl 8) or (opcode2 shl 5) or mOperand
        emitUnconditionalOpcode((highNibble shl 28) or word)
    }

    fun emitNullary(group: String, token: Int, firstConditionToken: Int) {
        when (group) {
            "nop" -> emitOpcode(token, firstConditionToken, 0xd shl 21)
            "wfe" -> emitOpcode(token, firstConditionToken, 0x0320f002)
            "wfi" -> emitOpcode(token, firstConditionToken, 0x0320f003)
            else -> expect("nullary instruction")
        }
    }

    fun emitUnary(group: String, token: Int, firstConditionToken: Int, operand: Operand) {
        if (group != "swi" && group != "svc") { expect("unary instruction"); return }
        if (operand.kind != Kind.IMM8) { expect("immediate 8-bit unsigned integer"); return }
        emitOpcode(token, firstConditionToken, (0xf shl 24) or operand.value.value)
    }

    /** Encodes ARM register-pair operations and immediate movw/movt forms. */
    fun emitBinary(
        group: String, token: Int, firstConditionToken: Int,
        destination: Operand, source: Operand, rotation: Int? = null,
    ) {
        if (destination.kind != Kind.REG32) { expect("(destination operand) register"); return }
        if (destination.register == 15) error("'$group' does not support 'pc' as operand")
        if (destination.register == 13) warning("Using 'sp' as operand with '$group' is deprecated by ARM")
        if (group == "movt" || group == "movw") {
            if (source.kind !in setOf(Kind.IMM8, Kind.IMM8N, Kind.IMM32) || source.value.value !in 0..0xffff) {
                expect("(source operand) immediate 16 bit value"); return
            }
            val opcode = (if (group == "movt") 0x03400000 else 0x03000000) or
                (destination.register shl 12) or ((source.value.value and 0xf000) shl 4) or (source.value.value and 0xfff)
            emitOpcode(token, firstConditionToken, opcode)
            return
        }
        if (source.kind != Kind.REG32) { expect("(source operand) register"); return }
        if (source.register == 15) error("'$group' does not support 'pc' as operand")
        if (source.register == 13) warning("Using 'sp' as operand with '$group' is deprecated by ARM")
        val rotateBits = when (rotation) {
            null -> 0
            8 -> 1 shl 10
            16 -> 2 shl 10
            24 -> 3 shl 10
            else -> { expect("'8', '16' or '24'"); 0 }
        }
        val opcode = when (group) {
            "clz" -> {
                if (rotateBits != 0) error("clz does not support rotation")
                0x016f0f10 or (destination.register shl 12) or source.register
            }
            "sxtb" -> 0x06af0070 or (destination.register shl 12) or source.register or rotateBits
            "sxth" -> 0x06bf0070 or (destination.register shl 12) or source.register or rotateBits
            "uxtb" -> 0x06ef0070 or (destination.register shl 12) or source.register or rotateBits
            "uxth" -> 0x06ff0070 or (destination.register shl 12) or source.register or rotateBits
            else -> { expect("binary instruction"); return }
        }
        emitOpcode(token, firstConditionToken, opcode)
    }

    /** Parses and encodes optional ARM barrel-shifter directives. */
    fun parseOptionalShift(group: String, amount: Operand? = null): Pair<Int, Operand?> {
        val mode = when (group) {
            "asl", "lsl" -> 0 shl 5
            "lsr" -> 1 shl 5
            "asr" -> 2 shl 5
            "ror", "rrx" -> 3 shl 5
            else -> { expect("shift directive"); 0 }
        }
        return mode to if (group == "rrx") null else amount
    }

    fun encodeShift(shift: Operand): Int = when (shift.kind) {
        Kind.REG32 -> {
            if (shift.register == 15) { error("r15 cannot be used as a shift count"); 0 }
            else (1 shl 4) or (shift.register shl 8)
        }
        Kind.IMM8 -> {
            val value = shift.value.value
            if (value in 1..31) value shl 7 else { error("shift count out of range"); 0 }
        }
        else -> { error("unknown shift amount"); 0 }
    }

    /** Emits ARM register shifts, including implicit-source and RRX spellings. */
    fun emitShift(group: String, token: Int, firstConditionToken: Int, operands: List<Operand>, setFlags: Boolean = false) {
        if (operands.size !in 2..3) { expect("two or three operands"); return }
        val destination = operands[0]
        if (destination.kind != Kind.REG32) { expect("(destination operand) register"); return }
        var opcode = 0xd shl 21
        var encoded = destination.register shl 12
        if (setFlags) opcode = opcode or (1 shl 20)
        if (operands.size == 2 && group == "rrx") {
            if (operands[1].kind != Kind.REG32) { expect("(first source operand) register"); return }
            emitOpcode(token, firstConditionToken, opcode or encoded or operands[1].register or (3 shl 5))
            return
        }
        val source: Operand
        val shift: Operand
        if (operands.size == 2) { source = destination; shift = operands[1] }
        else { source = operands[1]; shift = operands[2] }
        if (source.kind != Kind.REG32) { expect("(first source operand) register"); return }
        encoded = encoded or source.register
        if (shift.kind == Kind.REG32 && (destination.register == 15 || source.register == 15))
            error("Using the 'pc' register with a register-controlled shift is not implemented by ARM")
        val mode = when (group) { "lsl" -> 0; "lsr" -> 1; "asr" -> 2; "ror" -> 3; else -> { expect("shift instruction"); 0 } }
        if (shift.kind == Kind.IMM8) {
            val value = shift.value.value
            if (value == 0) {
                emitOpcode(token, firstConditionToken, opcode or encoded)
                return
            }
            encoded = encoded or encodeShift(shift)
        } else encoded = encoded or encodeShift(shift)
        encoded = encoded or (mode shl 5)
        emitOpcode(token, firstConditionToken, opcode or encoded)
    }

    /** Emits ARM MUL, MLA, MLS, SDIV, and UDIV encodings. */
    fun emitMultiply(group: String, token: Int, firstConditionToken: Int, operands: List<Operand>, setFlags: Boolean = false) {
        val long = group in setOf("smull", "umull", "smlal", "umlal")
        if (long) { emitLongMultiply(group, token, firstConditionToken, operands, setFlags); return }
        val isMla = group == "mla" || group == "mls"
        val actual = if (group == "mul" && operands.size == 2) listOf(operands[0], operands[1], operands[0]) else operands
        if (actual.size != if (isMla) 4 else 3) { expect(if (isMla) "four operands" else "three operands"); return }
        var opcode = 0x90
        val rd = actual[0].register
        if (actual.any { it.kind != Kind.REG32 }) { expect("register operands"); return }
        opcode = opcode or (rd shl 16) or actual[1].register or (actual[2].register shl 8)
        if (isMla) opcode = opcode or (1 shl 21) or (actual[3].register shl 12)
        if (setFlags) opcode = opcode or (1 shl 20)
        when (group) {
            "mul", "mla" -> Unit
            "mls" -> opcode = opcode or (1 shl 22)
            "sdiv" -> opcode = (opcode and 0xffffff7f.toInt()) or 0x0710f010
            "udiv" -> opcode = (opcode and 0xffffff7f.toInt()) or 0x0730f010
            else -> { expect("known multiplication instruction"); return }
        }
        emitOpcode(token, firstConditionToken, opcode)
    }

    private fun emitLongMultiply(group: String, token: Int, firstConditionToken: Int, operands: List<Operand>, setFlags: Boolean) {
        if (operands.size != 4 || operands.any { it.kind != Kind.REG32 }) { expect("four register operands"); return }
        var opcode = 0x90 or (1 shl 23) or (operands[0].register shl 12) or
            (operands[1].register shl 16) or operands[2].register or (operands[3].register shl 8)
        if (group == "smull" || group == "smlal") opcode = opcode or (1 shl 22)
        if (group == "smlal" || group == "umlal") opcode = opcode or (1 shl 21)
        if (setFlags) opcode = opcode or (1 shl 20)
        emitOpcode(token, firstConditionToken, opcode)
    }

    /** Encodes the ARM data-processing instruction family from a mnemonic opcode number. */
    fun emitDataProcessing(
        opcodeNumber: Int, token: Int, firstConditionToken: Int,
        operands: List<Operand>, setFlags: Boolean = false,
        shiftMode: Int = 0, shiftAmount: Operand? = null,
    ) {
        if (operands.size !in 2..3) { expect("two or three operands"); return }
        val opcodeNo = opcodeNumber and 15
        if (operands.size == 3 && opcodeNo in setOf(8, 9, 10, 11, 13, 15)) {
            error("instruction does not accept three operands"); return
        }
        val destination = operands[0]
        val firstSource = if (operands.size == 2) destination else operands[1]
        val secondSource = operands.last()
        if (destination.kind != Kind.REG32) { expect("(destination operand) register"); return }
        var opcode = opcodeNo shl 21
        var encoded = 0
        if (setFlags || opcodeNo in setOf(8, 9, 10, 11)) encoded = encoded or (1 shl 20)
        if (opcodeNo !in setOf(8, 9, 10, 11)) encoded = encoded or (destination.register shl 12)
        if (opcodeNo !in setOf(13, 15)) {
            if (firstSource.kind != Kind.REG32) { expect("(first source operand) register"); return }
            encoded = encoded or (firstSource.register shl 16)
        }
        when (secondSource.kind) {
            Kind.REG32 -> {
                encoded = encoded or secondSource.register
                if (shiftAmount != null) {
                    when (shiftAmount.kind) {
                        Kind.REG32 -> {
                            if (destination.register == 15 || firstSource.register == 15) {
                                error("Using pc with a register-controlled shift is not implemented by ARM"); return
                            }
                            encoded = encoded or (1 shl 4) or (shiftAmount.register shl 8) or shiftMode
                        }
                        Kind.IMM8 -> encoded = encoded or encodeShift(shiftAmount) or shiftMode
                        else -> { expect("register or immediate shift amount"); return }
                    }
                } else if (shiftMode != 0) encoded = encoded or shiftMode
            }
            Kind.IMM8, Kind.IMM32, Kind.IMM8N -> {
                var value = secondSource.value.value
                var operation = opcodeNo
                if (secondSource.kind == Kind.IMM8N) {
                    when (opcodeNo) {
                        0 -> { operation = 14; value = value.inv() }
                        2 -> { operation = 4; value = -value }
                        4 -> { operation = 2; value = -value }
                        5 -> { operation = 6; value = value.inv() }
                        6 -> { operation = 5; value = value.inv() }
                        10 -> { operation = 11; value = -value }
                        11 -> { operation = 10; value = -value }
                        13 -> { operation = 15; value = value.inv() }
                        14 -> { operation = 0; value = value.inv() }
                        else -> { error("negative immediate cannot be encoded by this instruction"); return }
                    }
                    opcode = operation shl 21
                }
                var rotated = value
                var rotation = 0
                while (rotation < 16 && rotated !in 0..255) {
                    rotated = (rotated shl 2) or (rotated ushr 30)
                    rotation++
                }
                if (rotation >= 16) { error("immediate cannot be encoded as an ARM rotated byte"); return }
                encoded = encoded or (1 shl 25) or (rotation shl 8) or rotated
            }
            else -> { expect("register or immediate operand"); return }
        }
        emitOpcode(token, firstConditionToken, opcode or encoded)
    }

    /** Encodes ARM single data transfers and exclusive loads/stores. */
    fun emitSingleDataTransfer(
        group: String, token: Int, firstConditionToken: Int,
        destination: Operand, base: Operand, offset: Operand? = null,
        preIndexed: Boolean = true, writeback: Boolean = false,
        subtractOffset: Boolean = false, shiftMode: Int = 0,
        shift: Operand? = null, statusRegister: Operand? = null,
    ) {
        if (destination.kind != Kind.REG32 || base.kind != Kind.REG32) { expect("register operands"); return }
        var opcode = (destination.register shl 12) or (base.register shl 16)
        if (preIndexed) opcode = opcode or (1 shl 24)
        if (writeback) opcode = opcode or (1 shl 21)
        val transferOffset = offset ?: Operand(Kind.IMM8, value = Expression(0))
        when (transferOffset.kind) {
            Kind.IMM8, Kind.IMM8N, Kind.IMM32 -> {
                if (subtractOffset && transferOffset.value.value < 0) { error("minus before immediate is unsupported"); return }
                val value = transferOffset.value.value
                val magnitude = if (value < 0) -value else value
                if (magnitude >= 0x1000) { error("offset out of range for '$group'"); return }
                if ((value >= 0) xor subtractOffset) opcode = opcode or (1 shl 23)
                opcode = opcode or magnitude
            }
            Kind.REG32 -> {
                if (transferOffset.register == 15) { error("pc register offset is unsupported for '$group'"); return }
                if (!subtractOffset) opcode = opcode or (1 shl 23)
                opcode = opcode or (1 shl 25) or transferOffset.register or shiftMode
                if (shift != null) opcode = opcode or encodeShift(shift)
            }
            else -> { expect("register or immediate offset"); return }
        }
        val exclusive = group.startsWith("strex") || group.startsWith("ldrex")
        when {
            group == "str" || group == "strb" -> {
                if (group == "strb") opcode = opcode or (1 shl 22)
                opcode = opcode or (1 shl 26)
            }
            group == "ldr" || group == "ldrb" -> {
                if (group == "ldrb") opcode = opcode or (1 shl 22)
                opcode = opcode or (1 shl 20) or (1 shl 26)
            }
            group.startsWith("strex") -> {
                val status = statusRegister ?: run { expect("status register"); return }
                if (status.kind != Kind.REG32) { expect("status register"); return }
                if (offset != null && (opcode and 0xfff != 0 || shift != null)) { error("neither offset nor shift allowed with '$group'"); return }
                if (transferOffset.kind == Kind.REG32) { error("offset not allowed with '$group'"); return }
                if (!preIndexed) { error("adding offset after transfer not allowed with '$group'"); return }
                if (group == "strexh") opcode = opcode or (1 shl 21)
                if (group == "strexb") opcode = opcode or (1 shl 22)
                opcode = (opcode or 0xf90) or status.register
            }
            group.startsWith("ldrex") -> {
                if (offset != null && (opcode and 0xfff != 0 || shift != null)) { error("neither offset nor shift allowed with '$group'"); return }
                if (transferOffset.kind == Kind.REG32) { error("offset not allowed with '$group'"); return }
                if (!preIndexed) { error("adding offset after transfer not allowed with '$group'"); return }
                if (group == "ldrexh") opcode = opcode or (1 shl 21)
                if (group == "ldrexb") opcode = opcode or (1 shl 22)
                opcode = opcode or (1 shl 20) or 0xf90 or 0x0f
            }
            else -> { expect("data transfer instruction"); return }
        }
        if (exclusive && shift != null) { error("shift not allowed with exclusive transfer"); return }
        emitOpcode(token, firstConditionToken, opcode)
    }

    /** Encodes ARM halfword and signed-byte load/store transfer instructions. */
    fun emitMiscDataTransfer(
        group: String, token: Int, firstConditionToken: Int,
        destination: Operand, base: Operand, offset: Operand? = null,
        preIndexed: Boolean = true, writeback: Boolean = false, subtractOffset: Boolean = false,
    ) {
        if (destination.kind != Kind.REG32 || base.kind != Kind.REG32) { expect("register operands"); return }
        var opcode = (1 shl 7) or (1 shl 4) or (destination.register shl 12) or (base.register shl 16)
        if (preIndexed) opcode = opcode or (1 shl 24)
        if (writeback) {
            if (!preIndexed) { error("writeback with post-indexing is unpredictable"); return }
            opcode = opcode or (1 shl 21)
        }
        val transferOffset = offset ?: Operand(Kind.IMM8, value = Expression(0))
        when (transferOffset.kind) {
            Kind.IMM8, Kind.IMM8N, Kind.IMM32 -> {
                val value = transferOffset.value.value
                if (subtractOffset && value < 0) { error("minus before immediate is unsupported"); return }
                val magnitude = if (value < 0) -value else value
                if (magnitude >= 0x100) { error("offset out of range for '$group'"); return }
                if ((value >= 0) xor subtractOffset) opcode = opcode or (1 shl 23)
                opcode = opcode or ((magnitude and 0xf0) shl 4) or (magnitude and 0x0f) or (1 shl 22)
            }
            Kind.REG32 -> {
                if (!subtractOffset) opcode = opcode or (1 shl 23)
                opcode = opcode or transferOffset.register
            }
            else -> { expect("register or immediate offset"); return }
        }
        when (group) {
            "ldrsb" -> opcode = opcode or (1 shl 6) or (1 shl 20)
            "ldrsh" -> opcode = opcode or (1 shl 5) or (1 shl 6) or (1 shl 20)
            "ldrh" -> opcode = opcode or (1 shl 5) or (1 shl 20)
            "strh" -> opcode = opcode or (1 shl 5)
            else -> { expect("miscellaneous data transfer instruction"); return }
        }
        emitOpcode(token, firstConditionToken, opcode)
    }

    /** Encodes ARM block load/store and push/pop register-list instructions. */
    fun emitBlockDataTransfer(
        group: String, token: Int, firstConditionToken: Int,
        operands: List<Operand>, writeback: Boolean = false,
    ) {
        if (group == "push" || group == "pop") {
            if (operands.size != 1 || operands[0].kind != Kind.REGSET32) { expect("exactly one register list"); return }
            val opcode = (if (group == "push") 0x92d else 0x8bd) shl 16
            emitOpcode(token, firstConditionToken, opcode or operands[0].registerSet)
            return
        }
        if (operands.size != 2 || operands[0].kind != Kind.REG32 || operands[1].kind != Kind.REGSET32) {
            expect("base register and register list"); return
        }
        val mode = when (group) {
            "stmda" -> 0x80; "ldmda" -> 0x81
            "stm", "stmia" -> 0x88; "ldm", "ldmia" -> 0x89
            "stmdb" -> 0x90; "ldmdb" -> 0x91
            "stmib" -> 0x98; "ldmib" -> 0x99
            else -> { expect("block data transfer instruction"); return }
        }
        var opcode = mode shl 20
        if (writeback) opcode = opcode or (1 shl 21)
        opcode = opcode or (operands[0].register shl 16) or operands[1].registerSet
        emitOpcode(token, firstConditionToken, opcode)
    }

    /** Encodes branches and records R_ARM_PC24 relocations for external targets. */
    fun emitBranch(
        group: String, token: Int, firstConditionToken: Int, position: Int,
        target: Expression? = null, register: Operand? = null,
        sameSectionAddress: (String) -> Int? = { null },
    ) {
        when (group) {
            "b", "bl" -> {
                val expression = target ?: run { expect("branch target"); return }
                val symbolAddress = expression.symbol?.let(sameSectionAddress)
                val base = if (expression.symbol != null && symbolAddress == null) {
                    relocation(expression.symbol, position, "R_ARM_PC24", 0)
                    position
                } else symbolAddress ?: 0
                var displacement = expression.value + base - position - 8
                if (displacement and 3 != 0) { error("branch target is not word aligned"); return }
                displacement /= 4
                if (displacement >= 0x7fffff || displacement < -0x800000) { error("branch offset is too far"); return }
                emitOpcode(token, firstConditionToken, ((if (group == "bl") 0xb else 0xa) shl 24) or (displacement and 0xffffff))
            }
            "bx", "blx" -> {
                val operand = register ?: run { expect("register"); return }
                if (operand.kind != Kind.REG32) { expect("register"); return }
                emitOpcode(token, firstConditionToken, ((if (group == "blx") 0x12fff3 else 0x12fff1) shl 4) or operand.register)
            }
            else -> expect("branch instruction")
        }
    }

    /** Encodes a coprocessor load/store word with its scaled offset. */
    fun emitCoprocessorDataTransfer(
        highNibble: Int, coprocessor: Int, crd: Int, base: Operand,
        offset: Operand, offsetMinus: Boolean = false, preincrement: Boolean = true,
        writeback: Boolean = false, longTransfer: Boolean = false, load: Boolean = false,
    ) {
        if (base.kind != Kind.REG32) { expect("register"); return }
        var opcode = (1 shl 26) or (1 shl 27) or (coprocessor shl 8) or (crd shl 12) or (base.register shl 16)
        if (longTransfer) opcode = opcode or (1 shl 22)
        if (load) opcode = opcode or (1 shl 20)
        if (preincrement) opcode = opcode or (1 shl 24)
        if (writeback) opcode = opcode or (1 shl 21)
        when (offset.kind) {
            Kind.IMM8, Kind.IMM8N, Kind.IMM32 -> {
                val value = offset.value.value
                if (offsetMinus && value < 0) { error("minus before immediate is unsupported"); return }
                val magnitude = if (value < 0) -value else value
                if (value >= 0 && !offsetMinus) opcode = opcode or (1 shl 23)
                if (magnitude and 3 != 0) { error("immediate offset must be a multiple of 4"); return }
                if (magnitude > 1020) { error("immediate offset must be between -1020 and 1020"); return }
                opcode = opcode or (magnitude ushr 2)
            }
            Kind.REG32 -> { error("register offset is not supported for coprocessor transfer"); return }
            Kind.VREG64 -> opcode = opcode or 16 or offset.register
            else -> { expect("immediate or VFP register"); return }
        }
        emitUnconditionalOpcode((highNibble shl 28) or opcode)
    }

    /** Parses the VFP immediate decimal format with seven fractional digits. */
    fun parseVmovImmediate(source: String): Int {
        val text = source.trim()
        val match = Regex("^([0-9]+)(?:\\.([0-9]{0,7}))?$").matchEntire(text)
            ?: run { expect("decimal numeral"); return 0 }
        val integral = match.groupValues[1].toLongOrNull() ?: 32L
        if (integral >= 32) { error("invalid floating-point immediate value"); return 0 }
        val fraction = match.groupValues[2].padEnd(7, '0').toIntOrNull() ?: 0
        return integral.toInt() * 10_000_000 + fraction
    }

    /** Encodes a decimal VFP immediate into its eight-bit VFPExpandImm form. */
    fun encodeVmovImmediate(value: Int): Int {
        var limit = 32 * 10_000_000
        var end = 0
        var beginning = 0
        var range = -1
        repeat(8) { index ->
            if (value < limit) {
                end = limit
                limit = limit ushr 1
                beginning = limit
                range = index
            } else limit = limit ushr 1
        }
        if (range < 0 || value < beginning || value > end) { error("invalid decimal number for vmov: $value"); return 0 }
        val step = (end - beginning) / 16
        val fraction = if (step == 0) -1 else (0 until 16).firstOrNull { beginning + it * step == value } ?: -1
        if (fraction < 0) { error("invalid decimal number for vmov: $value"); return 0 }
        return fraction or (((3 - range) and 7) shl 4)
    }

    /** Emits VFP compare-zero and immediate move encodings. */
    fun emitVfpImmediate(
        group: String, token: Int, firstConditionToken: Int,
        coprocessor: Int, destination: Int, value: Int, negative: Boolean = false,
    ) {
        var opcode1 = 11
        var opcode2 = 0
        val operands = intArrayOf(destination, 0, 0)
        when (group) {
            "vcmp" -> { opcode2 = 2; operands[1] = 5; if (value != 0) { expect("immediate value 0"); return } }
            "vcmpe" -> { opcode2 = 6; operands[1] = 5; if (value != 0) { expect("immediate value 0"); return } }
            "vmov" -> {
                if (negative) operands[1] = 8
                val code = encodeVmovImmediate(value)
                operands[1] = operands[1] or (code ushr 4)
                operands[2] = code and 15
            }
            else -> { expect("known floating point immediate instruction"); return }
        }
        if (coprocessor == 10) {
            if (operands[0] and 1 != 0) opcode1 = opcode1 or 4
            operands[0] = operands[0] ushr 1
        }
        emitCoprocessorOpcode(conditionCode(token, firstConditionToken), coprocessor, opcode1,
            operands[0], operands[1], operands[2], opcode2, false)
    }
}
