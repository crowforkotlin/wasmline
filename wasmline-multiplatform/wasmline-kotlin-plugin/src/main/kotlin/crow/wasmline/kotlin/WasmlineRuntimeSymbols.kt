package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@Suppress("DEPRECATION")
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class WasmlineRuntimeSymbols(private val pluginContext: IrPluginContext) {
    val wasmlineClass: IrClassSymbol? = referenceClass(MAIN_PACKAGE, "Wasmline")
    val byteArrayClass: IrClassSymbol = requireClass("kotlin", "ByteArray")
    val serializationFactoryClass: IrClassSymbol = requireClass(SERIALIZATION_PACKAGE, "WasmlineSerializationFactory")
    val function1Class: IrClassSymbol = pluginContext.irBuiltIns.functionN(1).symbol
    val function2Class: IrClassSymbol = pluginContext.irBuiltIns.functionN(2).symbol
    val function2InvokeFunction: IrSimpleFunctionSymbol = requireFunction(function2Class, "invoke", 2)
    val endpointClass: IrClassSymbol = requireClass(SPI_PACKAGE, "WasmlineEndpoint")
    val endpointInvokeFunction: IrSimpleFunctionSymbol = requireFunction(endpointClass, "invoke", 2)
    val generatedBridgeClass: IrClassSymbol = requireClass(SPI_PACKAGE, "WasmlineGeneratedBridge")
    val generatedBridgeInvokeFunction: IrSimpleFunctionSymbol = requireFunction(generatedBridgeClass, "invoke", 2)
    val generatedBridgeBindFunction: IrSimpleFunctionSymbol = requireFunction(generatedBridgeClass, "bind", 1)
    val unlinkedEndpointObject: IrClassSymbol = requireClass(SPI_PACKAGE, "UnlinkedWasmlineEndpoint")
    val generatedHostEndpointClass: IrClassSymbol? = referenceClass(MAIN_PACKAGE, "GeneratedWasmlineHostEndpoint")
    val generatedComponentRpcEndpointObject: IrClassSymbol? =
        referenceClass(MAIN_PACKAGE, "GeneratedWasmlineComponentRpcEndpoint")
    val wasmlineHandleInboundFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("wasmlineHandleInbound")),
        regularParameterCount = 2,
    )
    val wasmExportAnnotationClass: IrClassSymbol? = referenceClass("kotlin.wasm", "WasmExport")
    val wasmExportAnnotationConstructor: IrConstructorSymbol? = wasmExportAnnotationClass?.let { annotationClass ->
        annotationClass.owner.declarations
            .filterIsInstance<IrConstructor>()
            .singleOrNull { constructor ->
                constructor.parameters.count { parameter -> parameter.kind == IrParameterKind.Regular } == 1
            }
            ?.symbol
    }

    val emptyPayloadFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "emptyPayload",
        regularParameterCount = 0,
    )
    val requireGeneratedImplementationFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "requireGeneratedImplementation",
        regularParameterCount = 2,
    )
    val unknownGeneratedActionFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "unknownGeneratedAction",
        regularParameterCount = 2,
    )
    val encodeGeneratedValueFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "encodeGeneratedValue",
        regularParameterCount = 2,
    )
    val decodeGeneratedValueFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "decodeGeneratedValue",
        regularParameterCount = 2,
    )
    val encodeMultiParamsFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "encodeMultiParams",
        regularParameterCount = 4,
    )
    val decodeMultiParamsFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "decodeMultiParams",
        regularParameterCount = 4,
    )
    val buildParamsDescriptorFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "buildParamsDescriptor",
        regularParameterCount = 2,
    )
    val getMultiParamFunction: IrSimpleFunctionSymbol = requireTopLevelFunction(
        packageName = SPI_PACKAGE,
        functionName = "getMultiParam",
        regularParameterCount = 2,
    )
    val kSerializerClass: IrClassSymbol? = referenceClass("kotlinx.serialization", "KSerializer")
    val serializerFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName("kotlinx.serialization"), Name.identifier("serializer")),
        regularParameterCount = 0,
    )
    val arrayOfFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName("kotlin"), Name.identifier("arrayOf")),
        regularParameterCount = 1,
    )
    val arrayGetFunction: IrSimpleFunctionSymbol = pluginContext.irBuiltIns.arrayClass.owner.declarations
        .filterIsInstance<IrSimpleFunction>()
        .single { it.name.asString() == "get" && it.parameters.count { p -> p.kind == IrParameterKind.Regular } == 1 }
        .symbol
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
        (
            (
                function.parameters.firstOrNull {
                    it.kind == IrParameterKind.Regular
                }?.type as? IrSimpleType
                )?.classifier as? IrClassSymbol
            ) == pluginContext.irBuiltIns.kClassClass
    }
    val topLevelBindSingleFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("bind")),
        regularParameterCount = 1,
    )
    val topLevelBindContractFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("bind")),
        regularParameterCount = 2,
    ) { function ->
        (
            (
                function.parameters.firstOrNull {
                    it.kind == IrParameterKind.Regular
                }?.type as? IrSimpleType
                )?.classifier as? IrClassSymbol
            ) == pluginContext.irBuiltIns.kClassClass
    }
    val hostLinkFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "link",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 0,
    )
    val hostBindGeneratedFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "bindGenerated",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 1,
    )
    val generatedSerializationFactoryFunction: IrSimpleFunctionSymbol? = referenceTopLevelExtensionFunction(
        packageName = MAIN_PACKAGE,
        functionName = "generatedSerializationFactory",
        extensionReceiverClassName = "Wasmline",
        regularParameterCount = 0,
    )
    val topLevelBindGeneratedFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("bindGenerated")),
        regularParameterCount = 1,
    )
    val currentGeneratedSerializationFactoryFunction: IrSimpleFunctionSymbol? = referenceTopLevelFunction(
        callableId = CallableId(FqName(MAIN_PACKAGE), Name.identifier("currentGeneratedSerializationFactory")),
        regularParameterCount = 0,
    )

    fun isHostLinkCall(symbol: IrSimpleFunctionSymbol): Boolean = matchesExtensionFunction(
        symbol = symbol,
        resolvedSymbol = hostLinkFunction,
        functionName = "link",
        extensionReceiverClass = wasmlineClass ?: return false,
        regularParameterCount = 0,
    )

    fun isHostBindContractCall(symbol: IrSimpleFunctionSymbol): Boolean = matchesExtensionFunction(
        symbol = symbol,
        resolvedSymbol = hostBindContractFunction,
        functionName = "bind",
        extensionReceiverClass = wasmlineClass ?: return false,
        regularParameterCount = 2,
    ) { function ->
        (
            (
                function.parameters.firstOrNull {
                    it.kind == IrParameterKind.Regular
                }?.type as? IrSimpleType
                )?.classifier as? IrClassSymbol
            ) == pluginContext.irBuiltIns.kClassClass
    }

    fun isHostBindSingleCall(symbol: IrSimpleFunctionSymbol): Boolean = matchesExtensionFunction(
        symbol = symbol,
        resolvedSymbol = hostBindSingleFunction,
        functionName = "bind",
        extensionReceiverClass = wasmlineClass ?: return false,
        regularParameterCount = 1,
    )

    fun isTopLevelBindContractCall(symbol: IrSimpleFunctionSymbol): Boolean = matchesTopLevelFunction(
        symbol = symbol,
        resolvedSymbol = topLevelBindContractFunction,
        functionName = "bind",
        regularParameterCount = 2,
    ) { function ->
        (
            (
                function.parameters.firstOrNull {
                    it.kind == IrParameterKind.Regular
                }?.type as? IrSimpleType
                )?.classifier as? IrClassSymbol
            ) == pluginContext.irBuiltIns.kClassClass
    }

    fun isTopLevelBindSingleCall(symbol: IrSimpleFunctionSymbol): Boolean = matchesTopLevelFunction(
        symbol = symbol,
        resolvedSymbol = topLevelBindSingleFunction,
        functionName = "bind",
        regularParameterCount = 1,
    )

    fun actionHandlerType(): IrType = function1Class.typeWith(
        byteArrayClass.owner.defaultType,
        byteArrayClass.owner.defaultType,
    )

    fun actionRegistrarType(): IrType = function2Class.typeWith(
        pluginContext.irBuiltIns.stringType,
        actionHandlerType(),
        pluginContext.irBuiltIns.unitType,
    )

    fun endpointType(): IrType = endpointClass.owner.defaultType

    fun generatedBridgeType(): IrType = generatedBridgeClass.owner.defaultType

    fun serializationFactoryType(): IrType = serializationFactoryClass.owner.defaultType

    fun bridgeClassName(contract: IrClass): Name = Name.identifier("${contract.name.identifier}_WasmlineBridge")

    fun bridgeClassSymbol(contract: IrClass): IrClassSymbol? {
        val fqName = contract.fqNameWhenAvailable ?: return null
        return pluginContext.referenceClass(
            ClassId(
                fqName.parent(),
                bridgeClassName(contract),
            ),
        )
    }

    fun canGenerateWasiEntryExport(): Boolean = wasmlineHandleInboundFunction != null && wasmExportAnnotationConstructor != null

    private fun requireClass(packageName: String, className: String): IrClassSymbol = referenceClass(packageName, className)
        ?: error("Unable to resolve Wasmline runtime class $packageName.$className")

    private fun referenceClass(packageName: String, className: String): IrClassSymbol? = pluginContext.referenceClass(
        ClassId(FqName(packageName), Name.identifier(className)),
    )

    private fun requireFunction(ownerClass: IrClassSymbol, name: String, regularParameterCount: Int? = null): IrSimpleFunctionSymbol =
        ownerClass.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .single { function ->
                function.name.asString() == name &&
                    (
                        regularParameterCount == null ||
                            function.parameters.count {
                                it.kind == IrParameterKind.Regular
                            } == regularParameterCount
                        )
            }
            .symbol

    @Suppress("SameParameterValue")
    private fun requireTopLevelFunction(packageName: String, functionName: String, regularParameterCount: Int): IrSimpleFunctionSymbol =
        referenceTopLevelFunction(
            CallableId(FqName(packageName), Name.identifier(functionName)),
            regularParameterCount,
        ) ?: error("Unable to resolve top-level function $packageName.$functionName/$regularParameterCount")

    private fun referenceTopLevelExtensionFunction(
        packageName: String,
        functionName: String,
        extensionReceiverClassName: String,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean = { true },
    ): IrSimpleFunctionSymbol? {
        val extensionReceiverClass = referenceClass(packageName, extensionReceiverClassName) ?: return null
        return referenceTopLevelFunction(
            callableId = CallableId(FqName(packageName), Name.identifier(functionName)),
            regularParameterCount = regularParameterCount,
        ) { function ->
            (
                (
                    function.parameters.firstOrNull {
                        it.kind == IrParameterKind.ExtensionReceiver
                    }?.type as? IrSimpleType
                    )?.classifier as? IrClassSymbol
                ) == extensionReceiverClass &&
                extraFilter(function)
        }
    }

    private fun referenceTopLevelFunction(
        callableId: CallableId,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean = { true },
    ): IrSimpleFunctionSymbol? = pluginContext.referenceFunctions(callableId)
        .firstOrNull { function ->
            function.owner.parameters.count { it.kind == IrParameterKind.Regular } == regularParameterCount &&
                extraFilter(function.owner)
        }

    private fun matchesExtensionFunction(
        symbol: IrSimpleFunctionSymbol,
        resolvedSymbol: IrSimpleFunctionSymbol?,
        functionName: String,
        extensionReceiverClass: IrClassSymbol,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean = { true },
    ): Boolean = matchesFunction(
        symbol = symbol,
        resolvedSymbol = resolvedSymbol,
        functionName = functionName,
        regularParameterCount = regularParameterCount,
        extraFilter = { function ->
            val extensionReceiver = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                ?.type
                ?.classifierOrNull as? IrClassSymbol
            extensionReceiver == extensionReceiverClass && extraFilter(function)
        },
    )

    private fun matchesTopLevelFunction(
        symbol: IrSimpleFunctionSymbol,
        resolvedSymbol: IrSimpleFunctionSymbol?,
        functionName: String,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean = { true },
    ): Boolean = matchesFunction(
        symbol = symbol,
        resolvedSymbol = resolvedSymbol,
        functionName = functionName,
        regularParameterCount = regularParameterCount,
        extraFilter = { function ->
            function.parameters.none { it.kind == IrParameterKind.ExtensionReceiver } && extraFilter(function)
        },
    )

    private fun matchesFunction(
        symbol: IrSimpleFunctionSymbol,
        resolvedSymbol: IrSimpleFunctionSymbol?,
        functionName: String,
        regularParameterCount: Int,
        extraFilter: (IrFunction) -> Boolean,
    ): Boolean {
        if (symbol == resolvedSymbol) return true
        val function = symbol.owner
        return function.name.asString() == functionName &&
            function.fqNameWhenAvailable?.parent()?.asString() == MAIN_PACKAGE &&
            function.parameters.count { it.kind == IrParameterKind.Regular } == regularParameterCount &&
            extraFilter(function)
    }

    private companion object {
        const val MAIN_PACKAGE = "crow.wasmline"
        const val SPI_PACKAGE = "crow.wasmline.internal.bridge"
        const val SERIALIZATION_PACKAGE = "crow.wasmline.serialization"
    }
}
