package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentExport
import crow.wasmline.WasmlineComponentHostRegistry
import crow.wasmline.WasmlineComponentHostResourceBinding
import crow.wasmline.WasmlineComponentInstance
import crow.wasmline.WasmlineComponentInterfaceId
import crow.wasmline.WasmlineComponentResourceId
import crow.wasmline.WasmlineComponentResourceOwnership
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineGuestComponentResource
import crow.wasmline.WasmlineHostComponentResource
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.component
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineRuntimeCapabilities
import crow.wasmline.wasmlineShutdown
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies Component Model resource ownership through a Rust wit-bindgen guest.
 *
 * Runs only when the external precompiled Component fixture is explicitly supplied.
 *
 * Date: 2026-08-12
 * Author: crowforkotlin
 */
class NativeComponentResourceIntegrationTest {
    @Test
    fun guestOwnedResourceSupportsBorrowMethodsMoveAndDeterministicDrop() = withResourceModule { module ->
        val instance = module.component().instantiate()
        try {
            val baseline = instance.dropCount()
            val counter = instance.createCounter(10u)

            assertEquals(10u, counter.get())
            assertEquals(15u, counter.add(5u))
            assertEquals(15u, instance.inspect(counter))

            val returned = instance.roundTrip(counter)
            assertTrue(counter.isClosed, "Passing own<counter> must consume the original Host wrapper.")
            assertFailsWith<IllegalStateException> { counter.get() }
            assertEquals(15u, returned.get())

            returned.close()
            returned.close()
            assertTrue(returned.isClosed)
            assertEquals(baseline + 1u, instance.dropCount())
            assertFailsWith<IllegalStateException> { returned.get() }
        } finally {
            instance.close()
        }
    }

    @Test
    fun hostCallbackResourceSupportsBorrowAndOwnDestruction() = withResourceModule { module ->
        val dropped = mutableListOf<CallbackImplementation>()
        val instance = module.component().instantiate { bindImports(callbackRegistry(dropped)) }
        try {
            val counter = instance.createCounter(21u)
            val borrowedCallback = instance.createCallback(CallbackImplementation(3u))
            assertEquals(63u, instance.callbackWithBorrow(borrowedCallback, counter))
            assertEquals(1, instance.resourceDiagnostics().activeHostResources)
            assertEquals(0, dropped.size)

            borrowedCallback.close()
            assertEquals(1, dropped.size)
            assertEquals(0, instance.resourceDiagnostics().activeHostResources)

            val ownedCallback = instance.createCallback(CallbackImplementation(2u))
            assertEquals(18u, instance.consumeCallback(ownedCallback, 9u))
            assertTrue(ownedCallback.isClosed, "Passing own<callback> must consume the Host wrapper.")
            assertEquals(2, dropped.size)
            assertEquals(0, instance.resourceDiagnostics().activeHostResources)
            counter.close()
        } finally {
            instance.close()
        }
    }

