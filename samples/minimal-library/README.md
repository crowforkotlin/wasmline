# Minimal Library

Internal, non-published KMP library included by both minimal sample builds.
It targets Android, Desktop, JS, and WasmJS only.

The source directory is shared. Its build directory is mapped into the including
sample's build tree, so the examples do not overwrite each other's generated
classes, metadata, resources, or test reports.

## Responsibilities

| Layer | Files | Responsibility |
| --- | --- | --- |
| `ui` | `MinimalApp`, `MinimalScreen`, `RunButton` | Stateless result/button UI and animations |
| `model` | `MinimalExample`, `RunState` | Example inputs and observable run state |
| `runtime` | `PluginEnvironment`, `PluginRunner`, `RunController` | Platform-neutral runtime execution and state transitions |
| `platform` | Platform environment factories | Dispatcher and package loading |
| `security` | `SampleTrust` | Shared demonstration public key |

This module owns the process Wasmline runtime only for these isolated examples.
Do not reuse its global shutdown policy in an application with other active
Wasmline consumers. Hosts share a runtime mutex so Android screen recreation
cannot close another in-flight invocation's runtime.

The UI scope owns cancellation. Cancellation is rethrown, not converted into an
error message; synchronous native calls finish before their cleanup runs.
Browser calls execute on the browser thread. These examples do not introduce
worker infrastructure for their small guest operations.

Samples do not contain automated test targets. Runtime cleanup and platform
packaging are verified by manually running each platform application.
