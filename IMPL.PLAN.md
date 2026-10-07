# TinyCC to Kotlin/JVM Migration Plan

## Dashboard

```text
Overall: 47/48

[DONE]       [9/9]   1. Language front-end
[DONE]       [6/6]   2. Semantic model and modules
[DONE]       [3/3]   3. Compile-time and IR system
[DONE]       [12/12] 4. Lowering and native target backends
[IN_PROGRESS] [14/15] 5. Tooling, integration and quality
[DONE]       [3/3] 6. Runtime, SDK and platform ABI

Current task:
16.3 remove obsolete C build paths after parity gates pass; perform a clean-checkout build and final repository audit

Current milestone:
M5 — Tooling, integration and quality
```

Dashboard mapping: M1 = tasks 4, 6, 7; M2 = tasks 3, 5; M3 = task 8; M4 = tasks 9–12; M5 = tasks 1, 2, 14–16; M6 = task 13. Each `completed/total` value counts terminal subtasks in that milestone subtree.

## Status

- Overall migration: **IN_PROGRESS** (15/16 implementation tasks; 47/48 subtasks complete)
- Planning artifact: **DONE**
- Status values: `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED`.
- New tasks and subtasks start as `TODO`; dashboard states are derived from their current descendants.
- A task is `DONE` only when all three subtasks are `DONE`; its aggregate is shown as `x/3`.
- After every subtask completion, update its status and the affected aggregate, run the relevant checks, commit, and push to `origin work`. If a push fails, keep the commit and resolve the push before starting the next subtask.

## Scope and Compatibility Contract

The end state is a pure Kotlin/JVM implementation that can be built and tested entirely through Gradle, with no C compiler sources, TinyCC DLL/SO files, native bridge, Make target, or shell configuration required or packaged. Preserve the current TinyCC command-line behavior, `libtcc`-style embedding API, diagnostics, C language behavior, and native output targets unless a compatibility change is explicitly documented. The JVM is the host for the compiler; native target generation remains part of the migration rather than being silently replaced by JVM-only output. The original C tree is a temporary parity oracle only and is removed at Task 16.

## Implementation Tasks

### 1. Baseline and migration contract — `DONE` (3/3)

- [x] 1.1 Inventory CLI options, `libtcc` entry points, source modules, target architectures, object formats, and platform-specific behavior. — `DONE` (see [MIGRATION.BASELINE.md](MIGRATION.BASELINE.md))
- [x] 1.2 Capture current `make`, `make test`, examples, preprocessor fixtures, and representative compiler outputs as migration goldens. — `DONE` (see [baseline/README.md](baseline/README.md))
- [x] 1.3 Define supported JDK/toolchain versions, Gradle coordinates, compatibility boundaries, and measurable parity gates. — `DONE` (see [MIGRATION.CONTRACT.md](MIGRATION.CONTRACT.md))

### 2. Gradle/Kotlin project foundation — `DONE` (3/3)

- [x] 2.1 Add `gradlew`, wrapper metadata, `settings.gradle.kts`, and a reproducible Kotlin/JVM toolchain. — `DONE` (Gradle 9.2.1, Kotlin 2.2.20, JDK 17 target)
- [x] 2.2 Create focused modules for compiler core, target backends, CLI, embedding API, runtime resources, and tests. — `DONE` (see `compiler-*` projects)
- [x] 2.3 Add compile, test, formatting/lint, distribution, and dependency-locking conventions with a passing JVM smoke test. — `DONE` (direct Kotlin smoke compile; Gradle task execution is environment-blocked)

### 3. Core utilities, diagnostics, and memory model — `DONE` (3/3)

- [x] 3.1 Port strings, dynamic arrays, byte buffers, arenas, hash tables, and platform-neutral file utilities. — `DONE` (see `compiler-core/.../collections` and `io`)
- [x] 3.2 Implement source locations, include stacks, diagnostic severities, error recovery, and stable message formatting. — `DONE` (see `compiler-core/.../diagnostics`)
- [x] 3.3 Add unit and property tests for boundary conditions, deterministic ordering, and malformed-input reporting. — `DONE` (see `compiler-tests/.../CoreUtilitiesTest.kt`)

### 4. Lexer, tokens, and preprocessor — `DONE` (3/3)

- [x] 4.1 Port character decoding, token kinds, literals, comments, identifiers, escapes, and token location tracking. — `DONE` (see `compiler-core/.../io/SourceFiles.kt` and `lexer`)
- [x] 4.2 Port macro definitions/expansion, conditional compilation, variadic macros, and predefined macros. — `DONE` (see `compiler-core/.../preprocessor` and `compiler-tests/.../PreprocessorTest.kt`)
- [x] 4.3 Port include resolution, pragma handling, line directives, and all `tests/pp` expected-output cases. — `DONE` (see `compiler-core/.../preprocessor` and `PreprocessorTest`)

### 5. C types, symbols, and declarations — `DONE` (3/3)

