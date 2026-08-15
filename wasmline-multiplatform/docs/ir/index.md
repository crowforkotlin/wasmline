# IR Test Documentation

The Kotlin compiler plugin currently registers one generated test model: JVM box tests.

## Guides

- [Box Tests](box-ir.md) — current executable IR fixtures and snapshot workflow
- [Test Directives](test-directives.md) — directives used by the current box fixtures
- [Diagnostic Test Status](diagnostics-ir.md) — dormant diagnostic infrastructure that is not currently registered

## Current Test Model

Box fixtures live in `wasmline-kotlin-plugin/testData/box/`. Each fixture contains `fun box(): String` and returns `"OK"` on success.

The generator in `test-fixtures/crow/wasmline/kotlin/GenerateTests.kt` registers only `AbstractJvmBoxTest`. It produces:

```text
wasmline-kotlin-plugin/test-gen/
└── crow/wasmline/kotlin/runners/JvmBoxTestGenerated.java
```

There is no generated `JvmDiagnosticsTestGenerated` class and no active `testData/diagnostics/` directory.

## Run

Run only with explicit user instruction:

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test \
  --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

## Fixture Structure

```text
wasmline-kotlin-plugin/testData/box/
├── 01_emptyService.kt
├── 02_simpleMethod.kt
├── 03_parameterService.kt
├── 04_multiMethod.kt
├── 05_complexTypes.kt
└── 06_linkBindPattern.kt
```

Each `.kt` fixture has generated `.fir.txt` and `.fir.ir.txt` snapshots.

## Generated-file Rule

Edit only the source fixture. Generate the runner and snapshots through the Gradle tasks, review them, and commit the intended generated changes. Never repair an IR failure by editing `test-gen/`, `*.fir.txt`, or `*.fir.ir.txt` manually.
