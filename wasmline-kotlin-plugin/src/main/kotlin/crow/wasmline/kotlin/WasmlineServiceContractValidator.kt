@file:OptIn(UnsafeDuringIrConstructionAPI::class)

package crow.wasmline.kotlin

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * Finds Wasmline service contracts and validates the currently supported bridge rules.
 */
internal class WasmlineServiceContractValidator(private val messageCollector: MessageCollector) {
    /** Recursively collects interface declarations that ultimately extend `WasmlineService`. */
    fun scanContracts(container: IrDeclarationContainer, contracts: MutableList<IrClass>) {
        container.declarations.forEach { declaration ->
            when (declaration) {
                is IrClass -> {
                    if (declaration.kind == ClassKind.INTERFACE && declaration.isWasmlineServiceContract()) {
                        contracts += declaration
                    }
                    scanContracts(declaration, contracts)
                }
            }
        }
    }

    /** Validates one service contract and reports all diagnostics found in the current file. */
    fun validate(contract: IrClass, file: IrFile): Boolean {
        var isValid = true

        report(
            messageCollector = messageCollector,
            file = file,
            declaration = contract,
            severity = CompilerMessageSeverity.INFO,
            message = "[Wasmline] discovered service contract ${contract.fqNameWhenAvailable?.asString()}",
        )

        if (contract.typeParameters.isNotEmpty()) {
            isValid = false
            reportError(messageCollector, file, contract, "Generic Wasmline service contracts are not supported yet.")
        }

        contract.declarations.filterIsInstance<IrProperty>().forEach { property ->
            isValid = false
            reportError(messageCollector, file, property, "Wasmline service properties are not supported yet. Use functions instead.")
        }

        val functions = contract.declarations.filterIsInstance<IrSimpleFunction>()
            .filterNot { it.name.isSpecial }
            .filterNot { it.isFakeOverride }

        functions.groupBy { it.name }
            .filterValues { it.size > 1 }
            .forEach { (name, overloads) ->
                overloads.forEach { function ->
                    isValid = false
                    reportError(
                        messageCollector,
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
            reportError(messageCollector, file, function, "Wasmline service methods must be public.")
        }
        if (function.typeParameters.isNotEmpty()) {
            isValid = false
            reportError(messageCollector, file, function, "Generic Wasmline service methods are not supported yet.")
        }
        if (function.isSuspend) {
            isValid = false
            reportError(messageCollector, file, function, "Suspend Wasmline service methods are not supported yet.")
        }
        if (function.parameters.any { it.kind == IrParameterKind.ExtensionReceiver }) {
            isValid = false
            reportError(messageCollector, file, function, "Extension receiver service methods are not supported yet.")
        }

        regularParameters.forEach { parameter ->
            if (parameter.defaultValue != null) {
                isValid = false
                reportError(messageCollector, file, parameter, "Default arguments are not supported in Wasmline service methods.")
            }
            if (parameter.varargElementType != null) {
                isValid = false
                reportError(messageCollector, file, parameter, "Vararg parameters are not supported in Wasmline service methods.")
            }
            if (parameter.type.isWasmlineServiceType()) {
                isValid = false
                reportError(messageCollector, file, parameter, "Passing service contracts as parameters is not supported yet.")
            }
        }

        if (function.returnType.isWasmlineServiceType()) {
            isValid = false
            reportError(messageCollector, file, function, "Returning service contracts is not supported yet.")
        }

        return isValid
    }

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    private fun IrClass.isWasmlineServiceContract(visited: MutableSet<IrClassSymbol> = mutableSetOf()): Boolean {
        if (!visited.add(symbol)) return false
        return superTypes.any { superType ->
            val superClassSymbol = superType.classifierOrNull as? IrClassSymbol ?: return@any false
            val superClass = superClassSymbol.owner
            val superFqName = superClass.fqNameWhenAvailable?.asString()
            superFqName == WASMLINE_SERVICE_FQ_NAME ||
                (superClass.kind == ClassKind.INTERFACE && superClass.isWasmlineServiceContract(visited))
        }
    }

    private fun IrType.isWasmlineServiceType(): Boolean {
        val classSymbol = classifierOrNull as? IrClassSymbol ?: return false
        return classSymbol.owner.isWasmlineServiceContract()
    }
    private companion object {
        const val WASMLINE_SERVICE_FQ_NAME = "crow.wasmline.WasmlineService"
    }
}

/** Returns this class only when it is a concrete Wasmline service contract interface. */
internal fun IrClass.asWasmlineServiceContract(): IrClass? {
    return takeIf {
        kind == ClassKind.INTERFACE &&
            superTypes.any { superType ->
                val superClassSymbol = superType.classifierOrNull as? IrClassSymbol ?: return@any false
                val superClass = superClassSymbol.owner
                val superFqName = superClass.fqNameWhenAvailable?.asString()
                superFqName == "crow.wasmline.WasmlineService" ||
                    (superClass.kind == ClassKind.INTERFACE && superClass.asWasmlineServiceContract() != null)
            }
    }
}

/** Resolves a type back to its Wasmline service contract, if one exists. */
internal fun IrType.asWasmlineServiceContract(): IrClass? {
    val classSymbol = classifierOrNull as? IrClassSymbol ?: return null
    return classSymbol.owner.asWasmlineServiceContract()
}

/** Collects every Wasmline service contract implemented by the receiver type. */
internal fun IrType.implementedWasmlineServiceContracts(): Set<IrClass> {
    val classSymbol = classifierOrNull as? IrClassSymbol ?: return emptySet()
    return classSymbol.owner.implementedWasmlineServiceContracts()
}

private fun IrClass.implementedWasmlineServiceContracts(): Set<IrClass> {
    val result = linkedSetOf<IrClass>()
    collectImplementedWasmlineServiceContracts(result, linkedSetOf())
    return result
}

private fun IrClass.collectImplementedWasmlineServiceContracts(result: MutableSet<IrClass>, visited: MutableSet<IrClassSymbol>) {
    if (!visited.add(symbol)) return
    asWasmlineServiceContract()?.let { result += it }
    superTypes.mapNotNull { it.classifierOrNull as? IrClassSymbol }
        .forEach { superClass ->
            val owner = superClass.owner
            owner.asWasmlineServiceContract()?.let(result::add)
            owner.collectImplementedWasmlineServiceContracts(result, visited)
        }
}