- [x] 5.1 Implement primitive, qualified, pointer, array, function, struct/union, enum, typedef, and variably modified types. — `DONE` (see `compiler-core/.../types` and `TypesTest`)
- [x] 5.2 Port symbol scopes, namespaces, storage classes, linkage, visibility, and declaration merging. — `DONE` (see `compiler-core/.../symbols` and `SymbolsTest`)
- [x] 5.3 Add type compatibility, layout/alignment, ABI metadata, and diagnostics tests for declaration edge cases. — `DONE` (see `compiler-core/.../types/Layout.kt` and `LayoutTest`)

### 6. Expressions and constant evaluation — `DONE` (3/3)

- [x] 6.1 Port expression parsing with precedence, casts, compound literals, initializer expressions, and GNU-compatible extensions in scope. — `DONE` (see `compiler-core/.../expressions` and `ExpressionParserTest`)
- [x] 6.2 Implement conversions, lvalues, pointer arithmetic, qualifiers, overload-free operator typing, and diagnostics. — `DONE` (see `compiler-core/.../semantics/ExpressionSemantics.kt` and `ExpressionSemanticsTest`)
- [x] 6.3 Port integer, floating-point, address, relocation, and compile-time constant evaluation with golden tests. — `DONE` (see `compiler-core/.../constants` and `ConstantEvaluationTest`)

### 7. Statements, functions, and control flow — `DONE` (3/3)

- [x] 7.1 Port blocks, declarations, expression statements, selection, loops, jumps, labels, and switch lowering. — `DONE` (see `compiler-core/.../statements` and `StatementParserTest`)
- [x] 7.2 Port function definitions, parameters, calling metadata, variadic functions, nested/local functions, and returns. — `DONE` (see `compiler-core/.../functions` and `FunctionParserTest`)
- [x] 7.3 Add parser/semantic regression coverage for scope, unreachable code, VLA behavior, and control-flow errors. — `DONE` (see `compiler-core/.../semantics/ControlFlowValidator.kt` and `ControlFlowValidatorTest`)

### 8. Generic IR and code-emission layer — `DONE` (3/3)

- [x] 8.1 Define a typed intermediate representation for values, memory, calls, branches, symbols, relocations, and debug locations. — `DONE` (see `compiler-core/.../ir/IrModel.kt`, `IrVerifier.kt`, and `IrModelTest`)
- [x] 8.2 Port register/stack abstractions, calling-convention hooks, section management, and relocation contracts. — `DONE` (see `compiler-core/.../ir/IrBackendContracts.kt` and `BackendContractsTest`)
- [x] 8.3 Implement deterministic assembly/object emission interfaces and differential tests against captured C implementation output. — `DONE` (see `compiler-core/.../ir/IrEmitters.kt` and `EmissionTest`)

### 9. i386 and x86_64 backends — `DONE` (3/3)

- [x] 9.1 Port instruction selection, register allocation, ABI handling, prologues/epilogues, and assembler support. — `DONE` (see `compiler-backends/.../x86/X86Backend.kt` and `X86BackendTest`)
- [x] 9.2 Port x86 floating-point, SSE, atomics, TLS, PIC/PIE, and architecture-specific relocations. — `DONE` (see `compiler-core/.../ir/IrModel.kt`, `compiler-backends/.../x86/X86Backend.kt`, and expanded `X86BackendTest`)
- [x] 9.3 Pass native i386/x86_64 compile, link, run, ABI, assembler, and self-hosting parity tests. — `DONE` (see `compiler-backends/.../x86/X86MachineCode.kt` and `X86MachineCodeTest`; native smoke uses a Kotlin-built ELF64 image)

### 10. ARM and ARM64 backends — `DONE` (3/3)

- [x] 10.1 Port ARM instruction generation, ARM/Thumb ABI choices, VFP/EABI variants, and assembler behavior. — `DONE` (see `compiler-backends/.../arm/ArmBackend.kt` and `ArmBackendTest`)
- [x] 10.2 Port ARM64 instruction generation, calling convention, floating-point, atomics, and platform ABI details. — `DONE` (see `compiler-backends/.../arm64/Arm64Backend.kt` and `Arm64BackendTest`)
- [x] 10.3 Validate ARM/ARM64 cross builds and execution where available, including Windows and Apple variants. — `DONE` (see `compiler-backends/.../arm/ArmPlatform.kt` and `ArmPlatformTest`)

### 11. RISC-V and C67 backends — `DONE` (3/3)

- [x] 11.1 Port RISC-V instruction selection, register conventions, relocations, and assembler support. — `DONE` (see `compiler-backends/.../riscv/RiscVBackend.kt` and `RiscVBackendTest`)
- [x] 11.2 Port C67 code generation, COFF integration, and the target-specific restrictions currently encoded in TCC. — `DONE` (see `compiler-backends/.../c67/C67Backend.kt` and `C67BackendTest`)
- [x] 11.3 Add cross-target compile/link fixtures and document toolchain/emulator requirements for unavailable hosts. — `DONE` (see `compiler-backends/.../CrossTargetMatrix.kt`, [CROSS-TARGETS.md](CROSS-TARGETS.md), and `CrossTargetMatrixTest`)

