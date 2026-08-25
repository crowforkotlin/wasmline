package crow.wasmline.test.wasmtime

import crow.wasmline.CoreWasmNativeCodec
import crow.wasmline.CoreWasmSessionOptions
import crow.wasmline.JniWasmlineBindings
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawImport
import crow.wasmline.RawValue
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineRawCallResult
import crow.wasmline.WasmlineRawValue
import crow.wasmline.WasmlineRuntime
import crow.wasmline.WasmlineTypedInvocationCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import crow.wasmline.invokeComponentResult
import crow.wasmline.invokeRawResult
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import crow.wasmline.wasmlineAotLoadPathDiagnostics
import crow.wasmline.wasmlineResetAotLoadPathDiagnostics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests direct typed Component AOT calls and native raw artifact boundaries.
 *
 * Verifies direct typed Component AOT calls and native raw Core/Component rejection boundaries.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class NativeDirectInvocationTest {

    @Test
    fun rawExportFixturesRequirePrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, coreAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, coreAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            coreAotFormat("fixture.wasm")
        }
    }

    @Test
    fun rawExportAotFixtureReturnsValuesAndRecoverableFailures() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotCoreFixture()
        try {
            val handle = loadAotCore(artifact)
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineRawValue.I32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I64(2), WasmlineRawValue.I64(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.ARGUMENT_TYPE_MISMATCH, typeFailure.failure.code)

                val missingFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(exportName = "missing"),
                )
                assertEquals(WasmlineErrorCode.EXPORT_NOT_FOUND, missingFailure.failure.code)

                val trapFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(exportName = "trap"),
                )
                assertEquals(WasmlineErrorCode.WASM_TRAP, trapFailure.failure.code)
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    /** Verifies explicit session keys cannot alias implicit artifact sessions. */
    @Test
    fun explicitRawSessionKeyDoesNotAliasAnotherArtifact() {
        if (!liveTestsEnabled()) return

        val importedArtifact = copyAotCoreImportFixture()
        val basicArtifact = copyAotCoreFixture()
        try {
            val importedHandle = loadAotCore(importedArtifact)
            val basicHandle = loadAotCore(basicArtifact)
            var explicitCreated = false
            try {
                val importSignature = RawFunctionSignature(
                    parameters = listOf(RawValueType.I32, RawValueType.I32),
                    results = listOf(RawValueType.I32),
                )
                val dispatcher = object {
                    @Suppress("UNUSED_PARAMETER")
                    fun dispatchRaw(module: String, name: String, arguments: ByteArray): ByteArray = byteArrayOf()
                }
                val createdCarrier = requireNotNull(
                    JniWasmlineBindings.coreCreateSession(
                        artifactKey = importedArtifact.path,
                        sessionKey = basicArtifact.path,
                        imports = CoreWasmNativeCodec.encodeImports(listOf(rawImport(importSignature))),
                        dispatcher = dispatcher,
                        memoryExportName = "memory",
                    ),
                )
                assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                    WasmlineTypedInvocationCodec.decodeRawValues(createdCarrier),
                )
                explicitCreated = true

                val implicitFailure = assertIs<WasmlineCallResult.Failure>(
                    basicHandle.invokeRawResult(
                        exportName = "add64",
                        arguments = listOf(WasmlineRawValue.I64(2), WasmlineRawValue.I64(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.EXPORT_NOT_FOUND, implicitFailure.failure.code)

                basicHandle.close()
                val explicitArguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
                    WasmlineTypedInvocationCodec.encodeRawValues(listOf(RawValue.I64(2), RawValue.I64(3))),
                ).value
                val explicitCarrier = requireNotNull(
                    JniWasmlineBindings.coreInvoke(basicArtifact.path, "add64", explicitArguments),
                )
                assertEquals(
                    listOf(RawValue.I64(5)),
                    assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                        WasmlineTypedInvocationCodec.decodeRawValues(explicitCarrier),
                    ).value,
                )
            } finally {
                if (explicitCreated) JniWasmlineBindings.coreReleaseSession(basicArtifact.path)
                basicHandle.close()
                importedHandle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            basicArtifact.delete()
            importedArtifact.delete()
        }
    }

    /** Exercises the Core Wasm module/session contract with a synchronous host import. */
    @Test
    fun coreSessionConformanceCoversImportsMemoryErrorsAndLifecycle() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotCoreImportFixture()
        try {
            val handle = loadAotCore(artifact)
            val module = assertIs<WasmlineCallResult.Success<crow.wasmline.CoreWasmModule>>(
                handle.asCoreWasmModule(),
            ).value
            try {
                assertEquals(WasmlineArtifactFormat.CWASM, coreAotFormat(artifact.name))
                assertEquals(crow.wasmline.RawExportKind.FUNCTION, module.findExport("add")?.kind)
                assertEquals(crow.wasmline.RawExportKind.MEMORY, module.findExport("memory")?.kind)

                val importSignature = RawFunctionSignature(
                    parameters = listOf(RawValueType.I32, RawValueType.I32),
                    results = listOf(RawValueType.I32),
                )
                val missing = assertIs<WasmlineCallResult.Failure>(module.instantiate())
                assertEquals(WasmlineErrorCode.IMPORT_MISSING, missing.failure.code)

                val extra = assertIs<WasmlineCallResult.Failure>(
                    module.instantiate(
                        CoreWasmSessionOptions(
                            imports = listOf(
                                rawImport(importSignature),
                                RawImport("env", "extra", RawFunctionSignature()) { _, _ ->
                                    WasmlineCallResult.Success(emptyList())
                                },
                            ),
                        ),
                    ),
                )
                assertEquals(WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH, extra.failure.code)

                val mismatch = assertIs<WasmlineCallResult.Failure>(
                    module.instantiate(
                        CoreWasmSessionOptions(
                            imports = listOf(
                                rawImport(
                                    RawFunctionSignature(
                                        parameters = listOf(RawValueType.I64, RawValueType.I64),
                                        results = listOf(RawValueType.I64),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
                assertEquals(WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH, mismatch.failure.code)

                var callbackMemorySeen = false
                var callbackReentry: WasmlineFailure? = null
                val session = assertIs<WasmlineCallResult.Success<crow.wasmline.CoreWasmSession>>(
                    module.instantiate(
                        CoreWasmSessionOptions(
                            imports = listOf(
                                rawImport(importSignature) { context, values ->
                                    callbackMemorySeen = context.memory != null
                                    context.memory?.write(0, byteArrayOf(4, 5, 6))?.throwOnFailure()
                                    callbackReentry = assertIs<WasmlineCallResult.Failure>(
                                        context.session.invoke("add", listOf(RawValue.I32(1), RawValue.I32(2))),
                                    ).failure
                                    WasmlineCallResult.Success(
                                        listOf(
                                            RawValue.I32(
                                                (values[0] as RawValue.I32).value + (values[1] as RawValue.I32).value,
                                            ),
                                        ),
                                    )
                                },
                            ),
                        ),
                    ),
                ).value
                try {
                    assertEquals(
                        listOf(RawValue.I32(5)),
                        assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                            session.invoke("add", listOf(RawValue.I32(2), RawValue.I32(3))),
                        ).value,
                    )
                    assertEquals(
                        listOf(RawValue.I32(42)),
                        assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                            session.invoke("call_host", listOf(RawValue.I32(6), RawValue.I32(36))),
                        ).value,
                    )
                    assertTrue(callbackMemorySeen)
                    assertEquals(WasmlineErrorCode.REENTRANT_CALL, callbackReentry?.code)

                    val memory = requireNotNull(session.memory)
                    assertEquals(65_536L, memory.byteSize().getOrThrow())
                    assertEquals(1L, memory.pageCount().getOrThrow())
                    assertContentEquals(byteArrayOf(4, 5, 6), memory.read(0, 3).getOrThrow())
                    assertEquals(
                        WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
                        assertIs<WasmlineCallResult.Failure>(memory.read(-1, 1)).failure.code,
                    )
                    assertEquals(
                        WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
                        assertIs<WasmlineCallResult.Failure>(memory.read(65_535, 2)).failure.code,
                    )
                    assertEquals(1L, memory.grow(1).getOrThrow())
                    memory.write(65_536, byteArrayOf(9)).getOrThrow()
                    assertContentEquals(byteArrayOf(9), memory.read(65_536, 1).getOrThrow())

                    assertEquals(
                        WasmlineErrorCode.ARGUMENT_COUNT_MISMATCH,
                        assertIs<WasmlineCallResult.Failure>(session.invoke("add", listOf(RawValue.I32(1)))).failure.code,
                    )
                    assertEquals(
                        WasmlineErrorCode.ARGUMENT_TYPE_MISMATCH,
                        assertIs<WasmlineCallResult.Failure>(session.invoke("add", listOf(RawValue.I64(1), RawValue.I64(2)))).failure.code,
                    )
                    assertEquals(
                        WasmlineErrorCode.EXPORT_NOT_FOUND,
                        assertIs<WasmlineCallResult.Failure>(session.invoke("missing")).failure.code,
                    )
                    assertEquals(
                        WasmlineErrorCode.EXPORT_KIND_MISMATCH,
                        assertIs<WasmlineCallResult.Failure>(session.invoke("memory")).failure.code,
                    )
                    assertEquals(WasmlineErrorCode.WASM_TRAP, assertIs<WasmlineCallResult.Failure>(session.invoke("trap")).failure.code)
                } finally {
                    session.close()
                    session.close()
                }
                assertTrue(session.isClosed)
                assertEquals(WasmlineErrorCode.SESSION_CLOSED, assertIs<WasmlineCallResult.Failure>(session.invoke("add")).failure.code)
            } finally {
                module.close()
                module.close()
            }
            assertTrue(module.isClosed)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    /** Verifies a failing synchronous import is surfaced as IMPORT_HANDLER_FAILED. */
    @Test
    fun coreSessionMapsImportHandlerFailure() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotCoreImportFixture()
        try {
            val handle = loadAotCore(artifact)
            val module = assertIs<WasmlineCallResult.Success<crow.wasmline.CoreWasmModule>>(handle.asCoreWasmModule()).value
            try {
                val session = assertIs<WasmlineCallResult.Success<crow.wasmline.CoreWasmSession>>(
                    module.instantiate(
                        listOf(
                            rawImport { _, _ ->
                                WasmlineCallResult.Failure(WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "host rejected"))
                            },
                        ),
                    ),
                ).value
                try {
                    val failure = assertIs<WasmlineCallResult.Failure>(
                        session.invoke("call_host", listOf(RawValue.I32(1), RawValue.I32(2))),
                    )
                    assertEquals(WasmlineErrorCode.IMPORT_HANDLER_FAILED, failure.failure.code)
                } finally {
                    session.close()
                }
            } finally {
                module.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun conflictingWarmUpPreservesTheLoadedArtifact() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotCoreFixture()
        val artifactFormat = coreAotFormat(artifact.name)
        val conflictingEngine = when (artifactFormat) {
            WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.PULLEY
            WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.CRANELIFT
            WasmlineArtifactFormat.RAW_WASM -> error("A live native fixture must be precompiled.")
        }
        try {
            val handle = loadAotCore(artifact)
            try {
                assertFailsWith<IllegalStateException> {
                    WasmlineRuntime.warmUp(conflictingEngine)
                }

                val result = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineRawValue.I32(5)), result.value.values)
            } finally {
                handle.close()
            }

            WasmlineRuntime.warmUp(conflictingEngine)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun directTypedComponentFixturesRequirePrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, componentAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, componentAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            componentAotFormat("fixture.wasm")
        }
    }

    @Test
    fun componentAotExportLoadsWithoutWitAndConvertsValues() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotComponentFixture()
        try {
            val handle = loadAotComponent(artifact)
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    handle.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.S32(2), WasmlineComponentValue.S32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineComponentValue.S32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.StringValue("2"), WasmlineComponentValue.S32(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, typeFailure.failure.code)

                val missingFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(exportName = "missing"),
                )
                assertEquals(WasmlineErrorCode.COMPONENT_EXPORT_NOT_FOUND, missingFailure.failure.code)

                val trapFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(exportName = "trap"),
                )
                assertEquals(WasmlineErrorCode.COMPONENT_TRAP, trapFailure.failure.code)
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun successfulCoreAndComponentAotLoadsUseDeserializeWithoutRawCompilation() {
        if (!liveTestsEnabled()) return

        val coreArtifact = copyAotCoreFixture()
        val componentArtifact = copyAotComponentFixture()
        try {
            WasmlineRuntime.shutdown()
            wasmlineResetAotLoadPathDiagnostics()

            val core = loadAotCore(coreArtifact)
            try {
                assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    core.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
            } finally {
                core.close()
            }

            val component = loadAotComponent(componentArtifact)
            try {
                assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    component.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.S32(2), WasmlineComponentValue.S32(3)),
                    ),
                )
            } finally {
                component.close()
            }

            val diagnostics = wasmlineAotLoadPathDiagnostics()
            assertEquals(1, diagnostics.coreDeserializeSuccesses)
            assertEquals(1, diagnostics.componentDeserializeSuccesses)
            assertEquals(0, diagnostics.moduleNewCalls)
            assertEquals(0, diagnostics.componentNewCalls)
        } finally {
            WasmlineRuntime.shutdown()
            coreArtifact.delete()
            componentArtifact.delete()
        }
    }

    @Test
    fun rawCoreArtifactIsRejectedByBothNativeLoadingModes() {
        val artifact = createRawFixture()
        try {
            assertRawNativeArtifactIsRejectedByBothLoadingModes(
                WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
            )
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun coreCwasmFormatDoesNotCompileRawWasmBytes() {
        val artifact = createRawFixture()
        val runtime = platformWasmlineRuntimeCapabilities()
        try {
            val state = platformWasmlineLoadArtifact(
                descriptor = WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.CWASM,
                    targetCpu = runtime.targetCpu,
                    targetOs = runtime.targetOs,
                    targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
                    is64Bit = runtime.is64Bit,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            assertIs<WasmlineLoadState.Failure>(state)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun rawComponentArtifactIsRejectedByBothNativeLoadingModes() {
        val artifact = copyComponentFixture()
        try {
            assertRawNativeArtifactIsRejectedByBothLoadingModes(
                WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                    executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "add",
                ),
            )
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun componentCwasmFormatDoesNotCompileRawWasmBytes() {
        val artifact = copyComponentFixture()
        try {
            val state = platformWasmlineLoadArtifact(
                descriptor = componentAotDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.CWASM,
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            assertIs<WasmlineLoadState.Failure>(state)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    private fun loadAotComponent(artifact: File): crow.wasmline.Wasmline {
        val state = platformWasmlineLoadArtifact(
            descriptor = componentAotDescriptor(
                path = artifact.absolutePath,
                artifactFormat = componentAotFormat(artifact.name),
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun loadAotCore(artifact: File): crow.wasmline.Wasmline {
        val state = platformWasmlineLoadArtifact(
            descriptor = coreAotDescriptor(
                path = artifact.absolutePath,
                artifactFormat = coreAotFormat(artifact.name),
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun componentAotDescriptor(path: String, artifactFormat: WasmlineArtifactFormat): WasmlineArtifactDescriptor {
        val runtime = platformWasmlineRuntimeCapabilities()
        return WasmlineArtifactDescriptor(
            path = path,
            artifactFormat = artifactFormat,
            targetCpu = targetCpuFor(artifactFormat, runtime.is64Bit, runtime.targetCpu),
            targetOs = targetOsFor(artifactFormat, runtime.targetOs),
            targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
            is64Bit = runtime.is64Bit,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        )
    }

    private fun coreAotDescriptor(path: String, artifactFormat: WasmlineArtifactFormat): WasmlineArtifactDescriptor {
        val runtime = platformWasmlineRuntimeCapabilities()
        return WasmlineArtifactDescriptor(
            path = path,
            artifactFormat = artifactFormat,
            targetCpu = targetCpuFor(artifactFormat, runtime.is64Bit, runtime.targetCpu),
            targetOs = targetOsFor(artifactFormat, runtime.targetOs),
            targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
            is64Bit = runtime.is64Bit,
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        )
    }

    private fun assertRawNativeArtifactIsRejectedByBothLoadingModes(descriptor: WasmlineArtifactDescriptor) {
        listOf(false, true).forEach { supportConcurrent ->
            val state = platformWasmlineLoadArtifact(
                descriptor = descriptor,
                config = WasmlineConfig(supportConcurrent = supportConcurrent),
            )
            val failure = assertIs<WasmlineLoadState.Failure>(state)
            assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        }
    }

    private fun createRawFixture(): File = File.createTempFile("wasmline-raw-export-", ".wasm").apply {
        writeBytes(
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
                0x01, 0x0B, 0x02, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F, 0x60, 0x00, 0x01, 0x7F,
                0x03, 0x03, 0x02, 0x00, 0x01,
                0x07, 0x0E, 0x02, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x04, 0x74, 0x72, 0x61, 0x70, 0x00, 0x01,
                0x0A, 0x0D, 0x02, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B, 0x03, 0x00, 0x00, 0x0B,
            ),
        )
        deleteOnExit()
    }

    private fun copyComponentFixture(): File {
        val destination = File.createTempFile("wasmline-component-export-", ".wasm")
        NativeDirectInvocationTest::class.java.getResourceAsStream("/fixtures/component-export.wasm").use { input ->
            requireNotNull(input) { "Component fixture resource is missing." }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        destination.deleteOnExit()
        return destination
    }

    private fun copyAotComponentFixture(): File {
        val source = requireNotNull(System.getenv(DIRECT_COMPONENT_AOT_FIXTURE_ENV)) {
            "$DIRECT_COMPONENT_AOT_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$DIRECT_COMPONENT_AOT_FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val destination = File.createTempFile("wasmline-component-direct-", componentAotFormat(source.name).fileSuffix())
        source.copyTo(destination, overwrite = true)
        destination.deleteOnExit()
        return destination
    }

    private fun copyAotCoreFixture(): File {
        val source = requireNotNull(System.getenv(RAW_EXPORT_AOT_FIXTURE_ENV)) {
            "$RAW_EXPORT_AOT_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$RAW_EXPORT_AOT_FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val destination = File.createTempFile("wasmline-raw-export-aot-", coreAotFormat(source.name).fileSuffix())
        source.copyTo(destination, overwrite = true)
        destination.deleteOnExit()
        return destination
    }

    private fun copyAotCoreImportFixture(): File {
        val source = requireNotNull(System.getenv(RAW_EXPORT_IMPORT_AOT_FIXTURE_ENV)) {
            "$RAW_EXPORT_IMPORT_AOT_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$RAW_EXPORT_IMPORT_AOT_FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val destination = File.createTempFile("wasmline-raw-export-import-aot-", coreAotFormat(source.name).fileSuffix())
        source.copyTo(destination, overwrite = true)
        destination.deleteOnExit()
        return destination
    }

    private fun rawImport(
        signature: RawFunctionSignature = RawFunctionSignature(
            parameters = listOf(RawValueType.I32, RawValueType.I32),
            results = listOf(RawValueType.I32),
        ),
        handler: (crow.wasmline.RawImportContext, List<RawValue>) -> WasmlineCallResult<List<RawValue>> = { _, values ->
            WasmlineCallResult.Success(
                listOf(
                    RawValue.I32(
                        (values[0] as RawValue.I32).value + (values[1] as RawValue.I32).value,
                    ),
                ),
            )
        },
    ): RawImport = RawImport("env", "host_add", signature, handler)

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Direct Component fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun coreAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Core RAW_EXPORT fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun WasmlineArtifactFormat.fileSuffix(): String = when (this) {
        WasmlineArtifactFormat.CWASM -> ".cwasm"
        WasmlineArtifactFormat.PWASM -> ".pwasm"
        WasmlineArtifactFormat.RAW_WASM -> error("Direct Component fixtures cannot use raw Wasm.")
    }

    private fun targetCpuFor(artifactFormat: WasmlineArtifactFormat, is64Bit: Boolean, runtimeCpu: String): String = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeCpu
        WasmlineArtifactFormat.PWASM -> if (is64Bit) "pulley64" else "pulley32"
        WasmlineArtifactFormat.RAW_WASM -> error("Direct Component fixtures cannot use raw Wasm.")
    }

    private fun targetOsFor(artifactFormat: WasmlineArtifactFormat, runtimeOs: String): String? = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeOs
        WasmlineArtifactFormat.PWASM -> null
        WasmlineArtifactFormat.RAW_WASM -> error("Direct Component fixtures cannot use raw Wasm.")
    }

    private fun liveTestsEnabled(): Boolean = System.getenv(LIVE_TESTS_ENV) == "1"

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val DIRECT_COMPONENT_AOT_FIXTURE_ENV = "WASMLINE_TEST_DIRECT_COMPONENT_AOT"
        const val RAW_EXPORT_AOT_FIXTURE_ENV = "WASMLINE_TEST_RAW_EXPORT_AOT"
        const val RAW_EXPORT_IMPORT_AOT_FIXTURE_ENV = "WASMLINE_TEST_RAW_EXPORT_IMPORT_AOT"
    }
}
