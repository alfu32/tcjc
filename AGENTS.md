# Repository Guidelines

## Project Structure & Module Organization

The compiler front end and shared implementation live in the repository root (`tcc.c`, `tccpp.c`, `tccgen.c`, `tccelf.c`, and related headers). Architecture-specific code uses prefixes such as `i386-`, `x86_64-`, `arm-`, `arm64-`, and `riscv64-`. Runtime support is in `lib/`; public and bundled headers are in `include/`; platform-specific Windows files are under `win32/`. Use `examples/` for small usage programs and `tests/` for regression, integration, assembler, preprocessor, and ABI tests. Read `README`, `CodingStyle`, and `tcc-doc.texi` before making broad changes.

## Build, Test, and Development Commands

- `./configure` creates the host configuration (use `./configure --help` for compiler, target, and install options).
- `make` builds the native `tcc`, libraries, and documentation.
- `make test` rebuilds the test harness and runs the standard suite; `make tests2.all` and `make testspp.all` run the additional suites.
- `make cross-x86_64` builds one supported cross compiler; `make cross` builds all configured cross targets.
- `make clean` removes generated binaries and test output; `make distclean` also removes generated configuration and documentation files.

## Coding Style & Naming Conventions

Follow `CodingStyle` and the surrounding code. TCC is primarily C90: avoid introducing newer C features unless there is an established repository precedent. Use spaces rather than tabs in modified source lines, remove trailing whitespace, and preserve the local two- or four-space indentation. Keep filenames and subsystem names lowercase, using the existing architecture or component prefixes.

## Testing Guidelines

Add focused regression coverage in `tests/` for compiler, linker, runtime, or architecture changes; use the existing test files and `tests/tests2` or `tests/pp` patterns. Run `make test` at minimum. Changes involving relocations, targets, sanitization, or generated code should also be tested on the affected architecture or with the relevant `CodingStyle` sanitizer/Valgrind procedures. Do not commit generated files such as `config*.mak`, `*.o`, `test.ref`, or test output.

## Commit & Pull Request Guidelines

Use concise, imperative commit subjects prefixed by the affected component, for example `tccelf: Fix ...`, `tccgen: ...`, or `tests: ...`. Keep unrelated changes separate. Pull requests should explain the behavior change and regression risk, identify affected targets or operating systems, list commands actually run, and update documentation or tests when user-visible behavior changes. No repository-specific PR template is present.

## response guidelines

- always respond in the sum up in the commitizen format

All commits must follow the Commitizen / Conventional Commits standard using the structural layout below:

### Commitizen / Conventional Commits standard
```text
<type>(<scope>): <subject>

<body>
```

#### Field Definitions

* **`<type>`**: Must be one of the following lowercase tokens:
    * `feat`: A new feature or capability.
    * `fix`: A bug fix.
    * `docs`: Documentation changes only.
    * `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc).
    * `refactor`: A code change that neither fixes a bug nor adds a feature.
    * `perf`: A code change that improves performance.
    * `test`: Adding missing tests or correcting existing tests.
    * `chore`: Changes to the build process, auxiliary tools, or libraries/dependencies.
* **`<scope>`**: Optional. A noun naming the specific codebase component or module affected, wrapped in parentheses (e.g., `(parser)`, `(auth)`, `(runtime)`).
* **`<subject>`**: A brief, imperative-mood summary of the change. Do not capitalize the first letter. Do not end with a period.
* **`<body>`**: Optional. Separate from the subject with exactly one blank line. Provides the motivation for the change and contrasts it with previous behavior.

additionally the body should be structured as follows:

(REQUEST:)
- summary of what was asked/requested

(IMPLEMENTATION:)
- summary of the solution or answer
implementation details:
- bulleted list of technical/functional modifications or planning steps ( what you print out by default in the summary )

(NOT IMPLEMENTED:)
 - summary of not implemented features/parts of the request
 - features/requests remaining to be implemented/researched
 - eventual steps/tests to be taken by the user before proceeding

#### Examples

```text
fix(editor): persist and reveal mapped compiler diagnostics

REQUEST:
the user has to be able to see error points given by diagnostics by expandable markers in the gutter

IMPLEMENTATION:
  - Diagnostics are persisted on each node and restored with the project.
  - New validation/compilation clears previous diagnostics.
  - Gutter markers now reveal the mapped editor, section, and source line automatically.
  - Nodes with diagnostics show a red warning badge in the diagram.
  - Runtime/override errors without source-map entries are retained and shown as unmapped instead of being discarded.
  - The status bar now shows:
    generated-file:line:column -> node section source-line:column

NOT IMPLEMENTED:
  - colorisation and retrieval of code artifacts
  - research solution through local / embedded small LM.
    - we need CUDA working on this machine otherwise we'll not be able to test
```

```text
fix(compiler): resolve memory leaks on dynamic execution evaluation loops
```


## Security & Configuration Tips

Never commit credentials, tokens, private keys, or machine-specific configuration. Provide safe example configuration with placeholder values and document required environment variables. Review dependency and generated-file changes carefully before committing.


## Specification Changes

Treat `SPEC.LANG.md` as normative and `SPEC.TECH.md` as architectural guidance. Update both when an implementation decision changes language behavior and call out unresolved compatibility or lowering implications in the pull request.
