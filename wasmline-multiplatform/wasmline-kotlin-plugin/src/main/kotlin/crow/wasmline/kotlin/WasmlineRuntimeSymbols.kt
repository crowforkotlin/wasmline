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
    val serviceDefinitionClass: IrClassSymbol = requireClass(SPI_PACKAGE, "ServiceDefinition")
    val serviceIdClass: IrClassSymbol = requireClass(SPI_PACKAGE, "ServiceId")
    val endpointClass: IrClassSymbol = requireClass(SPI_PACKAGE, "WasmlineEndpoint")
    val bindingScopeClass: IrClassSymbol = requireClass(SPI_PACKAGE, "WasmlineBindingScope")
    val actionHandlerClass: IrClassSymbol = requireClass(SPI_PACKAGE, "WasmlineActionHandler")

    val serviceDefinitionContractProperty: IrPropertySymbol = requireProperty(serviceDefinitionClass, "contract")
    val serviceDefinitionServiceIdProperty: IrPropertySymbol = requireProperty(serviceDefinitionClass, "serviceId")
    val serviceDefinitionLinkFunction: IrSimpleFunctionSymbol = requireFunction(serviceDefinitionClass, "link")
    val serviceDefinitionBindFunction: IrSimpleFunctionSymbol = requireFunction(serviceDefinitionClass, "bind")
    val endpointInvokeFunction: IrSimpleFunctionSymbol = requireFunction(endpointClass, "invoke")
    val bindingScopeBindActionFunction: IrSimpleFunctionSymbol = requireFunction(bindingScopeClass, "bind")
    val actionHandlerHandleFunction: IrSimpleFunctionSymbol = requireFunction(actionHandlerClass, "handle")

    val serviceIdConstructor: IrConstructorSymbol = serviceIdClass.owner.declarations
        .filterIsInstance<IrConstructor>()
        .single()
        .symbol
    val emptyPayloadFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "emptyPayload",
        regularParameterCount = 0,
    )
    val registerServiceDefinitionFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "registerServiceDefinition",
        regularParameterCount = 1,
    )

    val endpointLinkNoArgFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = SPI_PACKAGE,
        functionName = "link",
        extensionReceiverClassName = "WasmlineEndpoint",
        regularParameterCount = 0,
    )
    val endpointLinkContractFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = SPI_PACKAGE,
        functionName = "link",
        extensionReceiverClassName = "WasmlineEndpoint",
        regularParameterCount = 1,
    )
    val bindingScopeBindSingleFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = SPI_PACKAGE,
        functionName = "bind",
        extensionReceiverClassName = "WasmlineBindingScope",
        regularParameterCount = 1,
    )
    val bindingScopeBindContractFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = SPI_PACKAGE,
        functionName = "bind",
        extensionReceiverClassName = "WasmlineBindingScope",
        regularParameterCount = 2,
    )
    val bindingScopeBindAsFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = SPI_PACKAGE,
        functionName = "bindAs",
        extensionReceiverClassName = "WasmlineBindingScope",
        regularParameterCount = 1,
    )
    val hostBindSingleFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "bind",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 1,
    )
    val hostBindContractFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "bind",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 2,
    ) { function ->
        ((function.parameters.firstOrNull { it.kind == IrParameterKind.Regular }?.type as? IrSimpleType)?.classifier as? IrClassSymbol) == pluginContext.irBuiltIns.kClassClass
    }
    val hostBindAsFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "bindAs",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 1,
    )
    val topLevelBindSingleFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("bind")),
        regularParameterCount = 1,
    )
    val topLevelBindContractFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("bind")),
        regularParameterCount = 2,
    ) { function ->
        ((function.parameters.firstOrNull { it.kind == IrParameterKind.Regular }?.type as? IrSimpleType)?.classifier as? IrClassSymbol) == pluginContext.irBuiltIns.kClassClass
    }
    val topLevelBindAsFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("bindAs")),
        regularParameterCount = 1,
    )
    val hostLinkFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "link",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 0,
    )
    val linkHostFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("linkHost")),
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

    private fun requireClass(packageName: String, className: String): IrClassSymbol {
        return pluginContext.referenceClass(
            ClassId(FqName(packageName), Name.identifier(className)),
        ) ?: error("Unable to resolve Wasmline runtime class $packageName.$className")
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

    @Suppress("SameParameterValue")
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


    private fun referenceTopLevelExtensionFunction(
        packageName: String,
        functionName: String,
        extensionReceiverClassName: String,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean = { true },
    ): IrSimpleFunctionSymbol? {
        return referenceTopLevelFunction(
            callableId = CallableId(FqName(packageName), Name.identifier(functionName)),
            regularParameterCount = regularParameterCount,
        ) { function ->
            ((function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.type as? IrSimpleType)?.classifier as? IrClassSymbol) == requireClass(packageName, extensionReceiverClassName) &&
                extraFilter(function)
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
        const val MAIN_PACKAGE = "crow.wasmline"
        const val SPI_PACKAGE = "crow.wasmline.spi"
    }
}




