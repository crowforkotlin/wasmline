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
| [`mind.md`](./mind.md) | Technical mind map, data flow, IR pipeline |

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

## Workflow Summary

1. Pre-check → 2. Asset verification → 3. Module targeting → 4. Execute on instruction.

For details on any step, read the referenced docs above.
