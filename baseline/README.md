# C Implementation Baseline Goldens

Captured on 2026-10-07 from commit `25b4ddda` on Linux x86_64 with GCC 13.3 and TinyCC `0.9.28rc`.

## Build gates

The following commands completed successfully from the repository root:

```text
./configure
make
make test
```

The test run ended with `------- ALL TESTS PASSED --------` and covered native execution, libtcc, threading, ABI, VLA, preprocessor, memory/bounds checking, DLL/PIC, cross-target compilation, and linker tests.

## Reproduce the goldens

Use the local compiler search paths used by the Makefile:

```text
./tcc -B. -Iinclude -I. -run examples/ex1.c
./tcc -B. -Iinclude -I. -run examples/ex3.c 10
./tcc -B. -Iinclude -I. -run examples/ex2.c 1238 2 3 4 10 13 4
./tcc -B. -Iinclude -I. -E -P tests/pp/01.c
./tcc -B. -Iinclude -I. -c examples/ex1.c -o ex1.o
```

Expected stdout is stored under `baseline/examples/`; preprocessor output is under `baseline/pp/`; `baseline/object/ex1.headers` records the stable ELF relocatable-header properties.
