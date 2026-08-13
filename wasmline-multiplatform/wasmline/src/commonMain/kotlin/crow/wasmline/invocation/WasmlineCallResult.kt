package crow.wasmline.invocation

/**
 * Represents the result of a Wasmline invocation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
sealed interface WasmlineCallResult<out T> {
    data class Success<T>(val value: T) : WasmlineCallResult<T>
    data class Failure(val error: WasmlineCallError) : WasmlineCallResult<Nothing>

    fun getOrNull(): @UnsafeVariance T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    fun errorOrNull(): WasmlineCallError? = when (this) {
        is Success -> null
        is Failure -> error
    }

    fun throwOnFailure(): @UnsafeVariance T = when (this) {
        is Success -> value
        is Failure -> throw WasmlineInvocationException(error)
    }

    fun getOrThrow(): @UnsafeVariance T = throwOnFailure()
}
