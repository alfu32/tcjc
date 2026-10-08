package tcc.kt

/** x86_64 va_list state consumed by lib/va_list.c. */
class VaList(
    var gpOffset: Int,
    var fpOffset: Int,
    val overflowArea: ByteArray,
    var overflowOffset: Int,
    val registerSaveArea: ByteArray,
)

/** A pointer-sized view returned by __va_arg(). */
data class VaArgPointer(val area: ByteArray, val offset: Int, val size: Int)

/** Kotlin translation of the x86_64 argument selection logic in lib/va_list.c. */
object VaListRuntime {
    const val VA_GEN_REG = 0
    const val VA_FLOAT_REG = 1
    const val VA_STACK = 2

    fun __va_arg(ap: VaList, argType: Int, size: Int, alignment: Int): VaArgPointer {
        val roundedSize = (size + 7) and -8
        val roundedAlignment = (alignment + 7) and -8

        when (argType) {
            VA_GEN_REG -> {
                if (ap.gpOffset + roundedSize <= 48) {
                    ap.gpOffset += roundedSize
                    return VaArgPointer(ap.registerSaveArea, ap.gpOffset - roundedSize, size)
                }
            }
            VA_FLOAT_REG -> {
                if (ap.fpOffset < 128 + 48) {
                    ap.fpOffset += 16
                    if (roundedSize == 8)
                        return VaArgPointer(ap.registerSaveArea, ap.fpOffset - 16, size)
                    if (ap.fpOffset < 128 + 48) {
                        ap.registerSaveArea.copyInto(
                            destination = ap.registerSaveArea,
                            destinationOffset = ap.fpOffset - 8,
                            startIndex = ap.fpOffset,
                            endIndex = ap.fpOffset + 8,
                        )
                        ap.fpOffset += 16
                        return VaArgPointer(ap.registerSaveArea, ap.fpOffset - 32, size)
                    }
                }
            }
            VA_STACK -> Unit
            else -> throw IllegalArgumentException("invalid va_arg type: $argType")
        }

        ap.overflowOffset += roundedSize
        if (roundedAlignment != 0) {
            ap.overflowOffset = (ap.overflowOffset + roundedAlignment - 1) and -roundedAlignment
        }
        return VaArgPointer(ap.overflowArea, ap.overflowOffset - roundedSize, size)
    }
}
