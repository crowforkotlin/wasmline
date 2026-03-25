package crow.wasmline

import crow.wasmline.spi.WasmlineBindingScope

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
 * The compiler plugin is expected to validate these rules and generate the
 * matching Wasmline definition / proxy / adapter glue.
 */
interface WasmlineService {
    companion object {

    }
}
