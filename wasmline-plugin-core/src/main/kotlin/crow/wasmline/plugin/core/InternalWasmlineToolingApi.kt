package crow.wasmline.plugin.core

/** Marks implementation APIs shared by Wasmline's CLI and Gradle plugin. */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is reserved for Wasmline build tooling and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalWasmlineToolingApi
