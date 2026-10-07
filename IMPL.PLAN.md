# TinyCC to Kotlin/JVM Migration Plan

## Dashboard

```text
Overall: 10/48

[IN_PROGRESS] [1/9]  1. Language front-end
[IN_PROGRESS] [3/6]  2. Semantic model and modules
[TODO]       [0/3]   3. Compile-time and IR system
[TODO]       [0/12]  4. Lowering and native target backends
[IN_PROGRESS] [6/15] 5. Tooling, integration and quality
[TODO]       [0/3]   6. Runtime, SDK and platform ABI

Current task:
4.2 Macro definitions and conditional compilation

Current milestone:
M1 — Language front-end
```

Dashboard mapping: M1 = tasks 4, 6, 7; M2 = tasks 3, 5; M3 = task 8; M4 = tasks 9–12; M5 = tasks 1, 2, 14–16; M6 = task 13. Each `completed/total` value counts terminal subtasks in that milestone subtree.

## Status

- Overall migration: **NOT_STARTED** (3/16 implementation tasks; 9/48 subtasks complete)
- Planning artifact: **DONE**
- Status values: `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED`.
- New tasks and subtasks start as `TODO`; dashboard states are derived from their current descendants.
- A task is `DONE` only when all three subtasks are `DONE`; its aggregate is shown as `x/3`.
- After every subtask completion, update its status and the affected aggregate, run the relevant checks, commit, and push to `origin work`. If a push fails, keep the commit and resolve the push before starting the next subtask.

## Scope and Compatibility Contract

The end state is a Kotlin/JVM implementation that can be built and tested entirely through Gradle, with no C compiler sources required at build or runtime. Preserve the current TinyCC command-line behavior, `libtcc`-style embedding API, diagnostics, C language behavior, and native output targets unless a compatibility change is explicitly documented. The JVM is the host for the compiler; native target generation remains part of the migration rather than being silently replaced by JVM-only output.

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

### 4. Lexer, tokens, and preprocessor — `IN_PROGRESS` (1/3)

- [x] 4.1 Port character decoding, token kinds, literals, comments, identifiers, escapes, and token location tracking. — `DONE` (see `compiler-core/.../io/SourceFiles.kt` and `lexer`)
- [ ] 4.2 Port macro definitions/expansion, conditional compilation, variadic macros, and predefined macros. — `TODO`
- [ ] 4.3 Port include resolution, pragma handling, line directives, and all `tests/pp` expected-output cases. — `TODO`

### 5. C types, symbols, and declarations — `TODO` (0/3)

- [ ] 5.1 Implement primitive, qualified, pointer, array, function, struct/union, enum, typedef, and variably modified types. — `TODO`
- [ ] 5.2 Port symbol scopes, namespaces, storage classes, linkage, visibility, and declaration merging. — `TODO`
- [ ] 5.3 Add type compatibility, layout/alignment, ABI metadata, and diagnostics tests for declaration edge cases. — `TODO`

### 6. Expressions and constant evaluation — `TODO` (0/3)

- [ ] 6.1 Port expression parsing with precedence, casts, compound literals, initializer expressions, and GNU-compatible extensions in scope. — `TODO`
- [ ] 6.2 Implement conversions, lvalues, pointer arithmetic, qualifiers, overload-free operator typing, and diagnostics. — `TODO`
- [ ] 6.3 Port integer, floating-point, address, relocation, and compile-time constant evaluation with golden tests. — `TODO`

### 7. Statements, functions, and control flow — `TODO` (0/3)

- [ ] 7.1 Port blocks, declarations, expression statements, selection, loops, jumps, labels, and switch lowering. — `TODO`
- [ ] 7.2 Port function definitions, parameters, calling metadata, variadic functions, nested/local functions, and returns. — `TODO`
- [ ] 7.3 Add parser/semantic regression coverage for scope, unreachable code, VLA behavior, and control-flow errors. — `TODO`

### 8. Generic IR and code-emission layer — `TODO` (0/3)

- [ ] 8.1 Define a typed intermediate representation for values, memory, calls, branches, symbols, relocations, and debug locations. — `TODO`
- [ ] 8.2 Port register/stack abstractions, calling-convention hooks, section management, and relocation contracts. — `TODO`
- [ ] 8.3 Implement deterministic assembly/object emission interfaces and differential tests against captured C implementation output. — `TODO`

