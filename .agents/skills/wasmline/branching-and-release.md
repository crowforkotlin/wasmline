# Branching, Versioning & Release Strategy

## Overview

This document defines the branching model, versioning semantics, and release workflow for the wasmline project. It explains **why** the system is designed this way. For operational steps and CI constraints, see the release section in `SKILL.md`.

---

## Dual-Dimension Version Model

wasmline uses two independent version dimensions:

| Dimension | Represents | Example |
|-----------|-----------|---------|
| **wasmline version** (`x.y.z`) | Feature / API / code iteration | `1.0.0` → `1.1.0` (new feature added) |
| **wasmtime version** (`v`) | Build-time compiler/engine dependency | `47.0.2` → `48.0.0` (engine upgraded) |

### Why Two Dimensions?

- The Kotlin layer (runtime, loader, plugin, gradle-plugin) is **independent** of wasmtime version.
- The native engine layer (`libwasmline.so`) is **compiled against** a specific wasmtime static library.
- AOT/Pulley artifacts (`.cwasm` / `.pwasm`) are **produced by** a specific wasmtime CLI version and carry version validation.

Therefore, a wasmtime upgrade does NOT imply a wasmline version change, and a wasmline feature release does NOT require a wasmtime upgrade.

### Version Semantics (x.y.z)

| Component | Meaning | Bump trigger |
|-----------|---------|-------------|
| `x` (major) | Breaking API changes, large-scale refactors | Public API incompatibility |
| `y` (minor) | New features, backward-compatible additions | New capability added |
| `z` (patch) | Bug fixes, small corrections | Defect resolution |

Practical guidelines:
- `z` reaching 5–10 → consider bumping `y` (indicates feature instability)
- `y` reaching 15–20 → consider bumping `x` (indicates API needs redesign)
- No hard digit limit; two digits (0–99) per component is more than sufficient.

---

## Branch Strategy

| Rule | Description |
|------|-------------|
| Core branch | `main` — the single source of truth |
| Sub-branches | `feature/*`, `fix/*`, `chore/*` — merged back to main, then deleted |
| Release branches | **Forbidden.** Releases are cut from `main` via tags. |
| Version branches | **Forbidden.** No per-wasmtime-version branches exist. |

All code lives on `main`. Multi-wasmtime-version support is achieved through **CI matrix builds**, not branch duplication.

---

## Tag Format

```
release-x.y.z.v
```

| Part | Source | Example |
|------|--------|---------|
| `x.y.z` | wasmline semantic version | `1.0.0` |
| `v` | wasmtime version encoding | `4702` (for wasmtime 47.0.2) |

### Wasmtime Version Encoding Rule

```
v = major × 100 + minor × 10 + patch
```

| Wasmtime version | Encoding | Calculation |
|-----------------|----------|-------------|
| 47.0.2 | `4702` | 47×100 + 0×10 + 2 |
| 48.0.0 | `4800` | 48×100 + 0×10 + 0 |
| 49.1.0 | `4910` | 49×100 + 1×10 + 0 |

> **Note**: This encoding assumes each version component stays within single digits for minor/patch. If wasmtime ever releases a version with minor ≥ 10, the encoding rule must be revised before that release.

### Tag Rules

- **A tag is created for every Maven publish. Tag and Maven release are always paired.**
- A wasmtime-only upgrade (no code change) still produces a tag (x.y.z unchanged, v changes).
- The `v` in the tag represents the **primary (latest) wasmtime version** used in that release.
- Tags are immutable code snapshots; they are never moved or deleted.
- Every Maven artifact must be traceable to an exact tag; every tag must have corresponding Maven artifacts.

---

## Maven Artifact Publishing

### Version Scheme

Modules use two version formats depending on wasmtime coupling:

| Module type | Version format | Example |
|-------------|---------------|----------|
| Kotlin modules (runtime, loader, plugin, gradle-plugin) | `x.y.z` (three-segment) | `1.1.0` |
| Engine modules (pulley, cranelift) | `x.y.z.v` (four-segment) | `1.1.0.4800` |

```
crow.wasmline:wasmline:1.1.0
crow.wasmline:wasmline-loader:1.1.0
crow.wasmline:wasmline-gradle-plugin:1.1.0
crow.wasmline:wasmline-kotlin-plugin:1.1.0
crow.wasmline:wasmline-engine-pulley:1.1.0.4700
crow.wasmline:wasmline-engine-pulley:1.1.0.4800
crow.wasmline:wasmline-engine-cranelift:1.1.0.4700
crow.wasmline:wasmline-engine-cranelift:1.1.0.4800
```

