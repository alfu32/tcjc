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
    private val checkingDepth = ThreadLocal.withInitial { 0 }
    @Volatile private var neverFatal = 0
    @Volatile private var initialized = false
    @Volatile var warnPointerAdd: Boolean = false
        private set
    @Volatile var printCalls: Boolean = false
        private set
    @Volatile var printHeap: Boolean = false
        private set
    @Volatile var printStatistics: Boolean = false
        private set

    @JvmStatic fun boundsChecking(delta: Int) { checkingDepth.set(checkingDepth.get() + delta) }
    @JvmStatic fun boundsChecking(disabled: Boolean) { checkingDepth.set(if (disabled) 1 else 0) }
    @JvmStatic fun neverFatal(value: Int) { neverFatal += value }

    /** Reads the TCC_BOUNDS_* environment switches used by __bound_init. */
    @JvmStatic
    fun initialize(environment: Map<String, String>) {
        if (initialized) return
        warnPointerAdd = environment.containsKey("TCC_BOUNDS_WARN_POINTER_ADD")
        printCalls = environment.containsKey("TCC_BOUNDS_PRINT_CALLS")
        printHeap = environment.containsKey("TCC_BOUNDS_PRINT_HEAP")
        printStatistics = environment.containsKey("TCC_BOUNDS_PRINT_STATISTIC")
        if (environment.containsKey("TCC_BOUNDS_NEVER_FATAL")) neverFatal(1)
        initialized = true
    }

    @JvmStatic fun isInitialized(): Boolean = initialized
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
        count("bound_ptr_add")
        if (checkingDepth.get() > 0) return pointer + offset
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
        count("bound_ptr_indir$width")
        if (checkingDepth.get() > 0) return pointer + offset
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
        count("bound_memcpy")
        check(destOffset.toLong(), size.toLong(), "memcpy dest")
        check(srcOffset.toLong(), size.toLong(), "memcpy src")
        require(!(dest === src && destOffset < srcOffset + size && srcOffset < destOffset + size)) { "overlapping regions in memcpy" }
        src.copyInto(dest, destOffset, srcOffset, srcOffset + size)
        return dest
    }

    @JvmStatic
    fun memMove(dest: ByteArray, destOffset: Int, src: ByteArray, srcOffset: Int, size: Int): ByteArray {
        count("bound_memmove")
        check(destOffset.toLong(), size.toLong(), "memmove dest")
        check(srcOffset.toLong(), size.toLong(), "memmove src")
        src.copyInto(dest, destOffset, srcOffset, srcOffset + size)
        return dest
    }

    @JvmStatic
    fun memSet(dest: ByteArray, value: Int, size: Int): ByteArray {
        count("bound_memset")
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


    data class Allocation(
        val address: Long,
        val bytes: ByteArray,
        val type: Int,
        val alignment: Int
    )

    private val allocations = mutableMapOf<Long, Allocation>()
    private var nextAddress = 0x10000L

    @JvmStatic
    fun malloc(size: Int): Allocation = lock.withLock {
        count("bound_malloc")
        allocate(size, 1)
    }

    @JvmStatic
    fun calloc(count: Int, size: Int): Allocation {
        require(count >= 0 && size >= 0)
        return lock.withLock { this.count("bound_calloc"); allocate(Math.multiplyExact(count, size), 2) }
    }

    @JvmStatic
    fun memalign(alignment: Int, size: Int): Allocation = lock.withLock {
        require(alignment > 0 && alignment and (alignment - 1) == 0)
        allocate(size, 4, alignment)
    }

    @JvmStatic
    fun realloc(address: Long, size: Int): Allocation = lock.withLock {
        count("bound_realloc")
        val previous = allocations[address] ?: throw IllegalArgumentException("realloc of unknown address 0x${address.toString(16)}")
        val replacement = allocate(size, 3)
        previous.bytes.copyInto(replacement.bytes, 0, 0, minOf(previous.bytes.size, replacement.bytes.size))
        allocations.remove(address)
        regions.remove(address)
        replacement
    }

    @JvmStatic
    fun free(address: Long) {
        count("bound_free")
        lock.withLock {
            if (allocations.remove(address) == null) return@withLock
            regions.remove(address)
        }
    }

    @JvmStatic
    fun allocation(address: Long): Allocation? = lock.withLock { allocations[address] }

    private fun allocate(size: Int, kind: Int, alignment: Int = 1): Allocation {
        require(size >= 0)
        val alignedAddress = (nextAddress + alignment - 1L) and (alignment - 1L).inv()
        val allocation = Allocation(alignedAddress, ByteArray(size), kind, alignment)
        allocations[alignedAddress] = allocation
        regions[alignedAddress] = Region(alignedAddress, size.toLong(), kind)
        nextAddress = alignedAddress + maxOf(size, 1) + 16L
        return allocation
    }


    @JvmStatic
    fun localRegions(frame: Long, stackSlots: LongArray) = lock.withLock {
        var i = 0
        while (i + 1 < stackSlots.size && stackSlots[i] != 0L) {
            val address = frame + stackSlots[i]
            val length = stackSlots[i + 1]
            regions[address] = Region(address, length)
            frameRegions.getOrPut(frame) { mutableListOf() }.add(address)
            i += 2
        }
    }

    @JvmStatic
    fun invalidateRegion(start: Long) = lock.withLock {
        regions[start]?.let { regions[start] = it.copy(invalid = true) }
    }

    data class JumpBuffer(val frame: Long)
    class BoundLongJump(val value: Int) : RuntimeException(null, null, false, false)

    @JvmStatic fun setjmp(frame: Long): JumpBuffer = JumpBuffer(frame)

    /** Removes stack regions before transferring control to a saved jump point. */
    @JvmStatic
    fun longjmp(buffer: JumpBuffer, value: Int): Nothing {
        deleteFrameRegions(buffer.frame)
        throw BoundLongJump(if (value == 0) 1 else value)
    }

    @JvmStatic
    fun isInvalidPointer(pointer: Long): Boolean = pointer == INVALID_POINTER


    /** Registers argv/env strings and their pointer vectors, as __bound_main_arg does. */
    @JvmStatic
    fun registerMainArguments(arguments: List<Pair<Long, Long>>, argvVector: Long, env: List<Pair<Long, Long>> = emptyList(), envVector: Long? = null, pointerSize: Int = 8) = lock.withLock {
        arguments.forEach { (address, length) -> regions[address] = Region(address, length) }
        if (arguments.isNotEmpty()) regions[argvVector] = Region(argvVector, (arguments.size + 1L) * pointerSize)
        env.forEach { (address, length) -> regions[address] = Region(address, length) }
        if (env.isNotEmpty() && envVector != null) regions[envVector] = Region(envVector, (env.size + 1L) * pointerSize)
    }

    /** Releases active heap/stack tracking during the C runtime destructor path. */
    @JvmStatic
    fun exit() = lock.withLock {
        frameRegions.clear()
        allocations.keys.toList().forEach { regions.remove(it) }
        allocations.clear()
        initialized = false
    }

    data class ThreadHandle(val thread: Thread)

    /** Wraps a created thread so it begins with a fresh bounds-checking state. */
    @JvmStatic
    fun createThread(name: String? = null, start: () -> Unit): ThreadHandle {
        val thread = Thread({
            checkingDepth.set(0)
            start()
        }, name)
        thread.start()
        return ThreadHandle(thread)
    }

    @JvmStatic
    fun joinThread(handle: ThreadHandle) = handle.thread.join()

    private val signalHandlers = mutableMapOf<Int, (Int) -> Unit>()

    @JvmStatic
    fun signal(signum: Int, handler: ((Int) -> Unit)?): ((Int) -> Unit)? = lock.withLock {
        val old = signalHandlers[signum]
        if (handler == null) signalHandlers.remove(signum) else signalHandlers[signum] = handler
        old
    }

    @JvmStatic
    fun dispatchSignal(signum: Int) {
        val handler = lock.withLock { signalHandlers[signum] }
        handler?.invoke(signum)
    }

    /** Maps and unmaps direct buffers while keeping their address ranges registered. */
    private val mappedRegions = mutableMapOf<Long, java.nio.ByteBuffer>()
    @JvmStatic
    fun mmap(size: Int): Pair<Long, java.nio.ByteBuffer> = lock.withLock {
        require(size >= 0)
        val address = allocate(size, 0).address
        val buffer = java.nio.ByteBuffer.allocateDirect(size)
        mappedRegions[address] = buffer
        address to buffer
    }

    @JvmStatic
    fun munmap(address: Long): Boolean = lock.withLock {
        val removed = mappedRegions.remove(address) ?: return@withLock false
        regions.remove(address)
        allocations.remove(address)
        removed.clear()
        true
    }


    /** Removes DLL static data entries from the region tree, mirroring __bound_exit_dll. */
    @JvmStatic
    fun exitDll(staticRegions: LongArray) = lock.withLock {
        var i = 0
        while (i + 1 < staticRegions.size && staticRegions[i] != 0L) {
            regions.remove(staticRegions[i])
            i += 2
        }
    }

    data class SignalAction(val handler: ((Int, Long) -> Unit)?, val mask: Set<Int> = emptySet(), val flags: Int = 0)
    private val signalActions = mutableMapOf<Int, SignalAction>()

    @JvmStatic
    fun sigaction(signum: Int, action: SignalAction?): SignalAction? = lock.withLock {
        val previous = signalActions[signum]
        if (action == null) signalActions.remove(signum) else signalActions[signum] = action
        previous
    }

    @JvmStatic
    fun dispatchSigaction(signum: Int, info: Long = 0L) {
        val action = lock.withLock { signalActions[signum] }
        action?.handler?.invoke(signum, info)
    }


    private val statistics = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun count(name: String) { statistics.merge(name, 1L, Long::plus) }

    @JvmStatic fun statistics(): Map<String, Long> = statistics.toSortedMap()
    @JvmStatic fun clearStatistics() = statistics.clear()

    @JvmStatic
    fun fork(forkBackend: () -> Int): Int {
        val result = forkBackend()
        if (result == 0) {
            checkingDepth.set(0)
            lock.withLock { signalHandlers.clear(); signalActions.clear() }
        }
        return result
    }

    @JvmStatic
    fun createThreadTask(name: String? = null, start: () -> Any?): java.util.concurrent.FutureTask<Any?> {
        val task = java.util.concurrent.FutureTask {
            checkingDepth.set(0)
            start()
        }
        Thread(task, name).start()
        return task
    }

}
