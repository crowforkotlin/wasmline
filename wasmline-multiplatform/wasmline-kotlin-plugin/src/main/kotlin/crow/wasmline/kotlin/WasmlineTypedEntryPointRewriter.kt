@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Rewrites typed Wasmline entrypoints such as `link()` and `bind()` into generated bridge usage.
 */
internal class WasmlineTypedEntryPointRewriter(
    private val messageCollector: MessageCollector,
) {
    /** Walks the module and rewrites typed entrypoints in-place after bridge generation. */
    fun rewrite(
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
                    return replaceTypedEntryPoint(
                        call = call,
                        file = file,
                        pluginContext = pluginContext,
                        runtimeSymbols = runtimeSymbols,
                        generatedBridges = generatedBridges,
                        ownerDeclaration = ownerDeclaration,
                    ) ?: transformed
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
                val bridgeConstructor = bridgeClass.constructors.single()
                builder.irCallConstructor(bridgeConstructor.symbol, emptyList()).apply {
                    arguments[0] = builder.irCallConstructor(endpointConstructor.symbol, emptyList()).apply {
                        arguments[0] = wasmline
                    }
                    arguments[1] = builder.irNull()
                }
            }

            runtimeSymbols.isHostBindContractCall(call.symbol) ||
                runtimeSymbols.isHostBindSingleCall(call.symbol) -> {
                val wasmline = call.extensionReceiverArgument() ?: return null
                val bindGenerated = runtimeSymbols.hostBindGeneratedFunction ?: return null
                val implementation = bindImplementationArgument(call, runtimeSymbols) ?: return null
                builder.irInvoke(
                    null,
                    bindGenerated,
                    buildBindBridge(builder, bridgeClass, implementation, runtimeSymbols),
                    extensionReceiver = wasmline,
                    typeHint = pluginContext.irBuiltIns.unitType,
                )
            }

            runtimeSymbols.isTopLevelBindContractCall(call.symbol) ||
                runtimeSymbols.isTopLevelBindSingleCall(call.symbol) -> {
                val bindGenerated = runtimeSymbols.topLevelBindGeneratedFunction ?: return null
                val implementation = bindImplementationArgument(call, runtimeSymbols) ?: return null
                builder.irInvoke(
                    null,
                    bindGenerated,
                    buildBindBridge(builder, bridgeClass, implementation, runtimeSymbols),
                    typeHint = pluginContext.irBuiltIns.unitType,
                )
            }

            else -> null
        }
    }

    private fun resolveContractForTypedEntryPoint(
        call: IrCall,
        runtimeSymbols: WasmlineRuntimeSymbols,
        file: IrFile,
        ownerDeclaration: IrDeclaration,
    ): IrClass? {
        return when {
            runtimeSymbols.isHostLinkCall(call.symbol) -> {
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
                            messageCollector,
                            file,
                            ownerDeclaration,
                            "Unable to resolve a concrete Wasmline service contract for bind(implementation). " +
                                "Implementation type ${implementationType.renderForDiagnostics()} does not implement a WasmlineService interface. " +
                                "Use bind(Contract::class, implementation) to disambiguate.",
                        )
                        null
                    }

                    else -> {
                        reportError(
                            messageCollector,
                            file,
                            ownerDeclaration,
                            buildString {
                                append("Ambiguous Wasmline bind(implementation) call. Implementation type ")
                                append(implementationType.renderForDiagnostics())
                                append(" matches multiple service contracts: ")
                                append(contracts.joinToString { it.fqNameWhenAvailable?.asString() ?: it.name.asString() })
                                append(". Use bind(Contract::class, implementation) to disambiguate.")
                            },
                        )
                        null
                    }
                }
            }

            else -> null
        }
    }
}

/** Builds the bridge instance used by rewritten bind entrypoints. */
internal fun buildBindBridge(
    builder: DeclarationIrBuilder,
    bridgeClass: IrClass,
    implementation: IrExpression,
    runtimeSymbols: WasmlineRuntimeSymbols,
): IrExpression {
    val bridgeConstructor = bridgeClass.constructors.single()
    return builder.irCallConstructor(bridgeConstructor.symbol, emptyList()).apply {
        arguments[0] = builder.irGetObject(runtimeSymbols.unlinkedEndpointObject)
        arguments[1] = implementation
    }
}

/** Reads the implementation argument for the currently supported bind overloads. */
internal fun bindImplementationArgument(
    call: IrCall,
    runtimeSymbols: WasmlineRuntimeSymbols,
): IrExpression? {
    return when {
        runtimeSymbols.isHostBindContractCall(call.symbol) || runtimeSymbols.isTopLevelBindContractCall(call.symbol) -> {
            call.regularValueArgument(1)
        }

        runtimeSymbols.isHostBindSingleCall(call.symbol) ||
            runtimeSymbols.isTopLevelBindSingleCall(call.symbol) -> {
            call.regularValueArgument(0)
        }

        else -> null
    }
}

/** Returns the regular value argument at the requested logical index. */
internal fun IrCall.regularValueArgument(index: Int): IrExpression? {
    val parameter = symbol.owner.parameters
        .filter { it.kind == IrParameterKind.Regular }
        .getOrNull(index)
        ?: return null
    return arguments[parameter.indexInParameters]
}

/** Returns the extension receiver argument when the call shape includes one. */
internal fun IrCall.extensionReceiverArgument(): IrExpression? {
    val parameter = symbol.owner.parameters
        .firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        ?: return null
    return arguments[parameter.indexInParameters]
}

/** Resolves a class literal expression such as `Foo::class` back to its contract type. */
internal fun IrExpression.classLiteralContract(): IrClass? {
    return (((this as? IrClassReference)?.symbol) as? IrClassSymbol)?.owner?.asWasmlineServiceContract()
}
