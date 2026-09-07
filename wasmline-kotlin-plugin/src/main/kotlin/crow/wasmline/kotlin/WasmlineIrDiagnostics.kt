@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/** Reports one error-level diagnostic against the given declaration. */
internal fun reportError(messageCollector: MessageCollector, file: IrFile, declaration: IrDeclaration, message: String) {
    report(messageCollector, file, declaration, CompilerMessageSeverity.ERROR, message)
}

/** Reports one compiler diagnostic using the source range of the supplied declaration. */
internal fun report(
    messageCollector: MessageCollector,
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

/** Returns `true` when this IR type is Kotlin `Unit`. */
internal fun IrType.isKotlinUnitType(): Boolean {
    val classSymbol = classifierOrNull as? IrClassSymbol ?: return false
    return classSymbol.owner.fqNameWhenAvailable?.asString() == "kotlin.Unit"
}

/** Renders a stable human-readable type name for diagnostics. */
internal fun IrType.renderForDiagnostics(): String {
    val classSymbol = classifierOrNull as? IrClassSymbol ?: return toString()
    return classSymbol.owner.fqNameWhenAvailable?.asString() ?: classSymbol.owner.name.asString()
}
