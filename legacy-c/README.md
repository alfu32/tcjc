# Legacy C Workspace

This directory is a quarantine boundary for the complete historical TinyCC
C/native implementation. It is preserved as a source and parity reference,
not as a Gradle source set, dependency, runtime resource, or distribution
input.

The tree contains the original compiler front end, target backends, headers,
runtime `lib/`, platform support, examples, Make/configure files, generated
configuration/docs, and local compiled residue. It is never allowed to become
an implementation shortcut: production code must be Kotlin/JVM and must not
load, execute, compile, link, or package anything from this directory.

The root `tests/` directory contains the original historical regression suite
and is intentionally kept in place. New executable parity tests belong under
`compiler-tests/src/test/kotlin/` and run through Gradle.

Do not add this directory to Gradle source sets or package its contents into a
JAR, distribution, or published artifact. Update `IMPL.PLAN.md` whenever a
historical feature is mapped to a Kotlin implementation and parity evidence.
