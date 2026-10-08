package tcc.kt

/** RISC-V assembler byte emission and operand representation from riscv64-asm.c. */
class Riscv64Asm(
    private val output: (Int) -> Unit,
    private val noCode: () -> Boolean = { false },
    private val expect: (String) -> Unit = { throw IllegalArgumentException("expected $it") },
    private val error: (String) -> Unit = { throw IllegalArgumentException(it) },
) {
    enum class OperandType { REGISTER, IMMEDIATE_12_SIGNED, IMMEDIATE_32 }
    data class Expression(val value: Long, val symbol: String? = null)
    data class Operand(var type: Int = 0, var register: Int = 0, var registerSet: Int = 0, var expression: Expression = Expression(0))
    data class AsmValue(
        val constant: Long = 0, val symbol: String? = null, val register: Int = -1,
        val local: Boolean = false, val lvalue: Boolean = false, val floating: Boolean = false,
    )

    companion object {
        const val REGISTER_COUNT = 64
        const val REG_FLOAT_MASK = 0x20
        const val OPT_REG = 0
        const val OPT_IM12S = 1
        const val OPT_IM32 = 2
        const val OP_REG = 1 shl OPT_REG
        const val OP_IM12S = 1 shl OPT_IM12S
        const val OP_IM32 = 1 shl OPT_IM32

        fun isFloatRegister(register: Int): Boolean = register and REG_FLOAT_MASK != 0
        fun registerValue(register: Int): Int = register and (REG_FLOAT_MASK - 1)
        fun encodeCompressedRs1(register: Int): Int = registerValue(register) shl 7
        fun encodeCompressedRs2(register: Int): Int = registerValue(register) shl 2
        fun encodeRd(register: Int): Int = registerValue(register) shl 7
        fun encodeRs1(register: Int): Int = registerValue(register) shl 15
        fun encodeRs2(register: Int): Int = registerValue(register) shl 20
        fun nthBit(value: Int, bit: Int): Int = (value ushr bit) and 1
    }

    fun emitByte(value: Int) {
        if (!noCode()) output(value and 0xff)
    }

    fun emitLittleEndian16(value: Int) {
        emitByte(value)
        emitByte(value ushr 8)
    }

    fun emitLittleEndian32(value: Int) {
        if (!noCode()) {
            output(value and 0xff)
            output((value ushr 8) and 0xff)
            output((value ushr 16) and 0xff)
            output((value ushr 24) and 0xff)
        }
    }

    fun emitExpression32(expression: Expression) = emitLittleEndian32(expression.value.toInt())
    fun emitOpcode(opcode: Int) = emitLittleEndian32(opcode)

    fun emitNullaryOpcode(name: String): Int? {
        val opcode = when (name) {
            "fence.i" -> (3 shl 2) or 3 or (1 shl 12)
            "ecall" -> (0x1c shl 2) or 3
            "ebreak" -> (0x1c shl 2) or 3 or (1 shl 20)
            "nop" -> 0x13
            "wfi" -> (0x1c shl 2) or 3 or (0x105 shl 20)
            "ret" -> 0x67 or encodeRs1(1)
            "c.ebreak" -> 2 or (9 shl 12)
            "c.nop" -> 1
            else -> { expect("nullary instruction"); return null }
        }
        if (name.startsWith("c.")) emitLittleEndian16(opcode) else emitOpcode(opcode)
        return opcode
    }

    fun parseFenceOperand(name: String): Int? {
        if (name == "iorw") return 0xf
        var result = 0
        for (character in name) result = result or when (character) {
            'i' -> 8; 'o' -> 4; 'r' -> 2; 'w' -> 1; else -> return null
        }
        return result
    }

    fun emitFence(predecessor: Int = 0xf, successor: Int = 0xf): Boolean {
        if (predecessor !in 0..15 || successor !in 0..15) { error("Expected valid fence predecessor and successor operands"); return false }
        emitOpcode((3 shl 2) or 3 or (successor shl 20) or (predecessor shl 24))
        return true
    }

    fun emitUnaryOpcode(name: String, operand: Operand, relocateCall: (String, Int) -> Unit = { _, _ -> }) : Boolean {
        if (name in setOf("rdcycle", "rdcycleh", "rdtime", "rdtimeh", "rdinstret", "rdinstreth", "frflags", "frrm", "frcsr")) {
            if (!requireRegister(operand, "destination operand")) return false
            val csr = when (name) {
                "rdcycle" -> 0xc00; "rdcycleh" -> 0xc80; "rdtime" -> 0xc01; "rdtimeh" -> 0xc81
                "rdinstret" -> 0xc02; "rdinstreth" -> 0xc82; "frflags" -> 1; "frrm" -> 2; else -> 3
            }
            emitOpcode((0x1c shl 2) or 3 or (2 shl 12) or (csr shl 20) or encodeRd(operand.register))
            return true
        }
        if (name == "jr") return emitI(0x67, Operand(OP_REG, register = 0), operand, Operand(OP_IM12S))
        if (name == "call" || name == "tail") {
            val symbol = operand.expression.symbol
            if (symbol == null) { error("Expected call target symbol"); return false }
            val temporary = if (name == "call") 1 else 6
            relocateCall(symbol, 18) // R_RISCV_CALL
            emitOpcode(3 or (5 shl 2) or encodeRd(temporary)) // auipc temporary, 0
            emitOpcode(0x67 or encodeRs1(temporary)) // jalr zero, 0(temporary)
            return true
        }
        if (name in setOf("c.j", "c.jal", "c.jr", "c.jalr")) {
            error("compressed unary encoder required for '$name'")
            return false
        }
        expect("unary instruction")
        return false
    }

    fun parseRegister(name: String): Int? {
        val text = name.trim().lowercase()
        if (text == "zero") return 0
        if (text == "ra") return 1
        if (text == "sp") return 2
        val aliases = mapOf(
            "gp" to 3, "tp" to 4, "t0" to 5, "t1" to 6, "t2" to 7,
            "s0" to 8, "fp" to 8, "s1" to 9, "a0" to 10, "a1" to 11,
            "a2" to 12, "a3" to 13, "a4" to 14, "a5" to 15, "a6" to 16,
            "a7" to 17, "s2" to 18, "s3" to 19, "s4" to 20, "s5" to 21,
            "s6" to 22, "s7" to 23, "s8" to 24, "s9" to 25, "s10" to 26,
            "s11" to 27, "t3" to 28, "t4" to 29, "t5" to 30, "t6" to 31,
        )
        aliases[text]?.let { return it }
        val match = Regex("([xf])([0-9]+)").matchEntire(text) ?: return null
        val register = match.groupValues[2].toIntOrNull() ?: return null
        return if (register in 0..31) register or if (match.groupValues[1] == "f") REG_FLOAT_MASK else 0 else null
    }

    fun parseRegisterOrError(name: String): Int {
        val register = parseRegister(name)
        if (register == null) error("invalid register '$name'")
        return register ?: 0
    }

    fun parseRegisterVariable(name: String): Int = parseRegister(name) ?: -1

    fun parseCsrVariable(name: String): Int = when (name.lowercase()) {
        "cycle" -> 0xc00; "fcsr" -> 3; "fflags" -> 1; "frm" -> 2; "instret" -> 0xc02
        "time" -> 0xc01; "cycleh" -> 0xc80; "instreth" -> 0xc82; "timeh" -> 0xc81; else -> -1
    }

    fun markClobber(clobbers: BooleanArray, name: String): Boolean {
        if (name in setOf("memory", "cc", "flags")) return true
        val register = parseRegisterVariable(name)
        if (register < 0 || register >= clobbers.size) { error("invalid clobber register '$name'"); return false }
        clobbers[register] = true
        return true
    }

    fun substituteAssemblyOperand(value: AsmValue, modifier: Char = '\u0000', leadingUnderscore: Boolean = false): String {
        if (value.symbol != null || value.local) {
            if (value.symbol == null) return value.constant.toString()
            val prefix = if (leadingUnderscore) "_" else ""
            if (value.constant == 0L) return prefix + value.symbol
            val adjusted = if (modifier == 'n') -value.constant else value.constant
            return "$prefix${value.symbol}+${adjusted}"
        }
        if (value.register >= 0) {
            val number = registerValue(value.register)
            return (if (isFloatRegister(value.register) || value.floating) "f" else "x") + number
        }
        val adjusted = if (modifier == 'n') -value.constant else value.constant
        return if (modifier == 'z' && adjusted == 0L) "zero" else adjusted.toString()
    }

    fun parseExpression(text: String): Expression {
        val value = text.trim().replace("_", "")
        val sign = if (value.startsWith('-')) -1L else 1L
        val digits = value.removePrefix("-").removePrefix("+")
        val radix = when { digits.startsWith("0x", true) -> 16; digits.startsWith("0b", true) -> 2; else -> 10 }
        val body = when (radix) { 16 -> digits.drop(2); 2 -> digits.drop(2); else -> digits }
        return body.toLongOrNull(radix)?.let { Expression(sign * it) }
            ?: if (Regex("[A-Za-z_.$][A-Za-z0-9_.$]*").matches(value)) Expression(0, value)
            else { expect("constant expression"); Expression(0) }
    }

    fun parseImmediate(text: String): Operand {
        val expression = parseExpression(text.trim().removePrefix("#"))
        val type = if (expression.value in -0x1000..0x0fff) OP_IM12S else OP_IM32
        return Operand(type = type, expression = expression)
    }

    fun parseOperand(source: String, csrValue: (String) -> Int? = { null },
        relocateSymbol: (String, Boolean) -> Unit = { _, _ -> }, isExternalOrStatic: (String) -> Boolean = { false }): Operand {
        val text = source.trim()
        parseRegister(text)?.let { return Operand(type = OP_REG, register = it) }
        val expressionText = text.removePrefix("$")
        val csr = csrValue(expressionText)
        val expression = if (csr != null) Expression(csr.toLong()) else parseExpression(expressionText)
        var operand = Operand(type = if (expression.value in -0x1000..0x0fff && expression.symbol == null) OP_IM12S else OP_IM32,
            expression = expression)
        if (expression.symbol != null && isExternalOrStatic(expression.symbol)) {
            relocateSymbol(expression.symbol, false)
            operand = operand.copy(type = OP_IM12S, expression = Expression(0, expression.symbol))
        } else if (expression.symbol != null) {
            expect("operand")
        }
        return operand
    }

    fun parseBranchOffset(source: String, isExternalOrStatic: (String) -> Boolean = { false }): Operand {
        val expression = parseExpression(source)
        val type = if (expression.symbol == null && expression.value in -0x1000..0x0fff) OP_IM12S else OP_IM32
        if (expression.symbol != null && !isExternalOrStatic(expression.symbol)) expect("operand")
        return Operand(type, expression = if (expression.symbol != null) Expression(0, expression.symbol) else expression)
    }

    fun parseJumpOffset(source: String, isExternalOrStatic: (String) -> Boolean = { false },
        relocateSymbol: (String, Boolean) -> Unit = { _, _ -> }): Operand {
        val expression = parseExpression(source)
        if (expression.symbol != null) {
            if (!isExternalOrStatic(expression.symbol)) expect("operand") else relocateSymbol(expression.symbol, true)
            return Operand(OP_IM12S, expression = Expression(0, expression.symbol))
        }
        return Operand(if (expression.value in -0x1000..0x0fff) OP_IM12S else OP_IM32, expression = expression)
    }

    fun parseOperands(source: String, count: Int, csrValue: (String) -> Int? = { null }): List<Operand> {
        val parts = splitOperands(source)
        if (parts.size != count) expect("$count operands")
        return parts.take(count).map { parseOperand(it, csrValue) }
    }

    /** Parses `X, imm(Y)` into destination, base register, and offset operands. */
    fun parseMemoryAccessOperands(source: String): List<Operand> {
        val parts = splitOperands(source)
        if (parts.size != 2) { expect("memory access operands"); return listOf(Operand(), Operand(), Operand()) }
        val destination = parseOperand(parts[0])
        val address = parts[1].trim()
        val open = address.indexOf('(')
        if (address.startsWith('(') && address.endsWith(')')) {
            val base = parseOperand(address.substring(1, address.length - 1))
            return listOf(destination, base, Operand(OP_IM12S))
        }
        if (open >= 0 && address.endsWith(')')) {
            val immediate = parseOperand(address.substring(0, open).ifBlank { "0" })
            val base = parseOperand(address.substring(open + 1, address.length - 1))
            return listOf(destination, base, immediate)
        }
        val base = parseOperand(address)
        return listOf(destination, base, Operand(OP_IM12S))
    }

    private fun splitOperands(source: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        source.forEachIndexed { index, char ->
            when (char) { '(' -> depth++; ')' -> depth-- ; ',' -> if (depth == 0) { parts += source.substring(start, index).trim(); start = index + 1 } }
        }
        if (source.isNotBlank()) parts += source.substring(start).trim()
        return parts
    }

    private fun requireRegister(operand: Operand, description: String): Boolean {
        if (operand.type == OP_REG) return true
        error("Expected $description to be a register")
        return false
    }

    fun emitR(opcode: Int, rd: Operand, rs1: Operand, rs2: Operand): Boolean {
        if (!requireRegister(rd, "destination operand") || !requireRegister(rs1, "first source operand") ||
            !requireRegister(rs2, "second source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register))
        return true
    }

    fun emitFloating(opcode: Int, rd: Operand, rs1: Operand, rs2: Operand): Boolean {
        if (!requireFloatRegister(rd, "destination operand") || !requireFloatRegister(rs1, "first source operand") ||
            !requireFloatRegister(rs2, "second source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register))
        return true
    }

    fun emitFloatingUnary(opcode: Int, rd: Operand, rs: Operand): Boolean {
        if (!requireFloatRegister(rd, "destination operand") || !requireFloatRegister(rs, "source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs.register))
        return true
    }

    fun emitFloatingQuaternary(opcode: Int, rd: Operand, rs1: Operand, rs2: Operand, rs3: Operand): Boolean {
        if (!requireFloatRegister(rd, "destination operand") || !requireFloatRegister(rs1, "first source operand") ||
            !requireFloatRegister(rs2, "second source operand") || !requireFloatRegister(rs3, "third source operand")) return false
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register) or (registerValue(rs3.register) shl 27))
        return true
    }

    private fun requireFloatRegister(operand: Operand, description: String): Boolean {
        if (operand.type == OP_REG && isFloatRegister(operand.register)) return true
        error("Expected $description to be a floating-point register")
        return false
    }

    fun emitI(opcode: Int, rd: Operand, rs1: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rd, "destination operand") || !requireRegister(rs1, "first source operand")) return false
        if (immediate.type != OP_IM12S) { error("Expected second source operand to be an immediate value between 0 and 8191"); return false }
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or (immediate.expression.value.toInt() shl 20))
        return true
    }

    fun emitU(opcode: Int, rd: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rd, "destination operand")) return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected source operand to be an immediate value"); return false }
        if (immediate.expression.value >= 0x100000) { error("Expected source operand immediate between 0 and 0xfffff"); return false }
        emitOpcode(opcode or encodeRd(rd.register) or (immediate.expression.value.toInt() shl 12))
        return true
    }

    fun emitJ(opcode: Int, rd: Operand, offset: Operand): Boolean {
        if (!requireRegister(rd, "destination operand")) return false
        if (offset.type != OP_IM12S && offset.type != OP_IM32) { error("Expected jump offset immediate"); return false }
        val immediate = offset.expression.value.toInt()
        if (immediate > (1 shl 20) - 1 || immediate <= -((1 shl 20) - 1)) { error("Expected jump offset in range"); return false }
        if (immediate and 1 != 0) { error("Expected an even jump offset"); return false }
        val encoded = (((immediate ushr 20) and 1) shl 31) or (((immediate ushr 1) and 0x3ff) shl 21) or
            (((immediate ushr 11) and 1) shl 20) or (((immediate ushr 12) and 0xff) shl 12)
        emitOpcode(opcode or encodeRd(rd.register) or encoded)
        return true
    }

    fun emitAtomic(opcode: Int, rd: Operand, rs2: Operand, rs1: Operand, acquire: Boolean, release: Boolean): Boolean {
        if (!requireRegister(rd, "first destination operand") || !requireRegister(rs2, "second source operand") ||
            !requireRegister(rs1, "third source operand")) return false
        emitOpcode(opcode or encodeRs1(rs1.register) or encodeRs2(rs2.register) or encodeRd(rd.register) or
            ((if (acquire) 1 else 0) shl 26) or ((if (release) 1 else 0) shl 25))
        return true
    }

    fun emitAtomicInstruction(name: String, rd: Operand, source: Operand?, base: Operand): Boolean {
        val acquire = name.endsWith("_aq") || name.endsWith("_aqrl")
        val release = name.endsWith("_rl") || name.endsWith("_aqrl")
        val operation = name.removeSuffix("_aqrl").removeSuffix("_aq").removeSuffix("_rl")
        val width = when { operation.endsWith("_w") -> 2; operation.endsWith("_d") -> 3; else -> { expect("atomic word or doubleword instruction"); return false } }
        val mnemonic = operation.removeSuffix("_w").removeSuffix("_d")
        val function = when (mnemonic) {
            "lr" -> 0x0c; "sc" -> 0x18; "amoadd" -> 0x00; "amoswap" -> 0x01
            "amoand" -> 0x0c; "amoor" -> 0x08; "amoxor" -> 0x04; "amomax" -> 0x14
            "amomaxu" -> 0x1c; "amomin" -> 0x10; "amominu" -> 0x18
            else -> { expect("atomic instruction"); return false }
        }
        val rs2 = if (mnemonic == "lr") Operand(OP_REG) else source ?: run { expect("atomic source register"); return false }
        return emitAtomic(0x2f or (width shl 12) or (function shl 27), rd, rs2, base, acquire, release)
    }

    fun emitFusedMultiplyAdd(name: String, operands: List<Operand>): Boolean {
        if (operands.size != 4) { expect("four fused multiply-add operands"); return false }
        val format = when {
            name.endsWith("_s") || name.endsWith(".s") -> 0
            name.endsWith("_d") || name.endsWith(".d") -> 1
            else -> { expect("single or double fused multiply-add"); return false }
        }
        if (name != "fmadd_s" && name != "fmadd_d" && name != "fmadd.s" && name != "fmadd.d") {
            expect("fused multiply-add instruction"); return false
        }
        return emitFloatingQuaternary(0x43 or (format shl 25) or (7 shl 12), operands[0], operands[1], operands[2], operands[3])
    }

    fun emitS(opcode: Int, rs1: Operand, rs2: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rs1, "first source operand") || !requireRegister(rs2, "second source operand")) return false
        if (immediate.type != OP_IM12S) { error("Expected immediate value between 0 and 8191"); return false }
        val value = immediate.expression.value.toInt()
        emitOpcode(opcode or encodeRs1(rs1.register) or encodeRs2(rs2.register) or ((value and 0x1f) shl 7) or ((value ushr 5) shl 25))
        return true
    }

    fun emitB(opcode: Int, rs1: Operand, rs2: Operand, immediate: Operand,
        expandFarBranch: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> error("far branch relocation required") }): Boolean {
        if (!requireRegister(rs1, "first source operand") || !requireRegister(rs2, "destination operand")) return false
        val symbol = immediate.expression.symbol
        if (immediate.type == OP_IM32 && symbol != null) {
            val inverseFunction = ((opcode ushr 12) and 7) xor 1
            val inverseOpcode = (opcode and (7 shl 12).inv()) or (inverseFunction shl 12)
            expandFarBranch(inverseOpcode, rs1.register, rs2.register, symbol)
            return true
        }
        if (immediate.type != OP_IM12S) { error("Expected branch immediate value between 0 and 8191"); return false }
        val offset = immediate.expression.value.toInt()
        emitOpcode(opcode or encodeRs1(rs1.register) or encodeRs2(rs2.register) or (((offset ushr 1) and 0xf) shl 8) or
            (((offset ushr 5) and 0x1f) shl 25) or (((offset ushr 11) and 1) shl 7) or (((offset ushr 12) and 1) shl 31))
        return true
    }

    private fun compactRegister(operand: Operand, role: String): Int? {
        if (!requireRegister(operand, role)) return null
        val register = registerValue(operand.register) - 8
        if (register !in 0..7 || isFloatRegister(operand.register)) { error("Expected $role to use a valid C-extension register"); return null }
        return register
    }

    fun emitCompressedCa(opcode: Int, rd: Operand, rs2: Operand): Boolean {
        val dst = compactRegister(rd, "destination operand") ?: return false
        val src = compactRegister(rs2, "source operand") ?: return false
        emitLittleEndian16(opcode or encodeCompressedRs2(src) or encodeCompressedRs1(dst))
        return true
    }

    fun emitCompressedCb(name: String, opcode: Int, rs1: Operand, immediate: Operand): Boolean {
        val source = compactRegister(rs1, "source operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset and 1 != 0) { error("Expected an even immediate value"); return false }
        val encoded = if (name == "c.beqz" || name == "c.bnez") {
            ((nthBit(offset, 5) or (((offset ushr 1) and 3) shl 1) or (((offset ushr 6) and 3) shl 3)) shl 2) or
                ((((offset ushr 3) and 3) or nthBit(offset, 8)) shl 10)
        } else ((offset and 0x1f) shl 2) or (nthBit(offset, 5) shl 12)
        emitLittleEndian16(opcode or encodeCompressedRs1(source) or encoded)
        return true
    }

    fun emitCompressedCi(name: String, opcode: Int, rd: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rd, "destination operand")) return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val value = immediate.expression.value.toInt()
        val encoded = when (name) {
            "c.addi", "c.addiw", "c.li", "c.slli" -> ((value and 0x1f) shl 2) or encodeRd(rd.register) or (nthBit(value, 5) shl 12)
            "c.addi16sp" -> nthBit(value, 5) shl 2 or (((value ushr 7) and 3) shl 3) or (nthBit(value, 6) shl 5) or
                (nthBit(value, 4) shl 6) or encodeRd(rd.register) or (nthBit(value, 9) shl 12)
            "c.lui" -> (((value ushr 12) and 0x1f) shl 2) or encodeRd(rd.register) or (nthBit(value, 17) shl 12)
            "c.fldsp", "c.ldsp" -> (((value ushr 6) and 7) shl 2) or (((value ushr 3) and 2) shl 5) or encodeRd(rd.register) or (nthBit(value, 5) shl 12)
            "c.flwsp", "c.lwsp" -> (((value ushr 6) and 3) shl 2) or (((value ushr 2) and 7) shl 4) or encodeRd(rd.register) or (nthBit(value, 5) shl 12)
            "c.nop" -> 0
            else -> { expect("known compressed instruction"); return false }
        }
        emitLittleEndian16(opcode or encoded)
        return true
    }

    fun emitCompressedCiw(opcode: Int, rd: Operand, immediate: Operand): Boolean {
        val dst = compactRegister(rd, "destination operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val value = immediate.expression.value.toInt()
        if (value > 0x3fc) { error("Expected immediate value between 0 and 0x3ff"); return false }
        if (value and 3 != 0) { error("Expected non-zero immediate divisible by 4"); return false }
        val field = nthBit(value, 3) or (nthBit(value, 2) shl 1) or (((value ushr 6) and 0xf) shl 2) or (((value ushr 4) and 3) shl 6)
        emitLittleEndian16(opcode or encodeCompressedRs2(dst) or (field shl 5))
        return true
    }

    fun emitCompressedCj(opcode: Int, immediate: Operand): Boolean {
        if (immediate.type != OP_IM12S) { error("Expected 12-bit immediate value"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset and 1 != 0) { error("Expected an even immediate value"); return false }
        val encoded = (nthBit(offset, 5) shl 2) or (((offset ushr 1) and 7) shl 3) or (nthBit(offset, 7) shl 6) or
            (nthBit(offset, 6) shl 7) or (nthBit(offset, 10) shl 8) or (((offset ushr 8) and 3) shl 9) or
            (nthBit(offset, 4) shl 11) or (nthBit(offset, 11) shl 12)
        emitLittleEndian16(opcode or encoded)
        return true
    }

    private fun compressedRegisterNumber(operand: Operand, role: String): Int? {
        if (!requireRegister(operand, role)) return null
        val register = registerValue(operand.register) - 8
        if (register !in 0..7) { error("Expected $role to use a valid C-extension register"); return null }
        return register
    }

    fun emitCompressedCl(name: String, opcode: Int, rd: Operand, rs1: Operand, immediate: Operand): Boolean {
        val dst = compressedRegisterNumber(rd, "destination operand") ?: return false
        val base = compressedRegisterNumber(rs1, "source operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset > 0xff) { error("Expected immediate value between 0 and 0xff"); return false }
        if (offset and 3 != 0) { error("Expected immediate divisible by 4"); return false }
        val encoded = when (name) {
            "c.flw", "c.lw" -> (nthBit(offset, 6) shl 5) or (nthBit(offset, 2) shl 6) or (((offset ushr 3) and 7) shl 10)
            "c.fld", "c.ld" -> (((offset ushr 6) and 3) shl 5) or (((offset ushr 3) and 7) shl 10)
            else -> { expect("known compressed load"); return false }
        }
        emitLittleEndian16(opcode or encodeCompressedRs2(dst) or encodeCompressedRs1(base) or encoded)
        return true
    }

    fun emitCompressedCr(opcode: Int, rd: Operand, rs2: Operand): Boolean {
        if (!requireRegister(rd, "destination operand") || !requireRegister(rs2, "source operand")) return false
        emitLittleEndian16(opcode or encodeCompressedRs1(rd.register) or encodeCompressedRs2(rs2.register))
        return true
    }

    fun emitCompressedCs(name: String, opcode: Int, rs2: Operand, rs1: Operand, immediate: Operand): Boolean {
        val base = compressedRegisterNumber(rs1, "base operand") ?: return false
        val source = compressedRegisterNumber(rs2, "source operand") ?: return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset > 0xff) { error("Expected immediate value between 0 and 0xff"); return false }
        if (offset and 3 != 0) { error("Expected immediate divisible by 4"); return false }
        val encoded = when (name) {
            "c.fsw", "c.sw" -> (nthBit(offset, 6) shl 5) or (nthBit(offset, 2) shl 6) or (((offset ushr 3) and 7) shl 10)
            "c.fsd", "c.sd" -> (((offset ushr 6) and 3) shl 5) or (((offset ushr 3) and 7) shl 10)
            else -> { expect("known compressed store"); return false }
        }
        emitLittleEndian16(opcode or encodeCompressedRs2(base) or encodeCompressedRs1(source) or encoded)
        return true
    }

    fun emitCompressedCss(name: String, opcode: Int, rs2: Operand, immediate: Operand): Boolean {
        if (!requireRegister(rs2, "destination operand")) return false
        if (immediate.type != OP_IM12S && immediate.type != OP_IM32) { error("Expected immediate source operand"); return false }
        val offset = immediate.expression.value.toInt()
        if (offset > 0xff) { error("Expected immediate value between 0 and 0xff"); return false }
        if (offset and 3 != 0) { error("Expected immediate divisible by 4"); return false }
        val encoded = when (name) {
            "c.fswsp", "c.swsp" -> (((offset ushr 6) and 3) shl 7) or (((offset ushr 2) and 0xf) shl 9)
            "c.fsdsp", "c.sdsp" -> (((offset ushr 6) and 7) shl 7) or (((offset ushr 3) and 7) shl 10)
            else -> { expect("known compressed stack store"); return false }
        }
        emitLittleEndian16(opcode or (registerValue(rs2.register) shl 2) or encoded)
        return true
    }

    fun emitTernaryInstruction(name: String, operands: List<Operand>): Boolean {
        if (operands.size != 3) { expect("three operands"); return false }
        val (rd, rs1, rs2) = operands
        val rOpcodes = mapOf(
            "sll" to (0x33 or (1 shl 12)), "srl" to (0x33 or (5 shl 12)), "sra" to (0x33 or (5 shl 12) or (0x20 shl 25)),
            "sllw" to (0x3b or (1 shl 12)), "srlw" to (0x3b or (5 shl 12)), "sraw" to (0x3b or (5 shl 12)),
            "add" to 0x33, "sub" to (0x33 or (0x20 shl 25)), "addw" to 0x3b,
            "subw" to (0x3b or (0x20 shl 25)), "xor" to (0x33 or (4 shl 12)),
            "or" to (0x33 or (6 shl 12)), "and" to (0x33 or (7 shl 12)),
            "slt" to (0x33 or (2 shl 12)), "sltu" to (0x33 or (3 shl 12)),
            "mul" to (0x33 or (1 shl 25)), "mulh" to (0x33 or (1 shl 12) or (1 shl 25)),
            "mulhsu" to (0x33 or (2 shl 12) or (1 shl 25)), "mulhu" to (0x33 or (3 shl 12) or (1 shl 25)),
            "mulw" to (0x3b or (1 shl 25)), "div" to (0x33 or (4 shl 12) or (1 shl 25)),
            "divu" to (0x33 or (5 shl 12) or (1 shl 25)), "divw" to (0x3b or (4 shl 12) or (1 shl 25)),
            "divuw" to (0x3b or (5 shl 12) or (1 shl 25)), "rem" to (0x33 or (6 shl 12) or (1 shl 25)),
            "remu" to (0x33 or (7 shl 12) or (1 shl 25)), "remw" to (0x3b or (6 shl 12) or (1 shl 25)),
            "remuw" to (0x3b or (7 shl 12) or (1 shl 25)),
        )
        rOpcodes[name]?.let { return emitR(it, rd, rs1, rs2) }
        val iOpcodes = mapOf(
            "slli" to (0x13 or (1 shl 12)), "srli" to (0x13 or (5 shl 12)), "srai" to (0x13 or (5 shl 12) or (16 shl 26)),
            "slliw" to (0x1b or (1 shl 12)), "srliw" to (0x1b or (5 shl 12)), "sraiw" to (0x1b or (5 shl 12)),
            "addi" to 0x13, "addiw" to 0x1b, "xori" to (0x13 or (4 shl 12)), "ori" to (0x13 or (6 shl 12)),
            "andi" to (0x13 or (7 shl 12)), "slti" to (0x13 or (2 shl 12)), "sltiu" to (0x13 or (3 shl 12)),
        )
        iOpcodes[name]?.let { return emitI(it, rd, rs1, rs2) }
        expect("ternary instruction")
        return false
    }

    fun parseRoundingMode(name: String?): Int = when (name) {
        null -> 7; "rne" -> 0; "rtz" -> 1; "rdn" -> 2; "rup" -> 3; "rmm" -> 4
        else -> { expect("rounding mode"); 7 }
    }

    fun emitFloatingInstruction(name: String, operands: List<Operand>, roundingMode: Int = 7): Boolean {
        if (operands.size != 2 && operands.size != 3) { expect("two or three floating point operands"); return false }
        val rd = operands[0]
        val rs1 = operands[1]
        val format = if (name.endsWith("_d") || name.endsWith(".d")) 1 else 0
        val simple = mapOf("fadd" to (0 to 7), "fsub" to (1 to 7), "fmul" to (2 to 7), "fdiv" to (3 to 7),
            "fsgnj" to (4 to 0), "fmin" to (5 to 0), "fmax" to (5 to 1))
        val operation = name.removeSuffix("_s").removeSuffix("_d").removeSuffix(".s").removeSuffix(".d")
        if (operation == "fsqrt") {
            if (operands.size != 2) { expect("two floating point operands"); return false }
            return emitFloatingUnary(0x53 or (11 shl 27) or (format shl 25) or (7 shl 12), rd, rs1)
        }
        if (operation in setOf("fneg", "fmv", "fabs")) {
            val function3 = when (operation) { "fneg" -> 1; "fmv" -> 0; else -> 2 }
            val function5 = if (operation == "fabs") 4 else 4
            val opcode = 0x53 or (function5 shl 27) or (format shl 25) or (function3 shl 12)
            return emitFloating(opcode, rd, rs1, rs1)
        }
        val comparison = mapOf("feq" to 2, "flt" to 1, "fle" to 0)
        if (operation in comparison && operands.size == 3) {
            val rs2 = operands[2]
            val opcode = 0x53 or (0x14 shl 27) or (format shl 25) or (comparison.getValue(operation) shl 12)
            emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register) or encodeRs2(rs2.register))
            return true
        }
        val encoding = simple[operation]
        if (encoding != null && operands.size == 3) {
            val opcode = 0x53 or (encoding.first shl 27) or (format shl 25) or (encoding.second shl 12)
            return emitFloating(opcode, rd, rs1, operands[2])
        }
        val convert = conversionEncoding(name.replace('_', '.')) ?: run { expect("floating point instruction"); return false }
        val rm = if (convert.third < 0) roundingMode else convert.third
        val opcode = 0x53 or (convert.first shl 25) or (rm shl 12) or (convert.second shl 20)
        emitOpcode(opcode or encodeRd(rd.register) or encodeRs1(rs1.register))
        return true
    }

    private fun conversionEncoding(name: String): Triple<Int, Int, Int>? {
        val encodings = mapOf(
            "fcvt.w.s" to Triple(0x60, 0, -1), "fcvt.wu.s" to Triple(0x60, 1, -1),
            "fcvt.l.s" to Triple(0x60, 2, -1), "fcvt.lu.s" to Triple(0x60, 3, -1),
            "fcvt.w.d" to Triple(0x61, 0, -1), "fcvt.wu.d" to Triple(0x61, 1, -1),
            "fcvt.l.d" to Triple(0x61, 2, -1), "fcvt.lu.d" to Triple(0x61, 3, -1),
            "fcvt.s.w" to Triple(0x68, 0, 7), "fcvt.s.wu" to Triple(0x68, 1, 7),
            "fcvt.s.l" to Triple(0x68, 2, 7), "fcvt.s.lu" to Triple(0x68, 3, 7),
            "fcvt.d.w" to Triple(0x69, 0, 7), "fcvt.d.wu" to Triple(0x69, 1, 7),
            "fcvt.d.l" to Triple(0x69, 2, 7), "fcvt.d.lu" to Triple(0x69, 3, 7),
            "fcvt.s.d" to Triple(0x20, 1, 7), "fcvt.d.s" to Triple(0x21, 0, 7),
            "fclass.s" to Triple(0x70, 0, 1), "fclass.d" to Triple(0x71, 0, 1),
        )
        return encodings[name]
    }

    fun emitBinaryInstruction(name: String, rd: Operand, source: Operand): Boolean = when (name) {
        "lui" -> emitU(0x37, rd, source)
        "auipc" -> emitU(0x17, rd, source)
        "c.add" -> emitCompressedCr(2 or (9 shl 12), rd, source)
        "c.mv" -> emitCompressedCr(2 or (8 shl 12), rd, source)
        "c.addi" -> emitCompressedCi(name, 1, rd, source)
        "c.addiw" -> emitCompressedCi(name, 1 or (1 shl 13), rd, source)
        "c.addi16sp" -> emitCompressedCi(name, 1 or (3 shl 13), rd, source)
        "c.fldsp" -> emitCompressedCi(name, 2 or (1 shl 13), rd, source)
        "c.flwsp", "c.ldsp" -> emitCompressedCi(name, 2 or (3 shl 13), rd, source)
        "c.li" -> emitCompressedCi(name, 1 or (2 shl 13), rd, source)
        "c.lui" -> emitCompressedCi(name, 1 or (3 shl 13), rd, source)
        "c.lwsp" -> emitCompressedCi(name, 2 or (2 shl 13), rd, source)
        "c.slli" -> emitCompressedCi(name, 2, rd, source)
        "c.addi4spn" -> emitCompressedCiw(0, rd, source)
        else -> { expect("binary instruction"); false }
    }

    fun emitMemoryInstruction(name: String, operands: List<Operand>, isStaticSymbol: (String) -> Boolean = { false },
        relocateAddress: (String) -> Unit = {}): Boolean {
        if (operands.size != 3) { expect("memory access operands"); return false }
        val (destination, parsedBase, parsedOffset) = operands
        var base = parsedBase
        var offset = parsedOffset
        val symbol = base.expression.symbol
        if (symbol != null && isStaticSymbol(symbol)) {
            relocateAddress(symbol)
            base = destination
            offset = Operand(OP_IM12S)
            if (!emitU(0x17, destination, Operand(OP_IM12S))) return false
        }
        val loads = mapOf("lb" to 0x03, "lh" to 0x1003, "lw" to 0x2003, "ld" to 0x3003,
            "lbu" to 0x4003, "lhu" to 0x5003, "lwu" to 0x6003, "fld" to 0x3007)
        loads[name]?.let { return emitI(it, destination, base, offset) }
        val stores = mapOf("sb" to 0x23, "sh" to 0x1023, "sw" to 0x2023, "sd" to 0x3023, "fsd" to 0x3027)
        stores[name]?.let { return emitS(it, base, destination, offset) }
        expect("memory access instruction")
        return false
    }

    fun emitBranchInstruction(name: String, operands: List<Operand>,
        expandFarBranch: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> error("far branch relocation required") }): Boolean {
        val zero = Operand(OP_REG, register = 0)
        val offset: Operand
        var first: Operand
        var second: Operand
        val comparison = when (name) {
            "beq", "beqz" -> 0; "bne", "bnez" -> 1; "blt", "bgt", "bltz", "bgtz" -> 4
            "bge", "ble", "blez", "bgez" -> 5; "bltu", "bgtu" -> 6; "bgeu", "bleu" -> 7
            else -> { expect("branch instruction"); return false }
        }
        if (name in setOf("beqz", "bnez", "blez", "bgez", "bltz", "bgtz")) {
            if (operands.size != 2) { expect("two branch operands"); return false }
            first = operands[0]; offset = operands[1]
            second = if (name == "bgez" || name == "bgtz") first.also { first = zero } else zero
        } else {
            if (operands.size != 3) { expect("three branch operands"); return false }
            first = operands[0]; second = operands[1]; offset = operands[2]
            if (name in setOf("bgt", "ble", "bgtu", "bleu")) { val swap = first; first = second; second = swap }
        }
        return emitB(0x63 or (comparison shl 12), first, second, offset, expandFarBranch)
    }
}
