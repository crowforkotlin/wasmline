package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import crow.wasmline.web.WebTestModule
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the public Web `CORE_WASM + RAW_EXPORT` session contract.
 *
 * The fixture intentionally contains only scalar Core Wasm values, one
 * synchronous function import, one linear memory, and one non-function export.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
class WebCoreWasmTest {

    @Test
    fun loadsRawBytesAndReflectsExports() {
        val loaded = load("web-core-reflection")
        try {
            val names = loaded.module.exports.map(RawExport::name)
            assertTrue("add" in names)
            assertTrue("add64" in names)
            assertTrue("pair" in names)
            assertEquals(RawExportKind.MEMORY, loaded.module.findExport("memory")?.kind)
            assertEquals(RawExportKind.GLOBAL, loaded.module.findExport("answer")?.kind)
            assertEquals(
                RawFunctionSignature(
                    parameters = listOf(RawValueType.I32, RawValueType.I32),
                    results = listOf(RawValueType.I32),
                ),
                loaded.module.findExport("add")?.signature,
            )
        } finally {
            loaded.module.close()
        }
    }

    @Test
    fun invokesAllScalarTypesAndResultShapes() {
        val loaded = load("web-core-values")
        try {
            val session = instantiate(loaded.module)
            try {
                assertEquals(
                    listOf(RawValue.I32(42)),
                    requireSuccess(session.invoke("add", listOf(RawValue.I32(19), RawValue.I32(23)))),
                )
                assertEquals(
                    listOf(RawValue.I64(2_999_999_999L)),
                    requireSuccess(session.invoke("add64", listOf(RawValue.I64(3_000_000_000L), RawValue.I64(-1L)))),
                )

                val f32 = requireSuccess(session.invoke("neg_f32", listOf(RawValue.F32(1.25f)))).single()
                assertEquals(-1.25f, (f32 as RawValue.F32).value)
                val f64 = requireSuccess(session.invoke("neg_f64", listOf(RawValue.F64(-0.0)))).single()
                val f64Value = (f64 as RawValue.F64).value
                assertEquals(0.0, f64Value)
                assertEquals(0.0.toBits(), f64Value.toBits())

                assertEquals(
                    listOf(RawValue.I32(7), RawValue.I64(42L)),
                    requireSuccess(session.invoke("pair", listOf(RawValue.I32(7)))),
                )
                assertEquals(emptyList(), requireSuccess(session.invoke("void", listOf(RawValue.I32(0)))))

                val nan = requireSuccess(session.invoke("neg_f32", listOf(RawValue.F32(Float.NaN)))).single()
                assertTrue((nan as RawValue.F32).value.isNaN())
                val infinity = requireSuccess(session.invoke("neg_f64", listOf(RawValue.F64(Double.POSITIVE_INFINITY)))).single()
                assertEquals(Double.NEGATIVE_INFINITY, (infinity as RawValue.F64).value)
            } finally {
                session.close()
            }
        } finally {
            loaded.module.close()
        }
    }

    @Test
    fun registersSynchronousImportBeforeInstantiationAndExposesMemory() {
        val loaded = load("web-core-import")
        var callbackSawMemory = false
        try {
            val session = instantiate(loaded.module) { context, values ->
                callbackSawMemory = context.memory != null
                context.memory?.write(0, byteArrayOf(9, 8, 7))?.throwOnFailure()
                WasmlineCallResult.Success(
                    listOf(
                        RawValue.I32(
                            (values[0] as RawValue.I32).value + (values[1] as RawValue.I32).value,
                        ),
                    ),
                )
            }
            try {
                assertEquals(
                    listOf(RawValue.I32(42)),
                    requireSuccess(session.invoke("call_host", listOf(RawValue.I32(6), RawValue.I32(36)))),
                )
                assertTrue(callbackSawMemory)
                assertContentEquals(byteArrayOf(9, 8, 7), requireNotNull(session.memory).read(0, 3).getOrThrow())
            } finally {
                session.close()
            }
        } finally {
            loaded.module.close()
        }
    }

