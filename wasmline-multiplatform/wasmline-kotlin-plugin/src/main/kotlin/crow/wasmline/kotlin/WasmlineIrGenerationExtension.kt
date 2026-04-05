package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol

/**
 * Coordinates the Wasmline IR pipeline.
 *
 * This entrypoint keeps orchestration small and delegates validation, bridge generation, and
 * typed entrypoint rewriting to focused collaborators.
 */
internal class WasmlineIrGenerationExtension(
    private val messageCollector: MessageCollector,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val runtimeSymbols = WasmlineRuntimeSymbols(pluginContext)
        val validator = WasmlineServiceContractValidator(messageCollector)
        val typedEntryPointRewriter = WasmlineTypedEntryPointRewriter(messageCollector)
        val generatedBridges = linkedMapOf<IrClassSymbol, IrClass>()

        moduleFragment.files.forEach { file ->
            val contracts = mutableListOf<IrClass>()
            validator.scanContracts(file, contracts)
            contracts.forEach { contract ->
                if (validator.validate(contract, file)) {
                    generatedBridges[contract.symbol] = generateBridge(
                        contract = contract,
                        file = file,
                        pluginContext = pluginContext,
                        runtimeSymbols = runtimeSymbols,
                        messageCollector = messageCollector,
                    )
                }
            }
        }

        typedEntryPointRewriter.rewrite(
            moduleFragment = moduleFragment,
            pluginContext = pluginContext,
            runtimeSymbols = runtimeSymbols,
            generatedBridges = generatedBridges,
        )
    }
}
