package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File

/** Canonical `wasmline:service` WIT shipped as a classpath resource. */
@InternalWasmlineToolingApi
object WasmlineServiceWit {
    const val DIRECTORY_RESOURCE: String = "META-INF/wasmline/wit/wasmline-service"
    const val FILE_NAME: String = "wasmline.wit"
    const val RESOURCE_PATH: String = "$DIRECTORY_RESOURCE/$FILE_NAME"

    /** Materializes the canonical WIT inside [directory] and returns the written file. */
    fun writeTo(directory: File): File {
        check(directory.mkdirs() || directory.isDirectory) {
            "Unable to create canonical Wasmline Service WIT directory: ${directory.absolutePath}"
        }
        val source = requireNotNull(WasmlineServiceWit::class.java.classLoader.getResourceAsStream(RESOURCE_PATH)) {
            "Wasmline canonical Wasmline Service WIT resource is missing: $RESOURCE_PATH"
        }
        val written = File(directory, FILE_NAME)
        source.use { input ->
            written.outputStream().use { output -> input.copyTo(output) }
        }
        return written
    }
}
