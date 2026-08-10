---
name: wasmline
description: Repository-level skill spec for the Wasmline repository. Defines hard constraints and references detailed docs.
---

# Wasmline Repository Skill Spec

Load this skill when working in the `wasmline` repository.

## References

| Document | Content |
|----------|---------|
| [`development-guide.md`](./development-guide.md) | Environment setup, module references, commands, generated artifact rules |
| [`branching-and-release.md`](./branching-and-release.md) | Version model, tag format, Maven publishing, compat window, scenarios |
| [`web-bindings-guide.md`](./web-bindings-guide.md) | Web platform (js/wasmJs) expect/actual layer, naming rules, WASI shims, tests |
| [Technical Mind Map](../../../wasmline-multiplatform/docs/design-mind.md) | Core architecture, data flow, IR transformation pipeline |
| [IR Test Documentation](../../../wasmline-multiplatform/docs/ir/index.md) | Compiler plugin test structure and guides |

---

## Hard Constraints (always active)

1. **Pre-check once per session.** Run `bash ./scripts/doctor.sh` at session start. Never re-trigger.
2. **No autonomous compilation or testing.** All build/test actions require explicit user instruction.
3. **Never hand-edit generated artifacts.** `test-gen/`, `*.fir.txt`, `*.fir.ir.txt`, `build/`, `build/platforms/` are forbidden.
4. **Module targeting before changes.** Identify the correct module before writing code.
5. **Tag ↔ Maven always paired.** No publish without tag; no tag without publish. Format: `release-x.y.z.v`.
6. **Engine x.y.z MUST match wasmline x.y.z.** Engine version is `x.y.z.v`; Kotlin modules are `x.y.z`. Mismatch is illegal.
7. **Branch: main only + sub-branches.** No release branches. No version branches.
8. **Wasmtime encoding:** `v = major×100 + minor×10 + patch` (47.0.2 → `4702`).
9. **Shell config files are read-only.** Never modify `~/.zshrc`, `~/.bashrc`, `~/.bash_profile`.

---

## Artifact Model (always active)

- Raw `.wasm` is the source format and is accepted by the browser path only; native Wasmline loading requires a precompiled artifact.
- `.cwasm` is platform-specific native AOT code produced by the Cranelift compiler. It needs a matching OS, CPU, bitness, and Wasmtime version.
- `.pwasm` is portable Pulley bytecode produced through the Cranelift compilation pipeline and executed by the Pulley interpreter. It is not raw `.wasm`.
- The Cranelift engine distribution includes both Cranelift and Pulley support: prefer a matching `.cwasm`, then fall back to matching-bitness `.pwasm` (`pulley32` or `pulley64`).
- The Pulley engine distribution includes Pulley only and therefore supports `.pwasm` only.
- iOS is interpreter-only in Wasmline: ship and select `pulley64` `.pwasm`; never use an iOS `.cwasm` artifact.

## Workflow Summary

1. Pre-check → 2. Asset verification → 3. Module targeting → 4. Execute on instruction.

For details on any step, read the referenced docs above.
