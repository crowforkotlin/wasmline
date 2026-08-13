# Kotlin Component Export sample

This module demonstrates `COMPONENT_MODEL + COMPONENT_EXPORT`. The WIT world
declares `add(s32, s32) -> s32`; the Wasmline Gradle plugin generates the guest
bindings, creates the Component, compiles native CWASM/PWASM artifacts, and
publishes a signed package.

```shell
./gradlew :sample-component-export-plugin:wasmlineAssembleDebug
```

The Desktop sample bundles this package automatically and invokes
`wasmline:sample-component-export/calculator@1.0.0#add` through
`invokeComponentResult`.
