# Box IR Tests

## Purpose

Verify IR code generation for WasmlineService contracts. Each test compiles a simple service interface and checks that the plugin generates correct IR snapshots.

## Files Generated

For each `*.kt` file:
- `*.fir.txt` - Frontend IR snapshot
- `*.fir.ir.txt` - Backend IR snapshot

## Current Tests

| File | Description |
|------|-------------|
| `01_emptyService.kt` | Empty interface |
| `02_simpleMethod.kt` | Single no-arg method |
| `03_parameterService.kt` | Method with parameters |
| `04_multiMethod.kt` | Multiple methods |
| `05_complexTypes.kt` | Complex types (Int, Long, String, ByteArray) |
| `06_linkBindPattern.kt` | Link/bind usage pattern |

## Test Requirements

Each box test must contain:
```kotlin
// WITH_STDLIB

package test.package.name

import crow.wasmline.WasmlineService

interface MyService : WasmlineService { ... }

fun box(): String {
    // Runtime verification
    return "OK"  // or error message
}
```

## Verify IR Snapshots

Check these in `.fir.ir.txt`:
- Bridge class generated (`*_WasmlineBridge`)
- Constructor with endpoint, implementation, serializationFactory
- `bind()` method registers action handlers
- `invoke()` method routes actions
- Method implementations with serialization/deserialization

## Run Tests

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests '*JvmBoxTestGenerated*'
```

## Add New Test

1. Create `.kt` file in `testData/box/`
2. Include `// WITH_STDLIB` directive
3. Define `interface X : WasmlineService`
4. Add `fun box(): String` with verification code
5. Generate tests and review snapshots
6. Commit `.kt`, `.fir.txt`, `.fir.ir.txt` together

Keep tests minimal. Focus on one behavior per test.
