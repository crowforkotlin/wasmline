@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrFieldSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.Name

/**
 * Generates the concrete `*_WasmlineBridge` class for one validated service contract.
 */
internal fun generateBridge(
    contract: IrClass,
    file: IrFile,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
    messageCollector: MessageCollector? = null,
): IrClass {
    val bridgeName = runtimeSymbols.bridgeClassName(contract)
    file.declarations.filterIsInstance<IrClass>().firstOrNull { it.name == bridgeName }?.let { return it }

    val contractFunctions = contract.declarations
        .filterIsInstance<IrSimpleFunction>()
        .filterNot { it.name.isSpecial }
        .filterNot { it.isFakeOverride }
    val fqName = contract.fqNameWhenAvailable?.asString() ?: contract.name.asString()
    val bridgeClass = pluginContext.irFactory.buildClass {
        initDefaults(contract)
        name = bridgeName
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        kind = ClassKind.CLASS
    }.apply {
        parent = file
        superTypes = listOf(contract.defaultType, runtimeSymbols.generatedBridgeType())
        createThisReceiverParameter()
    }

    val endpointField = createPrivateField(
        owner = bridgeClass,
        pluginContext = pluginContext,
        type = runtimeSymbols.endpointType(),
        name = Name.identifier("endpoint"),
    )
    val implementationField = createPrivateField(
        owner = bridgeClass,
        pluginContext = pluginContext,
        type = contract.defaultType.makeNullable(),
        name = Name.identifier("implementation"),
    )
    bridgeClass.declarations += endpointField
    bridgeClass.declarations += implementationField

    addBridgeConstructor(bridgeClass, contract, endpointField, implementationField, pluginContext, runtimeSymbols)

    contractFunctions.forEach { contractFunction ->
        bridgeClass.declarations += generateBridgeContractMethod(
            bridgeClass = bridgeClass,
            endpointField = endpointField,
            contractFunction = contractFunction,
            pluginContext = pluginContext,
            runtimeSymbols = runtimeSymbols,
            fqName = fqName,
        )
    }
    bridgeClass.declarations += generateBridgeBindMethod(
        bridgeClass = bridgeClass,
        contractFunctions = contractFunctions,
        pluginContext = pluginContext,
        runtimeSymbols = runtimeSymbols,
        fqName = fqName,
    )
    bridgeClass.declarations += generateBridgeDispatcherMethod(
        bridgeClass = bridgeClass,
        implementationField = implementationField,
        contract = contract,
        contractFunctions = contractFunctions,
        pluginContext = pluginContext,
        runtimeSymbols = runtimeSymbols,
        fqName = fqName,
    )

    file.declarations += bridgeClass

    if (messageCollector != null) {
        report(
            messageCollector = messageCollector,
            file = file,
            declaration = contract,
            severity = CompilerMessageSeverity.INFO,
            message = "[Wasmline] generated bridge ${bridgeName.asString()} for $fqName",
        )
    }

    return bridgeClass
}

/** Adds the single constructor used by rewritten `link()` and `bind()` entrypoints. */
private fun addBridgeConstructor(
    bridgeClass: IrClass,
    contract: IrClass,
    endpointField: IrField,
    implementationField: IrField,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
) {
    bridgeClass.addConstructor {
        initDefaults(contract)
        visibility = DescriptorVisibilities.PUBLIC
    }.apply {
        val endpointParameter = addValueParameter("endpoint", runtimeSymbols.endpointType())
        val implementationParameter = addValueParameter("implementation", contract.defaultType.makeNullable())
        irConstructorBody(pluginContext) { statements ->
            initializeBridgeConstructorState(
                pluginContext = pluginContext,
                bridgeClass = bridgeClass,
                endpointField = endpointField,
                endpointValue = irGet(endpointParameter),
                implementationField = implementationField,
                implementationValue = irGet(implementationParameter),
                statements = statements,
            )
        }
    }
}

/** Generates the typed proxy method that forwards contract calls to the transport endpoint. */
private fun generateBridgeContractMethod(
    bridgeClass: IrClass,
    endpointField: IrField,
    contractFunction: IrSimpleFunction,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
    fqName: String,
): IrSimpleFunction {
    val action = "$fqName#${contractFunction.name.asString()}"
    return createBridgeSimpleFunction(
        pluginContext = pluginContext,
        owner = bridgeClass,
        original = contractFunction,
        name = contractFunction.name,
        returnType = contractFunction.returnType,
        isOperator = contractFunction.isOperator,
        isInfix = contractFunction.isInfix,
    ).apply {
        overriddenSymbols = listOf(contractFunction.symbol)
        parameters += buildReceiverParameter {
            type = bridgeClass.defaultType
        }
        contractFunction.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .forEach { parameter ->
                addValueParameter(parameter.name.asString(), parameter.type)
            }
        val regularParameters = parameters.filter { it.kind == IrParameterKind.Regular }
        irFunctionBody(
            context = pluginContext,
            scopeOwnerSymbol = symbol,
        ) {
            val endpoint = irGetField(
                irGet(dispatchReceiverParameter!!),
                endpointField,
            )
            val payload = when (regularParameters.size) {
                0 -> irInvoke(null, runtimeSymbols.emptyPayloadFunction)
                else -> irGet(regularParameters.single())
            }
            val invokeCall = irInvoke(
                endpoint,
                runtimeSymbols.endpointInvokeFunction,
                irString(action),
                payload,
            )
            if (contractFunction.returnType.isKotlinUnitType()) {
                +irImplicitCoercionToUnit(invokeCall)
            } else {
                +irReturn(
                    value = invokeCall,
                    returnTargetSymbol = symbol,
                )
            }
        }
    }
}

