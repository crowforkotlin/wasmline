package crow.wasmline.internal

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineComponentHostDispatcher
import crow.wasmline.WasmlineComponentHostRegistry
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.invocation.WasmlineCallResult

/** Shared implementation for the Component bindings exposed as [Wasmline] members. */
internal object WasmlineComponentBindings {
    fun bindHost(wasmline: Wasmline, registry: WasmlineComponentHostRegistry): Wasmline {
        require(wasmline.descriptor.invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT) {
            "Typed Component host registries require invocationProtocol=COMPONENT_EXPORT."
        }
        wasmline.setComponentHostDispatcher(WasmlineComponentHostDispatcher(registry))
        return wasmline
    }

    fun bindService(wasmline: Wasmline, handler: (action: String, payload: ByteArray) -> WasmlineCallResult<ByteArray>): Wasmline {
        require(
            wasmline.descriptor.executionModel == WasmlineExecutionModel.COMPONENT_MODEL &&
                wasmline.descriptor.invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ) {
            "Component Service handlers require COMPONENT_MODEL with invocationProtocol=WASMLINE_SERVICE."
        }
        if (wasmline.hostServiceRegistry.registerRaw(handler)) {
            wasmline.setOutbound(wasmline.hostServiceRegistry.dispatcher)
        }
        return wasmline
    }
}
