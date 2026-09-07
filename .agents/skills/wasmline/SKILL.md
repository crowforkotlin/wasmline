---
name: wasmline
description: Apply repository-level workflow, architecture, versioning, release, and validation rules for Wasmline. Use when changing, diagnosing, reviewing, building, testing, or releasing anything in this repository, including the Kotlin Multiplatform runtime, native Wasmtime bridge, loaders, engines, plugins, samples, scripts, and documentation.
---

# Wasmline Repository Skill

Use this skill for work in the `wasmline` repository. Paths in the referenced documents are relative to the repository root unless stated otherwise.

## Execution Policy

Default to implementation only. A request to implement, fix, refactor, or execute
a plan is not authorization to run its validation steps. Only an explicit user
request to run a command or a defined build/validation scope permits execution;
stay within that scope without requesting approval for every routine step.

This applies to Wasmline, Wasmtime, Fumadocs, samples, scripts, and every language:
Gradle configuration/tasks, lint/check/test/build, Kotlin/Java compilers,
Zig/CMake, Cargo/Rust, Python pytest/unittest/compileall/py_compile, Shell
`bash -n`/ShellCheck, JavaScript/TypeScript package scripts, browser tests,
environment doctor commands, `git diff --check`, and custom validation scripts.
Do not substitute a "lightweight", "static", or read-only check for authorization.

Reading files, locating relevant source, and reading Git status/diffs to implement
the request are allowed; they are not permission to execute checks. Treat every
validation procedure in referenced documents as manual instructions unless
explicitly authorized. General communication or skill-authoring guidance that
recommends automatic validation does not override this project rule.

Generators or synchronization commands that compile, test, or validate also
require explicit authorization. Otherwise edit their source inputs, preserve
generated-file boundaries, and report the pending generation command.
Do not alter CI behavior merely to enforce this rule on the assistant.

In the final response, separate implementation status from validation status.
State "not run" when applicable and provide relevant commands with their working
directory and prerequisites for the user to run manually. Do not claim success
from reading code or from checks performed before the current edits.

## Reference Routing

Read only the documents required by the current task.

| Reference | Read when working on |
| --- | --- |
| [`development-guide.md`](./references/development-guide.md) | Environment checks, module selection, generated files, commands, validation, or CI |
| [`version-sync.md`](./references/version-sync.md) | Version changes or additions of duplicated version references |
| [`branching-and-release.md`](./references/branching-and-release.md) | Branches, tags, Maven publication, releases, or hotfixes |
| [`aot-compatibility.md`](./references/aot-compatibility.md) | AOT generation catalogs, selector DSL, compatibility checks, or release assets |
| [`web-bindings-guide.md`](./references/web-bindings-guide.md) | `webMain`, `jsMain`, `wasmJsMain`, browser loading, or Web tests |
| [Technical Mind Map](../../../docs/design-mind.md) | Runtime architecture, execution models, invocation protocols, Component Model, or IR flow |
| [Component Service Guide](<../../../fumadocs/content/docs/(reference)/(plugin-development)/component-service.mdx>) | WIT, Component build pipelines, generated host bindings, or cross-language Component fixtures |
| [IR Test Documentation](../../../docs/ir/index.md) | Compiler-plugin fixtures, generated runners, or IR snapshots |

## Hard Constraints