/** Generates the binder method that registers one action handler per contract function. */
private fun generateBridgeBindMethod(
    bridgeClass: IrClass,
    contractFunctions: List<IrSimpleFunction>,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
    fqName: String,
): IrSimpleFunction {
    return createBridgeSimpleFunction(
        pluginContext = pluginContext,
        owner = bridgeClass,
        original = bridgeClass,
        name = Name.identifier("bind"),
        returnType = pluginContext.irBuiltIns.unitType,
    ).apply {
        overriddenSymbols = listOf(runtimeSymbols.generatedBridgeBindFunction)
        val bindFunction = this
        parameters += buildReceiverParameter {
            type = bridgeClass.defaultType
        }
        val registerActionParameter = addValueParameter("registerAction", runtimeSymbols.actionRegistrarType())
        irFunctionBody(
            context = pluginContext,
            scopeOwnerSymbol = symbol,
        ) {
            contractFunctions.forEach { contractFunction ->
                val action = "$fqName#${contractFunction.name.asString()}"
                +irInvoke(
                    dispatchReceiver = irGet(registerActionParameter),
                    callee = runtimeSymbols.function2InvokeFunction,
                    irString(action),
                    generateBindActionHandler(
                        owner = bindFunction,
                        pluginContext = pluginContext,
                        runtimeSymbols = runtimeSymbols,
                        action = action,
                    ),
                )
            }
        }
    }
}

/** Builds one generated action handler lambda for bridge binding. */
private fun org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder.generateBindActionHandler(
    owner: IrSimpleFunction,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
    action: String,
): IrExpression {
    val lambda = pluginContext.irFactory.createSimpleFunction(
        startOffset = startOffset,
        endOffset = endOffset,
        origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA,
        name = Name.special("<anonymous>"),
        visibility = DescriptorVisibilities.LOCAL,
        isInline = false,
        isExpect = false,
        returnType = runtimeSymbols.byteArrayClass.owner.defaultType,
        modality = Modality.FINAL,
        symbol = IrSimpleFunctionSymbolImpl(),
        isTailrec = false,
        isSuspend = false,
        isOperator = false,
        isInfix = false,
        isExternal = false,
        containerSource = null,
        isFakeOverride = false,
    ).apply {
        parent = owner
        val payloadParameter = addValueParameter("payload", runtimeSymbols.byteArrayClass.owner.defaultType)
        irFunctionBody(
            context = pluginContext,
            scopeOwnerSymbol = symbol,
        ) {
            +irReturn(
                value = irInvoke(
                    dispatchReceiver = irGet(owner.dispatchReceiverParameter!!),
                    callee = runtimeSymbols.generatedBridgeInvokeFunction,
                    irString(action),
                    irGet(payloadParameter),
                ),
                returnTargetSymbol = symbol,
            )
        }
    }
    return IrFunctionExpressionImpl(
        startOffset = startOffset,
        endOffset = endOffset,
        type = runtimeSymbols.actionHandlerType(),
        function = lambda,
        origin = IrStatementOrigin.LAMBDA,
    )
}

/** Generates the dispatcher that routes incoming actions back to the bound implementation. */
private fun generateBridgeDispatcherMethod(
    bridgeClass: IrClass,
    implementationField: IrField,
    contract: IrClass,
    contractFunctions: List<IrSimpleFunction>,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
    fqName: String,
): IrSimpleFunction {
    return createBridgeSimpleFunction(
        pluginContext = pluginContext,
        owner = bridgeClass,
        original = bridgeClass,
        name = Name.identifier("invoke"),
        returnType = runtimeSymbols.byteArrayClass.owner.defaultType,
        isOperator = true,
    ).apply {
        overriddenSymbols = listOf(runtimeSymbols.generatedBridgeInvokeFunction)
        parameters += buildReceiverParameter {
            type = bridgeClass.defaultType
        }
        val actionParameter = addValueParameter("action", pluginContext.irBuiltIns.stringType)
        val payloadParameter = addValueParameter("payload", runtimeSymbols.byteArrayClass.owner.defaultType)
        irFunctionBody(
            context = pluginContext,
            scopeOwnerSymbol = symbol,
        ) {
            val bridgeThis = irGet(dispatchReceiverParameter!!)
            contractFunctions.forEach { contractFunction ->
                val action = "$fqName#${contractFunction.name.asString()}"
                +irIfThen(
                    type = pluginContext.irBuiltIns.unitType,
                    condition = irEquals(irGet(actionParameter), irString(action)),
                    thenPart = irReturn(
                        value = dispatcherResultExpression(
                            bridgeThis = bridgeThis,
                            implementationField = implementationField,
                            contract = contract,
                            contractFunction = contractFunction,
                            payloadParameter = payloadParameter,
                            runtimeSymbols = runtimeSymbols,
                            fqName = fqName,
                        ),
                        returnTargetSymbol = symbol,
                    ),
                )
            }
            +irReturn(
                value = irInvoke(
                    null,
                    runtimeSymbols.unknownGeneratedActionFunction,
                    irString(fqName),
                    irGet(actionParameter),
                ),
                returnTargetSymbol = symbol,
            )
        }
    }
}

