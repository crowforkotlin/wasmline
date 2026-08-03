# Diagnostic IR Tests

## Overview

Diagnostic tests verify compiler plugin behavior for edge cases and error conditions. These tests check that the Wasmline plugin correctly handles invalid code and reports appropriate diagnostics.

## Test Structure

```kotlin
// RUN_PIPELINE_TILL: FRONTEND

import crow.wasmline.WasmlineService

interface InvalidService {  // Missing : WasmlineService
    fun method(): String
}
```

### Required Elements

- `// RUN_PIPELINE_TILL:` directive specifies compilation stage
- No `fun box()` - tests compile-only behavior
- Focus on validation, not runtime verification

---

## Current Tests

| File | Description | Expected Behavior |
|------|-------------|-------------------|
| `01_typeMismatch.kt` | Wasmline service fixture with a type mismatch | Reports `TYPE_MISMATCH` |
| `02_unresolvedReference.kt` | Wasmline service fixture with a missing symbol | Reports `UNRESOLVED_REFERENCE` |

---

## Key Differences from Box Tests

| Aspect | Box Tests | Diagnostic Tests |
|--------|-----------|------------------|
| Purpose | Verify IR generation | Verify validation |
| Entry Point | `fun box(): String` | None (compile-only) |
| Return Value | `"OK"` or error string | N/A |
| Check Location | Generated classes | Compilation errors |
| Files Generated | `.fir.txt`, `.fir.ir.txt` | `.fir.txt` only |

---

## Error Markers

Use these syntax in diagnostic tests:

```kotlin
val x: Int = "hello"  // Should be: val x: Int = <!TYPE_MISMATCH!>"hello"<!>
obj.unknownMethod()   // Should be: obj.<!UNRESOLVED_REFERENCE!>unknownMethod<!>()
```

### Common Markers

| Marker | Use Case |
|--------|----------|
| `<!ARGUMENT_TYPE_MISMATCH!>` | Wrong argument type |
| `<!TYPE_MISMATCH!>` | Assignment type error |
| `<!UNRESOLVED_REFERENCE!>` | Unknown symbol |
| `<!NOT_INHERITED!>` | Wrong inheritance |
| `<!MISSING_OVERRIDE!>` | Override issue |

---

## Run Tests

Generate tests:

```bash
cd wasmline-multiplatform
./gradlew :wasmline-kotlin-plugin:generateTests
```

Run diagnostic tests:

```bash
./gradlew :wasmline-kotlin-plugin:test --tests '*JvmDiagnosticsTestGenerated*'
```

---

## How to Add New Diagnostic Tests

1. Create `.kt` file in `testData/diagnostics/`
2. Add `// RUN_PIPELINE_TILL: FRONTEND` directive
3. Write code that should trigger compiler errors
4. Mark expected errors with `<!ERROR_CODE!>message<!>`
5. Generate and run tests
6. Commit all generated files together

Keep tests minimal and focused on one validation rule.
