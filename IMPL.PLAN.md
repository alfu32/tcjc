# TinyCC to Kotlin/JVM Implementation Plan

## Dashboard

```text
Overall: 9/36

[DONE]        [3/3]  1. Source preservation and parity rebaseline
[DONE]        [3/3]  2. Lexer, tokens, and preprocessing
[DONE]        [3/3]  3. Types, declarations, symbols, and constants
[IN_PROGRESS] [0/3]  4. Expressions, statements, functions, and semantics
[TODO]        [0/3]  5. Complete IR, lowering, and optimization
[TODO]        [0/3]  6. i386 and x86_64 instruction and ABI support
[TODO]        [0/3]  7. ARM and ARM64 instruction and ABI support
[TODO]        [0/3]  8. RISC-V and C67 instruction and ABI support
[TODO]        [0/3]  9. Object formats, linker, and relocations
[TODO]        [0/3] 10. Runtime, execution, debugging, and bounds support
[TODO]        [0/3] 11. CLI, embedding API, configuration, and distributions
[TODO]        [0/3] 12. Original tests, differential parity, and final cutover

Current task:
4.1 — implement the complete expression grammar, conversions, lvalues, pointer arithmetic, compound literals, and GNU/TCC expression extensions

Current milestone:
M2 — complete C expression, statement, and function semantics
```

`completed/total` counts terminal subtasks in the complete subtree. A task is
`DONE` only when all of its subtasks are `DONE`. Statuses are `TODO`,
`IN_PROGRESS`, `DONE`, and `BLOCKED`.

## Objective and non-negotiable gates

The deliverable is a full Kotlin/JVM implementation of the TinyCC behavior
represented by the historical source tree at the parent of cutover commit
`4f0b9e93`. This includes the C language, GNU/TCC extensions, preprocessor,
assembler, all recorded targets, object formats, linker behavior, runtime
helpers, CLI, and `libtcc`-equivalent embedding API.

- `legacy-c/` is the preserved source/reference tree, never an active build
  input or packaged artifact.
- The original regression suite remains under `tests/`; new executable tests
  are Gradle/Kotlin tests under `compiler-tests/`.
- Production implementation and shipped artifacts contain only Kotlin/JVM
  code and JVM resources. No C source, native library, DLL/SO, JNI bridge, or
  external TinyCC executable may be required.
- Every historical data table, opcode definition, ABI rule, relocation form,
  and generated binary format must have an explicit Kotlin implementation and
  a parity test. A reduced enum, facade, placeholder, or smoke-only encoder is
  not completion.
- Completion requires a clean checkout build, the restored original tests,
  deterministic differential/golden tests, and a source/artifact audit proving
  the pure Kotlin/JVM boundary.

After each completed subtask, update this dashboard and its aggregate status,
run the relevant checks, commit using Conventional Commits, and push to
`origin work` before starting the next subtask.

## Implementation tasks

### 1. Source preservation and parity rebaseline — `DONE` (3/3)

- [x] 1.1 Restore every historical TinyCC implementation, header, target,
  runtime, build, and platform file under `legacy-c/`, excluding tests. — `DONE`
  (restored 61 C/C++ sources, 111 headers, 5 assembly files, platform/runtime
  support, build files, and associated historical material from `4f0b9e93^`)
- [x] 1.2 Restore and preserve the original C regression suite under `tests/`
  without deleting existing fixtures or generated outputs. — `DONE`
  (restored 174 C/C++ tests, 4 assembly tests, 354 historical test paths, and
  retained the existing generated fixtures)
- [x] 1.3 Produce a module-by-module gap matrix mapping every historical file,
  table, feature, option, target, and test to Kotlin code and parity evidence.
  — `DONE` (see `MIGRATION.GAP.md`; all current entries remain `PARTIAL` or
  `MISSING` until complete behavioral evidence exists)

### 2. Lexer, tokens, and preprocessing — `DONE` (3/3)

- [x] 2.1 Implement exact token numbering, identifiers, literals, escapes,
  comments, character sets, locations, and error recovery. — `DONE`
  (added historical base token IDs and literal classes, raw IDs on tokens,
  hexadecimal floating constants, digraphs, line splicing, GNU escapes, and
  universal character-name validation with focused lexer tests)
