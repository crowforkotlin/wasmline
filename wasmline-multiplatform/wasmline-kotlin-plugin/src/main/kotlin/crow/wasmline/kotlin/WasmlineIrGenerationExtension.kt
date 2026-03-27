@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
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
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

internal class WasmlineIrGenerationExtension(
    private val messageCollector: MessageCollector,
) : IrGenerationExtension {

    /**
     * Phase-one validator only.
     *
     * Current errors are intentionally conservative so the first generated
     * Wasmline service pipeline can stabilize around a small subset:
     * contract discovery -> method identity -> proxy -> adapter -> runtime glue.
     *
     * These checks should be relaxed incrementally in later phases rather than
     * treated as permanent product limitations.
     */
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val runtimeSymbols = WasmlineRuntimeSymbols(pluginContext)
        moduleFragment.files.forEach { file ->
            val contracts = mutableListOf<IrClass>()
            scanContainer(file, contracts)
            contracts.forEach { contract ->
                if (validateIfServiceContract(contract, file)) {
                    generateDefinitionSkeleton(contract, file, pluginContext, runtimeSymbols)
                }
            }
        }
        autoRegisterTypedEntryPoints(moduleFragment, pluginContext, runtimeSymbols)
    }

    private fun autoRegisterTypedEntryPoints(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
    ) {
        val ownerSymbols = ArrayDeque<org.jetbrains.kotlin.ir.symbols.IrSymbol>()
        val ownerRegisteredContracts = ArrayDeque<MutableSet<IrClassSymbol>>()
        moduleFragment.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitFunction(declaration: IrFunction): IrStatement {
                ownerSymbols.addLast(declaration.symbol)
                ownerRegisteredContracts.addLast(linkedSetOf())
                return try {
                    super.visitFunction(declaration)
                } finally {
                    ownerRegisteredContracts.removeLast()
                    ownerSymbols.removeLast()
                }
            }

            override fun visitConstructor(declaration: IrConstructor): IrStatement {
                ownerSymbols.addLast(declaration.symbol)
                ownerRegisteredContracts.addLast(linkedSetOf())
                return try {
                    super.visitConstructor(declaration)
                } finally {
                    ownerRegisteredContracts.removeLast()
                    ownerSymbols.removeLast()
                }
            }

            override fun visitCall(expression: IrCall): IrExpression {
                val transformed = super.visitCall(expression)
                val transformedCall = transformed as? IrCall ?: return transformed
                val contract = resolveContractForAutoRegistration(transformedCall, runtimeSymbols) ?: return transformedCall
                val definitionSymbol = runtimeSymbols.definitionObjectSymbol(contract) ?: return transformed
                val definitionClass = definitionSymbol.owner
                val contractGetter = definitionClass.requirePropertyGetter(Name.identifier("contract"))
                val serviceIdGetter = definitionClass.requirePropertyGetter(Name.identifier("serviceId"))
                val linkerGetter = definitionClass.requirePropertyGetter(runtimeSymbols.linkerPropertyName())
                val binderGetter = definitionClass.requirePropertyGetter(runtimeSymbols.binderPropertyName())
                val identityTag = definitionClass.fqNameWhenAvailable?.asString() ?: definitionClass.name.asString()
                val ownerSymbol = ownerSymbols.lastOrNull() ?: return transformed
                val registeredContracts = ownerRegisteredContracts.lastOrNull() ?: return transformed
                if (!registeredContracts.add(contract.symbol)) {
                    return transformedCall
                }
                val builder = DeclarationIrBuilder(
                    generatorContext = pluginContext,
                    symbol = ownerSymbol,
                    startOffset = transformedCall.startOffset,
                    endOffset = transformedCall.endOffset,
                )
                return IrBlockImpl(
                    startOffset = transformedCall.startOffset,
                    endOffset = transformedCall.endOffset,
                    type = transformedCall.type,
                    origin = null,
                ).apply {
                    statements += builder.irInvoke(
                        null,
                        runtimeSymbols.registerGeneratedServiceFunction,
                        builder.irInvoke(irGetObject(definitionSymbol), contractGetter.symbol),
                        builder.irInvoke(irGetObject(definitionSymbol), serviceIdGetter.symbol),
                        builder.irInvoke(irGetObject(definitionSymbol), linkerGetter.symbol),
                        builder.irInvoke(irGetObject(definitionSymbol), binderGetter.symbol),
                        builder.irString(identityTag),
                    )
                    statements += transformedCall
                }
            }
        })
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

    private fun resolveContractForAutoRegistration(
        call: IrCall,
        runtimeSymbols: WasmlineRuntimeSymbols,
    ): IrClass? {
        return when (call.symbol) {
            runtimeSymbols.hostLinkFunction,
            runtimeSymbols.linkHostFunction,
            runtimeSymbols.hostBindAsFunction,
            runtimeSymbols.topLevelBindAsFunction,
            -> call.typeArguments.getOrNull(0)?.asWasmlineServiceContract()

            runtimeSymbols.hostBindContractFunction,
            runtimeSymbols.topLevelBindContractFunction,
            -> call.regularValueArgument(0)?.classLiteralContract()

            runtimeSymbols.hostBindSingleFunction,
            runtimeSymbols.topLevelBindSingleFunction,
            -> call.regularValueArgument(0)?.type?.implementedWasmlineServiceContracts()?.singleOrNull()

            else -> null
        }
    }

    private fun IrCall.regularValueArgument(index: Int): IrExpression? {
        val parameter = symbol.owner.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .getOrNull(index)
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

    private fun generateDefinitionSkeleton(
        contract: IrClass,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
    ) {
        val definitionName = runtimeSymbols.definitionObjectName(contract)
        if (file.declarations.filterIsInstance<IrClass>().any { it.name == definitionName }) return

        val irFactory = pluginContext.irFactory
        val fqName = contract.fqNameWhenAvailable?.asString() ?: contract.name.asString()
        val proxyClass = generateProxySkeleton(contract, file, pluginContext, runtimeSymbols, fqName)
        val proxyConstructor = proxyClass.constructors.single()
        val adapterClass = generateAdapterSkeleton(contract, file, pluginContext, runtimeSymbols, fqName)
        val adapterConstructor = adapterClass.constructors.single()
        val adapterBindFunction = adapterClass.declarations
            .filterIsInstance<IrSimpleFunction>()
            .single { it.name.asString() == "bind" }

        val definitionObject = irFactory.buildClass {
            initDefaults(contract)
            name = definitionName
            visibility = DescriptorVisibilities.INTERNAL
            kind = ClassKind.OBJECT
        }.apply {
            parent = file
            superTypes = listOf(pluginContext.irBuiltIns.anyType)
            createThisReceiverParameter()
        }

        definitionObject.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            irConstructorBody(pluginContext) { statements ->
                val anyClass = pluginContext.irBuiltIns.anyType.classifierOrNull as IrClassSymbol
                statements += irDelegatingConstructorCall(
                    context = pluginContext,
                    symbol = anyClass.owner.constructors.single().symbol,
                )
                statements += irInstanceInitializerCall(
                    context = pluginContext,
                    classSymbol = definitionObject.symbol,
                )
            }
        }

        definitionObject.declarations += irVal(
            pluginContext = pluginContext,
            propertyType = runtimeSymbols.contractKClassType(contract),
            declaringClass = definitionObject,
            propertyName = Name.identifier("contract"),
            visibility = DescriptorVisibilities.PUBLIC,
        ) {
            irExprBody(irKClass(contract))
        }

        definitionObject.declarations += irVal(
            pluginContext = pluginContext,
            propertyType = pluginContext.irBuiltIns.stringType,
            declaringClass = definitionObject,
            propertyName = Name.identifier("serviceId"),
            visibility = DescriptorVisibilities.PUBLIC,
        ) {
            irExprBody(irString(fqName))
        }

        val definitionLinkFunction = generateLinkStub(
            contract,
            definitionObject,
            pluginContext,
            runtimeSymbols,
            proxyConstructor,
        )
        val definitionBindFunction = generateBindStub(
            contract,
            definitionObject,
            pluginContext,
            runtimeSymbols,
            adapterConstructor,
            adapterBindFunction,
        )
        val linkerClass = generateDefinitionLinkerClass(
            contract = contract,
            file = file,
            pluginContext = pluginContext,
            runtimeSymbols = runtimeSymbols,
            definitionObject = definitionObject,
            definitionLinkFunction = definitionLinkFunction,
        )
        val binderClass = generateDefinitionBinderClass(
            contract = contract,
            file = file,
            pluginContext = pluginContext,
            runtimeSymbols = runtimeSymbols,
            definitionObject = definitionObject,
            definitionBindFunction = definitionBindFunction,
        )
        definitionObject.declarations += definitionLinkFunction
        definitionObject.declarations += definitionBindFunction
        definitionObject.declarations += irVal(
            pluginContext = pluginContext,
            propertyType = runtimeSymbols.linkerType(contract),
            declaringClass = definitionObject,
            propertyName = runtimeSymbols.linkerPropertyName(),
            visibility = DescriptorVisibilities.PUBLIC,
        ) {
            irExprBody(irCallConstructor(linkerClass.constructors.single().symbol, emptyList()))
        }
        definitionObject.declarations += irVal(
            pluginContext = pluginContext,
            propertyType = runtimeSymbols.binderType(contract),
            declaringClass = definitionObject,
            propertyName = runtimeSymbols.binderPropertyName(),
            visibility = DescriptorVisibilities.PUBLIC,
        ) {
            irExprBody(irCallConstructor(binderClass.constructors.single().symbol, emptyList()))
        }
        file.declarations += definitionObject

        report(
            file = file,
            declaration = contract,
            severity = CompilerMessageSeverity.INFO,
            message = "[Wasmline] generated definition skeleton ${definitionName.asString()} for $fqName " +
                "(planned proxy=${runtimeSymbols.proxyClassName(contract).asString()}, adapter=${runtimeSymbols.adapterClassName(contract).asString()})",
        )
    }

    private fun generateProxySkeleton(
        contract: IrClass,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        fqName: String,
    ): IrClass {
        val proxyName = runtimeSymbols.proxyClassName(contract)
        file.declarations.filterIsInstance<IrClass>().firstOrNull { it.name == proxyName }?.let { return it }

        val proxyClass = pluginContext.irFactory.buildClass {
            initDefaults(contract)
            name = proxyName
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = file
            superTypes = listOf(contract.defaultType)
            createThisReceiverParameter()
        }

        val endpointField = createPrivateField(
            owner = proxyClass,
            pluginContext = pluginContext,
            type = runtimeSymbols.actionInvokerType(),
            name = Name.identifier("invokeAction"),
        )
        proxyClass.declarations += endpointField

        proxyClass.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            val endpointParameter = addValueParameter("invokeAction", runtimeSymbols.actionInvokerType())
            irConstructorBody(pluginContext) { statements ->
                val anyClass = pluginContext.irBuiltIns.anyType.classifierOrNull as IrClassSymbol
                statements += irDelegatingConstructorCall(
                    context = pluginContext,
                    symbol = anyClass.owner.constructors.single().symbol,
                )
                statements += irInstanceInitializerCall(
                    context = pluginContext,
                    classSymbol = proxyClass.symbol,
                )
                statements += irSetField(
                    receiver = irGet(proxyClass.thisReceiver!!),
                    field = endpointField,
                    value = irGet(endpointParameter),
                )
            }
        }

        contract.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filterNot { it.name.isSpecial }
            .filterNot { it.isFakeOverride }
            .forEach { contractFunction ->
                proxyClass.declarations += generateProxyMethodStub(
                    proxyClass = proxyClass,
                    endpointField = endpointField,
                    contractFunction = contractFunction,
                    pluginContext = pluginContext,
                    runtimeSymbols = runtimeSymbols,
                    fqName = fqName,
                )
            }

        file.declarations += proxyClass
        return proxyClass
    }

    private fun generateAdapterSkeleton(
        contract: IrClass,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        fqName: String,
    ): IrClass {
        val adapterName = runtimeSymbols.adapterClassName(contract)
        file.declarations.filterIsInstance<IrClass>().firstOrNull { it.name == adapterName }?.let { return it }

        val adapterClass = pluginContext.irFactory.buildClass {
            initDefaults(contract)
            name = adapterName
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = file
            superTypes = listOf(pluginContext.irBuiltIns.anyType)
            createThisReceiverParameter()
        }

        val implementationField = createPrivateField(
            owner = adapterClass,
            pluginContext = pluginContext,
            type = contract.defaultType,
            name = Name.identifier("implementation"),
        )
        adapterClass.declarations += implementationField

        val contractFunctions = contract.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filterNot { it.name.isSpecial }
            .filterNot { it.isFakeOverride }

        val handlerConstructors = contractFunctions.associateWith { contractFunction ->
            generateAdapterHandlerClass(
                contract = contract,
                contractFunction = contractFunction,
                file = file,
                pluginContext = pluginContext,
                runtimeSymbols = runtimeSymbols,
            ).constructors.single()
        }

        adapterClass.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.PUBLIC
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
                    classSymbol = adapterClass.symbol,
                )
                statements += irSetField(
                    receiver = irGet(adapterClass.thisReceiver!!),
                    field = implementationField,
                    value = irGet(implementationParameter),
                )
            }
        }

        adapterClass.declarations += pluginContext.irFactory.createSimpleFunction(
            startOffset = contract.startOffset,
            endOffset = contract.endOffset,
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
            parent = adapterClass
            parameters += buildReceiverParameter {
                type = adapterClass.defaultType
            }
            val registerActionParameter = addValueParameter("registerAction", runtimeSymbols.actionRegistrarType())
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                val implementation = irGetField(
                    irGet(dispatchReceiverParameter!!),
                    implementationField,
                )
                contractFunctions.forEach { contractFunction ->
                    val action = "$fqName#${contractFunction.id}"
                    val handlerConstructor = handlerConstructors.getValue(contractFunction)
                    +irInvoke(
                        irGet(registerActionParameter),
                        runtimeSymbols.function2InvokeFunction,
                        irString(action),
                        irCallConstructor(handlerConstructor.symbol, emptyList()).apply {
                            arguments[0] = implementation
                        },
                    )
                }
            }
        }

        file.declarations += adapterClass
        return adapterClass
    }

    private fun generateAdapterHandlerClass(
        contract: IrClass,
        contractFunction: IrSimpleFunction,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
    ): IrClass {
        val handlerName = Name.identifier(
            "${contract.name.identifier}_${contractFunction.name.identifier}_${contractFunction.id}_WasmlineHandler",
        )
        file.declarations.filterIsInstance<IrClass>().firstOrNull { it.name == handlerName }?.let { return it }

        val handlerClass = pluginContext.irFactory.buildClass {
            initDefaults(contractFunction)
            name = handlerName
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = file
            superTypes = listOf(runtimeSymbols.actionHandlerType())
            createThisReceiverParameter()
        }

        val implementationField = createPrivateField(
            owner = handlerClass,
            pluginContext = pluginContext,
            type = contract.defaultType,
            name = Name.identifier("implementation"),
        )
        handlerClass.declarations += implementationField

        handlerClass.addConstructor {
            initDefaults(contractFunction)
            visibility = DescriptorVisibilities.PUBLIC
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
                    classSymbol = handlerClass.symbol,
                )
                statements += irSetField(
                    receiver = irGet(handlerClass.thisReceiver!!),
                    field = implementationField,
                    value = irGet(implementationParameter),
                )
            }
        }

        handlerClass.declarations += pluginContext.irFactory.createSimpleFunction(
            startOffset = contractFunction.startOffset,
            endOffset = contractFunction.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("invoke"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = runtimeSymbols.function1InvokeFunction.owner.returnType,
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
            parent = handlerClass
            overriddenSymbols = listOf(runtimeSymbols.function1InvokeFunction)
            parameters += buildReceiverParameter {
                type = handlerClass.defaultType
            }
            val payloadParameter = addValueParameter(
                "payload",
                runtimeSymbols.function1InvokeFunction.owner.parameters
                    .single { it.kind == IrParameterKind.Regular }
                    .type,
            )
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                val implementation = irGetField(
                    irGet(dispatchReceiverParameter!!),
                    implementationField,
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

                if (contractFunction.returnType.isUnit()) {
                    +implementationCall
                    +irReturn(
                        value = irInvoke(null, runtimeSymbols.emptyPayloadFunction),
                        returnTargetSymbol = symbol,
                    )
                } else {
                    +irReturn(
                        value = implementationCall,
                        returnTargetSymbol = symbol,
                    )
                }
            }
        }

        file.declarations += handlerClass
        return handlerClass
    }

    private fun generateProxyMethodStub(
        proxyClass: IrClass,
        endpointField: IrField,
        contractFunction: IrSimpleFunction,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        fqName: String,
    ): IrSimpleFunction {
        val action = "$fqName#${contractFunction.id}"
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
            parent = proxyClass
            overriddenSymbols = listOf(contractFunction.symbol)
            parameters += buildReceiverParameter {
                type = proxyClass.defaultType
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
                val dispatchReceiver = parameters.first { it.kind == IrParameterKind.DispatchReceiver }
                val endpoint = irGetField(
                    irGet(dispatchReceiver),
                    endpointField,
                )
                val payload = when (regularParameters.size) {
                    0 -> irInvoke(
                        null,
                        runtimeSymbols.emptyPayloadFunction,
                    )

                    else -> irGet(regularParameters.single())
                }
                val invokeCall = irInvoke(
                    endpoint,
                    runtimeSymbols.function2InvokeFunction,
                    irString(action),
                    payload,
                )
                if (contractFunction.returnType.isUnit()) {
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

    private fun generateLinkStub(
        contract: IrClass,
        definitionObject: IrClass,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        proxyConstructor: IrConstructor,
    ): IrSimpleFunction {
        return pluginContext.irFactory.createSimpleFunction(
            startOffset = contract.startOffset,
            endOffset = contract.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("link"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = contract.defaultType,
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
            parent = definitionObject
            parameters += buildReceiverParameter {
                type = definitionObject.defaultType
            }
            val endpointParameter = addValueParameter("invokeAction", runtimeSymbols.actionInvokerType())
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                +irReturn(
                    value = irCallConstructor(proxyConstructor.symbol, emptyList()).apply {
                        arguments[0] = irGet(endpointParameter)
                    },
                    returnTargetSymbol = symbol,
                )
            }
        }
    }

    private fun generateBindStub(
        contract: IrClass,
        definitionObject: IrClass,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        adapterConstructor: IrConstructor,
        adapterBindFunction: IrSimpleFunction,
    ): IrSimpleFunction {
        return pluginContext.irFactory.createSimpleFunction(
            startOffset = contract.startOffset,
            endOffset = contract.endOffset,
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
            parent = definitionObject
            parameters += buildReceiverParameter {
                type = definitionObject.defaultType
            }
            val implementationParameter = addValueParameter("implementation", contract.defaultType)
            val scopeParameter = addValueParameter("registerAction", runtimeSymbols.actionRegistrarType())
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                val adapter = irCallConstructor(adapterConstructor.symbol, emptyList()).apply {
                    arguments[0] = irGet(implementationParameter)
                }
                +irInvoke(
                    adapter,
                    adapterBindFunction.symbol,
                    irGet(scopeParameter),
                )
            }
        }
    }

    private fun generateDefinitionLinkerClass(
        contract: IrClass,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        definitionObject: IrClass,
        definitionLinkFunction: IrSimpleFunction,
    ): IrClass {
        val linkerName = Name.identifier("${contract.name.identifier}_WasmlineLinker")
        file.declarations.filterIsInstance<IrClass>().firstOrNull { it.name == linkerName }?.let { return it }

        val linkerClass = pluginContext.irFactory.buildClass {
            initDefaults(contract)
            name = linkerName
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = file
            superTypes = listOf(runtimeSymbols.linkerType(contract))
            createThisReceiverParameter()
        }

        linkerClass.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            irConstructorBody(pluginContext) { statements ->
                val anyClass = pluginContext.irBuiltIns.anyType.classifierOrNull as IrClassSymbol
                statements += irDelegatingConstructorCall(
                    context = pluginContext,
                    symbol = anyClass.owner.constructors.single().symbol,
                )
                statements += irInstanceInitializerCall(
                    context = pluginContext,
                    classSymbol = linkerClass.symbol,
                )
            }
        }

        linkerClass.declarations += pluginContext.irFactory.createSimpleFunction(
            startOffset = contract.startOffset,
            endOffset = contract.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("invoke"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = contract.defaultType,
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
            parent = linkerClass
            overriddenSymbols = listOf(runtimeSymbols.function1InvokeFunction)
            parameters += buildReceiverParameter {
                type = linkerClass.defaultType
            }
            val invokeActionParameter = addValueParameter("invokeAction", runtimeSymbols.actionInvokerType())
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                +irReturn(
                    value = irInvoke(
                        irGetObject(definitionObject.symbol),
                        definitionLinkFunction.symbol,
                        irGet(invokeActionParameter),
                    ),
                    returnTargetSymbol = symbol,
                )
            }
        }

        file.declarations += linkerClass
        return linkerClass
    }

    private fun generateDefinitionBinderClass(
        contract: IrClass,
        file: IrFile,
        pluginContext: IrPluginContext,
        runtimeSymbols: WasmlineRuntimeSymbols,
        definitionObject: IrClass,
        definitionBindFunction: IrSimpleFunction,
    ): IrClass {
        val binderName = Name.identifier("${contract.name.identifier}_WasmlineBinder")
        file.declarations.filterIsInstance<IrClass>().firstOrNull { it.name == binderName }?.let { return it }

        val binderClass = pluginContext.irFactory.buildClass {
            initDefaults(contract)
            name = binderName
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
            kind = ClassKind.CLASS
        }.apply {
            parent = file
            superTypes = listOf(runtimeSymbols.binderType(contract))
            createThisReceiverParameter()
        }

        binderClass.addConstructor {
            initDefaults(contract)
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            irConstructorBody(pluginContext) { statements ->
                val anyClass = pluginContext.irBuiltIns.anyType.classifierOrNull as IrClassSymbol
                statements += irDelegatingConstructorCall(
                    context = pluginContext,
                    symbol = anyClass.owner.constructors.single().symbol,
                )
                statements += irInstanceInitializerCall(
                    context = pluginContext,
                    classSymbol = binderClass.symbol,
                )
            }
        }

        binderClass.declarations += pluginContext.irFactory.createSimpleFunction(
            startOffset = contract.startOffset,
            endOffset = contract.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            name = Name.identifier("invoke"),
            visibility = DescriptorVisibilities.PUBLIC,
            isInline = false,
            isExpect = false,
            returnType = pluginContext.irBuiltIns.unitType,
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
            parent = binderClass
            overriddenSymbols = listOf(runtimeSymbols.function2InvokeFunction)
            parameters += buildReceiverParameter {
                type = binderClass.defaultType
            }
            val implementationParameter = addValueParameter("implementation", contract.defaultType)
            val registerActionParameter = addValueParameter("registerAction", runtimeSymbols.actionRegistrarType())
            irFunctionBody(
                context = pluginContext,
                scopeOwnerSymbol = symbol,
            ) {
                +irInvoke(
                    irGetObject(definitionObject.symbol),
                    definitionBindFunction.symbol,
                    irGet(implementationParameter),
                    irGet(registerActionParameter),
                )
            }
        }

        file.declarations += binderClass
        return binderClass
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

    private fun IrClass.requirePropertyGetter(name: Name): IrSimpleFunction {
        return declarations
            .filterIsInstance<IrProperty>()
            .single { it.name == name }
            .getter
            ?: error("Missing getter for property ${name.asString()} on ${this.name.asString()}")
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

    private fun IrType.isUnit(): Boolean {
        val classSymbol = classifierOrNull as? IrClassSymbol ?: return false
        return classSymbol.owner.fqNameWhenAvailable?.asString() == "kotlin.Unit"
    }

    private fun IrType.isPhaseOneReturnType(): Boolean = isUnit() || isPhaseOnePayloadType()

    private companion object {
        const val WASMLINE_SERVICE_FQ_NAME = "crow.wasmline.WasmlineService"
    }
}

