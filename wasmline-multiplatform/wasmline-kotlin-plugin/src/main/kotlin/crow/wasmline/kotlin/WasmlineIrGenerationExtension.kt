@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
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
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal class WasmlineIrGenerationExtension(
    private val messageCollector: MessageCollector,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val runtimeSymbols = WasmlineRuntimeSymbols(pluginContext)
        val generatedBridges = linkedMapOf<IrClassSymbol, IrClass>()
        moduleFragment.files.forEach { file ->
            val contracts = mutableListOf<IrClass>()
            scanContainer(file, contracts)
            contracts.forEach { contract ->
                if (validateIfServiceContract(contract, file)) {
                    generatedBridges[contract.symbol] = generateBridge(contract, file, pluginContext, runtimeSymbols)
                }
            }
        }
        rewriteTypedEntryPoints(moduleFragment, pluginContext, runtimeSymbols, generatedBridges)
    }

    private fun rewriteTypedEntryPoints(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        generatedBridges: MutableMap<IrClassSymbol, IrClass>,
    ) {
        moduleFragment.files.forEach { file ->
            val ownerDeclarations = ArrayDeque<IrDeclaration>()
            file.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFunction(declaration: IrFunction): IrStatement {
                ownerDeclarations.addLast(declaration)
                return try {
                    super.visitFunction(declaration)
                } finally {
                    ownerDeclarations.removeLast()
                }
            }

            override fun visitConstructor(declaration: IrConstructor): IrStatement {
                ownerDeclarations.addLast(declaration)
                return try {
                    super.visitConstructor(declaration)
                } finally {
                    ownerDeclarations.removeLast()
                }
            }

            override fun visitCall(expression: IrCall): IrExpression {
                val transformed = super.visitCall(expression)
                val call = transformed as? IrCall ?: return transformed
                val ownerDeclaration = ownerDeclarations.lastOrNull() ?: return transformed
                return replaceTypedEntryPoint(call, file, pluginContext, runtimeSymbols, generatedBridges, ownerDeclaration) ?: transformed
            }
            })
        }
    }

    private fun replaceTypedEntryPoint(
        call: IrCall,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        generatedBridges: MutableMap<IrClassSymbol, IrClass>,
        ownerDeclaration: IrDeclaration,
    ): IrExpression? {
        val contract = resolveContractForTypedEntryPoint(call, runtimeSymbols, file, ownerDeclaration) ?: return null
        val bridgeClass = generatedBridges[contract.symbol]
            ?: runtimeSymbols.bridgeClassSymbol(contract)?.owner
            ?: generateBridge(contract, file, pluginContext, runtimeSymbols).also { generatedBridges[contract.symbol] = it }
        val builder = DeclarationIrBuilder(pluginContext, ownerDeclaration.symbol, call.startOffset, call.endOffset)
        return when {
            runtimeSymbols.isHostLinkCall(call.symbol) -> {
                val wasmline = call.extensionReceiverArgument() ?: return null
                val endpointClass = runtimeSymbols.generatedHostEndpointClass ?: return null
                val endpointConstructor = endpointClass.owner.constructors.single()
                val linkConstructor = bridgeClass.constructors.single {
                    it.parameters.count { parameter -> parameter.kind == IrParameterKind.Regular } == 1 &&
                        it.parameters.first { parameter -> parameter.kind == IrParameterKind.Regular }.type.classifierOrNull == runtimeSymbols.endpointClass
                }
                builder.irCallConstructor(linkConstructor.symbol, emptyList()).apply {
                    arguments[0] = builder.irCallConstructor(endpointConstructor.symbol, emptyList()).apply {
                        arguments[0] = wasmline
                    }
                }
            }

            runtimeSymbols.isHostBindContractCall(call.symbol) ||
                runtimeSymbols.isHostBindSingleCall(call.symbol) ||
                runtimeSymbols.isHostBindAsCall(call.symbol) -> {
                val wasmline = call.extensionReceiverArgument() ?: return null
                val bindGenerated = runtimeSymbols.hostBindGeneratedFunction ?: return null
                val implementation = bindImplementationArgument(call, runtimeSymbols) ?: return null
                builder.irInvoke(
                    null,
                    bindGenerated,
                    buildBindBridge(builder, bridgeClass, contract, implementation),
                    extensionReceiver = wasmline,
                    typeHint = pluginContext.irBuiltIns.unitType,
                )
            }

            runtimeSymbols.isTopLevelBindContractCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindSingleCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindAsCall(call.symbol) -> {
                val bindGenerated = runtimeSymbols.topLevelBindGeneratedFunction ?: return null
                val implementation = bindImplementationArgument(call, runtimeSymbols) ?: return null
                builder.irInvoke(
                    null,
                    bindGenerated,
                    buildBindBridge(builder, bridgeClass, contract, implementation),
                    typeHint = pluginContext.irBuiltIns.unitType,
                )
            }

            else -> null
        }
    }

    private fun buildBindBridge(
        builder: DeclarationIrBuilder,
        bridgeClass: IrClass,
        contract: IrClass,
        implementation: IrExpression,
    ): IrExpression {
        val bindConstructor = bridgeClass.constructors.single {
            it.parameters.count { parameter -> parameter.kind == IrParameterKind.Regular } == 1 &&
                it.parameters.first { parameter -> parameter.kind == IrParameterKind.Regular }.type.classifierOrNull == contract.symbol
        }
        return builder.irCallConstructor(bindConstructor.symbol, emptyList()).apply {
            arguments[0] = implementation
        }
    }

    private fun bindImplementationArgument(
        call: IrCall,
        runtimeSymbols: WasmlineRuntimeSymbols,
    ): IrExpression? {
        return when {
            runtimeSymbols.isHostBindContractCall(call.symbol) || runtimeSymbols.isTopLevelBindContractCall(call.symbol) -> {
                call.regularValueArgument(1)
            }

            runtimeSymbols.isHostBindSingleCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindSingleCall(call.symbol) ||
                runtimeSymbols.isHostBindAsCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindAsCall(call.symbol) -> {
                call.regularValueArgument(0)
            }

            else -> null
        }
    }

    private fun scanContainer(container: IrDeclarationContainer, contracts: MutableList<IrClass>) {
        container.declarations.forEach { declaration ->
            when (declaration) {
                is IrClass -> {
                    if (declaration.kind == ClassKind.INTERFACE && declaration.isWasmlineServiceContract()) {
                        contracts += declaration
                    }
                    scanContainer(declaration, contracts)
                }
            }
        }
    }

    private fun validateIfServiceContract(irClass: IrClass, file: IrFile): Boolean {
        var isValid = true

        report(
            file = file,
            declaration = irClass,
            severity = CompilerMessageSeverity.INFO,
            message = "[Wasmline] discovered service contract ${irClass.fqNameWhenAvailable?.asString()}",
        )

        if (irClass.typeParameters.isNotEmpty()) {
            isValid = false
            reportError(file, irClass, "Generic Wasmline service contracts are not supported yet.")
        }

        val properties = irClass.declarations.filterIsInstance<IrProperty>()
        properties.forEach { property ->
            isValid = false
            reportError(file, property, "Wasmline service properties are not supported yet. Use functions instead.")
        }

        val functions = irClass.declarations.filterIsInstance<IrSimpleFunction>()
            .filterNot { it.name.isSpecial }
            .filterNot { it.isFakeOverride }

        functions.groupBy { it.name }
            .filterValues { it.size > 1 }
            .forEach { (name, overloads) ->
                overloads.forEach { function ->
                    isValid = false
                    reportError(
                        file,
                        function,
                        "Overloaded Wasmline service methods are not supported yet. Duplicate method name: ${name.asString()}",
                    )
                }
            }

        functions.forEach { function ->
            if (!validateFunction(function, file)) {
                isValid = false
            }
        }

        return isValid
    }

    private fun validateFunction(function: IrSimpleFunction, file: IrFile): Boolean {
        var isValid = true
        val regularParameters = function.parameters.filter { it.kind == IrParameterKind.Regular }
        if (function.visibility != DescriptorVisibilities.PUBLIC) {
            isValid = false
            reportError(file, function, "Wasmline service methods must be public.")
        }
        if (function.typeParameters.isNotEmpty()) {
            isValid = false
            reportError(file, function, "Generic Wasmline service methods are not supported yet.")
        }
        if (function.isSuspend) {
            isValid = false
            reportError(file, function, "Suspend Wasmline service methods are not supported in phase one.")
        }
        if (function.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }) {
            isValid = false
            reportError(file, function, "Extension receiver service methods are not supported yet.")
        }

        regularParameters.forEach { parameter ->
            if (parameter.defaultValue != null) {
                isValid = false
                reportError(file, parameter, "Default arguments are not supported in Wasmline service methods.")
            }
            if (parameter.varargElementType != null) {
                isValid = false
                reportError(file, parameter, "Vararg parameters are not supported in Wasmline service methods.")
            }
            if (parameter.type.isWasmlineServiceType()) {
                isValid = false
                reportError(file, parameter, "Passing service contracts as parameters is not supported in phase one.")
            }
            if (!parameter.type.isPhaseOnePayloadType()) {
                isValid = false
                reportError(file, parameter, "Phase-one Wasmline generation currently supports ByteArray parameters only.")
            }
        }

        if (regularParameters.size > 1) {
            isValid = false
            reportError(file, function, "Phase-one Wasmline generation currently supports at most one regular parameter.")
        }

        if (function.returnType.isWasmlineServiceType()) {
            isValid = false
            reportError(file, function, "Returning service contracts is not supported in phase one.")
        }
        if (!function.returnType.isPhaseOneReturnType()) {
            isValid = false
            reportError(file, function, "Phase-one Wasmline generation currently supports ByteArray or Unit returns only.")
        }

        return isValid
    }

    private fun resolveContractForTypedEntryPoint(
        call: IrCall,
        runtimeSymbols: WasmlineRuntimeSymbols,
        file: IrFile,
        ownerDeclaration: IrDeclaration,
    ): IrClass? {
        return when {
            runtimeSymbols.isHostLinkCall(call.symbol) ||
                runtimeSymbols.isHostBindAsCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindAsCall(call.symbol) -> {
                call.typeArguments.getOrNull(0)?.asWasmlineServiceContract()
            }

            runtimeSymbols.isHostBindContractCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindContractCall(call.symbol) -> {
                call.regularValueArgument(0)?.classLiteralContract()
            }

            runtimeSymbols.isHostBindSingleCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindSingleCall(call.symbol) -> {
                val implementationType = call.regularValueArgument(0)?.type ?: return null
                val contracts = implementationType.implementedWasmlineServiceContracts().toList()
                when (contracts.size) {
                    1 -> contracts.single()

                    0 -> {
                        reportError(
                            file,
                            ownerDeclaration,
                            "Unable to resolve a concrete Wasmline service contract for bind(implementation). " +
                                "Implementation type ${implementationType.renderForDiagnostics()} does not implement a WasmlineService interface. " +
                                "Use bindAs<Contract>(implementation) or bind(Contract::class, implementation).",
                        )
                        null
                    }

                    else -> {
                        reportError(
                            file,
                            ownerDeclaration,
                            buildString {
                                append("Ambiguous Wasmline bind(implementation) call. Implementation type ")
                                append(implementationType.renderForDiagnostics())
                                append(" matches multiple service contracts: ")
                                append(contracts.joinToString { it.fqNameWhenAvailable?.asString() ?: it.name.asString() })
                                append(". Use bindAs<Contract>(implementation) or bind(Contract::class, implementation) to disambiguate.")
                            },
                        )
                        null
                    }
                }
            }

            else -> null
        }
    }

    private fun generateBridge(
        contract: IrClass,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
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
            visibility = DescriptorVisibilities.INTERNAL
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

        bridgeClass.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.INTERNAL
        }.apply {
            val endpointParameter = addValueParameter("endpoint", runtimeSymbols.endpointType())
            irConstructorBody(pluginContext) { statements ->
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
                    value = irGet(endpointParameter),
                )
                statements += irSetField(
                    receiver = irGet(bridgeClass.thisReceiver!!),
                    field = implementationField,
                    value = irNull(),
                )
            }
        }

        bridgeClass.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.INTERNAL
        }.apply {
            val implementationParameter = addValueParameter("implementation", contract.defaultType)
            irConstructorBody(pluginContext) { statements ->
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
                    value = irGetObject(runtimeSymbols.unlinkedEndpointObject),
                )
                statements += irSetField(
                    receiver = irGet(bridgeClass.thisReceiver!!),
                    field = implementationField,
                    value = irGet(implementationParameter),
                )
            }
        }

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

        report(
            file = file,
            declaration = contract,
            severity = CompilerMessageSeverity.INFO,
            message = "[Wasmline] generated bridge ${bridgeName.asString()} for $fqName",
        )

        return bridgeClass
    }

    private fun generateBridgeContractMethod(
        bridgeClass: IrClass,
        endpointField: IrField,
        contractFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        fqName: String,
    ): IrSimpleFunction {
        val action = "$fqName#${contractFunction.name.asString()}"
        return pluginContext.irFactory.createSimpleFunction(
            startOffset = contractFunction.startOffset,
            endOffset = contractFunction.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = contractFunction.name,
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = contractFunction.returnType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = contractFunction.isOperator,
            isInfix = contractFunction.isInfix,
            isExternal = false,
            containerSource = null,
            isFakeOverride = false,
        ).apply {
            parent = bridgeClass
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

    private fun generateBridgeBindMethod(
        bridgeClass: IrClass,
        contractFunctions: List<IrSimpleFunction>,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        fqName: String,
    ): IrSimpleFunction {
        return pluginContext.irFactory.createSimpleFunction(
            startOffset = bridgeClass.startOffset,
            endOffset = bridgeClass.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("bind"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = pluginContext.irBuiltIns.unitType,
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
            parent = bridgeClass
            overriddenSymbols = listOf(runtimeSymbols.generatedBridgeBindFunction)
            parameters += buildReceiverParameter {
                type = bridgeClass.defaultType
            }
            val registerActionParameter = addValueParameter("registerAction", runtimeSymbols.actionRegistrarType())
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                contractFunctions.forEach { contractFunction ->
                    +irInvoke(
                        null,
                        runtimeSymbols.bindGeneratedBridgeActionFunction,
                        irString("$fqName#${contractFunction.name.asString()}"),
                        irGet(dispatchReceiverParameter!!),
                        irGet(registerActionParameter),
                    )
                }
            }
        }
    }

    private fun generateBridgeDispatcherMethod(
        bridgeClass: IrClass,
        implementationField: IrField,
        contract: IrClass,
        contractFunctions: List<IrSimpleFunction>,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        fqName: String,
    ): IrSimpleFunction {
        return pluginContext.irFactory.createSimpleFunction(
            startOffset = bridgeClass.startOffset,
            endOffset = bridgeClass.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("invoke"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = runtimeSymbols.byteArrayClass.owner.defaultType,
            modality = Modality.FINAL,
            symbol = IrSimpleFunctionSymbolImpl(),
            isTailrec = false,
            isSuspend = false,
            isOperator = true,
            isInfix = false,
            isExternal = false,
            containerSource = null,
            isFakeOverride = false,
        ).apply {
            parent = bridgeClass
            overriddenSymbols = listOf(runtimeSymbols.function2InvokeFunction)
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

    private fun IrCall.regularValueArgument(index: Int): IrExpression? {
        val parameter = symbol.owner.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .getOrNull(index)
            ?: return null
        return arguments[parameter.indexInParameters]
    }

    private fun IrCall.extensionReceiverArgument(): IrExpression? {
        val parameter = symbol.owner.parameters
            .firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            ?: return null
        return arguments[parameter.indexInParameters]
    }

    private fun irGetObject(symbol: IrClassSymbol): IrExpression {
        return IrGetObjectValueImpl(
            startOffset = SYNTHETIC_OFFSET,
            endOffset = SYNTHETIC_OFFSET,
            type = symbol.owner.defaultType,
            symbol = symbol,
        )
    }

    private fun IrExpression.classLiteralContract(): IrClass? {
        return (((this as? IrClassReference)?.symbol) as? IrClassSymbol)?.owner?.asWasmlineServiceContract()
    }

    private fun IrType.asWasmlineServiceContract(): IrClass? {
        val classSymbol = classifierOrNull as? IrClassSymbol ?: return null
        return classSymbol.owner.asWasmlineServiceContract()
    }

    private fun IrClass.asWasmlineServiceContract(): IrClass? {
        return takeIf { kind == ClassKind.INTERFACE && isWasmlineServiceContract() }
    }

    private fun IrType.implementedWasmlineServiceContracts(): Set<IrClass> {
        val classSymbol = classifierOrNull as? IrClassSymbol ?: return emptySet()
        return classSymbol.owner.implementedWasmlineServiceContracts()
    }

    private fun IrClass.implementedWasmlineServiceContracts(): Set<IrClass> {
        val result = linkedSetOf<IrClass>()
        collectImplementedWasmlineServiceContracts(result, linkedSetOf())
        return result
    }

    private fun IrClass.collectImplementedWasmlineServiceContracts(
        result: MutableSet<IrClass>,
        visited: MutableSet<IrClassSymbol>,
    ) {
        if (!visited.add(symbol)) return
        asWasmlineServiceContract()?.let { result += it }
        superTypes.mapNotNull { it.classifierOrNull as? IrClassSymbol }
            .forEach { superClass ->
                val owner = superClass.owner
                owner.asWasmlineServiceContract()?.let(result::add)
                owner.collectImplementedWasmlineServiceContracts(result, visited)
            }
    }

    private fun reportError(file: IrFile, declaration: IrDeclaration, message: String) {
        report(file, declaration, CompilerMessageSeverity.ERROR, message)
    }

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

    private fun report(
        file: IrFile,
        declaration: IrDeclaration,
        severity: CompilerMessageSeverity,
        message: String,
    ) {
        val sourceRange = file.fileEntry.getSourceRangeInfo(
            beginOffset = declaration.startOffset,
            endOffset = declaration.endOffset,
        )
        messageCollector.report(
            severity,
            message,
            CompilerMessageLocationWithRange.create(
                path = sourceRange.filePath,
                lineStart = sourceRange.startLineNumber + 1,
                columnStart = sourceRange.startColumnNumber + 1,
                lineEnd = sourceRange.endLineNumber + 1,
                columnEnd = sourceRange.endColumnNumber + 1,
                lineContent = null,
            ),
        )
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrClass.isWasmlineServiceContract(
        visited: MutableSet<IrClassSymbol> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(symbol)) return false
        return superTypes.any { superType ->
            val superClassSymbol = superType.classifierOrNull as? IrClassSymbol ?: return@any false
            val superClass = superClassSymbol.owner
            val superFqName = superClass.fqNameWhenAvailable?.asString()
            superFqName == WASMLINE_SERVICE_FQ_NAME || (superClass.kind == ClassKind.INTERFACE && superClass.isWasmlineServiceContract(visited))
        }
    }

    private fun IrType.isWasmlineServiceType(): Boolean {
        val classSymbol = classifierOrNull as? IrClassSymbol ?: return false
        return classSymbol.owner.isWasmlineServiceContract()
    }

    private fun IrType.isPhaseOnePayloadType(): Boolean {
        val classSymbol = classifierOrNull as? IrClassSymbol ?: return false
        return classSymbol.owner.fqNameWhenAvailable?.asString() == "kotlin.ByteArray"
    }

    private fun IrType.isPhaseOneReturnType(): Boolean = isKotlinUnitType() || isPhaseOnePayloadType()

    private companion object {
        const val WASMLINE_SERVICE_FQ_NAME = "crow.wasmline.WasmlineService"
    }
}

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

private fun IrType.isKotlinUnitType(): Boolean {
    val classSymbol = classifierOrNull as? IrClassSymbol ?: return false
    return classSymbol.owner.fqNameWhenAvailable?.asString() == "kotlin.Unit"
}

private fun IrType.renderForDiagnostics(): String {
    val classSymbol = classifierOrNull as? IrClassSymbol ?: return toString()
    return classSymbol.owner.fqNameWhenAvailable?.asString() ?: classSymbol.owner.name.asString()
}

