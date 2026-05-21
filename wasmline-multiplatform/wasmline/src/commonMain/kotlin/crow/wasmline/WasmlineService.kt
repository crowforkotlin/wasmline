package crow.wasmline


/**
 * Marker interface for typed Wasmline service contracts.
 *
 * Current contract restrictions are intentionally conservative:
 * - the contract must be an `interface`
 * - members should be public functions
 * - overloads are not supported yet
 * - properties are not supported yet
 * - generic contracts and generic functions are not supported yet
 * - `suspend`, default arguments, and `vararg` are not supported yet
 * - methods currently support at most one regular parameter
 *
 * The compiler plugin validates these rules, generates one internal
 * `*_WasmlineBridge` per contract, and rewrites typed `link()` / `bind()`
 * call sites directly to that generated bridge.
 */
interface WasmlineService {
    companion object {

    }
}
