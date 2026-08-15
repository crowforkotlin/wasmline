package crow.wasmline.loader.internal

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineNativeBackend
import crow.wasmline.WasmlineTrustedKeySet
import crow.wasmline.loader.VerifiedPackageArtifact
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.internal.crypto.newKeyPairFromSeed
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies local package artifact selection for browser and native hosts. */
class WasmlineLocalPackageResolutionTest {

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `live Gradle Component manifest has a valid Ed25519 signature`() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val manifest = requiredLiveFile(COMPONENT_MANIFEST_ENV)
        val privateKey = requiredLiveFile(COMPONENT_PRIVATE_KEY_ENV).readText().trim().decodeHex()
        val envelope = ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifest.readBytes())
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), envelope.manifest)
        val keyPair = newKeyPairFromSeed(privateKey)

        assertTrue(
            Ed25519.verify(
                message = manifestBytes.toByteString(),
                signature = envelope.signature.toByteString(),
                publicKey = keyPair.publicKey,
            ),
        )
    }

    @Test
    fun `live CLI Component manifest resolves to a Component descriptor`() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val manifest = requiredLiveFile(COMPONENT_MANIFEST_ENV)
        val privateKey = requiredLiveFile(COMPONENT_PRIVATE_KEY_ENV).readText().trim().decodeHex()
        val keyPair = newKeyPairFromSeed(privateKey)
        val source = WasmlineSource.LocalManifestPath(manifest.absolutePath)

        val resolution = WasmlineLocalPackageResolution.resolve(
            source = source,
            request = WasmlineLoadRequest(
                source = source,
                config = WasmlineConfig(
                    trustedKeys = WasmlineTrustedKeySet.Builder()
                        .add("Ed25519", keyId = null, publicKey = keyPair.publicKey.toByteArray())
                        .build(),
                ),
            ),
        )
        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val artifact = assertIs<VerifiedPackageArtifact>(continuation.source)
        assertTrue(File(artifact.descriptor.path).isFile)
        val descriptor = artifact.descriptor
        assertEquals(WasmlineExecutionModel.COMPONENT_MODEL, descriptor.executionModel)
        assertEquals(WasmlineInvocationProtocol.COMPONENT_EXPORT, descriptor.invocationProtocol)
        assertEquals(WasmlineComponentServiceContract.DEFAULT_EXPORT, descriptor.exportName)
        assertEquals(
            WasmlineComponentServiceContract.DEFAULT_CODEC,
            descriptor.contractMetadata[WasmlineComponentServiceContract.METADATA_CODEC],
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `local package rejects a missing trusted key source before artifact selection`() {
        val manifestFile = File.createTempFile("wasmline-package", ".wlm")
        try {
            val envelope = SignedManifestEnvelope(
                signature = byteArrayOf(0),
                manifest = WasmlineManifest(
                    pluginId = "crow.wasmline.test",
                    version = "1.0.0",
                    versionCode = 1,
                    minSdkVersion = "0.0.0",
                    buildTimestamp = 0,
                    artifacts = emptyList(),
                ),
            )
            manifestFile.writeBytes(ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope))
            val source = WasmlineSource.LocalManifestPath(manifestFile.absolutePath)

            val resolution = WasmlineLocalPackageResolution.resolve(
                source = source,
                request = WasmlineLoadRequest(source = source),
            )

            val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertTrue(failure.cause.contains("requires trustedKeys"))
        } finally {
            manifestFile.delete()
        }
    }

    @Test
    fun `browser host prefers raw wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "plugin.pwasm",
                    sha256 = "pwasm",
                    targetCpu = "pulley64",
                    targetCompilerVersion = TEST_COMPILER_VERSION,
                    is64Bit = true,
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = "plugin.wasm",
                    sha256 = "wasm",
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true,
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "browser",
                cpu = "wasmjs",
                is64Bit = true,
            ),
        )

        assertEquals("plugin.wasm", selected?.url)
    }

    @Test
    fun `native host ignores raw wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = "plugin.wasm",
                    sha256 = "wasm",
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true,
                ),
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "plugin.pwasm",
                    sha256 = "pwasm",
                    targetCpu = "pulley64",
                    targetCompilerVersion = TEST_COMPILER_VERSION,
                    is64Bit = true,
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
                nativeBackend = WasmlineNativeBackend.PULLEY,
                wasmtimeVersion = TEST_WASMTIME_VERSION,
            ),
        )

        assertEquals("plugin.pwasm", selected?.url)
    }

    @Test
    fun `pulley64 artifacts are portable across 64-bit native hosts`() {
        val artifact = pulleyArtifact(cpu = "pulley64", is64Bit = true)
        val targets = listOf(
            nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.PULLEY),
            nativeTarget(os = "android", cpu = "aarch64", backend = WasmlineNativeBackend.PULLEY),
            nativeTarget(os = "macos", cpu = "aarch64", backend = WasmlineNativeBackend.PULLEY),
            nativeTarget(os = "windows", cpu = "x86_64", backend = WasmlineNativeBackend.PULLEY),
            nativeTarget(os = "ios", cpu = "aarch64", backend = WasmlineNativeBackend.PULLEY),
        )

        targets.forEach { target ->
            assertEquals(
                artifact,
                WasmlineLocalPackageResolution.selectArtifact(listOf(artifact), target),
                "Pulley64 should be selectable on ${target.os}/${target.cpu}.",
            )
        }
    }

    @Test
    fun `legacy pulley OS metadata remains portable`() {
        val artifact = pulleyArtifact(cpu = "pulley64", targetOs = "pulley", is64Bit = true)

        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(artifact),
            target = nativeTarget(os = "ios", cpu = "aarch64", backend = WasmlineNativeBackend.PULLEY),
        )

        assertEquals(artifact, selected)
    }

    @Test
    fun `pulley artifacts require matching bitness`() {
        val pulley64 = pulleyArtifact(cpu = "pulley64", is64Bit = true)
        val pulley32 = pulleyArtifact(cpu = "pulley32", is64Bit = false)

        assertNull(
            WasmlineLocalPackageResolution.selectArtifact(
                artifacts = listOf(pulley64),
                target = nativeTarget(
                    os = "android",
                    cpu = "x86",
                    is64Bit = false,
                    backend = WasmlineNativeBackend.PULLEY,
                ),
            ),
        )
        assertNull(
            WasmlineLocalPackageResolution.selectArtifact(
                artifacts = listOf(pulley32),
                target = nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.PULLEY),
            ),
        )
        assertEquals(
            pulley32,
            WasmlineLocalPackageResolution.selectArtifact(
                artifacts = listOf(pulley32),
                target = nativeTarget(
                    os = "android",
                    cpu = "x86",
                    is64Bit = false,
                    backend = WasmlineNativeBackend.PULLEY,
                ),
            ),
        )
    }

    @Test
    fun `PWASM selection rejects deserialize incompatible target metadata`() {
        val target = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.PULLEY,
        )
        val valid = pulleyArtifact(cpu = "pulley64", is64Bit = true)
        val invalid = listOf(
            valid.copy(targetCpu = null),
            valid.copy(targetCpu = "x86_64"),
            valid.copy(targetOs = "linux"),
            valid.copy(targetCpu = "pulley32"),
        )

        invalid.forEach { artifact ->
            assertNull(
                WasmlineLocalPackageResolution.selectArtifact(listOf(artifact), target),
                "PWASM metadata ${artifact.targetOs}/${artifact.targetCpu}/${artifact.is64Bit} must be ineligible.",
            )
        }
        assertEquals(valid, WasmlineLocalPackageResolution.selectArtifact(listOf(valid), target))
    }

    @Test
    fun `invalid higher scoring PWASM does not block a portable candidate`() {
        val invalid = pulleyArtifact(cpu = "pulley64", is64Bit = true).copy(
            url = "plugin-missing-target.pwasm",
            targetCpu = null,
        )
        val portable = pulleyArtifact(cpu = "pulley64", is64Bit = true)

        assertEquals(
            portable,
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(invalid, portable),
                nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.PULLEY),
            ),
        )
    }

    @Test
    fun `browser host rejects serialized artifacts without raw wasm`() {
        val target = WasmlineHostArtifactTarget(os = "browser", cpu = "wasmjs", is64Bit = true)

        listOf(
            cwasmArtifact(),
            pulleyArtifact(cpu = "pulley64", is64Bit = true),
        ).forEach { artifact ->
            assertNull(WasmlineLocalPackageResolution.selectArtifact(listOf(artifact), target))
        }
    }

    @Test
    fun `Cranelift host selects matching Core CWASM over portable PWASM`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                pulleyArtifact(cpu = "pulley64", is64Bit = true),
                cwasmArtifact(),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
                nativeBackend = WasmlineNativeBackend.CRANELIFT,
                wasmtimeVersion = TEST_WASMTIME_VERSION,
            ),
        )

        assertEquals("plugin-x86_64-linux.cwasm", selected?.url)
    }

    @Test
    fun `native backends select matching AOT format for Core and Component`() {
        val craneliftTarget = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.CRANELIFT,
        )
        val pulleyTarget = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.PULLEY,
        )

        listOf(WasmlineExecutionModel.CORE_WASM, WasmlineExecutionModel.COMPONENT_MODEL).forEach { executionModel ->
            val cwasm = cwasmArtifact(executionModel)
            val pwasm = pulleyArtifact(
                cpu = "pulley64",
                is64Bit = true,
                executionModel = executionModel,
            )
            val artifacts = listOf(pwasm, cwasm)

            assertEquals(cwasm, WasmlineLocalPackageResolution.selectArtifact(artifacts, craneliftTarget))
            assertEquals(pwasm, WasmlineLocalPackageResolution.selectArtifact(artifacts, pulleyTarget))
        }
    }

    @Test
    fun `selection enforces the complete physical type model and protocol matrix`() {
        val eligibleContracts = setOf(
            Triple(WasmlineArtifactType.WASM, WasmlineExecutionModel.CORE_WASM, WasmlineInvocationProtocol.WASMLINE_SERVICE),
            Triple(WasmlineArtifactType.CWASM, WasmlineExecutionModel.CORE_WASM, WasmlineInvocationProtocol.WASMLINE_SERVICE),
            Triple(WasmlineArtifactType.CWASM, WasmlineExecutionModel.CORE_WASM, WasmlineInvocationProtocol.RAW_EXPORT),
            Triple(
                WasmlineArtifactType.CWASM,
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
            Triple(
                WasmlineArtifactType.CWASM,
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
            ),
            Triple(WasmlineArtifactType.PWASM, WasmlineExecutionModel.CORE_WASM, WasmlineInvocationProtocol.WASMLINE_SERVICE),
            Triple(WasmlineArtifactType.PWASM, WasmlineExecutionModel.CORE_WASM, WasmlineInvocationProtocol.RAW_EXPORT),
            Triple(
                WasmlineArtifactType.PWASM,
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
            Triple(
                WasmlineArtifactType.PWASM,
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
            ),
        )
        val browserTarget = WasmlineHostArtifactTarget(os = "browser", cpu = "wasmjs", is64Bit = true)
        val craneliftTarget = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.CRANELIFT,
        )
        val pulleyTarget = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.PULLEY,
        )

        WasmlineArtifactType.entries.forEach { type ->
            WasmlineExecutionModel.entries.forEach { executionModel ->
                WasmlineInvocationProtocol.entries.forEach { invocationProtocol ->
                    val contract = Triple(type, executionModel, invocationProtocol)
                    val artifact = contractArtifact(type, executionModel, invocationProtocol)
                    val target = when (type) {
                        WasmlineArtifactType.WASM -> browserTarget

                        WasmlineArtifactType.CWASM,
                        WasmlineArtifactType.COMPONENT_WASM,
                        -> craneliftTarget

                        WasmlineArtifactType.PWASM -> pulleyTarget
                    }
                    val selected = WasmlineLocalPackageResolution.selectArtifact(listOf(artifact), target)

                    if (contract in eligibleContracts) {
                        assertEquals(artifact, selected, "$contract must be eligible.")
                    } else {
                        assertNull(selected, "$contract must be ineligible.")
                    }
                }
            }
        }
    }

    @Test
    fun `Cranelift selects PWASM when matching CWASM is unavailable`() {
        val cwasm = cwasmArtifact()
        val pwasm = pulleyArtifact(cpu = "pulley64", is64Bit = true)

        assertEquals(
            pwasm,
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(pwasm),
                nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.CRANELIFT),
            ),
        )
        assertNull(
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(cwasm),
                nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.PULLEY),
            ),
        )
        assertNull(
            WasmlineLocalPackageResolution.selectArtifact(
                artifacts = listOf(cwasm, pwasm),
                target = WasmlineHostArtifactTarget(os = "linux", cpu = "x86_64", is64Bit = true),
            ),
        )
    }

    @Test
    fun `native AOT selection requires the exact Wasmtime version`() {
        val matching = cwasmArtifact()
        val target = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.CRANELIFT,
        )

        listOf(null, TEST_WASMTIME_VERSION, INCOMPATIBLE_COMPILER_VERSION).forEach { compilerVersion ->
            assertNull(
                WasmlineLocalPackageResolution.selectArtifact(
                    listOf(matching.copy(targetCompilerVersion = compilerVersion)),
                    target,
                ),
                "Compiler version '$compilerVersion' must not match ${target.wasmtimeVersion}.",
            )
        }
        assertNull(
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(matching),
                target.copy(wasmtimeVersion = null),
            ),
        )
        assertNull(
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(matching),
                target.copy(wasmtimeVersion = "47.0"),
            ),
        )
        assertEquals(matching, WasmlineLocalPackageResolution.selectArtifact(listOf(matching), target))
    }

    @Test
    fun `PWASM selection requires the exact Wasmtime version`() {
        val matching = pulleyArtifact(cpu = "pulley64", is64Bit = true)
        val target = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.PULLEY,
        )

        listOf(null, TEST_WASMTIME_VERSION, INCOMPATIBLE_COMPILER_VERSION).forEach { compilerVersion ->
            assertNull(
                WasmlineLocalPackageResolution.selectArtifact(
                    listOf(matching.copy(targetCompilerVersion = compilerVersion)),
                    target,
                ),
            )
        }
        listOf(null, "47.0", INCOMPATIBLE_WASMTIME_VERSION).forEach { runtimeVersion ->
            assertNull(
                WasmlineLocalPackageResolution.selectArtifact(
                    listOf(matching),
                    target.copy(wasmtimeVersion = runtimeVersion),
                ),
            )
        }
        assertEquals(matching, WasmlineLocalPackageResolution.selectArtifact(listOf(matching), target))
    }

    @Test
    fun `incompatible version candidate is skipped before winner selection`() {
        val incompatible = cwasmArtifact().copy(
            url = "plugin-old.cwasm",
            targetCompilerVersion = INCOMPATIBLE_COMPILER_VERSION,
        )
        val compatible = cwasmArtifact()

        assertEquals(
            compatible,
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(incompatible, compatible),
                nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.CRANELIFT),
            ),
        )
    }

    @Test
    fun `iOS rejects CWASM and selects Core and Component PWASM`() {
        val craneliftTarget = nativeTarget(
            os = "ios",
            cpu = "aarch64",
            backend = WasmlineNativeBackend.CRANELIFT,
        )
        val pulleyTarget = nativeTarget(
            os = "ios",
            cpu = "aarch64",
            backend = WasmlineNativeBackend.PULLEY,
        )

        listOf(WasmlineExecutionModel.CORE_WASM, WasmlineExecutionModel.COMPONENT_MODEL).forEach { executionModel ->
            val cwasm = cwasmArtifact(executionModel).copy(
                url = "plugin-ios.cwasm",
                targetCpu = "aarch64",
                targetOs = "ios",
            )
            val pwasm = pulleyArtifact(
                cpu = "pulley64",
                is64Bit = true,
                executionModel = executionModel,
            )

            assertNull(WasmlineLocalPackageResolution.selectArtifact(listOf(cwasm), craneliftTarget))
            assertEquals(pwasm, WasmlineLocalPackageResolution.selectArtifact(listOf(pwasm), pulleyTarget))
        }
    }

    @Test
    fun `selection rejects invalid physical type and invocation contracts`() {
        val browserTarget = WasmlineHostArtifactTarget(os = "browser", cpu = "wasmjs", is64Bit = true)
        val craneliftTarget = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.CRANELIFT,
        )
        val pulleyTarget = nativeTarget(
            os = "linux",
            cpu = "x86_64",
            backend = WasmlineNativeBackend.PULLEY,
        )
        val invalidCandidates = listOf(
            WasmlineArtifact(
                type = WasmlineArtifactType.WASM,
                url = "component-as-core.wasm",
                sha256 = "component-as-core",
                targetCpu = "wasmjs",
                targetOs = "browser",
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = "plugin/invoke",
            ) to browserTarget,
            WasmlineArtifact(
                type = WasmlineArtifactType.WASM,
                url = "browser-raw-export.wasm",
                sha256 = "browser-raw-export",
                targetCpu = "wasmjs",
                targetOs = "browser",
                invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                exportName = "add",
            ) to browserTarget,
            cwasmArtifact().copy(
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = "plugin/invoke",
            ) to craneliftTarget,
            cwasmArtifact().copy(
                invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                exportName = null,
            ) to craneliftTarget,
            pulleyArtifact(cpu = "pulley64", is64Bit = true).copy(
                invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                exportName = null,
            ) to pulleyTarget,
        )

        invalidCandidates.forEach { (artifact, target) ->
            assertNull(
                WasmlineLocalPackageResolution.selectArtifact(listOf(artifact), target),
                "${artifact.type}/${artifact.executionModel}/${artifact.invocationProtocol} must be ineligible.",
            )
        }
    }

    @Test
    fun `native AOT selection preserves Core raw export contracts`() {
        val cwasm = cwasmArtifact().copy(
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        )
        val pwasm = pulleyArtifact(cpu = "pulley64", is64Bit = true).copy(
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        )

        assertEquals(
            cwasm,
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(cwasm),
                nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.CRANELIFT),
            ),
        )
        assertEquals(
            pwasm,
            WasmlineLocalPackageResolution.selectArtifact(
                listOf(pwasm),
                nativeTarget(os = "linux", cpu = "x86_64", backend = WasmlineNativeBackend.PULLEY),
            ),
        )
    }

    @Test
    fun `native host rejects a raw Component Wasm artifact`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.COMPONENT_WASM,
                    url = "plugin.component.wasm",
                    sha256 = "component",
                    executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "plugin/invoke",
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "linux",
                cpu = "x86_64",
                is64Bit = true,
                nativeBackend = WasmlineNativeBackend.CRANELIFT,
            ),
        )

        assertNull(selected)
    }

    @Test
    fun `browser host rejects raw Component Wasm artifacts`() {
        val selected = WasmlineLocalPackageResolution.selectArtifact(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.COMPONENT_WASM,
                    url = "plugin.component.wasm",
                    sha256 = "component",
                    executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "plugin/invoke",
                ),
            ),
            target = WasmlineHostArtifactTarget(
                os = "browser",
                cpu = "wasmjs",
                is64Bit = true,
            ),
        )

        assertEquals(null, selected)
    }

    private fun requiredLiveFile(environmentName: String): File {
        val path = requireNotNull(System.getenv(environmentName)) {
            "$environmentName must be set when $LIVE_TESTS_ENV=1."
        }
        return File(path).also { file ->
            require(file.isFile) { "$environmentName does not point to a file: ${file.absolutePath}" }
        }
    }

    private fun cwasmArtifact(): WasmlineArtifact = cwasmArtifact(WasmlineExecutionModel.CORE_WASM)

    private fun contractArtifact(
        type: WasmlineArtifactType,
        executionModel: WasmlineExecutionModel,
        invocationProtocol: WasmlineInvocationProtocol,
    ): WasmlineArtifact {
        val exportName = if (invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE) null else "plugin/invoke"
        return when (type) {
            WasmlineArtifactType.WASM -> WasmlineArtifact(
                type = type,
                url = "contract.wasm",
                sha256 = "contract-wasm",
                targetCpu = "wasmjs",
                targetOs = "browser",
                executionModel = executionModel,
                invocationProtocol = invocationProtocol,
                exportName = exportName,
            )

            WasmlineArtifactType.CWASM -> cwasmArtifact(executionModel).copy(
                invocationProtocol = invocationProtocol,
                exportName = exportName,
            )

            WasmlineArtifactType.PWASM -> pulleyArtifact(
                cpu = "pulley64",
                is64Bit = true,
                executionModel = executionModel,
            ).copy(
                invocationProtocol = invocationProtocol,
                exportName = exportName,
            )

            WasmlineArtifactType.COMPONENT_WASM -> WasmlineArtifact(
                type = type,
                url = "contract.component.wasm",
                sha256 = "contract-component",
                executionModel = executionModel,
                invocationProtocol = invocationProtocol,
                exportName = exportName,
            )
        }
    }

    private fun cwasmArtifact(executionModel: WasmlineExecutionModel): WasmlineArtifact = WasmlineArtifact(
        type = WasmlineArtifactType.CWASM,
        url = if (executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            "plugin-component-x86_64-linux.cwasm"
        } else {
            "plugin-x86_64-linux.cwasm"
        },
        sha256 = "cwasm-$executionModel",
        targetCpu = "x86_64",
        targetOs = "linux",
        targetCompilerVersion = TEST_COMPILER_VERSION,
        is64Bit = true,
        executionModel = executionModel,
        invocationProtocol = invocationProtocol(executionModel),
        exportName = exportName(executionModel),
    )

    private fun pulleyArtifact(
        cpu: String,
        targetOs: String? = null,
        is64Bit: Boolean,
        executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM,
    ): WasmlineArtifact = WasmlineArtifact(
        type = WasmlineArtifactType.PWASM,
        url = if (executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            "plugin-component-$cpu.pwasm"
        } else {
            "plugin-$cpu.pwasm"
        },
        sha256 = "pwasm-$cpu-$executionModel",
        targetCpu = cpu,
        targetOs = targetOs,
        targetCompilerVersion = TEST_COMPILER_VERSION,
        is64Bit = is64Bit,
        executionModel = executionModel,
        invocationProtocol = invocationProtocol(executionModel),
        exportName = exportName(executionModel),
    )

    private fun nativeTarget(
        os: String,
        cpu: String,
        is64Bit: Boolean = true,
        backend: WasmlineNativeBackend,
        wasmtimeVersion: String? = TEST_WASMTIME_VERSION,
    ): WasmlineHostArtifactTarget = WasmlineHostArtifactTarget(
        os = os,
        cpu = cpu,
        is64Bit = is64Bit,
        nativeBackend = backend,
        wasmtimeVersion = wasmtimeVersion,
    )

    private fun invocationProtocol(executionModel: WasmlineExecutionModel): WasmlineInvocationProtocol =
        if (executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            WasmlineInvocationProtocol.COMPONENT_EXPORT
        } else {
            WasmlineInvocationProtocol.WASMLINE_SERVICE
        }

    private fun exportName(executionModel: WasmlineExecutionModel): String? =
        if (executionModel == WasmlineExecutionModel.COMPONENT_MODEL) "plugin/invoke" else null

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val COMPONENT_MANIFEST_ENV = "WASMLINE_TEST_COMPONENT_MANIFEST"
        const val COMPONENT_PRIVATE_KEY_ENV = "WASMLINE_TEST_COMPONENT_PRIVATE_KEY"
        const val TEST_WASMTIME_VERSION = "47.0.2"
        const val TEST_COMPILER_VERSION = "wasmtime-$TEST_WASMTIME_VERSION"
        const val INCOMPATIBLE_WASMTIME_VERSION = "0.0.0"
        const val INCOMPATIBLE_COMPILER_VERSION = "wasmtime-$INCOMPATIBLE_WASMTIME_VERSION"
    }
}
