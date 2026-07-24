// RUN_PIPELINE_TILL: FRONTEND

import crow.wasmline.WasmlineService

/**
 * Diagnostic test for empty service interface.
 * Tests handling of interfaces with no methods.
 */
interface EmptyService : WasmlineService
