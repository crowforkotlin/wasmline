# IR Test Documentation

Welcome to Wasmline's IR test documentation. This directory contains guides for writing compiler plugin tests.

## Quick Links

- [Test Directives](test-directives.md) - Compiler directives and error markers
- [Box Tests](box-ir.md) - Runtime verification tests
- [Diagnostic Tests](diagnostics-ir.md) - Compiler error detection tests

## Overview

Wasmline uses two types of compiler plugin tests:

### Box Tests (`testData/box/`)

Verify IR code generation at runtime. Each test defines a service interface and includes a `fun box(): String` function that returns `"OK"` on success.

**Use case**: Test that the plugin generates correct IR for various service patterns.

### Diagnostic Tests (`testData/diagnostics/`)

Verify compiler plugin validation behavior. These tests check that invalid code triggers appropriate diagnostics using error markers like `<!ERROR_CODE!>message<!>`.

**Use case**: Ensure the plugin correctly validates input and reports errors.

## Run Tests

Generate test classes:

```bash
./gradlew :wasmline-kotlin-plugin:generateTests
```

Run tests:

```bash
cd ../../
./gradlew :wasmline-kotlin-plugin:test --tests '*JvmBoxTestGenerated*'
./gradlew :wasmline-kotlin-plugin:test --tests '*JvmDiagnosticsTestGenerated*'
```

## Structure

```
wasmline-multiplatform/wasmline-kotlin-plugin/testData/
├── box/                      # Box tests (6 files)
│   ├── 01_emptyService.kt    # Empty interface
│   ├── 02_simpleMethod.kt    # Single method
│   ├── 03_parameterService.kt # Parameters
│   ├── 04_multiMethod.kt     # Multiple methods
│   ├── 05_complexTypes.kt    # Complex types
│   └── 06_linkBindPattern.kt # Link/bind pattern
└── diagnostics/              # Diagnostic tests (2 files)
    ├── 01_typeMismatch.kt
    └── 02_unresolvedReference.kt
```

For detailed instructions, see the linked documents above.
