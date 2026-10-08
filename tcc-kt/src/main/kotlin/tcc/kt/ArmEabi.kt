package tcc.kt

/** ARM EABI arithmetic and memory helpers from lib/armeabi.c. */
object ArmEabi {
    data class DoubleUnsigned(val low: Int, val high: Int) {
        fun toLongBits(): Long = (high.toLong() shl 32) or (low.toLong() and 0xffff_ffffL)
        companion object {
            fun fromLongBits(value: Long) = DoubleUnsigned(value.toInt(), (value ushr 32).toInt())
        }
    }

    data class UnsignedInt(val low: Int, val high: Int)
    data class DivMod(val quotient: Long, val remainder: Long)

    @JvmStatic fun f2ulz(bits: Int): Long = Float.fromBits(bits).toDouble().toLong()
    @JvmStatic fun f2lz(bits: Int): Long = Float.fromBits(bits).toDouble().toLong()
    @JvmStatic fun d2ulz(bits: Long): Long = Double.fromBits(bits).toLong()
    @JvmStatic fun d2lz(bits: Long): Long = Double.fromBits(bits).toLong()
    @JvmStatic fun ul2f(value: Long): Int = value.toDouble().toFloat().toRawBits()
    @JvmStatic fun l2f(value: Long): Int = value.toFloat().toRawBits()
    @JvmStatic fun ul2d(value: Long): Long = value.toDouble().toRawBits()
    @JvmStatic fun l2d(value: Long): Long = value.toDouble().toRawBits()

    @JvmStatic
    fun uldivmod(numerator: Long, denominator: Long): DivMod {
        val n = java.math.BigInteger.valueOf(numerator).let { if (numerator < 0) it.add(java.math.BigInteger.ONE.shiftLeft(64)) else it }
        val d = java.math.BigInteger.valueOf(denominator).let { if (denominator < 0) it.add(java.math.BigInteger.ONE.shiftLeft(64)) else it }
        val qr = n.divideAndRemainder(d)
        return DivMod(qr[0].toLong(), qr[1].toLong())
    }

    @JvmStatic
    fun lldivmod(numerator: Long, denominator: Long): DivMod = DivMod(numerator / denominator, numerator % denominator)

    @JvmStatic
    fun uidivmod(numerator: Int, denominator: Int): DivMod {
        val n = numerator.toLong() and 0xffff_ffffL
        val d = denominator.toLong() and 0xffff_ffffL
        return DivMod(n / d, n % d)
    }

    @JvmStatic
    fun idivmod(numerator: Int, denominator: Int): DivMod = DivMod((numerator / denominator).toLong(), (numerator % denominator).toLong())

    @JvmStatic fun uidiv(numerator: Int, denominator: Int): Int = uidivmod(numerator, denominator).quotient.toInt()
    @JvmStatic fun idiv(numerator: Int, denominator: Int): Int = numerator / denominator
    @JvmStatic fun uldiv(numerator: Long, denominator: Long): Long = uldivmod(numerator, denominator).quotient
    @JvmStatic fun ldiv(numerator: Long, denominator: Long): Long = numerator / denominator

    /** Two-word unsigned left shift; the C helper returns the result in registers. */
    @JvmStatic
    fun llsl(value: DoubleUnsigned, shift: Int): DoubleUnsigned {
        if (shift >= 64) return DoubleUnsigned(0, 0)
        val bits = value.toLongBits() shl shift
        return DoubleUnsigned.fromLongBits(bits)
    }

    /** Two-word logical right shift. */
    @JvmStatic
    fun llsr(value: DoubleUnsigned, shift: Int): DoubleUnsigned {
        if (shift >= 64) return DoubleUnsigned(0, 0)
        return DoubleUnsigned.fromLongBits(value.toLongBits() ushr shift)
    }

    /** Signed two-word arithmetic right shift. */
    @JvmStatic
    fun lasr(value: UnsignedInt, shift: Int): UnsignedInt {
        val bits = (value.high.toLong() shl 32) or (value.low.toLong() and 0xffff_ffffL)
        val result = if (shift >= 64) if (value.high < 0) -1L else 0L else bits shr shift
        return UnsignedInt(result.toInt(), (result shr 32).toInt())
    }

    @JvmStatic fun memCopy(dst: ByteArray, dstOffset: Int, src: ByteArray, srcOffset: Int, size: Int): ByteArray {
        src.copyInto(dst, dstOffset, srcOffset, srcOffset + size)
        return dst
    }

    @JvmStatic fun memMove(dst: ByteArray, dstOffset: Int, src: ByteArray, srcOffset: Int, size: Int): ByteArray {
        val snapshot = src.copyOfRange(srcOffset, srcOffset + size)
        snapshot.copyInto(dst, dstOffset)
        return dst
    }

    @JvmStatic fun memSet(dst: ByteArray, size: Int, value: Int): ByteArray {
        dst.fill(value.toByte(), 0, size)
        return dst
    }
}
