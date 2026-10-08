package tcc.kt

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Region tracking and checked operations corresponding to lib/bcheck.c. */
object BoundCheck {
    private const val INVALID_POINTER: Long = -2L

    data class Region(val start: Long, val size: Long, val type: Int = 0, val invalid: Boolean = false) {
        val endExclusive: Long get() = start + size
    }

    private val lock = ReentrantLock()
    private val regions = java.util.TreeMap<Long, Region>()
    @Volatile private var checkingDisabled = false
    @Volatile private var neverFatal = 0

    @JvmStatic fun boundsChecking(noCheck: Boolean) { checkingDisabled = noCheck }
    @JvmStatic fun neverFatal(value: Int) { neverFatal = value }
    @JvmStatic fun lock() = lock.lock()
    @JvmStatic fun unlock() = lock.unlock()

    @JvmStatic
    fun newRegion(start: Long, size: Long, type: Int = 0, invalid: Boolean = false) = lock.withLock {
        require(size >= 0)
        regions[start] = Region(start, size, type, invalid)
    }

    @JvmStatic fun removeRegion(start: Long) = lock.withLock { regions.remove(start) }

    @JvmStatic
    fun regionFor(pointer: Long): Region? = lock.withLock {
        val candidate = regions.floorEntry(pointer)?.value ?: return null
        if (pointer <= candidate.endExclusive) candidate else null
    }

    /** Checks an address addition and returns INVALID_POINTER when it escapes a known region. */
    @JvmStatic
    fun pointerAdd(pointer: Long, offset: Long): Long {
        if (checkingDisabled) return pointer + offset
        val region = regionFor(pointer) ?: return pointer + offset
        val relative = pointer - region.start
        val next = relative + offset
        if (region.invalid || relative > region.size || next > region.size) {
            return if (neverFatal <= 0) INVALID_POINTER else pointer + offset
        }
        return pointer + offset
    }

    /** Checks access width, matching the generated __bound_ptr_indirN helpers. */
    @JvmStatic
    fun pointerIndir(pointer: Long, offset: Long, width: Int): Long {
        if (checkingDisabled) return pointer + offset
        val region = regionFor(pointer) ?: return pointer + offset
        val relative = pointer - region.start
        if (region.invalid || relative + offset + width > region.size) {
            return if (neverFatal <= 0) INVALID_POINTER else pointer + offset
        }
        return pointer + offset
    }

    @JvmStatic fun ptrIndir1(p: Long, o: Long) = pointerIndir(p, o, 1)
    @JvmStatic fun ptrIndir2(p: Long, o: Long) = pointerIndir(p, o, 2)
    @JvmStatic fun ptrIndir4(p: Long, o: Long) = pointerIndir(p, o, 4)
    @JvmStatic fun ptrIndir8(p: Long, o: Long) = pointerIndir(p, o, 8)
    @JvmStatic fun ptrIndir12(p: Long, o: Long) = pointerIndir(p, o, 12)
    @JvmStatic fun ptrIndir16(p: Long, o: Long) = pointerIndir(p, o, 16)

    @JvmStatic
    fun check(pointer: Long, size: Long, function: String) {
        if (size != 0L && pointerAdd(pointer, size) == INVALID_POINTER) {
            throw IndexOutOfBoundsException("invalid pointer 0x${pointer.toString(16)}, size 0x${size.toString(16)} in $function")
        }
    }

    @JvmStatic
    fun memCopy(dest: ByteArray, destOffset: Int, src: ByteArray, srcOffset: Int, size: Int): ByteArray {
        check(destOffset.toLong(), size.toLong(), "memcpy dest")
        check(srcOffset.toLong(), size.toLong(), "memcpy src")
        require(!(dest === src && destOffset < srcOffset + size && srcOffset < destOffset + size)) { "overlapping regions in memcpy" }
        src.copyInto(dest, destOffset, srcOffset, srcOffset + size)
        return dest
    }

    @JvmStatic
    fun memMove(dest: ByteArray, destOffset: Int, src: ByteArray, srcOffset: Int, size: Int): ByteArray {
        check(destOffset.toLong(), size.toLong(), "memmove dest")
        check(srcOffset.toLong(), size.toLong(), "memmove src")
        src.copyInto(dest, destOffset, srcOffset, srcOffset + size)
        return dest
    }

