# Kotlin/JVM Migration Gap Matrix

This matrix is the starting audit for the full reimplementation. The
historical reference is the complete tree in `legacy-c/` restored from
`4f0b9e93^`; the original regression suite is under `tests/`. `VERIFIED` means
behavioral parity evidence exists for the complete scope, not that a type or
test merely exists. Current entries are deliberately conservative.

| Area | Historical implementation | Current Kotlin evidence | Status | Required closure evidence |
|---|---|---|---|---|
| Core state and utilities | `tcc.h`, `libtcc.c`, allocators, arenas, hash tables | `compiler-core/.../collections`, API session | PARTIAL | Reentrant lifetime, allocation, error, and callback parity |
| Tokens and lexer | `tcctok.h`, `tccpp.c` token tables and character tables | `core/lexer/Lexer.kt`, `Token.kt`; ordered target token spellings and raw IDs (including assembler vocabulary) are JVM data for x86, ARM EABI/VFP/soft-float, ARM64, RISC-V, and C67; default and `CONFIG_TCC_BCHECK` tables are selectable; unknown identifiers are interned per profile and session | PARTIAL | Other build-option token variants, extension behavior, literal/escape edge cases, character sets, malformed-input parity |
| Preprocessor | `tccpp.c`, `include/tccdefs.h`, include search and pragma logic | `core/preprocessor` | PARTIAL | All `tests/pp`, macro edge cases, predefined/platform macros |
| Types and declarations | `tcc.h`, `tccgen.c` type/declaration machinery | `core/types`, `symbols` | PARTIAL | Full C/GNU types, attributes, VLA, bit-fields, linkage, ABI |
| Expressions/constants | `tccgen.c` expression parser and constant folding | `core/expressions`, `constants`, `semantics` | PARTIAL | All operators, conversions, relocatable constants, diagnostics |
| Statements/functions | `tccgen.c`, `tccrun.c` cleanup/control-flow paths | `core/statements`, `functions`, semantics | PARTIAL | Nested functions, computed goto, VLA cleanup, extensions |
| IR/lowering | `tccgen.c` value stack, lvalues, temporaries, direct lowering | `core/ir` model/verifier/emitters | PARTIAL | Complete lowering for every AST construct; no placeholder IR |
| x86 instruction data | `i386-asm.h`, `x86_64-asm.h`, `i386-asm.c` | `backends/x86/X86Backend.kt`; selected zero-operand instruction bytes are now represented and tested in the JVM encoder | PARTIAL | Port every opcode, operand class, prefix, alias, and encoding table |
| x86 code generation | `i386-gen.c`, `x86_64-gen.c`, link files | X86 selector, selected zero-operand encodings, LEA, TEST, signed IMUL, indirect/local CALL, register/memory/immediate PUSH/POP, SHL/SHR/SAR immediate and CL forms, MOV/ADD/SUB register and simple memory forms, immediate integer arithmetic/comparison, and rel32 JMP/all conditional branches with block fixups | PARTIAL | Complete byte-accurate instruction set, external call relocations, operand widths, indexed/relocated addressing, ABI, SSE/x87, TLS, PIC, atomics |
| ARM/ARM64 | `arm-*.c`, `arm64-*.c`, assembler tables | `backends/arm`, `arm64` | PARTIAL | Complete ARM/Thumb/AArch64 encoders and platform relocations |
| RISC-V/C67 | `riscv64-*.c`, `c67-*.c` | `backends/riscv`, `c67` | PARTIAL | Full instruction data, assembler, ABI, relocations, COFF |
| ELF/linker | `tccelf.c`, `elf.h`, `stab.h`, `tccrun.c` | `backends/object/ElfWriter.kt` | PARTIAL | Sections, archives, symbols, all relocations, TLS, DWARF/Stabs, linking |
| PE/COFF/Mach-O | `tcccoff.c`, `tccpe.c`, `tccmacho.c`, platform headers | `backends/portable/PortableObjectWriters.kt` | PARTIAL | Valid binary writers, imports/exports, relocations, platform startup |
| Runtime helpers | `lib/*.c`, `lib/*.S`, `libtcc1.c`, `win32/lib` | `compiler-runtime` Kotlin classes | PARTIAL | All arithmetic, atomics, varargs, startup, builtins, ABI helpers |
| Execution/debugging | `tccrun.c`, `tccdbg.c`, `lib/bcheck.c`, `lib/bt-*.c` | `api/execution`, runtime diagnostics | PARTIAL | Run, relocation, bounds, backtraces, debug/profiling parity |
| CLI/configuration | `tcc.c`, `libtcc.c`, `configure`, `Makefile` | `compiler-cli`, typed Gradle config; stdin identity/search, multi-file preprocess/token aggregation, stdout `-o -`, default/`-P`/`-P1` markers, and `-P10` numeric/character/string conversion and 64-bit overflow warning are covered | PARTIAL | `-P10` malformed/universal escapes, remaining target-specific wide-string and numeric cases; every other option, response file, multi-unit compilation/linking, full search-path, and diagnostic parity |
| Embedding API | `libtcc.h`, `libtcc.c` | `api/embedding/Embedding.kt` | PARTIAL | Complete lifecycle, callbacks, output, relocation, symbols, execution |
| Original tests | `tests/` (174 C/C++, 4 assembly, 354 paths) | 101 Kotlin tests and restored references | PARTIAL | Every historical test has an executable Kotlin/JVM parity counterpart |
| Artifact boundary | Native Make/configure and TinyCC binaries | Gradle/JVM, but legacy reference exists | PARTIAL | Clean artifact scan proves no C/native payload or dependency is shipped |

## Known explicit gaps

The active tree currently contains deliberate incompleteness that cannot be
called compatibility:

- `X86MachineCode.kt` supports only explicitly encoded scalar/control forms;
  other instruction families and many operand widths/addressing forms remain
  unsupported.
- `CrossTargetMatrix.kt` emits pending fixtures for some targets.
- `Main.kt` aggregates multiple files only for token/preprocess output; full
  multi-unit compilation and linking are not implemented.
- Execution and embedding explicitly reject native libraries.
- Backend classes contain unsupported-operation branches for real language
  operations, and the portable Mach-O path is not a complete object writer.

These are implementation tasks, not accepted platform limitations. Each must
be removed or replaced by a pure Kotlin/JVM implementation before completion.
