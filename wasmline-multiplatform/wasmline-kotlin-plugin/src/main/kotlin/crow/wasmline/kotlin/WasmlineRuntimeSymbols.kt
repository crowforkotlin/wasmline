package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@Suppress("DEPRECATION")
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class WasmlineRuntimeSymbols(
    private val pluginContext: IrPluginContext,
) {
    val serviceDefinitionClass: IrClassSymbol = requireClass("WasmlineServiceDefinition")
    val serviceIdClass: IrClassSymbol = requireClass("WasmlineServiceId")
    val endpointClass: IrClassSymbol = requireClass("WasmlineEndpoint")
    val bindingScopeClass: IrClassSymbol = requireClass("WasmlineBindingScope")

    val serviceDefinitionContractProperty: IrPropertySymbol = requireProperty(serviceDefinitionClass, "contract")
    val serviceDefinitionServiceIdProperty: IrPropertySymbol = requireProperty(serviceDefinitionClass, "serviceId")
    val serviceDefinitionLinkFunction: IrSimpleFunctionSymbol = requireFunction(serviceDefinitionClass, "link")
    val serviceDefinitionBindFunction: IrSimpleFunctionSymbol = requireFunction(serviceDefinitionClass, "bind")
    val endpointInvokeFunction: IrSimpleFunctionSymbol = requireFunction(endpointClass, "invoke")

    val serviceIdConstructor: IrConstructorSymbol = serviceIdClass.owner.declarations
        .filterIsInstance<IrConstructor>()
        .single()
        .symbol
    val kotlinErrorFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = "kotlin",
        functionName = "error",
        regularParameterCount = 1,
    )
    val emptyPayloadFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = RUNTIME_PACKAGE,
        functionName = "wasmlineEmptyPayload",
        regularParameterCount = 0,
    )
    val registerServiceDefinitionFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = RUNTIME_PACKAGE,
        functionName = "registerWasmlineServiceDefinition",
        regularParameterCount = 1,
    )

    val endpointLinkNoArgFunction: IrSimpleFunctionSymbol = requireTopLevelExtensionFunction(
        functionName = "link",
        extensionReceiverClassName = "WasmlineEndpoint",
        regularParameterCount = 0,
    )
    val endpointLinkContractFunction: IrSimpleFunctionSymbol = requireTopLevelExtensionFunction(
        functionName = "link",
        extensionReceiverClassName = "WasmlineEndpoint",
        regularParameterCount = 1,
    )
    val bindingScopeBindSingleFunction: IrSimpleFunctionSymbol = requireTopLevelExtensionFunction(
        functionName = "bind",
        extensionReceiverClassName = "WasmlineBindingScope",
        regularParameterCount = 1,
    )
    val bindingScopeBindContractFunction: IrSimpleFunctionSymbol = requireTopLevelExtensionFunction(
        functionName = "bind",
        extensionReceiverClassName = "WasmlineBindingScope",
        regularParameterCount = 2,
    )
    val bindingScopeBindAsFunction: IrSimpleFunctionSymbol = requireTopLevelExtensionFunction(
        functionName = "bindAs",
        extensionReceiverClassName = "WasmlineBindingScope",
        regularParameterCount = 1,
    )
    val hostLinkFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        functionName = "link",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 0,
    )
    val linkHostFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(RUNTIME_PACKAGE), Name.identifier("linkHost")),
        regularParameterCount = 0,
    )

    fun serviceDefinitionType(contract: IrClass): IrType {
        return serviceDefinitionClass.typeWith(contract.defaultType)
    }

    fun contractKClassType(contract: IrClass): IrType {
        return pluginContext.irBuiltIns.kClassClass.typeWith(contract.defaultType)
    }

    fun definitionObjectName(contract: IrClass): Name {
        return Name.identifier("${contract.name.identifier}_WasmlineDefinition")
    }

    fun proxyClassName(contract: IrClass): Name {
        return Name.identifier("${contract.name.identifier}_WasmlineProxy")
    }

    fun adapterClassName(contract: IrClass): Name {
        return Name.identifier("${contract.name.identifier}_WasmlineAdapter")
    }

    fun definitionObjectSymbol(contract: IrClass): IrClassSymbol? {
        val fqName = contract.fqNameWhenAvailable ?: return null
        return pluginContext.referenceClass(
            ClassId(
                fqName.parent(),
                definitionObjectName(contract),
            ),
        )
    }

    private fun requireClass(className: String): IrClassSymbol {
        return pluginContext.referenceClass(
            ClassId(FqName(RUNTIME_PACKAGE), Name.identifier(className)),
        ) ?: error("Unable to resolve Wasmline runtime class $RUNTIME_PACKAGE.$className")
    }

    private fun requireProperty(ownerClass: IrClassSymbol, name: String): IrPropertySymbol {
        return ownerClass.owner.declarations
            .filterIsInstance<IrProperty>()
            .single { it.name.asString() == name }
            .symbol
    }

    private fun requireFunction(ownerClass: IrClassSymbol, name: String): IrSimpleFunctionSymbol {
        return ownerClass.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .single { function ->
                function.name.asString() == name &&
                    function.parameters.count { it.kind == IrParameterKind.Regular } <= 2
            }
            .symbol
    }

    private fun requireTopLevelFunction(
        packageName: String,
        functionName: String,
        regularParameterCount: Int,
    ): IrSimpleFunctionSymbol {
        return referenceTopLevelFunction(
            CallableId(FqName(packageName), Name.identifier(functionName)),
            regularParameterCount,
        ) ?: error("Unable to resolve top-level function $packageName.$functionName/$regularParameterCount")
    }

    private fun requireTopLevelExtensionFunction(
        functionName: String,
        extensionReceiverClassName: String,
        regularParameterCount: Int,
    ): IrSimpleFunctionSymbol {
        return referenceTopLevelExtensionFunction(
            functionName = functionName,
            extensionReceiverClassName = extensionReceiverClassName,
            regularParameterCount = regularParameterCount,
        ) ?: error(
            "Unable to resolve top-level extension function $RUNTIME_PACKAGE.$functionName on $RUNTIME_PACKAGE.$extensionReceiverClassName/$regularParameterCount",
        )
    }

    private fun referenceTopLevelExtensionFunction(
        functionName: String,
        extensionReceiverClassName: String,
        regularParameterCount: Int,
    ): IrSimpleFunctionSymbol? {
        return referenceTopLevelFunction(
            callableId = CallableId(FqName(RUNTIME_PACKAGE), Name.identifier(functionName)),
            regularParameterCount = regularParameterCount,
        ) { function ->
            ((function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.type as? IrSimpleType)?.classifier as? IrClassSymbol) == requireClass(extensionReceiverClassName)
        }
    }


    private fun referenceTopLevelFunction(
        callableId: CallableId,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean = { true },
    ): IrSimpleFunctionSymbol? {
        return pluginContext.referenceFunctions(callableId)
            .firstOrNull { function ->
                function.owner.parameters.count { it.kind == IrParameterKind.Regular } == regularParameterCount &&
                    extraFilter(function.owner)
            }
    }

    private companion object {
        const val RUNTIME_PACKAGE = "crow.wasmline"
    }
}