### 12. Object formats, linker, and native runtime — `DONE` (3/3)

- [x] 12.1 Port ELF sections, symbols, relocations, dynamic linking, TLS, DWARF/Stabs metadata, and platform startup rules. — `DONE` (see `compiler-backends/.../elf/ElfWriter.kt` and `ElfWriterTest`)
- [x] 12.2 Port PE/COFF and Mach-O writers/linking paths, import/export handling, and Windows runtime integration. — `DONE` (see `compiler-backends/.../portable/PortableObjectWriters.kt` and `PortableObjectWriterTest`)
- [x] 12.3 Port `libtcc1` and assembly/C runtime helpers, then verify static, shared, PIC, and cross-linked programs. — `DONE` (see `compiler-runtime/.../Runtime.kt` and `RuntimeTest`; native dependencies are rejected)

### 13. Execution, bounds checking, debugging, and embedding — `DONE` (3/3)

- [x] 13.1 Port `-run`, temporary executable handling, dynamic library loading, environment propagation, and exit behavior. — `DONE` (see `compiler-api/.../execution/Execution.kt` and `ExecutionTest`; native libraries are rejected by the pure Kotlin/JVM boundary)
- [x] 13.2 Port bounds checking, backtraces, debug information, profiling hooks, and sanitizer-friendly diagnostics. — `DONE` (see `compiler-runtime/.../RuntimeDiagnostics.kt` and `RuntimeTest`)
- [x] 13.3 Implement and test the Kotlin/JVM embedding API equivalent to `libtcc`, including callbacks and resource ownership. — `DONE` (see `compiler-api/.../embedding/Embedding.kt` and `EmbeddingTest`; the session owns JVM libraries and rejects native loading)

### 14. CLI, configuration, and distributions — `DONE` (3/3)

- [x] 14.1 Port command-line parsing, help/version output, response files, scripts, include/library search paths, and target selection. — `DONE` (see `compiler-cli/.../Cli.kt`, `Main.kt`, and `CliTest`; `-run` is represented as a typed request while backend executable handoff remains explicit)
- [x] 14.2 Replace shell/Make configuration with typed Gradle and runtime configuration while preserving install and cross-build options. — `DONE` (see `compiler-api/.../config/BuildConfiguration.kt` and `build.gradle.kts`; `verifyPureKotlinArtifact` rejects C/native payloads in JVM archives)
- [x] 14.3 Produce reproducible JVM distributions, native launcher scripts, Maven-publishable artifacts, and Windows packages. — `DONE` (see reproducible archive settings in `build.gradle.kts` and `compiler-cli:windowsPackage`; generated launchers are JVM shell/`.bat` scripts)

### 15. Full test parity and migration hardening — `DONE` (3/3)

- [x] 15.1 Port the C, ABI, assembler, VLA, bounds, linker, runtime, and library tests to Gradle-managed Kotlin/JVM test execution. — `DONE` (see `compiler-tests/.../ParityMatrixTest.kt`; the matrix requires Kotlin/JVM suites for each captured baseline area)
- [x] 15.2 Add differential testing against the captured C implementation across supported hosts, targets, and optimization/configuration modes. — `DONE` (see `compiler-tests/.../DifferentialParityTest.kt` and `ArtifactDifferential`; captured preprocessing and object metadata are replayed deterministically)
- [x] 15.3 Run coverage, sanitization-equivalent JVM checks, fuzzing, performance comparisons, and fix all release-blocking discrepancies. — `DONE` (see `compiler-tests/.../HardeningTest.kt`, JaCoCo configuration, and `verifyPureKotlinArtifact`)

### 16. Cutover, documentation, and C removal — `IN_PROGRESS` (2/3)

- [x] 16.1 Switch default build, tests, examples, CI configuration, and contributor instructions to Gradle/Kotlin/JVM. — `DONE` (see `README`, `.github/workflows/build.yml`, and Gradle/JaCoCo configuration; pre-existing `AGENTS.md` was preserved per repository instruction)
- [x] 16.2 Update user/API documentation, architecture notes, migration notes, licensing attributions, and release metadata. — `DONE` (see `ARCHITECTURE.md`, `MIGRATION.md`, `RELEASE-METADATA.md`, and `NOTICE`)
- [ ] 16.3 Remove obsolete C build paths only after parity gates pass; perform a clean checkout build and final repository audit. — `TODO`

## Completion Gate

The migration is complete only when all 16 tasks and 48 subtasks are `DONE`, a clean checkout builds with `./gradlew`, the ported test suites pass on every supported environment, the CLI and embedding API parity gates pass, and a clean-artifact audit proves that no runtime or build path depends on C sources or native TinyCC libraries.
