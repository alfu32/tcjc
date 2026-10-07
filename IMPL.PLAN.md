# TinyCC to Kotlin/JVM Implementation Plan

## Dashboard

```text
Overall: 3/36

[DONE]        [3/3]  1. Source preservation and parity rebaseline
[IN_PROGRESS] [0/3]  2. Lexer, tokens, and preprocessing
[IN_PROGRESS] [0/3]  3. Types, declarations, symbols, and constants
[IN_PROGRESS] [0/3]  4. Expressions, statements, functions, and semantics
[TODO]        [0/3]  5. Complete IR, lowering, and optimization
[IN_PROGRESS] [0/3]  6. i386 and x86_64 instruction and ABI support
[TODO]        [0/3]  7. ARM and ARM64 instruction and ABI support
[TODO]        [0/3]  8. RISC-V and C67 instruction and ABI support
[TODO]        [0/3]  9. Object formats, linker, and relocations
[TODO]        [0/3] 10. Runtime, execution, debugging, and bounds support
[IN_PROGRESS] [0/3] 11. CLI, embedding API, configuration, and distributions
[TODO]        [0/3] 12. Original tests, differential parity, and final cutover

Current task:
6.2 — implement x86 machine-code selection and encoding (selected integer and control encodings are in place; most instructions and ABI behavior remain open)

Current milestone:
M6 — complete i386 and x86_64 instruction, relocation, and ABI support
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

### 2. Lexer, tokens, and preprocessing — `IN_PROGRESS` (0/3)

- [ ] 2.1 Implement exact token numbering, identifiers, literals, escapes,
  comments, character sets, locations, and error recovery. — `IN_PROGRESS`
  (base historical token IDs, literal classes, raw token IDs, hex floats,
  digraphs, line splicing, GNU escapes, and UCN checks exist; full token-table,
  character-set, and malformed-input parity is not yet demonstrated; x86
  Linux/Windows, ARM EABI/VFP/soft-float, ARM64, RISC-V, and C67 conditional
  builtin/atomic/pragma/runtime token orders are covered by profile tests against
  `tcctok.h`; target-specific assembler spellings and unknown-identifier
  interning now come from ordered JVM token data, with session-persistent ID
  tests; the `CONFIG_TCC_BCHECK` token-table variant is represented as separate
  JVM data and selected through lexer/embedding options; other build-option
  variants, character sets, and malformed-input parity remain open)
- [ ] 2.2 Implement macro expansion, token pasting/stringizing, conditionals,
  includes, pragmas, predefined macros, and line control. — `IN_PROGRESS`
  (many GNU macro and conditional forms are covered, but all platform
  predefined macros, include/search behavior, pragmas, and edge cases remain
  incomplete against the historical implementation)
- [ ] 2.3 Port every preprocessor fixture and add differential tests for all
  historical lexer/preprocessor edge cases. — `IN_PROGRESS`
  (the current token-level harness covers `tests/pp` fixtures, not the complete
  lexer/preprocessor corpus or every diagnostic and target configuration)

### 3. Types, declarations, symbols, and constants — `IN_PROGRESS` (0/3)

- [ ] 3.1 Implement all scalar, pointer, array, function, record, enum,
  typedef, VLA, qualifier, attribute, and compatible-type rules. — `IN_PROGRESS`
  (the type algebra and selected declaration/layout constraints exist; the gap
  matrix still identifies incomplete C/GNU type, VLA, and ABI behavior)
- [ ] 3.2 Implement namespaces, scopes, linkage, storage classes, tentative
  definitions, visibility, declaration merging, and symbol lifetime. — `IN_PROGRESS`
  (selected scopes, linkage merging, and tentative-definition behavior have
  tests; complete TinyCC symbol and linkage parity remains unverified)
- [ ] 3.3 Implement integer/floating/address constant evaluation, initializer
  folding, layout, alignment, bit-fields, and ABI metadata. — `IN_PROGRESS`
  (selected folding/layout paths and metadata are implemented, but historical
  constant forms, target layouts, bit-field rules, and ABI parity remain open)

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
  are now parsed; integer promotions and mixed signed/unsigned conversions now
  use target widths; shift expressions preserve the promoted left operand type;
  `__builtin_offsetof` resolves nested fields and constant array indexes;
  complex arithmetic promotions use the widest real
  component type; TinyCC special floating constants retain float typing
  and constant-evaluation semantics; atomic builtin signatures, pointer/value
  constraints, and memory-order operand checks are now validated; variadic
  builtin arity, va_list lvalues, compatible va_copy operands, and va_arg result
  type constraints are now checked; duplicate and incompatible type specifiers
  now diagnose instead of silently selecting a fallback type; frame and return
  address builtins require nonnegative integer constant levels; parser-local
  struct/union/enum tag references preserve shared tag identity; incomplete
  object pointers reject arithmetic/indexing while TinyCC byte-stride behavior
  for void/function pointers is preserved; pointer subtraction now selects
  target ptrdiff_t semantics for i386, x86_64 SysV, and x86_64 Win64; pointer
  comparisons accept TinyCC's integer/pointer and mismatched-pointer cases with
  warnings, while null-pointer comparisons remain warning-free; conditional
  pointer arms now follow TinyCC void-pointer preference, qualifier union, and
  warning behavior for incompatible pointer or integer arms; assignment,
  initializer, and argument conversions accept TinyCC pointer/integer cases and
  incompatible pointer types with warnings, preserving null constants)
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
  TLS, relocations, register allocation, and both i386 and SysV/Win64 ABIs. —
  `IN_PROGRESS` (selected zero-operand scalar/control/fence/CET instructions and
  register, base-displacement, SIB, and absolute memory forms for MOV/ADD/SUB
  plus immediate ADD/OR/AND/SUB/XOR/CMP and rel32 JMP/JNE with block-label
  resolution now have byte-exact Kotlin coverage; full tables, operand widths,
  and addressing support remain open)
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

### 11. CLI, embedding API, configuration, and distributions — `IN_PROGRESS` (0/3)

- [ ] 11.1 Implement every historical command-line option, response-file rule,
  search path, target flag, output mode, warning, and diagnostic format. — `IN_PROGRESS`
  (dash input now reads UTF-8 stdin for currently supported preprocess/token
  output modes, `__FILE__` expands to `"-"`, stdin diagnostics retain the `-`
  filename, and quoted includes search the working directory; multiple
  source files aggregate in token/preprocess output modes and `-o -` routes
  output to stdout; default GCC, `-P`, and `-P1` preprocessing line-marker
  modes preserve source/include transitions; `-P10` converts integer tokens to
  decimal, floating tokens to TinyCC placeholders, and canonical character and
  string tokens; malformed/universal escapes, remaining target-specific wide-
  string edge cases, `-P10` 64-bit overflow wrapping/warnings, other numeric
  edge cases, multi-unit compilation/linking,
  remaining options, and full diagnostic parity remain open)
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
