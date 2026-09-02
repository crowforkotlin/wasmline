package crow.wasmline.test.wasmtime

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
import crow.wasmline.WasmlineRuntime
import crow.wasmline.internal.core.CoreWasmNativeCodec
import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
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
 * Tests native AOT direct invocations and raw artifact boundaries.
 *
 * Date: 2026-09-01
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
        val artifact = copyAotCoreFixture()
        try {
            val handle = loadAotCore(artifact)
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(RawValue.I32(2), RawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(RawValue.I32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(RawValue.I64(2), RawValue.I64(3)),
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

    /** Verifies a Pulley RAW_EXPORT fixture loads and invokes through the JVM native runtime. */
    @Test
    fun rawExportPulleyAotFixtureLoadsAndInvokes() {
        val artifact = NativeFixtureTestSupport.copy("raw-export-basic", WasmlineArtifactFormat.PWASM)
        try {
            assertEquals(WasmlineArtifactFormat.PWASM, coreAotFormat(artifact.name))
            val handle = loadAotCore(artifact)
            try {
                assertEquals(
                    listOf(RawValue.I32(5)),
                    invokeRawValues(handle, "add", RawValue.I32(2), RawValue.I32(3)),
                )
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    /** Verifies scalar values, multi-value results, and void results through the direct native RAW_EXPORT API. */
    @Test
    fun rawExportAotFixturePreservesScalarValuesAndResultShapes() {
        val artifact = copyAotCoreFixture()
        try {
            val handle = loadAotCore(artifact)
            try {
                assertEquals(
                    listOf(RawValue.I64(2_999_999_999L)),
                    invokeRawValues(handle, "add64", RawValue.I64(3_000_000_000L), RawValue.I64(-1L)),
                )
                assertEquals(
                    listOf(RawValue.F32(-1.25f)),
                    invokeRawValues(handle, "neg_f32", RawValue.F32(1.25f)),
                )

                val f64 = invokeRawValues(handle, "neg_f64", RawValue.F64(-0.0)).single() as RawValue.F64
                assertEquals(0.0, f64.value)
                assertEquals(0.0.toBits(), f64.value.toBits())

                assertEquals(
                    listOf(RawValue.I32(7), RawValue.I64(42L)),
                    invokeRawValues(handle, "pair", RawValue.I32(7)),
                )
                assertEquals(emptyList(), invokeRawValues(handle, "void", RawValue.I32(0)))

                val nan = invokeRawValues(handle, "neg_f32", RawValue.F32(Float.NaN)).single() as RawValue.F32
                assertTrue(nan.value.isNaN())
                val infinity = invokeRawValues(
                    handle,
                    "neg_f64",
                    RawValue.F64(Double.POSITIVE_INFINITY),
                ).single() as RawValue.F64
                assertEquals(
                    Double.NEGATIVE_INFINITY,
                    infinity.value,
                )
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
                        exportName = "call_host",
                        arguments = listOf(RawValue.I32(2), RawValue.I32(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.EXPORT_NOT_FOUND, implicitFailure.failure.code)

                basicHandle.close()
                val explicitArguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
                    WasmlineTypedInvocationCodec.encodeRawArguments(listOf(RawValue.I64(2), RawValue.I64(3))),
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
                        listOf(RawValue.I64(2_999_999_999L)),
                        assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                            session.invoke("add64", listOf(RawValue.I64(3_000_000_000L), RawValue.I64(-1L))),
                        ).value,
                    )
                    assertEquals(
                        listOf(RawValue.F32(-1.25f)),
                        assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                            session.invoke("neg_f32", listOf(RawValue.F32(1.25f))),
                        ).value,
                    )
                    val f64 = assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                        session.invoke("neg_f64", listOf(RawValue.F64(-0.0))),
                    ).value.single() as RawValue.F64
                    assertEquals(0.0, f64.value)
                    assertEquals(0.0.toBits(), f64.value.toBits())
                    assertEquals(
                        listOf(RawValue.I32(7), RawValue.I64(42L)),
                        assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                            session.invoke("pair", listOf(RawValue.I32(7))),
                        ).value,
                    )
                    assertEquals(
                        emptyList(),
                        assertIs<WasmlineCallResult.Success<List<RawValue>>>(
                            session.invoke("void", listOf(RawValue.I32(0))),
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
                    val source = byteArrayOf(99, 10, 11, 12, 88)
                    val destination = ByteArray(7) { -1 }
                    memory.writeFrom(source, sourceOffset = 1, destinationOffset = 48, length = 3).getOrThrow()
                    memory.readInto(destination, destinationOffset = 2, sourceOffset = 48, length = 3).getOrThrow()
                    assertContentEquals(byteArrayOf(-1, -1, 10, 11, 12, -1, -1), destination)
                    memory.readInto(destination, destinationOffset = destination.size, sourceOffset = 65_536, length = 0).getOrThrow()
                    assertEquals(
                        WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
                        assertIs<WasmlineCallResult.Failure>(
                            memory.writeFrom(source, sourceOffset = 4, destinationOffset = 48, length = 2),
                        ).failure.code,
                    )
                    assertEquals(
                        WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS,
                        assertIs<WasmlineCallResult.Failure>(
                            memory.readInto(destination, destinationOffset = 6, sourceOffset = 48, length = 2),
                        ).failure.code,
                    )
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
        val artifact = copyAotCoreFixture()
        val artifactFormat = coreAotFormat(artifact.name)
        val conflictingEngine = when (artifactFormat) {
            WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.PULLEY
            WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.CRANELIFT
            WasmlineArtifactFormat.RAW_WASM -> error("A native AOT fixture must be precompiled.")
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
                        arguments = listOf(RawValue.I32(2), RawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(RawValue.I32(5)), result.value.values)
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
                        arguments = listOf(RawValue.I32(2), RawValue.I32(3)),
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
                descriptor = nativeTestArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.CWASM,
                    runtime = runtime,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            val failure = assertIs<WasmlineLoadState.Failure>(state)
            assertEquals(WasmlineErrorCode.MODULE_FORMAT_INVALID, failure.failure.code)
            assertTrue(failure.failure.details?.decodeToString()?.contains("ELF") == true)
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
            val failure = assertIs<WasmlineLoadState.Failure>(state)
            assertEquals(WasmlineErrorCode.MODULE_FORMAT_INVALID, failure.failure.code)
            assertTrue(failure.failure.details?.decodeToString()?.contains("ELF") == true)
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
        return nativeTestArtifactDescriptor(
            path = path,
            artifactFormat = artifactFormat,
            runtime = runtime,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        )
    }

    private fun coreAotDescriptor(path: String, artifactFormat: WasmlineArtifactFormat): WasmlineArtifactDescriptor {
        val runtime = platformWasmlineRuntimeCapabilities()
        return nativeTestArtifactDescriptor(
            path = path,
            artifactFormat = artifactFormat,
            runtime = runtime,
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
            assertEquals(WasmlineErrorCode.ARTIFACT_NOT_COMPATIBLE, failure.failure.code)
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

    private fun copyAotComponentFixture(): File = NativeFixtureTestSupport.copy("component-direct")

    private fun copyAotCoreFixture(): File = NativeFixtureTestSupport.copy("raw-export-basic")

    private fun copyAotCoreImportFixture(): File = NativeFixtureTestSupport.copy("raw-export-import-memory")

    /** Invokes a direct raw export and returns its successful values. */
    private fun invokeRawValues(handle: crow.wasmline.Wasmline, exportName: String, vararg arguments: RawValue): List<RawValue> =
        assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
            handle.invokeRawResult(exportName, arguments.toList()),
        ).value.values

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
}
