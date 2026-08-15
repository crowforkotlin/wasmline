# Compiler Plugin Test Directives

## Current Box Format

```kotlin
// WITH_STDLIB

package test.example

import crow.wasmline.WasmlineService

interface MyService : WasmlineService

fun box(): String {
    return "OK"
}
```

Required elements:

- `// WITH_STDLIB` enables the Kotlin standard library in the compiler test.
- `fun box(): String` is the executable entry point.
- Return `"OK"` on success and a focused error message on failure.

## Multi-file and Multi-module Directives

The Kotlin compiler test framework supports directives such as `// MODULE:` and `// FILE:`. Introduce them only when the fixture genuinely requires more than one file or module.

```kotlin
// MODULE: lib
// FILE: helper.kt
package sample

fun value(): Int = 42

// MODULE: main(lib)
// FILE: main.kt
package sample

fun box(): String = if (value() == 42) "OK" else "Unexpected value"
```

## Locations

- Source fixtures: `wasmline-kotlin-plugin/testData/box/*.kt`
- Generated runner: `wasmline-kotlin-plugin/test-gen/`
- Generated snapshots: adjacent `*.fir.txt` and `*.fir.ir.txt` files

## Add a Fixture

1. Add one focused `.kt` file under `testData/box/`.
2. Include the required directives and `box()` entry point.
3. Generate the test runner.
4. Run the generated box suite.
5. Review the generated runner and snapshots.

Do not edit generated files manually.

## Diagnostic Directives

The repository retains an `AbstractJvmDiagnosticTest` scaffold, but its generator registration is disabled and no diagnostic fixtures are present. Do not add diagnostic directives or document a diagnostic test command until the generator model and fixture directory are enabled together.