- [x] 2.2 Implement macro expansion, token pasting/stringizing, conditionals,
  includes, pragmas, predefined macros, and line control. — `DONE`
  (added correct raw/expanded substitution, GNU named variadics, comma elision,
  `__has_include`, full integer/ternary conditional parsing, pragma event
  capture, and focused tests)
- [x] 2.3 Port every preprocessor fixture and add differential tests for all
  historical lexer/preprocessor edge cases. — `DONE`
  (added a token-level differential harness covering all restored `tests/pp`
  C/assembly fixtures and `pp-counter.c`, including diagnostics for macro
  redefinition; the complete focused preprocessor suite passes)

### 3. Types, declarations, symbols, and constants — `DONE` (3/3)

- [x] 3.1 Implement all scalar, pointer, array, function, record, enum,
  typedef, VLA, qualifier, attribute, and compatible-type rules. — `DONE`
  (expanded the Kotlin type algebra, target-independent declarator rules,
  parameter adjustment, C attributes, calling conventions, completeness, and
  constraint validation with focused type/layout tests)
- [x] 3.2 Implement namespaces, scopes, linkage, storage classes, tentative
  definitions, visibility, declaration merging, and symbol lifetime. — `DONE`
  (added linkage-aware block `extern` reuse, declaration history, tentative
  definition finalization, namespace/storage diagnostics, and lifetime tests)
- [x] 3.3 Implement integer/floating/address constant evaluation, initializer
  folding, layout, alignment, bit-fields, and ABI metadata. — `DONE` (implemented
  short-circuit and mixed numeric folding, hexadecimal floating constants,
  relocatable addresses, aggregate initializer zero-fill, flexible/vector
  layout, bit-field packing and constraints, and target ABI metadata for i386,
  x86_64, ARM, ARM64, RISC-V, and C67; focused and full Gradle suites pass)

### 4. Expressions, statements, functions, and semantics — `IN_PROGRESS` (0/3)

- [ ] 4.1 Implement the complete expression grammar, conversions, lvalues,
  pointer arithmetic, compound literals, and GNU/TCC expression extensions. — `IN_PROGRESS`
  (implemented postfix operators, lvalue/modifiability and scalar/pointer
  conversions, pointer arithmetic, compound-literal validation, `_Generic`,
  `typeof`, `_Alignof`, GNU statement expressions, and core builtins including
  `__builtin_choose_expr`, `__builtin_constant_p`, `__builtin_expect`, frame/
  return-address, `alloca`, `unreachable`, type-compatible and variadic type
  queries, label addresses, and atomic builtin families; remaining
  designated initializer AST/validation and aggregate placement, abstract
  array compound literals, and `__builtin_offsetof` are now covered; inline-asm
  templates/statements with constraint validation are now covered; target-
  specific atomic and variadic lowering, global asm, and full
  declarator-aware expression coverage remain open; GNU elvis conditionals,
  adjacent string concatenation, and complex scalar type parsing, promotion,
  and layout are also covered; abstract array/function-pointer declarators and
  calls through function-pointer expressions are now covered; parser-provided
  typedef and tagged-record/enum type names are now accepted in type names;
  specifier permutations, `_Atomic(type)`, and GNU `typeof(type)` type forms
  are now parsed; TinyCC special floating constants now retain float typing
  and constant-evaluation semantics)
- [ ] 4.2 Implement declarations in blocks, control flow, labels, switches,
  VLA cleanup, function definitions, variadics, nested functions, and returns.
  — `TODO`
- [ ] 4.3 Match semantic diagnostics, recovery, unreachable-code behavior,
  constraints, and all parser/semantic regression cases. — `TODO`

### 5. Complete IR, lowering, and optimization — `TODO` (0/3)

- [ ] 5.1 Port the full value stack, lvalue model, temporaries, memory model,
  calls, aggregates, atomics, TLS, symbols, sections, and relocations. — `TODO`
- [ ] 5.2 Implement target-independent lowering, constant folding, register
  allocation, stack frames, calling-convention hooks, and required optimizations.
  — `TODO`
- [ ] 5.3 Verify that every C construct lowers deterministically to complete IR
  with source locations, diagnostics, and no placeholder operation. — `TODO`

### 6. i386 and x86_64 instruction and ABI support — `TODO` (0/3)

