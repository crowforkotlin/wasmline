# Minimal Sample Support

Internal, non-published KMP module included by both minimal sample builds.
It targets Android, Desktop, JS, and WasmJS only.

The source directory is shared. Its build directory is mapped into the including
sample's build tree, so the examples do not overwrite each other's generated
classes, metadata, resources, or test reports.

## Responsibilities

| Layer | Files | Responsibility |
| --- | --- | --- |
| Presentation | `MinimalScreen`, `RunButton` | Stateless result/button UI and animations |
| State | `RunController`, `RunState` | Logging, duplicate-click protection, success, failure, cancellation |
| Integration | `MinimalApp`, `MinimalExample` | Compose lifecycle and example-specific invocation |
| Execution | `PluginRunner` | Serialized runtime ownership and unconditional cleanup |
| Platform | `PluginEnvironment`, platform environment factories | Dispatcher and package loading |
| Trust | `SampleTrust` | Shared demonstration public key |

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