1. **Never validate or build by default.** Do not run `./scripts/wasmline doctor`, Gradle, `gradlew`, Zig, CMake, native builds, lint, check, test suites, Python test suites, package-manager checks, documentation builds, or other verification commands unless the user explicitly authorizes the exact validation. This prohibition applies to all repository languages and tools.
2. **Provide commands for manual validation.** After implementation, list the recommended validation commands in the final response and state that the user must run them. Listing a command does not authorize running it.
3. **Generated files are not edited manually.** This includes `test-gen/`, `*.fir.txt`, `*.fir.ir.txt`, `**/build/`, `build/platforms/`, `.zig-cache/`, and `zig-out/`.
4. **Select the owning module first.** Confirm the module and source set before changing code.
5. **Scalar versions come from one root manifest.** Root `versions.json` contains only duplicated project and toolchain versions. Edit the source manifest and provide `./scripts/wasmline versions sync` for manual execution unless synchronization is explicitly authorized. Native AOT compatibility history is maintained separately in root `aot-compatibility.json`; the same execution policy applies to `./scripts/wasmline aot sync`.
6. **Tags and Maven releases remain paired.** The release tag format is `release-x.y.z.v`. Do not create a release tag without its Maven release, and do not publish a Maven release without its tag.
7. **Maven modules use one project version.** All published modules, including engine modules, use `wasmline.version` in `x.y.z` form. Do not introduce four-segment engine Maven versions.
8. **Use `main` and temporary sub-branches.** Do not create long-lived release or Wasmtime-version branches.
9. **Wasmtime tag encoding is fixed.** `v = major×100 + minor×10 + patch` (48.0.1 → `4801`). Minor and patch must remain single digits while this format is in use.
10. **Shell profiles are read-only.** Never modify `~/.zshrc`, `~/.bashrc`, or `~/.bash_profile`.

## Artifact and Execution Model

- Physical format, execution model, and invocation protocol are separate fields. A filename extension does not select `CORE_WASM` or `COMPONENT_MODEL`.
- Raw `.wasm` is a build input and a browser runtime artifact for `CORE_WASM + WASMLINE_SERVICE` or `CORE_WASM + RAW_EXPORT`; native loading rejects raw Core and Component artifacts.
- `.cwasm` is platform-specific Cranelift AOT output. `.pwasm` is Pulley bytecode produced by Wasmtime; it is not raw WebAssembly.
- A Cranelift engine can select matching `.cwasm` and fall back to compatible `.pwasm`. A Pulley engine accepts `.pwasm` only.
- iOS uses the Pulley interpreter. Select `pulley64` `.pwasm`; never select iOS `.cwasm`.
- Native runtime AOT tests use the internal `wasmline-native-test-fixtures` module. Its `assembleNativeTestFixtures` task generates `.cwasm`, `.pwasm`, and `fixture-index.json` below `build/`; do not commit those files or supply replacement artifacts through test environment variables.
- `:wasmline:nativeAotJvmTest` depends on fixture assembly and obtains the index through a system property. The iOS simulator test obtains the same index through `WASMLINE_NATIVE_FIXTURE_INDEX` and accepts only a matching `pulley64` `.pwasm` record.
- One signed `manifest.wlm` may describe several immutable, backend-specific AOT compatibility profiles. Wasmtime `x.y.z` selects catalog records; it is not the serialized-artifact identity.
- Package artifacts use `artifacts/sha256/{prefix}/{digest}.{extension}`. Core Web `.wasm` is profile-independent and stored once; remote loading downloads only the manifest and one selected artifact.
- Runtime, loader, build tools, and engine modules share one Maven version. Do not upgrade an engine independently; use the BOM where its Gradle platform is consumable.
- Native AOT selection is explicit: use exactly one of `current()`, `minimum()`, `all()`, or `versionRanges {}`. The default is no selector, which is a configuration error for native AOT.
- `wasmlineCheckAotCompatibility` is advisory and runs after a successful Wasmline assemble. Warnings are enabled by default; `suppressCompatibilityWarning.set(true)` suppresses only the log message.
- The root `aot-compatibility.json` is the only manually maintained AOT compatibility catalog. `./scripts/wasmline aot sync` validates it, resolves verified fork release metadata only for a newly appended current distribution, generates the internal lock, packages an identical classpath resource, and updates `NativeBuildIdentity.h` plus `WasmlineReleaseIdentity.kt`. Remote catalog data never enters AOT task inputs.
- Stable releases use `release-x.y.z.v`; `v` encodes the fork Wasmtime `x.y.z` as `major×100 + minor×10 + patch`. The release workflow validates the tag before Maven publication.

## Workflow

1. Classify the task and determine the owning module and source set.
2. Read the matching reference and identify the owning module and source set.
3. Verify current paths, APIs, and generated-file boundaries in the repository.
4. Make the requested change.
5. Inspect the final diff and report validation as not run unless the user explicitly authorized it.

For AOT and release changes, read [`aot-compatibility.md`](./references/aot-compatibility.md) and [`branching-and-release.md`](./references/branching-and-release.md) before editing.
