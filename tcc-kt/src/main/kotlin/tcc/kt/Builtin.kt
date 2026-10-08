package tcc.kt

/** Bit-operation builtins from lib/builtin.c. */
object Builtin {
    fun __tcc_builtin_ffs(value: Int): Int = if (value == 0) 0 else Integer.numberOfTrailingZeros(value) + 1
    fun __tcc_builtin_ffsll(value: Long): Int = if (value == 0L) 0 else java.lang.Long.numberOfTrailingZeros(value) + 1
    fun __tcc_builtin_ffsl(value: Long, longSize: Int = 8): Int =
        if (longSize == 4) __tcc_builtin_ffs(value.toInt()) else __tcc_builtin_ffsll(value)

    fun __tcc_builtin_clz(value: Int): Int = if (value == 0) 31 else Integer.numberOfLeadingZeros(value)
    fun __tcc_builtin_clzll(value: Long): Int = if (value == 0L) 63 else java.lang.Long.numberOfLeadingZeros(value)
    fun __tcc_builtin_clzl(value: Long, longSize: Int = 8): Int =
        if (longSize == 4) __tcc_builtin_clz(value.toInt()) else __tcc_builtin_clzll(value)

    fun __tcc_builtin_ctz(value: Int): Int = if (value == 0) 0 else Integer.numberOfTrailingZeros(value)
    fun __tcc_builtin_ctzll(value: Long): Int = if (value == 0L) 0 else java.lang.Long.numberOfTrailingZeros(value)
    fun __tcc_builtin_ctzl(value: Long, longSize: Int = 8): Int =
        if (longSize == 4) __tcc_builtin_ctz(value.toInt()) else __tcc_builtin_ctzll(value)

    fun __tcc_builtin_clrsb(value: Int): Int {
        val magnitude = if (value < 0) value.inv() else value
        return clzNonZeroOrWidthMinusOne(magnitude shl 1, Int.SIZE_BITS)
    }

    fun __tcc_builtin_clrsbll(value: Long): Int {
        val magnitude = if (value < 0L) value.inv() else value
        return clzNonZeroOrWidthMinusOne(magnitude shl 1, Long.SIZE_BITS)
    }
    fun __tcc_builtin_clrsbl(value: Long, longSize: Int = 8): Int =
        if (longSize == 4) __tcc_builtin_clrsb(value.toInt()) else __tcc_builtin_clrsbll(value)

    fun __tcc_builtin_popcount(value: Int): Int = Integer.bitCount(value)
    fun __tcc_builtin_popcountll(value: Long): Int = java.lang.Long.bitCount(value)
    fun __tcc_builtin_popcountl(value: Long, longSize: Int = 8): Int =
        if (longSize == 4) __tcc_builtin_popcount(value.toInt()) else __tcc_builtin_popcountll(value)

    fun __tcc_builtin_parity(value: Int): Int = __tcc_builtin_popcount(value) and 1
    fun __tcc_builtin_parityll(value: Long): Int = __tcc_builtin_popcountll(value) and 1
    fun __tcc_builtin_parityl(value: Long, longSize: Int = 8): Int =
        if (longSize == 4) __tcc_builtin_parity(value.toInt()) else __tcc_builtin_parityll(value)

    private fun clzNonZeroOrWidthMinusOne(value: Int, width: Int): Int =
        if (value == 0) width - 1 else Integer.numberOfLeadingZeros(value)

    private fun clzNonZeroOrWidthMinusOne(value: Long, width: Int): Int =
        if (value == 0L) width - 1 else java.lang.Long.numberOfLeadingZeros(value)

    // GCC/Clang aliases exported by the C implementation.
    fun __builtin_ffs(value: Int): Int = __tcc_builtin_ffs(value)
    fun __builtin_ffsl(value: Long, longSize: Int = 8): Int = __tcc_builtin_ffsl(value, longSize)
    fun __builtin_ffsll(value: Long): Int = __tcc_builtin_ffsll(value)
    fun __builtin_clz(value: Int): Int = __tcc_builtin_clz(value)
    fun __builtin_clzl(value: Long, longSize: Int = 8): Int = __tcc_builtin_clzl(value, longSize)
    fun __builtin_clzll(value: Long): Int = __tcc_builtin_clzll(value)
    fun __builtin_ctz(value: Int): Int = __tcc_builtin_ctz(value)
    fun __builtin_ctzl(value: Long, longSize: Int = 8): Int = __tcc_builtin_ctzl(value, longSize)
    fun __builtin_ctzll(value: Long): Int = __tcc_builtin_ctzll(value)
    fun __builtin_clrsb(value: Int): Int = __tcc_builtin_clrsb(value)
    fun __builtin_clrsbl(value: Long, longSize: Int = 8): Int = __tcc_builtin_clrsbl(value, longSize)
    fun __builtin_clrsbll(value: Long): Int = __tcc_builtin_clrsbll(value)
    fun __builtin_popcount(value: Int): Int = __tcc_builtin_popcount(value)
    fun __builtin_popcountl(value: Long, longSize: Int = 8): Int = __tcc_builtin_popcountl(value, longSize)
    fun __builtin_popcountll(value: Long): Int = __tcc_builtin_popcountll(value)
    fun __builtin_parity(value: Int): Int = __tcc_builtin_parity(value)
    fun __builtin_parityl(value: Long, longSize: Int = 8): Int = __tcc_builtin_parityl(value, longSize)
    fun __builtin_parityll(value: Long): Int = __tcc_builtin_parityll(value)
}