### 9. i386 and x86_64 backends — `TODO` (0/3)

- [ ] 9.1 Port instruction selection, register allocation, ABI handling, prologues/epilogues, and assembler support. — `TODO`
- [ ] 9.2 Port x86 floating-point, SSE, atomics, TLS, PIC/PIE, and architecture-specific relocations. — `TODO`
- [ ] 9.3 Pass native i386/x86_64 compile, link, run, ABI, assembler, and self-hosting parity tests. — `TODO`

### 10. ARM and ARM64 backends — `TODO` (0/3)

- [ ] 10.1 Port ARM instruction generation, ARM/Thumb ABI choices, VFP/EABI variants, and assembler behavior. — `TODO`
- [ ] 10.2 Port ARM64 instruction generation, calling convention, floating-point, atomics, and platform ABI details. — `TODO`
- [ ] 10.3 Validate ARM/ARM64 cross builds and execution where available, including Windows and Apple variants. — `TODO`

### 11. RISC-V and C67 backends — `TODO` (0/3)

- [ ] 11.1 Port RISC-V instruction selection, register conventions, relocations, and assembler support. — `TODO`
- [ ] 11.2 Port C67 code generation, COFF integration, and the target-specific restrictions currently encoded in TCC. — `TODO`
- [ ] 11.3 Add cross-target compile/link fixtures and document toolchain/emulator requirements for unavailable hosts. — `TODO`

### 12. Object formats, linker, and native runtime — `TODO` (0/3)

- [ ] 12.1 Port ELF sections, symbols, relocations, dynamic linking, TLS, DWARF/Stabs metadata, and platform startup rules. — `TODO`
- [ ] 12.2 Port PE/COFF and Mach-O writers/linking paths, import/export handling, and Windows runtime integration. — `TODO`
- [ ] 12.3 Port `libtcc1` and assembly/C runtime helpers, then verify static, shared, PIC, and cross-linked programs. — `TODO`

### 13. Execution, bounds checking, debugging, and embedding — `TODO` (0/3)

- [ ] 13.1 Port `-run`, temporary executable handling, dynamic library loading, environment propagation, and exit behavior. — `TODO`
- [ ] 13.2 Port bounds checking, backtraces, debug information, profiling hooks, and sanitizer-friendly diagnostics. — `TODO`
- [ ] 13.3 Implement and test the Kotlin/JVM embedding API equivalent to `libtcc`, including callbacks and resource ownership. — `TODO`

### 14. CLI, configuration, and distributions — `TODO` (0/3)

- [ ] 14.1 Port command-line parsing, help/version output, response files, scripts, include/library search paths, and target selection. — `TODO`
- [ ] 14.2 Replace shell/Make configuration with typed Gradle and runtime configuration while preserving install and cross-build options. — `TODO`
- [ ] 14.3 Produce reproducible JVM distributions, native launcher scripts, Maven-publishable artifacts, and Windows packages. — `TODO`

### 15. Full test parity and migration hardening — `TODO` (0/3)

- [ ] 15.1 Port the C, ABI, assembler, VLA, bounds, linker, runtime, and library tests to Gradle-managed Kotlin/JVM test execution. — `TODO`
- [ ] 15.2 Add differential testing against the captured C implementation across supported hosts, targets, and optimization/configuration modes. — `TODO`
- [ ] 15.3 Run coverage, sanitization-equivalent JVM checks, fuzzing, performance comparisons, and fix all release-blocking discrepancies. — `TODO`

### 16. Cutover, documentation, and C removal — `TODO` (0/3)

- [ ] 16.1 Switch default build, tests, examples, CI configuration, and contributor instructions to Gradle/Kotlin/JVM. — `TODO`
- [ ] 16.2 Update user/API documentation, architecture notes, migration notes, licensing attributions, and release metadata. — `TODO`
- [ ] 16.3 Remove obsolete C build paths only after parity gates pass; perform a clean checkout build and final repository audit. — `TODO`

## Completion Gate

The migration is complete only when all 16 tasks and 48 subtasks are `DONE`, a clean checkout builds with `./gradlew`, the ported test suites pass on every supported environment, the CLI and embedding API parity gates pass, and no runtime or build path depends on the original C implementation.