    @Test
    fun trapReleasesBorrowAndCrossInstanceCarriersFailWithoutNativeCrash() = withResourceModule { module ->
        val first = module.component().instantiate()
        val second = module.component().instantiate()
        try {
            val counter = first.createCounter(31u)
            val crossInstance = assertIs<WasmlineCallResult.Failure>(
                second.invoke(INSPECT, listOf(counter.toComponentValue())),
            )
            assertEquals(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, crossInstance.error.code)
            assertTrue(crossInstance.error.message.isNotBlank())

            val reference = counter.toComponentValue()
            val stale = assertIs<WasmlineCallResult.Failure>(
                first.invoke(INSPECT, listOf(reference.withGeneration(reference.generation + 1u))),
            )
            assertEquals(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, stale.error.code)
            val wrongType = assertIs<WasmlineCallResult.Failure>(
                first.invoke(INSPECT, listOf(reference.withType(reference.typeId + 1u))),
            )
            assertEquals(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, wrongType.error.code)

            val borrowed = BorrowedCounter(first, reference.withOwnership(WasmlineComponentResourceOwnership.BORROW))
            assertFailsWith<IllegalStateException> { borrowed.close() }
            assertTrue(!borrowed.isClosed)
            assertEquals(31u, counter.get(), "A rejected cross-instance carrier must not consume its owner.")

            val trap = assertIs<WasmlineCallResult.Failure>(
                first.invoke(TRAP_WITH_BORROW, listOf(counter.toComponentValue())),
            )
            assertEquals(WasmlineErrorCode.COMPONENT_TRAP, trap.error.code)
            assertTrue(trap.error.message.isNotBlank())
            assertTrue(!counter.isClosed, "Passing borrow<counter> must not consume the owner wrapper.")

            val statelessAfterTrap = first.invoke(COUNTER_DROP_COUNT)
            val poisoned = assertIs<WasmlineCallResult.Failure>(statelessAfterTrap)
            assertTrue(
                poisoned.error.message.contains("cannot enter component instance"),
                "Wasmtime rejects all subsequent calls after this guest trap.",
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun instanceCloseDropsGuestAndHostResourcesStillOwnedByTheHost() = withResourceModule { module ->
        val dropped = mutableListOf<CallbackImplementation>()
        val instance = module.component().instantiate { bindImports(callbackRegistry(dropped)) }
        val counter = instance.createCounter(44u)
        instance.createCallback(CallbackImplementation(5u))
        assertEquals(1, instance.resourceDiagnostics().activeHostResources)

        instance.close()

        assertEquals(1, dropped.size)
        assertFailsWith<IllegalStateException> { counter.get() }
        assertFailsWith<IllegalStateException> { counter.close() }
    }

    private inline fun withResourceModule(block: (Wasmline) -> Unit) {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return
        val artifact = copyFixture()
        val handle = loadComponent(artifact)
        try {
            block(handle)
        } finally {
            handle.close()
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun WasmlineComponentInstance.createCounter(initial: UInt): GuestCounter {
        val result = successful(invoke(COUNTER_CONSTRUCTOR, listOf(WasmlineComponentValue.U32(initial))))
        return GuestCounter(this, assertIs(result.values.single()))
    }

    private fun WasmlineComponentInstance.dropCount(): UInt =
        assertIs<WasmlineComponentValue.U32>(successful(invoke(COUNTER_DROP_COUNT)).values.single()).value

    private fun WasmlineComponentInstance.inspect(counter: GuestCounter): UInt = assertIs<WasmlineComponentValue.U32>(
        successful(invoke(INSPECT, listOf(counter.toComponentValue()))).values.single(),
    ).value

    private fun WasmlineComponentInstance.roundTrip(counter: GuestCounter): GuestCounter {
        val result = successful(invoke(ROUND_TRIP, listOf(counter.transferToComponent())))
        return GuestCounter(this, assertIs(result.values.single()))
    }

    private fun WasmlineComponentInstance.callbackWithBorrow(callback: HostCallback, counter: GuestCounter): UInt =
        assertIs<WasmlineComponentValue.U32>(
            successful(
                invoke(
                    CALLBACK_WITH_BORROW,
                    listOf(callback.toComponentValue(), counter.toComponentValue()),
                ),
            ).values.single(),
        ).value

    private fun WasmlineComponentInstance.consumeCallback(callback: HostCallback, value: UInt): UInt = assertIs<WasmlineComponentValue.U32>(
        successful(
            invoke(
                CONSUME_CALLBACK,
                listOf(callback.transferToComponent(), WasmlineComponentValue.U32(value)),
            ),
        ).values.single(),
    ).value

    private fun WasmlineComponentInstance.createCallback(implementation: CallbackImplementation): HostCallback =
        HostCallback(this, createHostResource(CALLBACK_RESOURCE, implementation))

    private fun callbackRegistry(dropped: MutableList<CallbackImplementation>): WasmlineComponentHostRegistry =
        WasmlineComponentHostRegistry.builder()
            .registerResource(
                CALLBACK_RESOURCE,
                WasmlineComponentHostResourceBinding(
                    methods = mapOf(
                        "call" to { implementation, arguments ->
                            val callback = implementation as CallbackImplementation
                            val value = assertIs<WasmlineComponentValue.U32>(arguments.single()).value
                            WasmlineCallResult.Success(listOf(WasmlineComponentValue.U32(value * callback.multiplier)))
                        },
                    ),
                    drop = { implementation -> dropped += implementation as CallbackImplementation },
                ),
            )
            .build()

    private fun successful(result: WasmlineCallResult<WasmlineComponentCallResult>): WasmlineComponentCallResult = when (result) {
        is WasmlineCallResult.Success -> result.value

        is WasmlineCallResult.Failure -> error(
            "Component call failed [${result.error.code}]: ${result.error.message}",
        )
    }

    private fun loadComponent(artifact: File): Wasmline {
        val runtime = wasmlineRuntimeCapabilities()
        val state = wasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = artifact.absolutePath,
                artifactFormat = WasmlineArtifactFormat.CWASM,
                targetCpu = runtime.targetCpu,
                targetOs = runtime.targetOs,
                targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
                is64Bit = runtime.is64Bit,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun copyFixture(): File {
        val source = requireNotNull(System.getenv(FIXTURE_ENV)) {
            "$FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile && source.name.endsWith(".cwasm")) {
            "$FIXTURE_ENV must point to a precompiled Component .cwasm: ${source.absolutePath}"
        }
        return File.createTempFile("wasmline-component-resource-", ".cwasm").apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private class GuestCounter(instance: WasmlineComponentInstance, reference: WasmlineComponentValue.ResourceValue) :
        WasmlineGuestComponentResource(instance, reference) {
        fun get(): UInt = call(COUNTER_GET)

        fun add(delta: UInt): UInt = call(COUNTER_ADD, WasmlineComponentValue.U32(delta))

        private fun call(export: WasmlineComponentExport, vararg arguments: WasmlineComponentValue): UInt =
            assertIs<WasmlineComponentValue.U32>(
                when (val result = instance.invoke(export, listOf(toComponentValue()) + arguments)) {
                    is WasmlineCallResult.Success -> result.value

                    is WasmlineCallResult.Failure -> error(
                        "Component resource call failed [${result.error.code}]: ${result.error.message}",
                    )
                }.values.single(),
            ).value
    }

    private class HostCallback(instance: WasmlineComponentInstance, reference: WasmlineComponentValue.ResourceValue) :
        WasmlineHostComponentResource(instance, reference)

    private class BorrowedCounter(instance: WasmlineComponentInstance, reference: WasmlineComponentValue.ResourceValue) :
        WasmlineGuestComponentResource(instance, reference)

    private fun WasmlineComponentValue.ResourceValue.withGeneration(value: UInt) = resourceCopy(generation = value)

    private fun WasmlineComponentValue.ResourceValue.withType(value: UInt) = resourceCopy(typeId = value)

    private fun WasmlineComponentValue.ResourceValue.withOwnership(value: WasmlineComponentResourceOwnership) =
        resourceCopy(ownership = value)

    private fun WasmlineComponentValue.ResourceValue.resourceCopy(
        typeId: UInt = this.typeId,
        generation: UInt = this.generation,
        ownership: WasmlineComponentResourceOwnership = this.ownership,
    ): WasmlineComponentValue.ResourceValue = WasmlineComponentValue.ResourceValue(
        instanceKey = instanceKey,
        typeId = typeId,
        handleId = handleId,
        generation = generation,
        ownership = ownership,
        origin = origin,
    )

    private data class CallbackImplementation(val multiplier: UInt)

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_RESOURCE"
        val EXPORT_INTERFACE = WasmlineComponentInterfaceId.of("wasmline:resource-fixture/resources@1.0.0")
        val HOST_INTERFACE = WasmlineComponentInterfaceId.of("wasmline:resource-fixture/host@1.0.0")
        val CALLBACK_RESOURCE = WasmlineComponentResourceId(HOST_INTERFACE, "callback")
        val COUNTER_CONSTRUCTOR = WasmlineComponentExport.of(EXPORT_INTERFACE, "[constructor]counter")
        val COUNTER_GET = WasmlineComponentExport.of(EXPORT_INTERFACE, "[method]counter.get")
        val COUNTER_ADD = WasmlineComponentExport.of(EXPORT_INTERFACE, "[method]counter.add")
        val COUNTER_DROP_COUNT = WasmlineComponentExport.of(EXPORT_INTERFACE, "[static]counter.drop-count")
        val INSPECT = WasmlineComponentExport.of(EXPORT_INTERFACE, "inspect")
        val ROUND_TRIP = WasmlineComponentExport.of(EXPORT_INTERFACE, "round-trip")
        val CALLBACK_WITH_BORROW = WasmlineComponentExport.of(EXPORT_INTERFACE, "callback-with-borrow")
        val CONSUME_CALLBACK = WasmlineComponentExport.of(EXPORT_INTERFACE, "consume-callback")
        val TRAP_WITH_BORROW = WasmlineComponentExport.of(EXPORT_INTERFACE, "trap-with-borrow")
    }
}
