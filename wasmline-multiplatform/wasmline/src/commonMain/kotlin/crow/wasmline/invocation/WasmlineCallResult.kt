package crow.wasmline.invocation

/**
 * Represents the result of a Wasmline operation.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
sealed interface WasmlineCallResult<out T> {
    /**
     * Carries a successful operation value.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property value Operation result.
     */
    data class Success<T>(val value: T) : WasmlineCallResult<T>

    /**
     * Carries an expected, structured operation failure.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property failure Canonical non-throwing failure value.
     */
    data class Failure(val failure: WasmlineFailure) : WasmlineCallResult<Nothing>

    /** Returns the success value, or `null` for a failure. */
    fun getOrNull(): @UnsafeVariance T? = when (this) {
        is Success -> value
        is Failure -> null
    }

    /** Returns the canonical failure value, or `null` for a successful result. */
    fun failureOrNull(): WasmlineFailure? = when (this) {
        is Success -> null
        is Failure -> failure
    }

    /** Returns the success value or throws [WasmlineException] for a failure. */
    fun throwOnFailure(): @UnsafeVariance T = when (this) {
        is Success -> value
        is Failure -> throw WasmlineException(failure)
    }

    /** Returns the success value or throws [WasmlineException] for a failure. */
    fun getOrThrow(): @UnsafeVariance T = throwOnFailure()
}
