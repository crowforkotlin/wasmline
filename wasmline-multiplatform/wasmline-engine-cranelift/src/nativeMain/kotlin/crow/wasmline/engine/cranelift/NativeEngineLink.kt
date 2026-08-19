@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.engine.cranelift

import crow.wasmline.engine.native.wasmline_native_engine_link_anchor

/**
 * Keeps the Cranelift Native cinterop and its static bridge in the published
 * engine KLIB dependency graph.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal fun ensureCraneliftNativeEngineLinked() {
    wasmline_native_engine_link_anchor()
}
