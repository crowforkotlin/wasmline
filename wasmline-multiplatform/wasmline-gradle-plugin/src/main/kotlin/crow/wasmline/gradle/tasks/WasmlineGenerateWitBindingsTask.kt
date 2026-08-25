package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.component.KotlinBindingsRequest
import crow.wasmline.plugin.core.component.WitBindgenTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Generates Kotlin guest bindings for the configured WIT world. */
internal abstract class WasmlineGenerateWitBindingsTask : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val world: Property<String>

    @get:Input
    abstract val kotlinImports: Property<String>

    @get:Input
    abstract val invocationProtocol: Property<WasmlineInvocationProtocol>

    @get:Input
    abstract val executionModel: Property<WasmlineExecutionModel>

    @get:Input
    abstract val witBindgenVersion: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val autoDownload: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val witBindgenExecutable: RegularFileProperty

    @get:Internal
    abstract val toolCacheDirectory: DirectoryProperty

    @get:Internal
    abstract val githubToken: Property<String>

    @TaskAction
    fun generate() = runBlocking {
        val protocol = invocationProtocol.get()
        val componentService = executionModel.get() == WasmlineExecutionModel.COMPONENT_MODEL &&
            protocol == WasmlineInvocationProtocol.WASMLINE_SERVICE
        val output = outputDirectory.get().asFile
        val serviceWitDirectory = File(output, "wit")
        val bindingInput = if (componentService) {
            materializeCanonicalServiceWit(serviceWitDirectory)
            serviceWitDirectory
        } else {
            witDirectory.get().asFile
        }
        val bindingWorld = if (componentService) SERVICE_WORLD else world.orNull
        val bindingImports = if (componentService) {
            "$SERVICE_ADAPTER_PACKAGE.*"
        } else {
            kotlinImports.orNull
        }
        val additionalArguments = if (componentService) {
            listOf("--kotlin-package-name", SERVICE_BINDING_PACKAGE)
        } else {
            emptyList()
        }
        val downloader = ToolDownloader(logger = { message -> logger.info(message) })
        try {
            val executable = witBindgenExecutable.orNull?.asFile ?: ToolResolver(
                ToolCache(toolCacheDirectory.get().asFile),
                downloader,
            ).resolve(
                tool = WasmlineTool.WIT_BINDGEN,
                version = witBindgenVersion.get(),
                platform = platform.get(),
                autoDownload = autoDownload.get(),
                githubToken = githubToken.orNull,
            ).file
            WitBindgenTool(
                executable = executable,
                runner = ExternalToolRunner(logger = { message -> logger.info(message) }),
            ).generateKotlin(
                KotlinBindingsRequest(
                    witDirectory = bindingInput,
                    outputDirectory = output,
                    world = bindingWorld,
                    kotlinImports = bindingImports,
                    additionalArguments = additionalArguments,
                    witBindgenVersion = witBindgenVersion.get(),
                ),
            )
            if (componentService) {
                File(output, "WasmlineComponentServiceAdapter.kt").writeText(componentServiceAdapterSource())
            }
        } finally {
            downloader.close()
        }
    }

    private fun materializeCanonicalServiceWit(directory: File): File {
        check(directory.mkdirs() || directory.isDirectory) {
            "Unable to create canonical Wasmline Service WIT directory: ${directory.absolutePath}"
        }
        val source = requireNotNull(javaClass.classLoader.getResourceAsStream(CANONICAL_SERVICE_WIT_RESOURCE)) {
            "Wasmline canonical Wasmline Service WIT resource is missing: $CANONICAL_SERVICE_WIT_RESOURCE"
        }.bufferedReader().use { it.readText() }
        return File(directory, "wasmline.wit").apply { writeText(source) }
    }

    private fun componentServiceAdapterSource(): String = """
        // Generated by Wasmline. DO NOT EDIT!
        @file:OptIn(crow.wasmline.WasmlineTransportApi::class)

        package $SERVICE_ADAPTER_PACKAGE

        import crow.wasmline.WasmlineComponentServiceInit
        import crow.wasmline.WasmlineComponentServiceOutboundTransport
        import crow.wasmline.WasmlineGuestServiceRuntime
        import crow.wasmline.invocation.WasmlineCallResult
        import crow.wasmline.invocation.WasmlineErrorCode
        import crow.wasmline.invocation.WasmlineFailure
        import $SERVICE_BINDING_PACKAGE.Host
        import $SERVICE_BINDING_PACKAGE.Plugin
        import $SERVICE_BINDING_PACKAGE.runtime.ComponentException

        object PluginImpl : Plugin {
            override fun invoke(request: Plugin.Request): Result<List<UByte>> =
                when (
                    val result = WasmlineGuestServiceRuntime.invoke(
                        action = request.action,
                        codec = request.codec,
                        payload = request.payload.toByteArray(),
                        transport = ComponentServiceOutboundTransport,
                        initialize = ::wasmlineComponentServiceInitialize,
                    )
                ) {
                    is WasmlineCallResult.Success -> Result.success(result.value.toUByteList())
                    is WasmlineCallResult.Failure -> Result.failure(
                        ComponentException(result.failure.toPluginServiceError()),
                    )
                }
        }

        object ComponentServiceOutboundTransport : WasmlineComponentServiceOutboundTransport {
            override fun invoke(
                action: String,
                codec: String,
                payload: ByteArray,
            ): WasmlineCallResult<ByteArray> = Host.Import.invoke(
                Host.Request(
                    action = action,
                    codec = codec,
                    payload = payload.toUByteList(),
                ),
            ).fold(
                onSuccess = { WasmlineCallResult.Success(it.toByteArray()) },
                onFailure = { WasmlineCallResult.Failure(it.toHostCallFailure()) },
            )
        }

        @WasmlineComponentServiceInit
        fun wasmlineComponentServiceInitialize() = Unit

        private fun Throwable.toHostCallFailure(): WasmlineFailure {
            val serviceError = (this as? ComponentException)?.value as? Host.ServiceError
                ?: return WasmlineFailure(
                    code = WasmlineErrorCode.HANDLER_FAILED,
                    message = message ?: "Wasmline Service Host binding failed.",
                )
            val rawCode = serviceError.code.toIntOrNull() ?: WasmlineErrorCode.UNKNOWN.value
            return WasmlineFailure(
                code = WasmlineErrorCode.fromValue(rawCode),
                rawCode = rawCode,
                message = serviceError.message,
                details = serviceError.details.takeIf { it.isNotEmpty() }?.toByteArray(),
            )
        }

        private fun WasmlineFailure.toPluginServiceError(): Plugin.ServiceError = Plugin.ServiceError(
            code = rawCode.toString(),
            message = message,
            details = details?.toUByteList() ?: emptyList(),
        )

        private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { index -> this[index].toByte() }

        private fun ByteArray.toUByteList(): List<UByte> = map(Byte::toUByte)
    """.trimIndent() + "\n"

    private companion object {
        const val CANONICAL_SERVICE_WIT_RESOURCE = "META-INF/wasmline/wit/wasmline-service/wasmline.wit"
        const val SERVICE_WORLD = "plugin"
        const val SERVICE_BINDING_PACKAGE = "crow.wasmline.component.service.binding"
        const val SERVICE_ADAPTER_PACKAGE = "crow.wasmline.component.service.generated"
    }
}