- [ ] 6.1 Port the complete instruction-definition matrices from the historical
  assembler tables, including operand classes, prefixes, groups, and aliases.
  — `TODO`
- [ ] 6.2 Implement instruction selection/encoding, x87/SSE/atomics, PIC/PIE,
  TLS, relocations, register allocation, and both i386 and SysV/Win64 ABIs. — `TODO`
- [ ] 6.3 Pass byte-level assembler, compile, link, run, ABI, and self-hosting
  parity tests for both x86 targets. — `TODO`

### 7. ARM and ARM64 instruction and ABI support — `TODO` (0/3)

- [ ] 7.1 Port ARM/Thumb instruction matrices, unified assembler parsing,
  VFP/EABI rules, relocations, and register/stack conventions. — `TODO`
- [ ] 7.2 Port ARM64 instruction selection/encoding, AAPCS64, floating point,
  atomics, TLS, PIC, Apple, Windows, and ELF platform rules. — `TODO`
- [ ] 7.3 Validate byte-level outputs and cross-target fixtures with execution
  where available and deterministic golden checks otherwise. — `TODO`

### 8. RISC-V and C67 instruction and ABI support — `TODO` (0/3)

- [ ] 8.1 Port RISC-V instruction/assembler tables, RV32/RV64 conventions,
  atomics, floating point, relocations, and compressed instructions. — `TODO`
- [ ] 8.2 Port C67 code generation, restrictions, assembler behavior, and COFF
  integration without reducing target coverage to metadata. — `TODO`
- [ ] 8.3 Run complete cross-target compile/link fixtures and document only
  environment-specific execution requirements, never omitted implementation.
  — `TODO`

### 9. Object formats, linker, and relocations — `TODO` (0/3)

- [ ] 9.1 Implement complete ELF sections, symbols, archives, relocations,
  dynamic linking, TLS, DWARF/Stabs, and startup rules. — `TODO`
- [ ] 9.2 Implement complete COFF/PE and Mach-O output, import/export data,
  DLL/shared-library metadata, and platform relocation behavior. — `TODO`
- [ ] 9.3 Implement linker scripts, static/shared/PIC modes, symbol resolution,
  alignment, common symbols, weak symbols, and error diagnostics. — `TODO`

### 10. Runtime, execution, debugging, and bounds support — `TODO` (0/3)

- [ ] 10.1 Port all `libtcc1`, builtin, arithmetic, startup, atomics, varargs,
  and compiler-runtime helpers as Kotlin/JVM-owned implementations. — `TODO`
- [ ] 10.2 Implement `-run`, temporary images, environment/exit behavior,
  symbol registration, in-memory relocation, and execution policies. — `TODO`
- [ ] 10.3 Implement bounds checking, backtraces, debug metadata, profiling,
  sanitizer diagnostics, and resource ownership with parity tests. — `TODO`

### 11. CLI, embedding API, configuration, and distributions — `TODO` (0/3)

- [ ] 11.1 Implement every historical command-line option, response-file rule,
  search path, target flag, output mode, warning, and diagnostic format. — `TODO`
- [ ] 11.2 Implement the complete `libtcc`-equivalent lifecycle, callbacks,
  source/file compilation, output, relocation, execution, and symbol APIs. — `TODO`
- [ ] 11.3 Produce reproducible Gradle/JVM distributions and verify that no
  legacy source or native payload enters any published artifact. — `TODO`

### 12. Original tests, differential parity, and final cutover — `TODO` (0/3)

- [ ] 12.1 Port the restored C, ABI, assembler, VLA, bounds, linker, runtime,
  library, and platform tests to Gradle-managed Kotlin/JVM execution. — `TODO`
- [ ] 12.2 Compare Kotlin outputs with captured historical behavior across all
  targets, options, optimization modes, diagnostics, and malformed inputs. — `TODO`
- [ ] 12.3 Run clean-checkout release gates, fuzzing, coverage, performance,
  source audits, and final documentation review before marking completion. — `TODO`

## Progress rules

No task may be marked `DONE` because a class or test exists. It must have
behavioral evidence covering the complete historical scope named by that task.
If a feature is not yet implemented, leave it `TODO` or `IN_PROGRESS`; do not
rename it, hide it behind a facade, or claim compatibility by documentation.
