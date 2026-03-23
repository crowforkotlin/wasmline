# Wasmline IR box testData guide

This directory contains the **formal IR fixtures** for `wasmline-kotlin-plugin`.

Its job is narrow on purpose: verify what the Kotlin IR plugin discovers and generates, then keep the resulting snapshots stable.

## What belongs here

Each case normally consists of three files:

- `caseName.kt` — the source fixture with a `box(): String` entrypoint
- `caseName.fir.txt` — FIR dump snapshot
- `caseName.fir.ir.txt` — IR dump snapshot

Current example:

- `echoProxyRoundTrip.kt`
- `echoProxyRoundTrip.fir.txt`
- `echoProxyRoundTrip.fir.ir.txt`

## What these fixtures should verify

Keep the scope focused on **IR-plugin behavior**:

- service contract discovery
- phase-one validation rules
- generated `*_WasmlineDefinition`
- generated `*_WasmlineProxy`
- generated `*_WasmlineAdapter`
- generated IR call shapes such as `endpoint.invoke(...)`
- current glue behavior such as `link()` and the temporary `bind()` stub

## Expected result for a valid case

When a fixture is correct and the plugin is wired correctly:

1. `generateTests` creates or updates a matching test method in `test-gen/.../JvmBoxTestGenerated.java`
2. running the generated test succeeds
3. `*.fir.txt` matches the current frontend snapshot
4. `*.fir.ir.txt` matches the current IR snapshot

Because `wasmline-kotlin-plugin` is an **IR plugin**, generated declarations are not emitted as source files under `build/generated`.
They are injected into IR and show up in compiled output or IR snapshots instead.

## Important authoring rule

Prefer testing generated declarations through **runtime lookup / reflection / observable behavior**, not direct source-level references.

Why:

- the plugin currently generates declarations in IR, not in FIR/source
- direct source references to generated names are fragile in IR-only tests
- reflection or runtime-visible behavior better matches the current implementation stage

## How to verify testData

Run from `wasmline-multiplatform/`:

```zsh
./gradlew :wasmline-kotlin-plugin:generateTests
./gradlew :wasmline-kotlin-plugin:test --tests 'crow.wasmline.kotlin.runners.JvmBoxTestGenerated'
```

If your local Gradle setup requires a JetBrains Runtime / JDK 21 toolchain, export that JDK before running the commands.

Useful checks after editing a fixture:

- confirm a new method appears in `wasmline-kotlin-plugin/test-gen/crow/wasmline/kotlin/runners/JvmBoxTestGenerated.java`
- confirm the test is no longer a vacuous `testAllFilesPresentInBox()`-only class
- inspect the updated `*.fir.txt` and `*.fir.ir.txt` files before committing

## How to modify or add a case

1. Add or edit a `*.kt` fixture in this directory.
2. Keep the fixture small and focused on one IR behavior.
3. Run the generated box test.
4. Review the resulting `*.fir.txt` and `*.fir.ir.txt` snapshots.
5. Commit the fixture and its snapshots together.

## What not to put here

Avoid mixing in unrelated concerns:

- KSP-style source generation
- broad runtime integration flows across many modules
- packaging / manifest / loader behavior
- native Wasmtime lifecycle tests
- UI or app-level behavior

Those belong in runtime, CLI, loader, or sample tests instead.

## Quick checklist before commit

- [ ] one clear `*.kt` fixture per behavior
- [ ] `box(): String` returns `"OK"`
- [ ] generated test method exists
- [ ] `*.fir.txt` updated
- [ ] `*.fir.ir.txt` updated
- [ ] targeted box test passes

