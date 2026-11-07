# Branching, Versioning, and Release Guide

This document records the repository's current branch, version, tag, and publication model. It does not authorize pushing, tagging, or publishing.

Local synchronization, verification, build, and test procedures also require
explicit user authorization under the repository Execution Policy. Otherwise
provide the commands for manual execution and report those steps as not run.

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

`versions.json` contains independent version inputs:

- `wasmline_version`: the Maven version used by every published Wasmline module.
- `wasmtime_version`: the Wasmtime toolchain and native-runtime version.
- `sample_plugin_version`: the version used by sample plugin packages.
- Kotlin, Dokka, AGP, Gradle, Zig, and JBR toolchain versions.

Use the version synchronizer instead of editing repeated references:

```bash
./scripts/wasmline versions sync --set wasmline_version=<Maven-version>
./scripts/wasmline versions sync --set wasmtime_version=<major.minor.patch>
./scripts/wasmline versions check
```

See [`version-sync.md`](./version-sync.md) for the complete procedure.

## Maven Version Model

The root `build.gradle.kts` assigns `wasmline.version` to all projects. Runtime, loader, compiler plugin, Gradle plugin, network adapters, plugin core, and engine modules therefore share one Maven version: `x.y.z`, `x.y.z-alphaN`, or `x.y.z-betaN`.

`N` is a positive integer without leading zeros. Use `alpha5` and `beta1`,
without a dot between the stage and number. For the same numeric base,
`alpha < beta < stable`, and iteration numbers compare numerically
(`beta2 < beta10`). The suffix belongs to the Maven version and is used by the
BOM, dependencies, and Gradle plugin together.

Engine modules do **not** use `x.y.z.v` Maven versions. JVM native libraries are published as platform classifiers and advertised as variants in Gradle module metadata. Android and other KMP variants use the same project version.

Wasmtime AOT identity is carried separately from the Maven version. Native
engines report backend-specific compatibility profile IDs, and signed manifests
bind CWASM or PWASM variants to those exact IDs. The human-readable Wasmtime
`x.y.z` identifies the internal catalog distribution and its diagnostics; it is
not the public AOT selection input and it is not sufficient proof that a
serialized artifact can be loaded.

The published `wasmline-bom` applies strict same-version constraints to runtime,
loader, network, build-tool, and engine modules. Consumers that cannot use the
BOM must still assign the same `wasmline_version` to every Wasmline coordinate.

Because Maven coordinates are immutable, a Wasmtime upgrade that changes published engine binaries also requires a new `wasmline_version`. A patch increment is sufficient when no public API changes.

A Wasmtime upgrade appends immutable Cranelift and Pulley profile descriptors,
compiler-asset digests, and build-host bindings through the root
`aot-compatibility.json` maintenance workflow. Already released profile IDs and
provenance records are never rewritten. A
plugin selects compatibility generations with typed `AotGenerations` strategies
or concrete versions; an omitted or empty selection selects the current
generation only, and building every locally retained generation requires the
explicit `AotGenerations.All` opt-in. Consumers do not enter Wasmtime versions or
profile digests in the normal DSL; the Gradle plugin resolves those internal
bindings from its packaged catalog. Publishing several generations in one
manifest does not create additional Wasmline Maven versions or engine artifacts.

## Release Tag

Release tags use:

```text
release-<Maven-version>.<Wasmtime-code>
```

- `<Maven-version>` is the complete `wasmline_version`, including any alpha/beta suffix.
- `<Wasmtime-code>` encodes the numeric Wasmtime version as `major×100 + minor×10 + patch`.

Example: Wasmtime `12.3.4` encodes to `1234`, so Wasmline `1.2.3` uses `release-1.2.3.1234`.
Wasmline `1.2.3-beta1` with the same Wasmtime uses
`release-1.2.3-beta1.1234`; the Wasmtime code never appears in a Maven coordinate.

The fourth component of the fork distribution, such as the `.2` in `12.3.4.2`,
does not enter that encoding. It remains recorded in the AOT catalog and release
notes. `./scripts/release.sh expected-tag` derives the current tag from
`versions.json`; it never creates a tag.

The encoding is unambiguous only while Wasmtime minor and patch values are single digits. Revise the tag format before accepting a version that violates this condition.

