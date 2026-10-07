# Kotlin/JVM Migration Contract

## Toolchain and artifacts

- Minimum supported runtime and compilation target: JDK 17 LTS. JDK 21 is the current verification environment.
- Gradle is consumed only through the checked-in wrapper; Task 2.1 pins Gradle 9.2.1, which supports JDK 17 and 21.
- Kotlin/JVM is pinned through plugin management at Kotlin 2.2.20, with upgrades allowed only through a green parity run.
- Proposed Maven coordinates are `org.tinycc:tcc-jvm` for the embedding library and `org.tinycc:tcc-cli` for the command-line distribution. Versioning follows `VERSION` until the first Kotlin-only release.
- Compiler core has no native runtime dependency. Test-only dependencies may use JUnit 5 through Gradle's Kotlin test support.

## Compatibility boundaries

The port must preserve the CLI contract, diagnostics, C language and GNU/TinyCC extensions currently exercised by the repository, `libtcc` capabilities, include/library search behavior, in-memory execution, and native output formats/targets recorded in `MIGRATION.BASELINE.md`. Kotlin APIs may be idiomatic, but a Java-friendly facade must expose equivalent lifecycle, compilation, output, relocation, execution, symbol, and callback operations. Differences are allowed only when documented with a migration note and a regression test.

The migration is a pure Kotlin/JVM implementation, not a facade over TinyCC. The default Gradle build, published artifacts, CLI, and runtime do not compile, package, load, invoke, or require C sources, Make targets, shell configuration, native TinyCC binaries, or TinyCC DLL/SO files. Task 16.3 removes the temporary parity oracle and verifies the clean Kotlin-only artifact.

## Parity gates

Every implementation phase must keep these gates green where applicable:

1. `./gradlew clean check` on JDK 17 and JDK 21.
2. Exact comparison with the committed example, preprocessor, and object-format goldens.
3. Ported tests for parser, diagnostics, ABI, assembler, linker, runtime, bounds, embedding, and CLI behavior.
4. Native target matrix coverage for i386, x86_64, ARM, ARM64, RISC-V, and C67, with unavailable execution environments covered by deterministic object-level tests.
5. Final clean-checkout audit proving no Gradle source set or published artifact depends on the original C build.
