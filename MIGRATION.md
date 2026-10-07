# TinyCC to Kotlin/JVM Migration Notes

The active build is Gradle-based and targets JDK 17. Use `./gradlew check` for the managed test suite and `./gradlew verifyPureKotlinArtifact` to audit produced JARs. The CLI is `tcc-jvm`; run `./gradlew :compiler-cli:run --args="--help"` for typed options.

The Kotlin implementation preserves the front-end, diagnostics, typed IR, target/object metadata, runtime checks, embedding lifecycle, response files, and cross-target configuration represented by the current compatibility baseline. The embedding API is stateful and owns callbacks, JVM libraries, source results, symbols, and closeable resources.

The deliberate purity boundary is important: no C source, native bridge, DLL/SO/DYLIB, system library loader, Make target, or shell configuration is part of the Kotlin/JVM artifact. JVM JAR providers are supported through `KotlinJvmLibrary`; native dynamic-library requests fail with an explicit diagnostic. Native executable execution is isolated behind `KotlinProcessRunner` and does not load native libraries into the JVM.

The staged migration is tracked in `IMPL.PLAN.md`. The former C parity oracle has been removed after the final audit; `baseline/` contains only inert captured evidence and is not a build dependency.
