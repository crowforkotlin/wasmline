# Samples

Runnable samples are organized by use case:

- [minimal-raw-export](minimal-raw-export/README.md): one button invoking a raw
  integer addition export, with the result on screen and in the console.
- [minimal-service](minimal-service/README.md): one button calling a typed
  greeting service, with one shared interface and one guest implementation.
- [multiplatform-app](multiplatform-app/README.md): application hosts, UI,
  signed package loading, and guest plugins for the supported execution contracts.

Guest implementations and their build helpers belong to the example's
`plugin/` or `plugins/` directory. Native integration test fixtures belong to
`wasmline-native-test-fixtures/src/fixtures/`, outside the samples tree.

Both minimal examples target Android, Desktop, JS, and WasmJS, excluding iOS.
They reuse [minimal support](minimal-support/README.md) for UI,
execution state, and platform loading; only their guest contracts differ.
