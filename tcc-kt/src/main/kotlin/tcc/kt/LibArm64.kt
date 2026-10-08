package tcc.kt

/** AArch64 128-bit floating point runtime helpers mechanically translated from lib-arm64.c. */
object LibArm64 {
    data class UInt128(val low: ULong, val high: ULong)
    private data class Unpacked(val sign: Int, val exponent: Int, val mantissa: UInt128)

    private fun zero(sign: Int) = UInt128(0uL, sign.toULong() shl 63)
    private fun infinity(sign: Int) = UInt128(0uL, (sign.toULong() shl 63) or 0x7fff000000000000uL)
    private fun nan() = UInt128(ULong.MAX_VALUE, 0x7fffffffffffffffuL)

    private fun isZero(value: UInt128) = value.low == 0uL && value.high == 0uL
    private fun isNaN(value: Unpacked) = value.exponent == 32767 && (value.mantissa.low or (value.mantissa.high shl 16)) != 0uL

    private fun unpack(value: UInt128): Unpacked {
        val sign = (value.high shr 63).toInt()
        var exponent = ((value.high shr 48) and 32767uL).toInt()
        var high = value.high shl 16 shr 16
        if (exponent != 0) high = high or (1uL shl 48) else exponent = 1
        return Unpacked(sign, exponent, UInt128(value.low, high))
    }

    private fun normalized(exponent: Int, mantissa: UInt128): Pair<Int, UInt128> {
        var exp = exponent
        var low = mantissa.low
        var high = mantissa.high
        if (low == 0uL && high == 0uL) return exp to mantissa
        if (high == 0uL) { high = low; low = 0uL; exp -= 64 }
        var shift = 32
        while (shift > 0) {
            if (high shr (64 - shift) == 0uL) {
                high = (high shl shift) or (low shr (64 - shift))
                low = low shl shift
                exp -= shift
            }
            shift = shift shr 1
        }
        return exp to UInt128(low, high)
    }

    private fun stickyShift(shift: Int, mantissa: UInt128): UInt128 {
        var n = shift
        var low = mantissa.low
        var high = mantissa.high
        if (n >= 128) return UInt128(if (low or high != 0uL) 1uL else 0uL, 0uL)
        if (n >= 64) {
            low = high or if (low != 0uL) 1uL else 0uL
            high = 0uL
            n -= 64
        }
        if (n > 0) {
            low = (low shr n) or (high shl (64 - n)) or if (low shl (64 - n) != 0uL) 1uL else 0uL
            high = high shr n
        }
        return UInt128(low, high)
    }

    private fun round(sign: Int, exponent: Int, value: UInt128): UInt128 {
        var exp = exponent
        var x = if (exp > 0) stickyShift(13, value) else stickyShift(14 - exp, value).also { exp = 0 }
        val error = (x.low and 3uL).toInt()
        x = UInt128((x.low shr 2) or (x.high shl 62), x.high shr 2)
        if (error == 3 || (error == 2 && (x.low and 1uL) != 0uL)) {
            val old = x.low
            val low = old + 1uL
            var high = x.high
            if (low == 0uL) {
                high++
                if (high == (1uL shl 48)) exp = 1
                else if (high == (1uL shl 49)) {
                    exp++
                    high = (high shr 1) or (low shl 63)
                }
            }
            x = UInt128(low, high)
        }
        if (exp >= 32767) return infinity(sign)
        val high = (x.high shl 16 shr 16) or (exp.toULong() shl 48) or (sign.toULong() shl 63)
        return UInt128(x.low, high)
    }

    private fun propagateNaN(a: Unpacked, b: Unpacked): UInt128? {
        val source = when { isNaN(a) -> a; isNaN(b) -> b; else -> return null }
        return UInt128(source.mantissa.low, source.mantissa.high or 0x7fff800000000000uL or (source.sign.toULong() shl 63))
    }

    private fun add(left: UInt128, right: UInt128, negateRight: Boolean): UInt128 {
        val a = unpack(left); val b0 = unpack(right)
        propagateNaN(a, b0)?.let { return it }
        val bSign = b0.sign xor if (negateRight) 1 else 0
        val am = a.mantissa; val bm = b0.mantissa
        if (a.exponent == 32767 && b0.exponent == 32767 && a.sign != bSign) return nan()
        if (a.exponent == 32767) return infinity(a.sign)
        if (b0.exponent == 32767) return infinity(bSign)
        if (isZero(am) && isZero(bm)) return zero(a.sign and bSign)
        var al = (am.low shl 3); var ah = (am.high shl 3) or (am.low shr 61)
        var bl = (bm.low shl 3); var bh = (bm.high shl 3) or (bm.low shr 61)
        var ae = a.exponent; var be = b0.exponent
        if (ae <= be) { val shifted = stickyShift(be - ae, UInt128(al, ah)); al = shifted.low; ah = shifted.high; ae = be }
        else { val shifted = stickyShift(ae - be, UInt128(bl, bh)); bl = shifted.low; bh = shifted.high; be = ae }
        var sign = a.sign
        var low: ULong
        var high: ULong
        if (a.sign == bSign) {
            low = al + bl
            high = ah + bh + if (low < al) 1uL else 0uL
        } else {
            low = al - bl
            high = ah - bh - if (al < bl) 1uL else 0uL
            if (high shr 63 != 0uL) {
                sign = sign xor 1
                low = 0uL - low
                high = 0uL - high - if (low != 0uL) 1uL else 0uL
            }
        }
        if (low == 0uL && high == 0uL) return zero(0)
        val (exp, normalized) = normalized(ae, UInt128(low, high))
        return round(sign, exp + 12, normalized)
    }

    fun addtf3(a: UInt128, b: UInt128): UInt128 = add(a, b, false)
    fun subtf3(a: UInt128, b: UInt128): UInt128 = add(a, b, true)
    fun negtf2(value: UInt128): UInt128 = UInt128(value.low, value.high xor (1uL shl 63))
}
