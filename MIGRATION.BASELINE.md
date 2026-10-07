# TinyCC Compatibility Baseline

This inventory records the current C implementation that the Kotlin/JVM port must replace. Primary references are the current `work` branch, `Makefile`, `configure`, `tcc.h`, `libtcc.h`, and `tests/Makefile`.

## Public surfaces

- CLI driver: `tcc.c`; option parsing and linker options: `libtcc.c`. The compatibility surface includes preprocessing (`-E`, `-M*`), compilation (`-c`, `-run`, `-shared`, `-static`, `-r`), diagnostics (`-W*`, `-w`, `-g`, `-bt`), search/configuration (`-I`, `-isystem`, `-L`, `-l`, `-B`, `-D`, `-U`, `-include`), target flags (`-m*`, `-f*`), output (`-o`, `-soname`), and `-Wl,`/`-Wp,` forwarding.
- Embedding API: `libtcc.h` exposes lifecycle, allocator, error callback, options, include/define configuration, source/file compilation, output selection, library/symbol registration, file output, in-memory relocation, execution, symbol lookup, symbol enumeration, setjmp, and backtrace callbacks.

## Implementation modules

- Front end: `tccpp.c` (preprocessor), `tccgen.c` (parser, types, semantic analysis, code generation), `tccasm.c` (inline/standalone assembler).
- Core/linking: `libtcc.c`, `tccelf.c`, `tcctools.c`, `tccrun.c`, `tccdbg.c`, plus `tcc.h`, `tcctok.h`, `elf.h`, `coff.h`, and `dwarf.h`.
- Target backends: i386, x86_64, ARM, ARM64, RISC-V, and C67 `*-gen.c`, `*-link.c`, and assembler files.
- Formats/runtime: `tccpe.c` (PE), `tccmacho.c` (Mach-O), `tcccoff.c` (COFF), `lib/` (`libtcc1`, builtins, startup, atomics), and `include/`/`win32/include/` headers.

## Targets and platforms

The Makefile lists native/cross targets `i386`, `x86_64`, `i386-win32`, `x86_64-win32`, `x86_64-osx`, `arm`, `arm64`, `arm64-win32`, `arm-wince`, `c67`, `riscv64`, and `arm64-osx`, with additional ARM ABI variants. `configure` also handles Linux, Darwin, Windows, Android/Termux, FreeBSD, NetBSD, OpenBSD, and DragonFly, including musl/uClibc, PIE/PIC, TLS, DWARF/Stabs, bounds checking, and backtrace switches.

## Outputs and tests

The implementation emits ELF, binary images, COFF, PE, and Mach-O outputs; loads archives, objects, shared libraries, DLLs, and linker scripts; and supports static/shared libraries plus in-memory execution. Regression coverage is in `tests/`, `tests/pp`, `tests/tests2`, `examples/`, and Windows batch tests. The primary gates are `make test`, the preprocessor expected files, ABI/assembler tests, runtime/linker tests, and cross-target builds.

The passing host build/test run and reproducible representative outputs are recorded in [`baseline/README.md`](baseline/README.md), with example, preprocessor, and ELF-header goldens under `baseline/`.
