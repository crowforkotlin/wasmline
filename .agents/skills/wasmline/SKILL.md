---
name: wasmline
description: Apply repository-level workflow, architecture, versioning, release, and validation rules for Wasmline. Use when changing, diagnosing, reviewing, building, testing, or releasing anything in this repository, including the Kotlin Multiplatform runtime, native Wasmtime bridge, loaders, engines, plugins, samples, scripts, and documentation.
---

# Wasmline Repository Skill

Use this skill for work in the `wasmline` repository. Paths in the referenced documents are relative to the repository root unless stated otherwise.

## Reference Routing

Read only the documents required by the current task.

| Reference | Read when working on |
| --- | --- |
| [`development-guide.md`](./references/development-guide.md) | Environment checks, module selection, generated files, commands, validation, or CI |
| [`version-sync.md`](./references/version-sync.md) | Version changes or additions of duplicated version references |
| [`branching-and-release.md`](./references/branching-and-release.md) | Branches, tags, Maven publication, releases, or hotfixes |
| [`web-bindings-guide.md`](./references/web-bindings-guide.md) | `webMain`, `jsMain`, `wasmJsMain`, browser loading, or Web tests |
| [Technical Mind Map](../../../wasmline-multiplatform/docs/design-mind.md) | Runtime architecture, execution models, invocation protocols, Component Model, or IR flow |
| [Component Service Guide](../../../docs/content/docs/component-service.mdx) | WIT, Component build pipelines, generated host bindings, or cross-language Component fixtures |
| [IR Test Documentation](../../../wasmline-multiplatform/docs/ir/index.md) | Compiler-plugin fixtures, generated runners, or IR snapshots |

## Hard Constraints

1. **Conditional pre-check, once per session.** Run `./scripts/wasmline doctor` immediately before the first validation of changes to files in this repository. Do not run it for read-only work, tasks unrelated to Wasmline, or changes that require no validation. If a later turn first introduces a change that must be validated, run the command then. Never rerun it in the same session.
2. **Compilation and tests require explicit instruction.** Do not run Gradle, Zig, CMake, native builds, or test suites unless the user explicitly requests the relevant build, test, or verification.
3. **Generated files are not edited manually.** This includes `test-gen/`, `*.fir.txt`, `*.fir.ir.txt`, `**/build/`, `build/platforms/`, `.zig-cache/`, and `zig-out/`.
4. **Select the owning module first.** Confirm the module and source set before changing code.
5. **Versions come from one manifest.** `scripts/versions.json` is authoritative. Edit the manifest and run `./scripts/wasmline versions sync`, or use `versions sync --set key=value` for the same operation. Extend the synchronizer and its tests when adding a duplicated version reference.
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

## Workflow

1. Classify the task and apply the conditional `doctor` rule.
2. Read the matching reference and identify the owning module and source set.
3. Verify current paths, APIs, and generated-file boundaries in the repository.
4. Make the requested change.
5. Run only the validation authorized for the task, then inspect the final diff.
