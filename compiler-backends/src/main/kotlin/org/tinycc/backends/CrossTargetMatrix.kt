package org.tinycc.backends

import org.tinycc.backends.c67.C67AssemblyEmitter
import org.tinycc.backends.c67.C67CoffEmitter
import org.tinycc.backends.c67.C67InstructionSelector
import org.tinycc.backends.riscv.RiscVAssemblyEmitter
import org.tinycc.backends.riscv.RiscVInstructionSelector
import org.tinycc.backends.riscv.RiscVTargetOptions
import org.tinycc.backends.riscv.RiscVVariant
import org.tinycc.core.ir.IrFunction

enum class CrossTargetArchitecture { I386, X86_64, ARM32, ARM64, RISCV32, RISCV64, C67 }

enum class CrossTargetFormat { ELF, PE_COFF, MACH_O, C67_COFF }

enum class ExecutionRequirement { HOST, QEMU, DOSBOX, UNAVAILABLE }

data class CrossTargetProfile(
    val name: String,
    val architecture: CrossTargetArchitecture,
    val format: CrossTargetFormat,
    val execution: ExecutionRequirement,
    val requirements: List<String>,
)

object CrossTargetProfiles {
    val x86_64Linux = CrossTargetProfile("x86_64-linux", CrossTargetArchitecture.X86_64, CrossTargetFormat.ELF, ExecutionRequirement.HOST, emptyList())
    val arm64Linux = CrossTargetProfile("aarch64-linux", CrossTargetArchitecture.ARM64, CrossTargetFormat.ELF, ExecutionRequirement.QEMU, listOf("qemu-aarch64"))
    val riscv64Linux = CrossTargetProfile("riscv64-linux", CrossTargetArchitecture.RISCV64, CrossTargetFormat.ELF, ExecutionRequirement.QEMU, listOf("qemu-riscv64"))
    val windowsArm64 = CrossTargetProfile("aarch64-windows", CrossTargetArchitecture.ARM64, CrossTargetFormat.PE_COFF, ExecutionRequirement.UNAVAILABLE, listOf("Windows ARM64 host or emulator"))
    val appleArm64 = CrossTargetProfile("arm64-apple", CrossTargetArchitecture.ARM64, CrossTargetFormat.MACH_O, ExecutionRequirement.UNAVAILABLE, listOf("macOS ARM64 host"))
    val c67 = CrossTargetProfile("c67-coff", CrossTargetArchitecture.C67, CrossTargetFormat.C67_COFF, ExecutionRequirement.DOSBOX, listOf("C67 simulator or DSP board"))

    val all = listOf(x86_64Linux, arm64Linux, riscv64Linux, windowsArm64, appleArm64, c67)
}

data class CrossTargetFixture(
    val profile: CrossTargetProfile,
    val assembly: String,
    val objectBytes: ByteArray,
    val executionAvailable: Boolean,
)

class CrossTargetFixtureCompiler {
    fun compile(profile: CrossTargetProfile, function: IrFunction): CrossTargetFixture = when (profile.architecture) {
        CrossTargetArchitecture.RISCV32 -> compileRiscV(profile, function, RiscVVariant.RV32I)
        CrossTargetArchitecture.RISCV64 -> compileRiscV(profile, function, RiscVVariant.RV64GC)
        CrossTargetArchitecture.C67 -> {
            val selected = C67InstructionSelector().select(function)
            CrossTargetFixture(profile, C67AssemblyEmitter().emit(selected), C67CoffEmitter.emit(selected).bytes, HostTargetAvailability.supports(profile))
        }
        else -> CrossTargetFixture(profile, "; backend fixture pending for ${profile.name}\n", ByteArray(0), HostTargetAvailability.supports(profile))
    }

    private fun compileRiscV(profile: CrossTargetProfile, function: IrFunction, variant: RiscVVariant): CrossTargetFixture {
        val selected = RiscVInstructionSelector(RiscVTargetOptions(variant)).select(function)
        val assembly = RiscVAssemblyEmitter().emit(selected)
        return CrossTargetFixture(profile, assembly, assembly.encodeToByteArray(), HostTargetAvailability.supports(profile))
    }
}

object HostTargetAvailability {
    fun supports(profile: CrossTargetProfile): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when (profile.architecture) {
            CrossTargetArchitecture.X86_64 -> arch in setOf("amd64", "x86_64") && "linux" in os
            CrossTargetArchitecture.ARM64 -> arch in setOf("aarch64", "arm64")
            CrossTargetArchitecture.ARM32 -> arch.startsWith("arm")
            else -> false
        }
    }
}