    @JvmStatic
    fun memSet(dest: ByteArray, value: Int, size: Int): ByteArray {
        check(0, size.toLong(), "memset")
        dest.fill(value.toByte(), 0, size)
        return dest
    }

    @JvmStatic fun memCompare(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, size: Int): Int {
        for (i in 0 until size) {
            val delta = (a[aOffset + i].toInt() and 0xff) - (b[bOffset + i].toInt() and 0xff)
            if (delta != 0) return delta
        }
        return 0
    }

    @JvmStatic fun strlen(value: ByteArray): Int = value.indexOf(0).let { if (it < 0) value.size else it }
    @JvmStatic fun strcmp(a: ByteArray, b: ByteArray): Int = memCompare(a, 0, b, 0, minOf(strlen(a), strlen(b))).let { d ->
        if (d != 0) d else strlen(a).compareTo(strlen(b))
    }

    @JvmStatic fun strcpy(dest: ByteArray, destOffset: Int, src: ByteArray, srcOffset: Int): ByteArray {
        val length = strlen(src.copyOfRange(srcOffset, src.size)) + 1
        check(destOffset.toLong(), length.toLong(), "strcpy dest")
        check(srcOffset.toLong(), length.toLong(), "strcpy src")
        require(!(dest === src && destOffset < srcOffset + length && srcOffset < destOffset + length)) { "overlapping regions in strcpy" }
        src.copyInto(dest, destOffset, srcOffset, srcOffset + length)
        return dest
    }

    @JvmStatic fun strncpy(dest: ByteArray, destOffset: Int, src: ByteArray, srcOffset: Int, size: Int): ByteArray {
        val available = strlen(src.copyOfRange(srcOffset, src.size))
        val copied = minOf(size, available)
        check(destOffset.toLong(), size.toLong(), "strncpy dest")
        check(srcOffset.toLong(), copied.toLong(), "strncpy src")
        for (i in 0 until size) dest[destOffset + i] = if (i < copied) src[srcOffset + i] else 0
        return dest
    }

    @JvmStatic fun strcat(dest: ByteArray, src: ByteArray): ByteArray {
        val destLength = strlen(dest)
        val srcLength = strlen(src)
        check(0, (destLength + srcLength + 1).toLong(), "strcat dest")
        check(0, (srcLength + 1).toLong(), "strcat src")
        require(!(dest === src)) { "overlapping regions in strcat" }
        src.copyInto(dest, destLength, 0, srcLength + 1)
        return dest
    }

    @JvmStatic fun strncat(dest: ByteArray, src: ByteArray, count: Int): ByteArray {
        val destLength = strlen(dest)
        val length = minOf(strlen(src), count)
        check(0, (destLength + length + 1).toLong(), "strncat dest")
        check(0, length.toLong(), "strncat src")
        src.copyInto(dest, destLength, 0, length)
        dest[destLength + length] = 0
        return dest
    }

    @JvmStatic fun strncmp(a: ByteArray, aOffset: Int, b: ByteArray, bOffset: Int, count: Int): Int {
        for (i in 0 until count) {
            val av = a[aOffset + i].toInt() and 0xff
            val bv = b[bOffset + i].toInt() and 0xff
            if (av != bv || av == 0) return av - bv
        }
        return 0
    }

    @JvmStatic fun strchr(value: ByteArray, character: Int): Int {
        val ch = character and 0xff
        for (i in value.indices) {
            if ((value[i].toInt() and 0xff) == ch) return i
            if (value[i].toInt() == 0) return -1
        }
        return if (ch == 0) value.size else -1
    }

    @JvmStatic fun strrchr(value: ByteArray, character: Int): Int {
        val ch = character and 0xff
        var found = if (ch == 0) strlen(value) else -1
        for (i in 0 until strlen(value)) if ((value[i].toInt() and 0xff) == ch) found = i
        return found
    }

    @JvmStatic fun strdup(value: ByteArray): ByteArray = value.copyOfRange(0, strlen(value) + 1)

    @JvmStatic
    fun allocaRegion(pointer: Long, size: Long, frame: Long) = lock.withLock {
        newRegion(pointer, size)
        frameRegions.getOrPut(frame) { mutableListOf() }.add(pointer)
    }

    @JvmStatic
    fun deleteFrameRegions(frame: Long) = lock.withLock {
        frameRegions.remove(frame)?.forEach { regions.remove(it) }
    }

    private val frameRegions = mutableMapOf<Long, MutableList<Long>>()

}
