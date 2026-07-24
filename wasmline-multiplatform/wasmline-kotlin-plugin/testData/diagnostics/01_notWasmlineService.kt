// RUN_PIPELINE_TILL: FRONTEND

/**
 * Diagnostic test for non-service class.
 * Verifies that the plugin doesn't process classes not implementing WasmlineService.
 */
class NotAService {
    fun customMethod(): String = "hello"
}
