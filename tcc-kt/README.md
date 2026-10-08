# tcc-kt

A Kotlin migration of Tiny C Compiler. All translated Kotlin files use the `tcc.kt` package.

The file-by-file migration status and exhaustive translation inventory are in [PORTING.md](PORTING.md). C examples and C test case programs remain in the original repository; `tests/tcctest.c`, the compiler testing harness, is being translated.

The Kotlin sources currently cover the command-line driver, runtime support, coverage data, configuration probing, and executable backtrace support. Compiler and architecture files remain tracked in the dashboard. Original C files remain in the repository as the migration reference. The upstream license is included in `COPYING`.

Build with `gradle build`.
