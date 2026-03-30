package crow.wasmline


/**
 * Marker interface for first-phase typed RPC contracts.
 *
 * First-phase contract restrictions are intentionally conservative:
 * - the contract must be an `interface`
 * - members should be public functions
 * - overloads are not supported yet
 * - properties are not supported yet
 * - generic contracts and generic functions are not supported yet
 * - `suspend`, default arguments, and `vararg` are not supported yet
 *
 * The compiler plugin validates these rules, generates one internal
 * `*_WasmlineBridge` per contract, and rewrites typed `link()` / `bind()`
 * call sites directly to that generated bridge.
 */
interface WasmlineService {
    companion object {

    }
}
