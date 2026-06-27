@file:OptIn(UnsafeDuringIrConstructionAPI::class)
@file:Suppress("SpellCheckingInspection")

package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.Name

private const val WASMLINE_INIT_EXPORT_NAME = "__wasmline_wasi_init"
private const val WASMLINE_ENTRY_EXPORT_NAME = "__wasmline_wasi_entry"

internal fun generateWasiEntryExport(
    moduleFragment: IrModuleFragment,
    pluginContext: IrPluginContext,
    runtimeSymbols: WasmlineRuntimeSymbols,
    messageCollector: MessageCollector,
) {
    if (!runtimeSymbols.canGenerateWasiEntryExport()) return

    val files = moduleFragment.files
    if (files.isEmpty()) return

    val functions = files.asSequence()
        .flatMap { file -> file.declarations.filterIsInstance<IrSimpleFunction>().asSequence() }

    val existingInit = functions.firstOrNull(::isManualWasmlineInit)
    if (existingInit == null) {
        val userMain = files.asSequence()
            .flatMap { file -> file.declarations.filterIsInstance<IrSimpleFunction>().asSequence() }
            .firstOrNull { function -> isUserMain(function, pluginContext) }
        if (userMain != null) {
            val initTargetFile = userMain.parent as? IrFile ?: files.first()
            initTargetFile.declarations += createWasmlineInitFunction(
                pluginContext = pluginContext,
                targetFile = initTargetFile,
                userMain = userMain,
                wasmExportAnnotationClass = runtimeSymbols.wasmExportAnnotationClass
                    ?: error("WasmExport annotation class is required when generating wasm exports"),
            )
            messageCollector.report(
                CompilerMessageSeverity.INFO,
                "[Wasmline] generated wasmWasi init export '$WASMLINE_INIT_EXPORT_NAME' for ${userMain.fqNameWhenAvailable?.asString() ?: userMain.name.asString()}.",
            )
        } else {
            messageCollector.report(
                CompilerMessageSeverity.INFO,
                "[Wasmline] skip generated wasm init export because no top-level main(): Unit was found.",
            )
        }
    }

    val existingEntry = files.asSequence()
        .flatMap { file -> file.declarations.filterIsInstance<IrSimpleFunction>().asSequence() }
        .firstOrNull(::isManualWasmlineEntry)
    if (existingEntry != null) {
        messageCollector.report(
            CompilerMessageSeverity.INFO,
            "[Wasmline] skip generated wasm entry export because user-defined ${existingEntry.fqNameWhenAvailable?.asString() ?: existingEntry.name.asString()} already exists.",
        )
        return
    }

    val targetFile = files.first()
    targetFile.declarations += createWasmlineEntryFunction(
        pluginContext = pluginContext,
        targetFile = targetFile,
        runtimeSymbols = runtimeSymbols,
    )

    messageCollector.report(
        CompilerMessageSeverity.INFO,
        buildString {
            append("[Wasmline] generated wasmWasi entry export '")
            append(WASMLINE_ENTRY_EXPORT_NAME)
            append("'")
        },
    )
}

private fun createWasmlineInitFunction(
    pluginContext: IrPluginContext,
    targetFile: IrFile,
    userMain: IrSimpleFunction,
    wasmExportAnnotationClass: IrClassSymbol,
): IrSimpleFunction {
    val generatedFunction = pluginContext.irFactory.createSimpleFunction(
        startOffset = SYNTHETIC_OFFSET,
        endOffset = SYNTHETIC_OFFSET,
        origin = IrDeclarationOrigin.DEFINED,
        name = Name.identifier(WASMLINE_INIT_EXPORT_NAME),
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
        parent = targetFile
    }

    val functionBuilder = DeclarationIrBuilder(pluginContext, generatedFunction.symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET)
    generatedFunction.annotations += IrAnnotationImpl.fromSymbolOwner(
        wasmExportAnnotationClass.defaultType,
        wasmExportAnnotationClass.owner.declarations
            .filterIsInstance<IrConstructor>()
            .single { constructor ->
                constructor.parameters.count { parameter -> parameter.kind == IrParameterKind.Regular } == 1
            }.symbol
    ).apply {
        arguments[0] = functionBuilder.irString(WASMLINE_INIT_EXPORT_NAME)
    }
    generatedFunction.irFunctionBody(
        context = pluginContext,
        scopeOwnerSymbol = generatedFunction.symbol,
    ) {
        +irCall(userMain.symbol)
    }
    return generatedFunction
}

