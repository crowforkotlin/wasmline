package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmlineComponentFacadeTest {
    @Test
    fun rejectsTypedCapabilityForCoreAndComponentServiceArtifacts() {
        assertFailsWith<IllegalArgumentException> { handle(WasmlineExecutionModel.CORE_WASM).component() }
        assertFailsWith<IllegalArgumentException> {
            handle(
                model = WasmlineExecutionModel.COMPONENT_MODEL,
                protocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            ).component()
        }
    }

    @Test
    fun typedAndComponentServiceCapabilitiesNeverFallbackToEachOther() {
        val typed = handle(
            model = WasmlineExecutionModel.COMPONENT_MODEL,
            protocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
        )
        val serviceCall = assertIs<WasmlineCallResult.Failure>(typed.callResult("service/action"))
        assertEquals(WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH, serviceCall.error.code)
        assertFailsWith<IllegalArgumentException> {
            typed.bindComponentService { _, _ -> WasmlineCallResult.Success(ByteArray(0)) }
        }

        val service = handle(
            model = WasmlineExecutionModel.COMPONENT_MODEL,
            protocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
        )
        assertFailsWith<IllegalArgumentException> {
            service.bindComponentHost(WasmlineComponentHostRegistry.builder().build())
        }
        assertFailsWith<IllegalArgumentException> { service.component() }
    }

    @Test
    fun contractRequiresExactPackageWorldAndDigestMetadata() {
        val contract = contract()
        val descriptor = descriptor(metadata = contractMetadata())

        contract.requireMatches(descriptor)

        listOf(
            WasmlineTypedComponentContract.METADATA_WIT_PACKAGE,
            WasmlineTypedComponentContract.METADATA_WORLD,
            WasmlineTypedComponentContract.METADATA_WIT_SHA256,
        ).forEach { key ->
            assertFailsWith<IllegalStateException> {
                contract.requireMatches(descriptor.copy(contractMetadata = descriptor.contractMetadata - key))
            }
            assertFailsWith<IllegalStateException> {
                contract.requireMatches(descriptor.copy(contractMetadata = descriptor.contractMetadata + (key to "wrong")))
            }
        }
    }

    @Test
    fun exportBuilderUsesCanonicalWitBindgenName() {
        val export = WasmlineComponentExport.of("wasmline:calculator/calculator@0.1.0", "evaluate")

        assertEquals("wasmline:calculator/calculator@0.1.0#evaluate", export.value)
        assertEquals("run", WasmlineComponentExport.root("run").value)
    }

    @Test
    fun importBuilderMergesRegistriesAndRejectsDuplicateFunctions() {
        val function = WasmlineComponentFunctionId.of(
            WasmlineComponentInterfaceId.of("wasmline:calculator/host-log@0.1.0"),
            "log",
        )
        val registry = WasmlineComponentHostRegistry.builder()
            .register(function) { crow.wasmline.invocation.WasmlineCallResult.Success(emptyList()) }
            .build()
        val builder = WasmlineComponentInstanceBuilder(descriptor(contractMetadata()))

        builder.bindImports(registry)
        assertFailsWith<IllegalStateException> { builder.bindImports(registry) }
    }

    @Test
    fun resourceWrapperConsumesOwnershipAndRejectsUseAfterTransfer() {
        val instance = detachedInstance("instance-a")
        val resource = TestResource(instance, resource("instance-a", WasmlineComponentResourceOwnership.OWN))

        assertEquals("instance-a", resource.toComponentValue().instanceKey)
        assertEquals(WasmlineComponentResourceOwnership.OWN, resource.transferToComponent().ownership)
        assertTrue(resource.isClosed)
        assertFailsWith<IllegalStateException> { resource.toComponentValue() }

        resource.close()
        assertTrue(resource.isClosed)
    }

    @Test
    fun borrowedResourceCannotBeClosedOrTransferred() {
        val instance = detachedInstance("instance-a")
        val resource = TestResource(instance, resource("instance-a", WasmlineComponentResourceOwnership.BORROW))

        assertFailsWith<IllegalStateException> { resource.close() }
        assertFailsWith<IllegalStateException> { resource.transferToComponent() }
        assertTrue(!resource.isClosed)
    }

    @Test
    fun resourceWrapperRejectsWrongInstanceAndClosedInstanceBeforeNativeAccess() {
        val instance = detachedInstance("instance-a")
        val wrongInstance = TestResource(instance, resource("instance-b", WasmlineComponentResourceOwnership.OWN))
        assertFailsWith<IllegalStateException> { wrongInstance.toComponentValue() }

        val resource = TestResource(instance, resource("instance-a", WasmlineComponentResourceOwnership.OWN))
        instance.close()
        assertFailsWith<IllegalStateException> { resource.toComponentValue() }
        assertFailsWith<IllegalStateException> { resource.close() }
        assertTrue(!resource.isClosed, "A failed drop must not consume the local wrapper state.")
    }

    private fun contract(): WasmlineComponentContract = WasmlineComponentContract(
        packageId = PACKAGE,
        world = WORLD,
        witSha256 = DIGEST,
    )

    private fun contractMetadata(): Map<String, String> = mapOf(
        WasmlineTypedComponentContract.METADATA_WIT_PACKAGE to PACKAGE,
        WasmlineTypedComponentContract.METADATA_WORLD to WORLD,
        WasmlineTypedComponentContract.METADATA_WIT_SHA256 to DIGEST,
    )

    private fun handle(
        model: WasmlineExecutionModel,
        protocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
    ): Wasmline = Wasmline("facade-test", WasmlineConfig(), descriptor(model = model, protocol = protocol))

    private fun descriptor(
        metadata: Map<String, String> = emptyMap(),
        model: WasmlineExecutionModel = WasmlineExecutionModel.COMPONENT_MODEL,
        protocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
    ): WasmlineArtifactDescriptor = WasmlineArtifactDescriptor(
        path = "/unused/component.cwasm",
        artifactFormat = WasmlineArtifactFormat.CWASM,
        executionModel = model,
        invocationProtocol = protocol,
        contractMetadata = metadata,
    )

    private fun detachedInstance(instanceKey: String): WasmlineComponentInstance {
        val owner = handle(
            model = WasmlineExecutionModel.COMPONENT_MODEL,
            protocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
        )
        return WasmlineComponentInstance(
            state = owner.componentModuleState,
            instanceKey = instanceKey,
            contract = null,
            dispatcher = WasmlineComponentHostDispatcher(WasmlineComponentHostRegistry.builder().build()),
        )
    }

    private fun resource(instanceKey: String, ownership: WasmlineComponentResourceOwnership): WasmlineComponentValue.ResourceValue =
        WasmlineComponentValue.ResourceValue(
            instanceKey = instanceKey,
            typeId = 3u,
            handleId = 11uL,
            generation = 7u,
            ownership = ownership,
            origin = WasmlineComponentResourceOrigin.GUEST,
        )

    private class TestResource(instance: WasmlineComponentInstance, reference: WasmlineComponentValue.ResourceValue) :
        WasmlineGuestComponentResource(instance, reference)

    private companion object {
        const val PACKAGE = "wasmline:calculator@0.1.0"
        const val WORLD = "calculator-plugin"
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
