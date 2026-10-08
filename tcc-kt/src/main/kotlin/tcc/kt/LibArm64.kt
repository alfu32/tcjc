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

    fun multf3(left: UInt128, right: UInt128): UInt128 {
        val a = unpack(left); val b = unpack(right)
        propagateNaN(a, b)?.let { return it }
        if ((a.exponent == 32767 && isZero(b.mantissa)) || (b.exponent == 32767 && isZero(a.mantissa))) return nan()
        if (a.exponent == 32767 || b.exponent == 32767) return infinity(a.sign xor b.sign)
        if (isZero(a.mantissa) || isZero(b.mantissa)) return zero(a.sign xor b.sign)
        val (aExp, am) = normalized(a.exponent, a.mantissa)
        val (bExp, bm) = normalized(b.exponent, b.mantissa)
        var exponent = aExp + bExp - 16352
        val a0 = (am.low shl 28 shr 34)
        val b0 = (bm.low shl 28 shr 34)
        val a1 = (am.low shr 36) or (am.high shl 62 shr 34)
        val b1 = (bm.low shr 36) or (bm.high shl 62 shr 34)
        val a2 = am.high shl 32 shr 34
        val b2 = bm.high shl 32 shr 34
        val a3 = am.high shr 32
        val b3 = bm.high shr 32
        val x0 = a0 * b0
        val x1 = (x0 shr 30) + a0 * b1 + a1 * b0
        val x2 = (x1 shr 30) + a0 * b2 + a1 * b1 + a2 * b0
        val x3 = (x2 shr 30) + a0 * b3 + a1 * b2 + a2 * b1 + a3 * b0
        val x4 = (x3 shr 30) + a1 * b3 + a2 * b2 + a3 * b1
        val x5 = (x4 shr 30) + a2 * b3 + a3 * b2
        val x6 = (x5 shr 30) + a3 * b3
        var low = (x5 shl 34) or (x4 shl 34 shr 30) or (x3 shl 34 shr 60) or
            if ((x3 shl 38) or ((x2 or x1 or x0) shl 34) != 0uL) 1uL else 0uL
        var high = x6
        if (high shr 63 == 0uL) {
            high = (high shl 1) or (low shr 63)
            low = low shl 1
            exponent--
        }
        return round(a.sign xor b.sign, exponent, UInt128(low, high))
    }

    fun divtf3(left: UInt128, right: UInt128): UInt128 {
        val a = unpack(left); val b = unpack(right)
        propagateNaN(a, b)?.let { return it }
        if ((a.exponent == 32767 && b.exponent == 32767) || (isZero(a.mantissa) && isZero(b.mantissa))) return nan()
        if (a.exponent == 32767 || isZero(b.mantissa)) return infinity(a.sign xor b.sign)
        if (isZero(a.mantissa) || b.exponent == 32767) return zero(a.sign xor b.sign)
        val (aExp, am0) = normalized(a.exponent, a.mantissa)
        val (bExp, bm0) = normalized(b.exponent, b.mantissa)
        val exponent = aExp - bExp + 16395
        var aLow = (am0.low shr 1) or (am0.high shl 63)
        var aHigh = am0.high shr 1
        val bLow = (bm0.low shr 1) or (bm0.high shl 63)
        val bHigh = bm0.high shr 1
        var xLow = 0uL
        var xHigh = 0uL
        repeat(116) {
            xHigh = (xHigh shl 1) or (xLow shr 63)
            xLow = xLow shl 1
            if (aHigh > bHigh || (aHigh == bHigh && aLow >= bLow)) {
                val oldLow = aLow
                aLow -= bLow
                aHigh = aHigh - bHigh - if (oldLow < bLow) 1uL else 0uL
                xLow = xLow or 1uL
            }
            aHigh = (aHigh shl 1) or (aLow shr 63)
            aLow = aLow shl 1
        }
        xLow = xLow or if (aLow or aHigh != 0uL) 1uL else 0uL
        val (normalizedExponent, normalizedValue) = normalized(exponent, UInt128(xLow, xHigh))
        return round(a.sign xor b.sign, normalizedExponent, normalizedValue)
    }

    fun extendsftf2(value: Float): UInt128 {
        val bits = value.toRawBits().toUInt()
        val raw = bits.toULong()
        var low = 0uL
        val high: ULong = when {
            (bits shl 1) == 0u -> raw shl 32
            ((bits shl 1) shr 24) == 255u -> 0x7fff000000000000uL or (raw shr 31 shl 63) or (raw shl 41 shr 16) or ((if (bits shl 9 != 0u) 1uL else 0uL) shl 47)
            ((bits shl 1) shr 24) == 0u -> {
                var adjustment = 0
                while (((bits shl 1) shr 1 shr (23 - adjustment)) == 0u) adjustment++
                (raw shr 31 shl 63) or ((16256 - adjustment + 1).toULong() shl 48) or (raw shl adjustment shl 41 shr 16)
            }
            else -> (raw shr 31 shl 63) or ((((raw shr 23) and 255uL) + 16256uL) shl 48) or (raw shl 41 shr 16)
        }
        return UInt128(low, high)
    }

    fun extenddftf2(value: Double): UInt128 {
        val bits = value.toRawBits().toULong()
        var low = bits shl 60
        val high = when {
            bits shl 1 == 0uL -> bits
            (bits shl 1 shr 53) == 2047uL -> 0x7fff000000000000uL or (bits shr 63 shl 63) or (bits shl 12 shr 16) or ((if (bits shl 12 != 0uL) 1uL else 0uL) shl 47)
            (bits shl 1 shr 53) == 0uL -> {
                var adjustment = 0
                while ((bits shl 1 shr 1 shr (52 - adjustment)) == 0uL) adjustment++
                low = low shl adjustment
                (bits shr 63 shl 63) or ((15360 - adjustment + 1).toULong() shl 48) or (bits shl adjustment shl 12 shr 16)
            }
            else -> (bits shr 63 shl 63) or ((((bits shr 52) and 2047uL) + 15360uL) shl 48) or (bits shl 12 shr 16)
        }
        return UInt128(low, high)
    }

    fun trunctfsf2(value: UInt128): Float {
        val unpacked = unpack(value)
        val mantissa = unpacked.mantissa
        val bits = when {
            unpacked.exponent == 32767 && (mantissa.low or (mantissa.high shl 16)) != 0uL -> 0x7fc00000u or (unpacked.sign.toUInt() shl 31) or ((mantissa.high shr 25).toUInt() and 0x007fffffu)
            unpacked.exponent > 16510 -> 0x7f800000u or (unpacked.sign.toUInt() shl 31)
            unpacked.exponent < 16233 -> unpacked.sign.toUInt() shl 31
            else -> {
                var exp = unpacked.exponent - 16257
                var x = (mantissa.high shr 23) or (if ((mantissa.low or (mantissa.high shl 41)) != 0uL) 1uL else 0uL)
                if (exp < 0) { x = (x shr -exp) or (if (x shl (32 + exp) != 0uL) 1uL else 0uL); exp = 0 }
                if ((x and 3uL) == 3uL || (x and 7uL) == 6uL) x += 4uL
                (((x shr 2) + (exp.toULong() shl 23)).toUInt()) or (unpacked.sign.toUInt() shl 31)
            }
        }
        return Float.fromBits(bits.toInt())
    }

    fun trunctfdf2(value: UInt128): Double {
        val unpacked = unpack(value)
        val mantissa = unpacked.mantissa
        val bits = when {
            unpacked.exponent == 32767 && (mantissa.low or (mantissa.high shl 16)) != 0uL -> 0x7ff8000000000000uL or (unpacked.sign.toULong() shl 63) or (mantissa.high shl 16 shr 12) or (mantissa.low shr 60)
            unpacked.exponent > 17406 -> 0x7ff0000000000000uL or (unpacked.sign.toULong() shl 63)
            unpacked.exponent < 15308 -> unpacked.sign.toULong() shl 63
            else -> {
                var exp = unpacked.exponent - 15361
                var x = (mantissa.high shl 6) or (mantissa.low shr 58) or (if (mantissa.low shl 6 != 0uL) 1uL else 0uL)
                if (exp < 0) { x = (x shr -exp) or (if (x shl (64 + exp) != 0uL) 1uL else 0uL); exp = 0 }
                if ((x and 3uL) == 3uL || (x and 7uL) == 6uL) x += 4uL
                ((x shr 2) + (exp.toULong() shl 52)) or (unpacked.sign.toULong() shl 63)
            }
        }
        return Double.fromBits(bits.toLong())
    }

    fun fixtfsi(value: UInt128): Int {
        val a = unpack(value)
        if (a.exponent < 16369) return 0
        if (a.exponent > 16413) return if (a.sign != 0) Int.MIN_VALUE else Int.MAX_VALUE
        val x = (a.mantissa.high shr (16431 - a.exponent)).toInt()
        return if (a.sign != 0) -x else x
    }

    fun fixtfdi(value: UInt128): Long {
        val a = unpack(value)
        if (a.exponent < 16383) return 0
        if (a.exponent > 16445) return if (a.sign != 0) Long.MIN_VALUE else Long.MAX_VALUE
        val x = ((a.mantissa.high shl 15) or (a.mantissa.low shr 49)) shr (16446 - a.exponent)
        return if (a.sign != 0) 0uL.minus(x).toLong() else x.toLong()
    }

    fun fixunstfsi(value: UInt128): UInt = when (val a = unpack(value)) {
        else -> when {
            a.sign != 0 || a.exponent < 16369 -> 0u
            a.exponent > 16414 -> UInt.MAX_VALUE
            else -> (a.mantissa.high shr (16431 - a.exponent)).toUInt()
        }
    }

    fun fixunstfdi(value: UInt128): ULong {
        val a = unpack(value)
        if (a.sign != 0 || a.exponent < 16383) return 0uL
        if (a.exponent > 16446) return ULong.MAX_VALUE
        return ((a.mantissa.high shl 15) or (a.mantissa.low shr 49)) shr (16446 - a.exponent)
    }

    fun floatsitf(value: Int): UInt128 {
        var sign = 0
        var exponent = 16414
        var mantissa = value.toUInt()
        if (value == 0) return UInt128(0uL, 0uL)
        if (value < 0) { sign = 1; mantissa = 0u - mantissa }
        var shift = 16
        while (shift > 0) {
            if (mantissa shr (32 - shift) == 0u) { mantissa = mantissa shl shift; exponent -= shift }
            shift = shift shr 1
        }
        val high = (sign.toULong() shl 63) or (exponent.toULong() shl 48) or ((mantissa shl 1).toULong() shl 16)
        return UInt128(0uL, high)
    }

    fun floatunsitf(value: UInt): UInt128 {
        var exponent = 16414
        var mantissa = value
        if (value == 0u) return UInt128(0uL, 0uL)
        var shift = 16
        while (shift > 0) {
            if (mantissa shr (32 - shift) == 0u) { mantissa = mantissa shl shift; exponent -= shift }
            shift = shift shr 1
        }
        return UInt128(0uL, (exponent.toULong() shl 48) or ((mantissa shl 1).toULong() shl 16))
    }

    fun floatditf(value: Long): UInt128 {
        var sign = 0
        var exponent = 16446
        var mantissa = value.toULong()
        if (value == 0L) return UInt128(0uL, 0uL)
        if (value < 0) { sign = 1; mantissa = 0uL - mantissa }
        var shift = 32
        while (shift > 0) {
            if (mantissa shr (64 - shift) == 0uL) { mantissa = mantissa shl shift; exponent -= shift }
            shift = shift shr 1
        }
        val low = mantissa shl 49
        val high = (sign.toULong() shl 63) or (exponent.toULong() shl 48) or (mantissa shl 1 shr 16)
        return UInt128(low, high)
    }

    fun floatunditf(value: ULong): UInt128 {
        var exponent = 16446
        var mantissa = value
        if (value == 0uL) return UInt128(0uL, 0uL)
        var shift = 32
        while (shift > 0) {
            if (mantissa shr (64 - shift) == 0uL) { mantissa = mantissa shl shift; exponent -= shift }
            shift = shift shr 1
        }
        return UInt128(mantissa shl 49, (exponent.toULong() shl 48) or (mantissa shl 1 shr 16))
    }

    private fun compare(left: UInt128, right: UInt128): Int {
        val a = left; val b = right
        if ((a.low or (a.high shl 1) or b.low or (b.high shl 1)) == 0uL) return 0
        fun nanBits(v: UInt128) = ((v.high shl 1) shr 49) == 0x7fffuL && (v.low or (v.high shl 16)) != 0uL
        if (nanBits(a) || nanBits(b)) return 2
        val aSign = (a.high shr 63).toInt(); val bSign = (b.high shr 63).toInt()
        if (aSign != bSign) return bSign - aSign
        val negativeFactor = if (aSign != 0) -1 else 1
        if (a.high < b.high) return -negativeFactor
        if (a.high > b.high) return negativeFactor
        if (a.low < b.low) return -negativeFactor
        if (a.low > b.low) return negativeFactor
        return 0
    }

    fun eqtf2(a: UInt128, b: UInt128): Int = if (compare(a, b) != 0) 1 else 0
    fun netf2(a: UInt128, b: UInt128): Int = if (compare(a, b) != 0) 1 else 0
    fun lttf2(a: UInt128, b: UInt128): Int = compare(a, b)
    fun letf2(a: UInt128, b: UInt128): Int = compare(a, b)
    fun gttf2(a: UInt128, b: UInt128): Int = -compare(b, a)
    fun getf2(a: UInt128, b: UInt128): Int = -compare(b, a)
}
