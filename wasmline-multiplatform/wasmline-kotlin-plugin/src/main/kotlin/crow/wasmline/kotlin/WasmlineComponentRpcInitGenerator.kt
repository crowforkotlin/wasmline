@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName

private val COMPONENT_RPC_INIT_ANNOTATION = FqName("crow.wasmline.WasmlineComponentRpcInit")

internal fun wireComponentRpcInitHook(
    moduleFragment: IrModuleFragment,
    pluginContext: IrPluginContext,
    messageCollector: MessageCollector,
    rewrittenBindCalls: Int,
) {
    val functions = moduleFragment.files.flatMap { file -> file.declarations.filterIsInstance<IrSimpleFunction>() }
    val markers = functions.filter { it.hasAnnotation(COMPONENT_RPC_INIT_ANNOTATION) }
    if (markers.size != 1) {
        messageCollector.report(
            CompilerMessageSeverity.ERROR,
            "[Wasmline] Component RPC requires exactly one generated init marker, found ${markers.size}. " +
                "Run the Wasmline Component RPC binding generation task and do not declare this marker manually.",
        )
        return
    }

    val mains = functions.filter { function -> isComponentRpcUserMain(function, pluginContext) }
    if (mains.size > 1) {
        messageCollector.report(
            CompilerMessageSeverity.ERROR,
            "[Wasmline] Component RPC found multiple top-level main(): Unit initializers: " +
                mains.joinToString { it.fqNameWhenAvailable?.asString() ?: it.name.asString() } + ".",
        )
        return
    }
    val userMain = mains.singleOrNull()
    if (userMain == null) {
        if (rewrittenBindCalls > 0) {
            messageCollector.report(
                CompilerMessageSeverity.ERROR,
                "[Wasmline] Component RPC rewrote $rewrittenBindCalls bind call(s), but no top-level main(): Unit " +
                    "initializer was found.",
            )
        }
        return
    }

    val marker = markers.single()
    marker.irFunctionBody(pluginContext, marker.symbol) {
        +irCall(userMain.symbol)
    }
    messageCollector.report(
        CompilerMessageSeverity.INFO,
        "[Wasmline] wired Component RPC one-shot init marker to " +
            (userMain.fqNameWhenAvailable?.asString() ?: userMain.name.asString()) + ".",
    )
}

private fun isComponentRpcUserMain(function: IrSimpleFunction, pluginContext: IrPluginContext): Boolean =
    function.name.asString() == "main" &&
        function.parent is IrFile &&
        function.parameters.none { it.kind == IrParameterKind.ExtensionReceiver } &&
        function.parameters.count { it.kind == IrParameterKind.Regular } == 0 &&
        function.returnType == pluginContext.irBuiltIns.unitType