### Engine Four-Segment Version

Engine modules embed the wasmtime version encoding directly in the version string:

```
version = x.y.z.v
```

- `x.y.z` = wasmline semantic version (same as other modules)
- `v` = wasmtime version encoding (same rule as tag `v`)

This ensures the engine Maven version **exactly matches** the tag format (minus the `release-` prefix).

### User-Side Dependency

```kotlin
dependencies {
    implementation("crow.wasmline:wasmline:1.1.0")
    implementation("crow.wasmline:wasmline-engine-pulley:1.1.0.4800")
}
```

Users select:
1. wasmline version (`x.y.z`) → determines available features
2. Engine version (`x.y.z.v`) → must match their wasmtime runtime environment

### Gradle Version Catalog Example

```toml
[versions]
wasmline = "1.1.0"
wasmline-engine = "1.1.0.4800"

[libraries]
crow-wasmline = { module = "crow.wasmline:wasmline", version.ref = "wasmline" }
crow-wasmline-engine-pulley = { module = "crow.wasmline:wasmline-engine-pulley", version.ref = "wasmline-engine" }
crow-wasmline-engine-cranelift = { module = "crow.wasmline:wasmline-engine-cranelift", version.ref = "wasmline-engine" }
```

---

## Compatibility Window

### Definition

The compatibility window is the set of wasmtime versions that a wasmline release builds engine artifacts for. Defined in `scripts/versions.json`:

```json
{
  "versions": {
    "wasmline_version": "1.1.0",
    "wasmtime_version": "48.0.0",
    "wasmtime_compat_window": ["47.0.0", "48.0.0"]
  }
}
```

### Sliding Policy

- Window size: **2–3 versions** (recommended starting point).
- When wasmtime releases a new version: add it to the window, optionally remove the oldest.
- Removed versions: their existing Maven artifacts remain available permanently; no re-building.
- Window changes are **author-controlled**, not automatic.

### Example Timeline

```
wasmline 1.0.0: window = [47.0.2]
wasmline 1.1.0: window = [47.0.2, 48.0.0]
wasmline 1.2.0: window = [47.0.2, 48.0.0, 49.0.0]
wasmline 1.3.0: window = [48.0.0, 49.0.0, 50.0.0]   ← 47.0.2 retired
```

Users on wasmtime 47.0.2 continue using `wasmline-engine-pulley:1.2.0.4702` from Maven (artifacts are immutable).

---

## Change Scenario Analysis

### Scenario Matrix

| # | Wasmtime upgraded? | C++ changed? | Kotlin changed? | Version bump | New tag? | Engine rebuild? |
|---|:-----------------:|:------------:|:--------------:|:------------:|:--------:|:--------------:|
| 1 | ✅ | ❌ | ❌ | None (v only) | ✅ | ✅ |
| 2 | ✅ | ✅ | ❌ | z+1 | ✅ | ✅ |
| 3 | ✅ | ✅ | ✅ | y+1 or x+1 | ✅ | ✅ |
| 4 | ❌ | ✅ | ❌ | z+1 | ✅ | ✅ |
| 5 | ❌ | ❌ | ✅ | z+1 or y+1 | ✅ | ❌ (but publish for alignment) |

> **Core rule: Every Maven publish has a corresponding tag. Every tag has corresponding Maven artifacts.**

### Scenario Details

**Scenario 1: Wasmtime-only upgrade**
- Rebuild engine modules against new wasmtime static library.
- Publish engine artifacts with new four-segment version (e.g., `wasmline-engine-pulley:1.0.0.4800`).
- wasmline x.y.z unchanged; only `v` changes.
- Tag: `release-1.0.0.4800` (tag and Maven publish are always paired).
- Update `wasmtime_version` in `versions.json`.

**Scenario 2: Wasmtime + C++ change**
- Engine modules fully recompiled (new code + new wasmtime).
- wasmline patch version bump (z+1).
- Tag: `release-x.y.(z+1).v`

**Scenario 3: Full-stack change**
- All modules rebuilt and republished.
- Minor or major version bump depending on change scope.
- Tag: `release-x.(y+1).0.v` or `release-(x+1).0.0.v`

**Scenario 4: C++ fix, same wasmtime**
- Engine modules recompiled with fixed code, same wasmtime linkage.
- Patch version bump. Engine `v` segment unchanged.
- Tag: `release-x.y.(z+1).v` (same v as before)

**Scenario 5: Kotlin-only change**
- Kotlin modules republished with fix/feature.
- Engine binary content unchanged, but republished for version alignment (Policy B).
- Patch or minor version bump.
- Tag: `release-x.(y+1).z.v` or `release-x.y.(z+1).v`

