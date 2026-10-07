# Cross-target fixture matrix

The Kotlin backends keep cross-target assembly/object fixtures deterministic and do not invoke a C compiler, assembler, linker, DLL, or shared object. Native execution is attempted only when the host matches the target; otherwise the fixture records the required emulator or host capability.

| Profile | Format | Execution requirement |
| --- | --- | --- |
| `x86_64-linux` | ELF | Native host |
| `aarch64-linux` | ELF | `qemu-aarch64` or ARM64 host |
| `riscv64-linux` | ELF | `qemu-riscv64` or RISC-V host |
| `aarch64-windows` | PE/COFF | Windows ARM64 host/emulator |
| `arm64-apple` | Mach-O | macOS ARM64 host |
| `c67-coff` | C67 COFF | C67 simulator or DSP board |

Unavailable target execution is a recorded capability result, never a reason to load native TinyCC artifacts. The fixture compiler still validates target selection, deterministic assembly, object metadata, and required emulator/toolchain documentation on every JVM host.
