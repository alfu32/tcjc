# tcc-kt

Kotlin migration of Tiny C Compiler. All Kotlin files use the `tcc.kt` package.

## Port progress

- `tcc.c`: translated the CLI helper routines into `TccDriver.kt` (`print_dirs`, `print_search_dirs`, `set_environment`, `default_outputfile`, and `getclock_ms`). The `main` driver and its library/tool dependencies are still to be ported.
- C regression tests remain in the original `tests/` tree and have not been converted.

The original C sources remain in the repository as the reference during migration. `COPYING` contains the upstream license.

Build with `gradle build`.
