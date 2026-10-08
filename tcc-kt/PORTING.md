# C to Kotlin port dashboard

- **Translation candidates:** 54
- **DONE:** 38
- **DOING:** 1
- **TODO:** 16
- **Currently doing:** `c67-gen.c` → `src/main/kotlin/tcc/kt/C67Gen.kt`
- **C tests retained:** all test case bodies and test programs under `tests/`; `tcctest.c` runner is translated
- **C examples retained:** all C sources under `examples/` and `win32/examples/`

Statuses: **TODO** = no Kotlin translation started; **DOING** = partial translation in progress; **DONE** = Kotlin translation completed. C examples and test case sources intentionally remain C.

## Exhaustive translation inventory

| C source | Status |
|---|---|
| `arm-asm.c` | DONE |
| `arm-gen.c` | DONE |
| `arm-link.c` | DONE |
| `arm64-asm.c` | DONE |
| `arm64-gen.c` | DONE |
| `arm64-link.c` | DONE |
| `c67-gen.c` | DOING |
| `c67-link.c` | DONE |
| `conftest.c` | DONE |
| `i386-asm.c` | DONE |
| `i386-gen.c` | DONE |
| `i386-link.c` | DONE |
| `il-gen.c` | TODO |
| `legacy-c/conftest.c` | DONE |
| `lib/armeabi.c` | DONE |
| `lib/armflush.c` | DONE |
| `lib/bcheck.c` | DONE |
| `lib/bt-dll.c` | DONE |
| `lib/bt-exe.c` | DONE |
| `lib/bt-log.c` | DONE |
| `lib/builtin.c` | DONE |
| `lib/dsohandle.c` | DONE |
| `lib/lib-arm64.c` | TODO |
| `lib/libtcc1.c` | DONE |
| `lib/runmain.c` | DONE |
| `lib/stdatomic.c` | DONE |
| `lib/tcov.c` | DONE |
| `lib/va_list.c` | DONE |
| `libtcc.c` | TODO |
| `riscv64-asm.c` | TODO |
| `riscv64-gen.c` | TODO |
| `riscv64-link.c` | DONE |
| `tcc.c` | DONE |
| `tccasm.c` | TODO |
| `tcccoff.c` | TODO |
| `tccdbg.c` | TODO |
| `tccelf.c` | TODO |
| `tccgen.c` | TODO |
| `tccmacho.c` | TODO |
| `tccpe.c` | TODO |
| `tccpp.c` | TODO |
| `tccrun.c` | DONE |
| `tcctools.c` | TODO |
| `tests/tcctest.c` | DONE |
| `win32/lib/crt1.c` | DONE |
| `win32/lib/crt1w.c` | DONE |
| `win32/lib/crtinit.c` | DONE |
| `win32/lib/dllcrt1.c` | DONE |
| `win32/lib/dllmain.c` | DONE |
| `win32/lib/wincrt1.c` | DONE |
| `win32/lib/wincrt1w.c` | DONE |
| `win32/lib/winex.c` | DONE |
| `x86_64-gen.c` | TODO |
| `x86_64-link.c` | DONE |
