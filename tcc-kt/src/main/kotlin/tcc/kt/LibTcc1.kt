package tcc.kt

import java.math.BigInteger

/** Generic arithmetic runtime helpers from lib/libtcc1.c. */
object LibTcc1 {
    private val TWO_64 = BigInteger.ONE.shiftLeft(64)
    private val TWO_64_MASK = TWO_64.subtract(BigInteger.ONE)
    private val SIGN_BIT = Long.MIN_VALUE

    data class UnsignedDivision(val quotient: Long, val remainder: Long)
    data class Extended80(val significand: Long, val signExponent: Int)

    private fun unsigned(value: Long): BigInteger = BigInteger.valueOf(value).let { if (value < 0) it.add(TWO_64) else it }
    private fun low64(value: BigInteger): Long = value.and(TWO_64_MASK).toLong()

    @JvmStatic fun udivmoddi4(numerator: Long, denominator: Long): UnsignedDivision {
        val result = unsigned(numerator).divideAndRemainder(unsigned(denominator))
        return UnsignedDivision(low64(result[0]), low64(result[1]))
    }
    @JvmStatic fun divdi3(numerator: Long, denominator: Long): Long = numerator / denominator
    @JvmStatic fun moddi3(numerator: Long, denominator: Long): Long = numerator % denominator
    @JvmStatic fun udivdi3(numerator: Long, denominator: Long): Long = udivmoddi4(numerator, denominator).quotient
    @JvmStatic fun umoddi3(numerator: Long, denominator: Long): Long = udivmoddi4(numerator, denominator).remainder

    @JvmStatic fun ashrdi3(value: Long, shift: Int): Long = if (shift >= 64) if (value < 0) -1L else 0L else value shr shift
    @JvmStatic fun lshrdi3(value: Long, shift: Int): Long = if (shift >= 64) 0L else value ushr shift
    @JvmStatic fun ashldi3(value: Long, shift: Int): Long = if (shift >= 64) 0L else value shl shift

    @JvmStatic fun floatundisf(value: Long): Float = unsigned(value).toFloat()
    @JvmStatic fun floatundidf(value: Long): Double = unsigned(value).toDouble()
    @JvmStatic
    fun floatundixf(value: Long): Extended80 {
        val magnitude = unsigned(value)
        if (magnitude.signum() == 0) return Extended80(0L, 0)
        val exponent = magnitude.bitLength() - 1
        val significand = low64(magnitude.shiftLeft(63 - exponent))
        return Extended80(significand, exponent + 16383)
    }

    /** Unsigned conversion following the C helper's exponent and mantissa shifts. */
    @JvmStatic
    fun fixunssfdi(value: Float): Long {
        val bits = value.toRawBits()
        if (bits == 0) return 0
        val exponent = ((bits ushr 23) and 0xff) - 126 - 24
        var mantissa = ((bits and 0x7fffff) or (1 shl 23)).toLong()
        mantissa = when {
            exponent >= 41 -> return SIGN_BIT
            exponent >= 0 -> mantissa shl exponent
            exponent >= -23 -> mantissa ushr -exponent
            else -> return 0
        }
        val result = mantissa
        return if (bits < 0) -result else result
    }

    @JvmStatic fun fixsfdi(value: Float): Long = if (value >= 0f) fixunssfdi(value) else -fixunssfdi(-value)

    @JvmStatic
    fun fixunsdfdi(value: Double): Long {
        val bits = value.toRawBits()
        if (bits == 0L) return 0
        val exponent = ((bits ushr 52) and 0x7ff).toInt() - 1022 - 53
        var mantissa = (bits and ((1L shl 52) - 1)) or (1L shl 52)
        mantissa = when {
            exponent >= 12 -> return SIGN_BIT
            exponent >= 0 -> mantissa shl exponent
            exponent >= -52 -> mantissa ushr -exponent
            else -> return 0
        }
        return if (bits < 0) -mantissa else mantissa
    }

    @JvmStatic fun fixdfdi(value: Double): Long = if (value >= 0.0) fixunsdfdi(value) else -fixunsdfdi(-value)

    /** Converts the C x86 extended-precision representation to an unsigned word. */
    @JvmStatic
    fun fixunsxfdi(value: Extended80): Long {
        val exponent = (value.signExponent and 0x7fff) - 16382 - 64
        if (value.significand == 0L && value.signExponent == 0) return 0
        if (exponent > 0) return SIGN_BIT
        if (exponent < -63) return 0
        val magnitude = value.significand ushr -exponent
        return if ((value.signExponent and 0x8000) != 0) -magnitude else magnitude
    }

    @JvmStatic fun fixxfdi(value: Extended80): Long = if ((value.signExponent and 0x8000) == 0) fixunsxfdi(value) else -fixunsxfdi(value.copy(signExponent = value.signExponent and 0x7fff))
}