---

## Version Alignment Constraint

### Rule

The `x.y.z` portion of engine module versions **MUST** match the wasmline runtime version exactly:

```
wasmline:x.y.z  ←→  wasmline-engine-*:x.y.z.v
     ^^^^^^^^^           ^^^^^^^^^
     MUST be identical
```

The `v` (wasmtime encoding) is the only independently selectable component.

### Valid / Invalid Examples

```kotlin
// ✅ Valid: x.y.z matches
implementation("crow.wasmline:wasmline:1.1.0")
implementation("crow.wasmline:wasmline-engine-pulley:1.1.0.4800")

// ❌ Invalid: x.y.z mismatch (1.1.0 vs 1.2.0)
implementation("crow.wasmline:wasmline:1.1.0")
implementation("crow.wasmline:wasmline-engine-pulley:1.2.0.4800")
```

### Why This Constraint Exists

- The JNI bridge (`Api.cpp` ↔ `Wasmline.jni.kt`) is a hard coupling point.
- C Interop on iOS shares the same native ABI contract.
- Version mismatch can cause `UnsatisfiedLinkError`, silent data corruption, or native crashes.

### Enforcement

- **Runtime fail-fast check**: On initialization, the Kotlin layer reads the native library's embedded version string and compares it to the runtime's own version. Mismatch → immediate exception with actionable error message.
- **Documentation**: All dependency examples must show matched x.y.z versions.

---

## Feature–Wasmtime Binding

When a new wasmline feature requires a minimum wasmtime version (e.g., uses new C-API):

1. Declare `wasmtime_min_version` in `versions.json`.
2. The compatibility window only includes versions ≥ min.
3. Users on older wasmtime remain on the previous wasmline release.

This is analogous to Android's `minSdk` — a feature requiring API 34 doesn't get backported to API 28.

```json
{
  "wasmline_version": "1.1.0",
  "wasmtime_version": "61.0.0",
  "wasmtime_min_version": "61.0.0",
  "wasmtime_compat_window": ["61.0.0", "62.0.0"]
}
```

---

## Old Version Hotfix

When a bug is discovered in a previously released version (e.g., 1.0.0) while `main` has moved far ahead (e.g., 10.0.0):

### Workflow

```bash
# 1. Checkout the release tag
git checkout release-1.0.0.4702

# 2. Create a temporary fix branch
git checkout -b fix/1.0.1-<description>

# 3. Apply the fix, commit

# 4. Release patch (Maven publish + tag: release-1.0.1.4702)

# 5. If the bug also exists on main → cherry-pick the fix commit to main
#    If the bug is irrelevant to main (code refactored/removed) → do NOT merge

# 6. Delete the temporary branch
git branch -d fix/1.0.1-<description>
```

### Merge-Back Decision Rule

| Situation | Merge to main? |
|-----------|:--------------:|
| Bug also exists on main | ✅ Yes (cherry-pick) |
| Bug only in old code (refactored/removed on main) | ❌ No |
| Bug is wasmtime-specific, already fixed in newer wasmtime | ❌ No |
| Old API design flaw, already redesigned on main | ❌ No |

### Constraints

- The fix branch is **temporary** — deleted after release. This is NOT a long-lived release branch.
- The patch version uses the **same wasmtime version** as the original release (e.g., 1.0.0.4702 → 1.0.1.4702).
- Old version hotfixes are **exceptions, not routine**. If frequent, users should be guided to upgrade.
- Maven artifacts for the patch are published normally; the old release artifacts remain untouched.

---

## Design Rationale

### Why not one wasmline version per wasmtime version?

If wasmline 1.0.0 through 60.0.0 only differ by wasmtime version (code unchanged), those are not real software versions — they are build variants of the same code. This leads to:
- Fake version inflation
- Impossible backport scenarios ("merge Feature A into 60 versions")
- User confusion about what actually changed

### Why not release branches?

- Code is always on `main`.
- Multi-version engine artifacts are produced by CI matrix builds.
- Release branches create sync burden with zero benefit.

### Why four-segment version for engine modules?

- Engine version `x.y.z.v` exactly mirrors the tag format — full traceability.
- Maven version ordering works naturally: `1.1.0.4700 < 1.1.0.4800`.
- No classifier syntax needed; simpler dependency declarations.
- Kotlin modules stay at clean three-segment `x.y.z` (wasmtime-independent).

---

*This document defines design decisions. For CI workflow implementation details, see `.agents/docs/github-actions-workflows.md` (planned).*
