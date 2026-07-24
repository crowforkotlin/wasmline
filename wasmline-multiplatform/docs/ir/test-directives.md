# Compiler Plugin Test Directives

## Box Tests Format

```kotlin
// WITH_STDLIB

package test.example

import crow.wasmline.WasmlineService

interface MyService : WasmlineService { ... }

fun box(): String {
    // Verification code here
    return "OK"  // or error message on failure
}
```

### Required Elements

- `// WITH_STDLIB` - Include Kotlin standard library
- `fun box(): String` - Entry point (required)
- Return `"OK"` on success, error string on failure

---

## Diagnostic Tests Format

```kotlin
// RUN_PIPELINE_TILL: FRONTEND

// MODULE: lib
// FILE: util.kt
package example.util

fun takeInt(x: Int) {}

// MODULE: main(lib)
// FILE: test.kt
package example.test

import example.util.takeInt

fun test() {
    takeInt(<!ARGUMENT_TYPE_MISMATCH!>"Wrong"<!>)
}
```

### Required Elements

- `// RUN_PIPELINE_TILL:` - Specifies compilation stage
- `// MODULE:` - Defines module structure (optional)
- `// FILE:` - Specifies file within module (optional)
- Error markers `<!ERROR_CODE!>message<!>` for expected errors

---

## Compilation Stages

Use these values with `RUN_PIPELINE_TILL`:

| Stage | Description |
|-------|-------------|
| `FRONTEND` | Parse and resolve types |
| `FIR Generation` | Generate Frontend IR |
| `FIR Lowering` | Convert FIR to Backend IR |
| `Backend IR` | Final IR representation |
| `Codegen` | Generate bytecode |

**Recommended**: Use `FRONTEND` for most IR plugin tests.

---

## Error Markers

Mark expected compiler errors using this syntax:

```kotlin
val x = 123
takeInt("Wrong")  // Should be: takeInt(<!ARGUMENT_TYPE_MISMATCH!>"Wrong"<!>)
obj.unknown()     // Should be: obj.<!UNRESOLVED_REFERENCE!>unknown<!>()
```

### Common Error Codes

| Code | Description | Example |
|------|-------------|---------|
| `ARGUMENT_TYPE_MISMATCH` | Wrong argument type | Function call error |
| `TYPE_MISMATCH` | Type doesn't match | Assignment error |
| `UNRESOLVED_REFERENCE` | Unknown symbol | Missing import/type |
| `NOT_INHERITED` | Wrong inheritance | Superclass error |
| `MISSING_OVERRIDE` | Override issue | Abstract method |
| `RECEIVER_TYPE_MISMATCH` | Invalid receiver | Extension function |

---

## Module Configuration

Define multi-module projects:

```kotlin
// MODULE: core
// FILE: helper.kt
package core

fun getValue(): Int = 42

// MODULE: app(core)
// FILE: main.kt
package app

import core.getValue

fun useHelper() {
    val x = getValue()  // OK - imports from core
}
```

---

## File Locations

- **Box tests**: `testData/box/*.kt`
- **Diagnostic tests**: `testData/diagnostics/*.kt`

Both automatically generate tests when running:

```bash
./gradlew :wasmline-kotlin-plugin:generateTests
```

---

## Add New Test

1. Create `.kt` file in appropriate directory
2. Write valid Kotlin code
3. Add required directives (`WITH_STDLIB` or `RUN_PIPELINE_TILL`)
4. For diagnostics: mark expected errors with `<!...!>`
5. Generate tests
6. Review generated snapshots
7. Commit all files together

Keep each test minimal. Focus on one behavior per file.