    @Test
    fun rejectsMissingExtraAndMismatchedImports() {
        val missing = load("web-core-missing-import")
        try {
            val failure = requireFailure(missing.module.instantiate())
            assertEquals(WasmlineErrorCode.IMPORT_MISSING, failure.code)
        } finally {
            missing.module.close()
        }

        val extra = load("web-core-extra-import")
        try {
            val failure = requireFailure(
                extra.module.instantiate(
                    CoreWasmSessionOptions(imports = listOf(defaultImport(), extraImport())),
                ),
            )
            assertEquals(WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH, failure.code)
        } finally {
            extra.module.close()
        }

        val mismatch = load("web-core-signature-mismatch")
        try {
            val failure = requireFailure(
                mismatch.module.instantiate(
                    CoreWasmSessionOptions(
                        imports = listOf(
                            defaultImport(
                                signature = RawFunctionSignature(
                                    parameters = listOf(RawValueType.I64, RawValueType.I64),
                                    results = listOf(RawValueType.I64),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            assertEquals(WasmlineErrorCode.IMPORT_SIGNATURE_MISMATCH, failure.code)
        } finally {
            mismatch.module.close()
        }
    }

    @Test
    fun validatesArgumentsExportsTrapsAndMemoryBounds() {
        val loaded = load("web-core-errors")
        try {
            val session = instantiate(loaded.module)
            try {
                assertEquals(
                    WasmlineErrorCode.ARGUMENT_COUNT_MISMATCH,
                    requireFailure(session.invoke("add", listOf(RawValue.I32(1)))).code,
                )
                assertEquals(
                    WasmlineErrorCode.ARGUMENT_TYPE_MISMATCH,
                    requireFailure(session.invoke("add", listOf(RawValue.I64(1), RawValue.I64(2)))).code,
                )
                assertEquals(
                    WasmlineErrorCode.EXPORT_NOT_FOUND,
                    requireFailure(session.invoke("missing")).code,
                )
                assertEquals(
                    WasmlineErrorCode.EXPORT_KIND_MISMATCH,
                    requireFailure(session.invoke("memory")).code,
                )
                assertEquals(
                    WasmlineErrorCode.WASM_TRAP,
                    requireFailure(session.invoke("trap")).code,
                )

                val memory = assertNotNull(session.memory)
                assertEquals(65_536L, memory.byteSize().getOrThrow())
                assertEquals(1L, memory.pageCount().getOrThrow())
                memory.write(32, byteArrayOf(1, 2, 3)).getOrThrow()
                assertContentEquals(byteArrayOf(1, 2, 3), memory.read(32, 3).getOrThrow())
                assertEquals(WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS, requireFailure(memory.read(-1, 1)).code)
                assertEquals(WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS, requireFailure(memory.read(65_535, 2)).code)
                assertEquals(WasmlineErrorCode.MEMORY_OUT_OF_BOUNDS, requireFailure(memory.write(65_536, byteArrayOf(1))).code)
                assertEquals(1L, memory.grow(1).getOrThrow())
                assertEquals(131_072L, memory.byteSize().getOrThrow())
                memory.write(65_536, byteArrayOf(4)).getOrThrow()
                assertContentEquals(byteArrayOf(4), memory.read(65_536, 1).getOrThrow())
            } finally {
                session.close()
            }
        } finally {
            loaded.module.close()
        }
    }

    @Test
    fun mapsImportHandlerFailureAndCloseLifecycle() {
        val loaded = load("web-core-lifecycle")
        try {
            val session = instantiate(loaded.module) { _, _ ->
                WasmlineCallResult.Failure(
                    WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "handler failed"),
                )
            }
            assertEquals(
                WasmlineErrorCode.IMPORT_HANDLER_FAILED,
                requireFailure(session.invoke("call_host", listOf(RawValue.I32(1), RawValue.I32(2)))).code,
            )
            session.close()
            session.close()
            assertTrue(session.isClosed)
            assertEquals(WasmlineErrorCode.SESSION_CLOSED, requireFailure(session.invoke("add")).code)
            loaded.module.close()
            loaded.module.close()
            assertTrue(loaded.module.isClosed)
            assertEquals(WasmlineErrorCode.SESSION_CLOSED, requireFailure(loaded.module.instantiate()).code)
        } finally {
            loaded.module.close()
        }
    }

    private fun load(key: String): LoadedModule {
        WasmlineWeb.registerBytes(key, WebTestModule.bytes())
        val state = platformWasmlineLoadArtifact(
            descriptor = descriptor(key),
            config = WasmlineConfig(supportConcurrent = false),
        )
        val handle = assertIs<WasmlineLoadState.Success>(state).wasmline
        val module = requireSuccess(handle.asCoreWasmModule())
        return LoadedModule(module)
    }

    private fun instantiate(
        module: CoreWasmModule,
        handler: (RawImportContext, List<RawValue>) -> WasmlineCallResult<List<RawValue>> = { _, values ->
            WasmlineCallResult.Success(
                listOf(
                    RawValue.I32(
                        (values[0] as RawValue.I32).value + (values[1] as RawValue.I32).value,
                    ),
                ),
            )
        },
    ): CoreWasmSession = requireSuccess(
        module.instantiate(
            CoreWasmSessionOptions(
                imports = listOf(defaultImport(handler = handler)),
            ),
        ),
    )

    private fun defaultImport(
        signature: RawFunctionSignature = HOST_SIGNATURE,
        handler: (RawImportContext, List<RawValue>) -> WasmlineCallResult<List<RawValue>> = { _, values ->
            WasmlineCallResult.Success(
                listOf(
                    RawValue.I32(
                        (values[0] as RawValue.I32).value + (values[1] as RawValue.I32).value,
                    ),
                ),
            )
        },
    ): RawImport = RawImport(WebTestModule.HOST_MODULE, WebTestModule.HOST_FUNCTION, signature, handler)

    private fun extraImport(): RawImport = RawImport(
        module = "env",
        name = "extra",
        signature = RawFunctionSignature(),
        handler = { _, _ -> WasmlineCallResult.Success(emptyList()) },
    )

    private fun descriptor(key: String): WasmlineArtifactDescriptor = WasmlineArtifactDescriptor(
        path = key,
        artifactFormat = WasmlineArtifactFormat.RAW_WASM,
        executionModel = WasmlineExecutionModel.CORE_WASM,
        invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        rawAbi = RawAbiMetadata(
            exports = listOf(
                RawExport(
                    "add",
                    RawExportKind.FUNCTION,
                    RawFunctionSignature(listOf(RawValueType.I32, RawValueType.I32), listOf(RawValueType.I32)),
                ),
                RawExport(
                    "add64",
                    RawExportKind.FUNCTION,
                    RawFunctionSignature(listOf(RawValueType.I64, RawValueType.I64), listOf(RawValueType.I64)),
                ),
                RawExport("neg_f32", RawExportKind.FUNCTION, RawFunctionSignature(listOf(RawValueType.F32), listOf(RawValueType.F32))),
                RawExport("neg_f64", RawExportKind.FUNCTION, RawFunctionSignature(listOf(RawValueType.F64), listOf(RawValueType.F64))),
                RawExport(
                    "pair",
                    RawExportKind.FUNCTION,
                    RawFunctionSignature(listOf(RawValueType.I32), listOf(RawValueType.I32, RawValueType.I64)),
                ),
                RawExport("void", RawExportKind.FUNCTION, RawFunctionSignature(listOf(RawValueType.I32), emptyList())),
                RawExport("trap", RawExportKind.FUNCTION, RawFunctionSignature()),
                RawExport("call_host", RawExportKind.FUNCTION, HOST_SIGNATURE),
                RawExport("memory", RawExportKind.MEMORY),
                RawExport("answer", RawExportKind.GLOBAL),
            ),
            imports = listOf(RawImportDeclaration(WebTestModule.HOST_MODULE, WebTestModule.HOST_FUNCTION, HOST_SIGNATURE)),
        ),
    )

    private fun <T> requireSuccess(result: WasmlineCallResult<T>): T = assertIs<WasmlineCallResult.Success<T>>(result).value

    private fun requireFailure(result: WasmlineCallResult<*>): WasmlineFailure = assertIs<WasmlineCallResult.Failure>(result).failure

    /**
     * Owns a Core Wasm module loaded for one Web conformance test.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property module Loaded module under test.
     */
    private data class LoadedModule(val module: CoreWasmModule)

    /**
     * Defines the shared host function signature used by the Web fixture.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    private companion object {
        val HOST_SIGNATURE = RawFunctionSignature(
            parameters = listOf(RawValueType.I32, RawValueType.I32),
            results = listOf(RawValueType.I32),
        )
    }
}