/** Builds the dispatcher result expression for one generated action branch. */
private fun org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder.dispatcherResultExpression(
    bridgeThis: IrExpression,
    implementationField: IrField,
    contract: IrClass,
    contractFunction: IrSimpleFunction,
    payloadParameter: IrValueParameter,
    runtimeSymbols: WasmlineRuntimeSymbols,
    fqName: String,
): IrExpression {
    val implementation = irInvoke(
        dispatchReceiver = null,
        callee = runtimeSymbols.requireGeneratedImplementationFunction,
        typeArguments = listOf(contract.defaultType),
        valueArguments = listOf(
            irGetField(bridgeThis, implementationField),
            irString(fqName),
        ),
        returnTypeHint = contract.defaultType,
    )
    val regularParameters = contractFunction.parameters.filter { it.kind == IrParameterKind.Regular }
    val implementationCall = when (regularParameters.size) {
        0 -> irInvoke(
            implementation,
            contractFunction.symbol,
        )

        else -> irInvoke(
            implementation,
            contractFunction.symbol,
            irGet(payloadParameter),
        )
    }
    return if (contractFunction.returnType.isKotlinUnitType()) {
        IrBlockImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            type = runtimeSymbols.byteArrayClass.owner.defaultType,
            origin = null,
        ).apply {
            statements += implementationCall
            statements += irInvoke(null, runtimeSymbols.emptyPayloadFunction)
        }
    } else {
        implementationCall
    }
}

/** Creates a private backing field used by the generated bridge class. */
private fun createPrivateField(
    owner: IrClass,
    pluginContext: IrPluginContext,
    type: IrType,
    name: Name,
): IrField {
    return pluginContext.irFactory.createField(
        startOffset = owner.startOffset,
        endOffset = owner.endOffset,
        origin = IrDeclarationOrigin.DEFINED,
        symbol = IrFieldSymbolImpl(),
        name = name,
        type = type,
        visibility = DescriptorVisibilities.PRIVATE,
        isFinal = true,
        isExternal = false,
        isStatic = false,
    ).apply {
        parent = owner
    }
}

private fun DeclarationIrBuilder.initializeBridgeConstructorState(
    pluginContext: IrPluginContext,
    bridgeClass: IrClass,
    endpointField: IrField,
    endpointValue: IrExpression,
    implementationField: IrField,
    implementationValue: IrExpression,
    statements: MutableList<IrStatement>,
) {
    val anyClass = pluginContext.irBuiltIns.anyType.classifierOrNull as IrClassSymbol
    statements += irDelegatingConstructorCall(
        context = pluginContext,
        symbol = anyClass.owner.constructors.single().symbol,
    )
    statements += irInstanceInitializerCall(
        context = pluginContext,
        classSymbol = bridgeClass.symbol,
    )
    statements += irSetField(
        receiver = irGet(bridgeClass.thisReceiver!!),
        field = endpointField,
        value = endpointValue,
    )
    statements += irSetField(
        receiver = irGet(bridgeClass.thisReceiver!!),
        field = implementationField,
        value = implementationValue,
    )
}

private fun createBridgeSimpleFunction(
    pluginContext: IrPluginContext,
    owner: IrClass,
    original: IrElement,
    name: Name,
    returnType: IrType,
    visibility: DescriptorVisibility = DescriptorVisibilities.PUBLIC,
    isOperator: Boolean = false,
    isInfix: Boolean = false,
): IrSimpleFunction {
    return pluginContext.irFactory.createSimpleFunction(
        startOffset = original.startOffset,
        endOffset = original.endOffset,
        origin = IrDeclarationOrigin.DEFINED,
        name = name,
        visibility = visibility,
        isInline = false,
        isExpect = false,
        returnType = returnType,
        modality = Modality.FINAL,
        symbol = IrSimpleFunctionSymbolImpl(),
        isTailrec = false,
        isSuspend = false,
        isOperator = isOperator,
        isInfix = isInfix,
        isExternal = false,
        containerSource = null,
        isFakeOverride = false,
    ).apply {
        parent = owner
    }
}

/** Returns an object-get expression for a singleton runtime symbol used by generated bridges. */
private fun irGetObject(symbol: IrClassSymbol): IrExpression {
    return IrGetObjectValueImpl(
        startOffset = SYNTHETIC_OFFSET,
        endOffset = SYNTHETIC_OFFSET,
        type = symbol.owner.defaultType,
        symbol = symbol,
    )
}