Tags are immutable. Do not move or reuse a release tag.

`./scripts/release.sh github-release-metadata` prints the Maven version, tag,
`prerelease`, and `make_latest` values consumed by the workflow. Alpha/beta
versions use `prerelease=true` and `make_latest=false`; stable versions use
`prerelease=false` and `make_latest=true`. The GitHub Release title is
`wasmline <Maven-version>`, and its body comes from `CHANGELOG.md` plus generated
runtime compatibility metadata.

The tag suffix describes the Wasmtime version linked by that Wasmline release.
It does not enumerate every historical AOT profile that the plugin build catalog
can compile.

## Tag and Maven Pairing

Each Maven release must correspond to exactly one release tag, and each release tag must correspond to its Maven release.

Before either action:

1. Confirm `main` contains the intended release commit.
2. Synchronize and verify all managed versions.
3. Complete the authorized build and test matrix.
4. Confirm Maven credentials and signing configuration.
5. Push the matching release tag to trigger publication of the same Maven version.

The GitHub Actions CI workflow has no publication or release jobs; a successful CI run is not a release. Publication is implemented by `.github/workflows/release.yml`, which runs only for a validated release tag in the format above. It publishes Maven artifacts, then creates the matching GitHub Release with `aot-compatibility.json` and its checksum. The local `scripts/release.sh` validates inputs and prepares release notes/assets but never creates tags or publishes artifacts. A candidate can be committed and pushed directly to `main`; the release procedure does not require a PR.

For the first unpublished alpha/beta release, set `wasmline_version` and the
catalog's current, minimum, and first range-start versions to the candidate.
Run `versions sync`, then `aot sync --rebuild` to replace unpublished generated
history. Rebuild checks local and origin `release-*` tags and stops on an
existing tag or lookup failure. Confirm that no Maven version has been published
before using this initialization path. Later releases use normal `aot sync`
and preserve historical range starts, profiles, and compiler bindings. Moving
from beta to stable alone does not create an AOT generation.

The Linux job verifies native AOT, JVM tests, and dynamic plugin tests, then transfers its JVM and Android engine libraries to the publisher. The iOS simulator Pulley gate runs separately on macOS. Publication uses one macOS job because the Kotlin builds declare Apple targets only on macOS; publishing root metadata on Linux would omit those target variants. Before the single `publishToMavenCentral` invocation, it publishes locally, runs `./scripts/release.sh verify-publications --require-apple`, and tests `wasmline-plugin-test` with `-Pwasmline.source=false`. The verifier checks target redirects, current versions, artifact files/digests, native libraries, and self dependencies. A Linux local publication cannot verify Apple compilation, linking, or publication.

Keep release changes in `CHANGELOG.md` under `Unreleased`. The release notes
generator includes that section with the Wasmtime distribution and AOT metadata.
See the [Releasing guide](<../../../../fumadocs/content/docs/(reference)/(repository-development)/releasing.mdx>)
for prerequisites, Maven secrets, and consumer verification.

## Change Scenarios

| Change | `wasmline_version` | `wasmtime_version` | Native rebuild |
| --- | --- | --- | --- |
| Documentation or repository automation only | Usually unchanged | Unchanged | No |
| Backward-compatible feature | Minor increment | Usually unchanged | When native code or packaged engines change |
| Bug fix | Patch increment | Usually unchanged | When native code or packaged engines change |
| Breaking public API | Major increment | As required | As required |
| Wasmtime upgrade only | Patch increment or greater | Update | Yes |

There is no implemented `wasmtime_compat_window` or `wasmtime_min_version` field in `versions.json`. Do not document or depend on such fields until the manifest, synchronizer, loader policy, and publication workflow implement them together.

## Old Release Hotfix

Use a temporary branch from the release tag:

```bash
git switch --detach release-x.y.z.v
git switch -c fix/x.y.next-description
```

Apply and verify the fix, publish it under a new `wasmline_version`, and create a new matching tag. Cherry-pick the fix to `main` only when the defect still exists there. Remove the temporary branch after the release.

An old-release hotfix is not a long-lived release branch. Never overwrite existing Maven artifacts or move the original tag.
