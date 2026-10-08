package tcc.kt

import java.util.concurrent.atomic.AtomicLong

/** Addressable integer storage standing in for the typed C atomic object. */
class AtomicCell(val byteWidth: Int, initialValue: Long = 0) {
    internal val bits = AtomicLong(truncate(initialValue))

    fun load(): Long = truncate(bits.get())

    fun store(value: Long) {
        bits.set(truncate(value))
    }

    private fun truncate(value: Long): Long = value and when (byteWidth) {
        1 -> 0xffL
        2 -> 0xffffL
        4 -> 0xffff_ffffL
        8 -> -1L
        else -> throw IllegalArgumentException("unsupported atomic width: $byteWidth")
    }
}

/** Atomic operations from lib/stdatomic.c; all updates use a sequentially consistent CAS loop. */
object AtomicRuntime {
    const val RELAXED = 0
    const val CONSUME = 1
    const val ACQUIRE = 2
    const val RELEASE = 3
    const val ACQ_REL = 4
    const val SEQ_CST = 5

    fun exchange(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { _, replacement -> replacement }

    fun addFetch(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        update(atom, value) { old, operand -> old + operand }

    fun subFetch(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        update(atom, value) { old, operand -> old - operand }

    fun andFetch(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        update(atom, value) { old, operand -> old and operand }

    fun orFetch(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        update(atom, value) { old, operand -> old or operand }

    fun xorFetch(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        update(atom, value) { old, operand -> old xor operand }

    fun nandFetch(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        update(atom, value) { old, operand -> (old and operand).inv() }

    fun fetchAdd(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { old, operand -> old + operand }

    fun fetchSub(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { old, operand -> old - operand }

    fun fetchAnd(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { old, operand -> old and operand }

    fun fetchOr(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { old, operand -> old or operand }

    fun fetchXor(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { old, operand -> old xor operand }

    fun fetchNand(atom: AtomicCell, value: Long, memoryOrder: Int = SEQ_CST): Long =
        updateReturningOld(atom, value) { old, operand -> (old and operand).inv() }

    /** Equivalent to the generated __atomic_is_lock_free implementation for the target. */
    fun isLockFree(size: Int, targetHasLockFree64Bit: Boolean): Boolean = when (size) {
        1, 2, 4 -> true
        8 -> targetHasLockFree64Bit
        else -> false
    }

    private inline fun update(
        atom: AtomicCell,
        operand: Long,
        crossinline operation: (Long, Long) -> Long,
    ): Long = updateLoop(atom, operand, operation) { _, next -> next }

    private inline fun updateReturningOld(
        atom: AtomicCell,
        operand: Long,
        crossinline operation: (Long, Long) -> Long,
    ): Long = updateLoop(atom, operand, operation) { old, _ -> old }

    private inline fun updateLoop(
        atom: AtomicCell,
        operand: Long,
        crossinline operation: (Long, Long) -> Long,
        crossinline result: (old: Long, next: Long) -> Long,
    ): Long {
        val mask = maskFor(atom.byteWidth)
        var old = atom.bits.get() and mask
        while (true) {
            val next = operation(old, operand) and mask
            if (atom.bits.compareAndSet(old, next)) return result(old, next)
            old = atom.bits.get() and mask
        }
    }

    private fun maskFor(width: Int): Long = when (width) {
        1 -> 0xffL
        2 -> 0xffffL
        4 -> 0xffff_ffffL
        8 -> -1L
        else -> throw IllegalArgumentException("unsupported atomic width: $width")
    }

}
