package crow.wasmline

/**
 * Explicit backend warmup mode for host-side Wasmtime runtimes.
 *
 * `Wasmline.load(...)` still auto-selects the backend from the artifact suffix.
 * This enum only controls optional eager engine creation when callers want to
 * shift that cost earlier in the app lifecycle.
 *
 * - [PULLEY] — warm up the Pulley interpreter (available in both engine modules).
 * - [CRANELIFT] — warm up the Cranelift JIT compiler (requires `wasmline-engine-cranelift`).
 */
enum class WasmlineWarmupMode {
    PULLEY,
    CRANELIFT,
}
