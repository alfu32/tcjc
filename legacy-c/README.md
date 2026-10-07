# Legacy C Workspace

This directory is a quarantine boundary for residual TinyCC C/native material
left by the pre-Kotlin workspace. It is not a Gradle source set, dependency,
runtime resource, or distribution input.

The local residue moved here includes compiled objects and archives, the old
`tcc` executable, generated C build configuration and documentation, the
legacy runtime `lib/`, and empty legacy `examples/`, `include/`, and `win32/`
directories. The tracked repository contains no C source tree or native
library required by the Kotlin/JVM implementation.

The root `tests/` directory is intentionally kept in place for historical
regression fixtures and must not be moved or deleted. New compiler tests belong
under `compiler-tests/src/test/kotlin/` and are executed through Gradle.

Do not add this directory to Gradle source sets or package its contents into a
JAR, distribution, or published artifact. Any future cleanup of residual
native files should move them here and update `IMPL.PLAN.md`.
