package tcc.kt

/** Architecture-specific instruction-cache flushing from lib/armflush.c. */
object ArmFlush {
    enum class Architecture { ARM32, ARM64, RISCV64 }

    var arm32Syscall: ((number: Int, beginning: Long, end: Long, flags: Int) -> Unit)? = null
    var arm64ClearCache: ((beginning: Long, end: Long) -> Unit)? = null
    var riscv64ClearCache: ((beginning: Long, end: Long) -> Unit)? = null

    fun arm32CacheFlushSyscallNumber(thumb: Boolean, eabi: Boolean): Int {
        val syscallBase = if (thumb || eabi) 0 else 0x900000
        val armSyscallBase = syscallBase + 0x0f0000
        return armSyscallBase + 2
    }

    /** The native syscall / compiler intrinsic is supplied by the target runtime. */
    fun clearCache(
        architecture: Architecture,
        beginning: Long,
        end: Long,
        thumb: Boolean = false,
        eabi: Boolean = true,
    ) {
        when (architecture) {
            Architecture.ARM32 -> (arm32Syscall
                ?: error("ARM32 cache-flush syscall is not installed"))(
                arm32CacheFlushSyscallNumber(thumb, eabi), beginning, end, 0,
            )
            Architecture.ARM64 -> (arm64ClearCache
                ?: error("ARM64 cache-flush intrinsic is not installed"))(beginning, end)
            Architecture.RISCV64 -> (riscv64ClearCache
                ?: error("RISC-V cache-flush intrinsic is not installed"))(beginning, end)
        }
    }
}