private fun createWasmlineEntryFunction(
    pluginContext: IrPluginContext,
    targetFile: IrFile,
    runtimeSymbols: WasmlineRuntimeSymbols,
): IrSimpleFunction {
    val generatedFunction = pluginContext.irFactory.createSimpleFunction(
        startOffset = SYNTHETIC_OFFSET,
        endOffset = SYNTHETIC_OFFSET,
        origin = IrDeclarationOrigin.DEFINED,
        name = Name.identifier(WASMLINE_ENTRY_EXPORT_NAME),
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
        parent = targetFile
    }

    val actionLenParameter = generatedFunction.addValueParameter("actionLen", pluginContext.irBuiltIns.intType)
    val inputLenParameter = generatedFunction.addValueParameter("inputLen", pluginContext.irBuiltIns.intType)
    val functionBuilder = DeclarationIrBuilder(pluginContext, generatedFunction.symbol, SYNTHETIC_OFFSET, SYNTHETIC_OFFSET)
    val wasmExportAnnotationClass = runtimeSymbols.wasmExportAnnotationClass
        ?: error("WasmExport annotation class is required when generating the wasm entry export")
    generatedFunction.annotations += IrAnnotationImpl.fromSymbolOwner(
        wasmExportAnnotationClass.defaultType,
        wasmExportAnnotationClass.owner.declarations
            .filterIsInstance<IrConstructor>()
            .single { constructor ->
                constructor.parameters.count { parameter -> parameter.kind == IrParameterKind.Regular } == 1
            }.symbol
    ).apply {
        arguments[0] = functionBuilder.irString(WASMLINE_ENTRY_EXPORT_NAME)
    }

    generatedFunction.irFunctionBody(
        context = pluginContext,
        scopeOwnerSymbol = generatedFunction.symbol,
    ) {
        val wasmlineHandleInbound = runtimeSymbols.wasmlineHandleInboundFunction
            ?: error("wasmlineHandleInbound symbol is required when generating the wasm entry export")
        +irInvoke(
            null,
            wasmlineHandleInbound,
            irGet(actionLenParameter),
            irGet(inputLenParameter),
            typeHint = pluginContext.irBuiltIns.unitType,
        )
    }

    return generatedFunction
}

private fun isUserMain(function: IrSimpleFunction, pluginContext: IrPluginContext): Boolean {
    return function.name.asString() == "main" &&
        function.parent is IrFile &&
        function.parameters.none { it.kind == IrParameterKind.ExtensionReceiver } &&
        function.parameters.count { it.kind == IrParameterKind.Regular } == 0 &&
        function.returnType == pluginContext.irBuiltIns.unitType
}

private fun isManualWasmlineInit(function: IrSimpleFunction): Boolean {
    return function.name.asString() == WASMLINE_INIT_EXPORT_NAME &&
        function.parent is IrFile &&
        function.parameters.none { it.kind == IrParameterKind.ExtensionReceiver } &&
        function.parameters.count { it.kind == IrParameterKind.Regular } == 0
}

private fun isManualWasmlineEntry(function: IrSimpleFunction): Boolean {
    return function.name.asString() == WASMLINE_ENTRY_EXPORT_NAME &&
        function.parent is IrFile &&
        function.parameters.none { it.kind == IrParameterKind.ExtensionReceiver } &&
        function.parameters.count { it.kind == IrParameterKind.Regular } == 2
}
