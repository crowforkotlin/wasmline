package crow.wasmline

/** Marks the binary SPI shared between Wasmline's separately published runtime modules. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for Wasmline runtime modules and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalWasmlineRuntimeApi
