# Rust typed Component resource fixture

This guest uses the standard `wit-bindgen` Component Model API. It provides a guest-owned
`counter` resource and consumes the Host-owned `callback` resource as both `borrow<T>` and
`own<T>`. Wasmline's JVM integration tests use it to verify resource identity, ownership transfer,
destructors, trap cleanup, and instance isolation without any Kotlin compiler IR adaptation.

Build the portable Component with:

```shell
cargo build --target wasm32-wasip2
```
