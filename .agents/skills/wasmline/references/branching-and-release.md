# Branching, Versioning, and Release Guide

This document records the repository's current branch, version, tag, and publication model. It does not authorize pushing, tagging, or publishing.

## Contents

- [Branch Model](#branch-model)
- [Version Sources](#version-sources)
- [Maven Version Model](#maven-version-model)
- [Release Tag](#release-tag)
- [Tag and Maven Pairing](#tag-and-maven-pairing)
- [Change Scenarios](#change-scenarios)
- [Old Release Hotfix](#old-release-hotfix)

## Branch Model

| Role | Convention |
| --- | --- |
| Primary branch | `main` |
| Temporary work | `feature/*`, `fix/*`, or `chore/*` |
| Release branches | Not used |
| Wasmtime-version branches | Not used |

Merge temporary branches into `main` and remove them after use. Releases are cut from an exact commit rather than maintained on a long-lived release branch.

## Version Sources

`scripts/versions.json` contains independent version inputs:

- `wasmline_version`: the Maven version used by every published Wasmline module.
- `wasmtime_version`: the Wasmtime toolchain and native-runtime version.
- `sample_plugin_version`: the version used by sample plugin packages.
- Kotlin, Dokka, AGP, Zig, and JBR toolchain versions.

Use the version synchronizer instead of editing repeated references:

```bash
./scripts/wasmline versions sync --set wasmline_version=<x.y.z>
./scripts/wasmline versions sync --set wasmtime_version=<major.minor.patch>
./scripts/wasmline versions check
```

See [`version-sync.md`](./version-sync.md) for the complete procedure.

## Maven Version Model

The root `wasmline-multiplatform/build.gradle.kts` assigns `wasmline.version` to all projects. Runtime, loader, compiler plugin, Gradle plugin, network adapters, plugin core, and engine modules therefore share one `x.y.z` Maven version.

Engine modules do **not** use `x.y.z.v` Maven versions. JVM native libraries are published as platform classifiers and advertised as variants in Gradle module metadata. Android and other KMP variants use the same project version.

The Wasmtime identity is carried separately by native assets and artifact metadata such as `targetCompilerVersion`. Precompiled `.cwasm` and `.pwasm` artifacts must match the runtime's Wasmtime version and target properties.

Because Maven coordinates are immutable, a Wasmtime upgrade that changes published engine binaries also requires a new `wasmline_version`. A patch increment is sufficient when no public API changes.

## Release Tag

Release tags use:

```text
release-x.y.z.v
```

- `x.y.z` is `wasmline_version`.
- `v` encodes the numeric Wasmtime version as `major×100 + minor×10 + patch`.

Example: Wasmtime `12.3.4` encodes to `1234`, so Wasmline `1.2.3` uses `release-1.2.3.1234`.

The encoding is unambiguous only while Wasmtime minor and patch values are single digits. Revise the tag format before accepting a version that violates this condition.

Tags are immutable. Do not move or reuse a release tag.

## Tag and Maven Pairing

Each Maven release must correspond to exactly one release tag, and each release tag must correspond to its Maven release.

Before either action:

1. Confirm `main` contains the intended release commit.
2. Synchronize and verify all managed versions.
3. Complete the authorized build and test matrix.
4. Confirm Maven credentials and signing configuration.
5. Publish the `x.y.z` modules and create the matching `release-x.y.z.v` tag as one release operation.

The GitHub Actions CI workflow has no publication or release jobs. Maven publication and GitHub release automation remain unimplemented; a successful CI run is not a release.

## Change Scenarios

| Change | `wasmline_version` | `wasmtime_version` | Native rebuild |
| --- | --- | --- | --- |
| Documentation or repository automation only | Usually unchanged | Unchanged | No |
| Backward-compatible feature | Minor increment | Usually unchanged | When native code or packaged engines change |
| Bug fix | Patch increment | Usually unchanged | When native code or packaged engines change |
| Breaking public API | Major increment | As required | As required |
| Wasmtime upgrade only | Patch increment or greater | Update | Yes |

There is no implemented `wasmtime_compat_window` or `wasmtime_min_version` field in `scripts/versions.json`. Do not document or depend on such fields until the manifest, synchronizer, loader policy, and publication workflow implement them together.

## Old Release Hotfix

Use a temporary branch from the release tag:

```bash
git switch --detach release-x.y.z.v
git switch -c fix/x.y.next-description
```

Apply and verify the fix, publish it under a new `wasmline_version`, and create a new matching tag. Cherry-pick the fix to `main` only when the defect still exists there. Remove the temporary branch after the release.

An old-release hotfix is not a long-lived release branch. Never overwrite existing Maven artifacts or move the original tag.
