package org.tinycc.backends.arm

import org.tinycc.backends.arm64.Arm64AssemblyEmitter
import org.tinycc.backends.arm64.Arm64InstructionSelector
import org.tinycc.backends.arm64.Arm64TargetOptions
import org.tinycc.core.ir.IrFunction

enum class ArmOperatingSystem { LINUX, WINDOWS, APPLE }

enum class ArmObjectFormat { ELF, PE_COFF, MACH_O }

enum class ArmArchitecture { ARM32, ARM64 }

data class ArmPlatformTarget(
    val architecture: ArmArchitecture,
    val operatingSystem: ArmOperatingSystem,
    val objectFormat: ArmObjectFormat,
    val triple: String,
    val symbolPrefix: String = "",
)

object ArmPlatformTargets {
    val linuxArm32 = ArmPlatformTarget(ArmArchitecture.ARM32, ArmOperatingSystem.LINUX, ArmObjectFormat.ELF, "arm-linux-gnueabihf")
    val linuxArm64 = ArmPlatformTarget(ArmArchitecture.ARM64, ArmOperatingSystem.LINUX, ArmObjectFormat.ELF, "aarch64-linux-gnu")
    val windowsArm32 = ArmPlatformTarget(ArmArchitecture.ARM32, ArmOperatingSystem.WINDOWS, ArmObjectFormat.PE_COFF, "armv7-windows-msvc")
    val windowsArm64 = ArmPlatformTarget(ArmArchitecture.ARM64, ArmOperatingSystem.WINDOWS, ArmObjectFormat.PE_COFF, "aarch64-windows-msvc")
    val appleArm64 = ArmPlatformTarget(ArmArchitecture.ARM64, ArmOperatingSystem.APPLE, ArmObjectFormat.MACH_O, "arm64-apple-darwin", "_")

    val all: List<ArmPlatformTarget> = listOf(linuxArm32, linuxArm64, windowsArm32, windowsArm64, appleArm64)
}

data class ArmCrossBuildArtifact(
    val target: ArmPlatformTarget,
    val assembly: String,
    val executableCapable: Boolean,
)

class ArmCrossCompiler {
    fun compile(target: ArmPlatformTarget, function: IrFunction): ArmCrossBuildArtifact {
        val assembly = when (target.architecture) {
            ArmArchitecture.ARM32 -> ArmAssemblyEmitter().emit(
                ArmInstructionSelector(ArmTargetOptions(isa = if (target.operatingSystem == ArmOperatingSystem.APPLE) ArmIsa.ARM else ArmIsa.THUMB2)).select(function),
            )
            ArmArchitecture.ARM64 -> Arm64AssemblyEmitter().emit(
                Arm64InstructionSelector(Arm64TargetOptions(pic = target.operatingSystem != ArmOperatingSystem.WINDOWS)).select(function),
            )
        }
        val decorated = buildString {
            appendLine("@ tcjc cross-target ${target.triple}")
            appendLine("@ object-format ${target.objectFormat}")
            appendLine("@ symbol-prefix '${target.symbolPrefix}'")
            append(assembly)
        }
        return ArmCrossBuildArtifact(target, decorated, executableCapable = ArmExecutionAvailability.supports(target))
    }
}

object ArmExecutionAvailability {
    fun supports(target: ArmPlatformTarget): Boolean {
        val host = System.getProperty("os.arch").lowercase()
        val hostArm64 = host in setOf("aarch64", "arm64")
        val hostArm32 = host.startsWith("arm") && !hostArm64
        return when (target.architecture) {
            ArmArchitecture.ARM32 -> hostArm32
            ArmArchitecture.ARM64 -> hostArm64
        } && when (target.operatingSystem) {
            ArmOperatingSystem.LINUX -> System.getProperty("os.name").contains("linux", ignoreCase = true)
            ArmOperatingSystem.WINDOWS -> System.getProperty("os.name").contains("windows", ignoreCase = true)
            ArmOperatingSystem.APPLE -> System.getProperty("os.name").contains("mac", ignoreCase = true)
        }
    }
}
