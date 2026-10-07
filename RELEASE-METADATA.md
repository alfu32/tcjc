# Release Metadata

- Source compatibility version: `0.9.28rc` (from `VERSION`)
- Proposed Maven group: `org.tinycc`
- Proposed library artifact: `tcc-jvm`
- CLI distribution: `tcc-jvm`
- Minimum runtime: JDK 17
- Kotlin plugin: 2.2.20
- Gradle wrapper: 9.2.1
- Artifact policy: JVM bytecode and Kotlin-owned metadata only; no C/native shared-library payloads

The source version remains unchanged until the first Kotlin-only release is cut. Distribution and publication tasks are reproducible and write to Gradle build directories.
