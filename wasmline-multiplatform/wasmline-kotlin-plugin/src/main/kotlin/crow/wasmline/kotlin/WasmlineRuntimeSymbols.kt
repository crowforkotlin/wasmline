package crow.wasmline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@Suppress("DEPRECATION")
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class WasmlineRuntimeSymbols(
    private val pluginContext: IrPluginContext,
) {
    val serviceDefinitionClass: IrClassSymbol = requireClass("WasmlineServiceDefinition")
    val serviceIdClass: IrClassSymbol = requireClass("WasmlineServiceId")
    val endpointClass: IrClassSymbol = requireClass("WasmlineEndpoint")
    val bindingScopeClass: IrClassSymbol = requireClass("WasmlineBindingScope")

    val serviceDefinitionContractProperty: IrPropertySymbol = requireProperty(serviceDefinitionClass, "contract")
    val serviceDefinitionServiceIdProperty: IrPropertySymbol = requireProperty(serviceDefinitionClass, "serviceId")
    val serviceDefinitionLinkFunction: IrSimpleFunctionSymbol = requireFunction(serviceDefinitionClass, "link")
    val serviceDefinitionBindFunction: IrSimpleFunctionSymbol = requireFunction(serviceDefinitionClass, "bind")

    val serviceIdConstructor: IrConstructorSymbol = serviceIdClass.owner.declarations
        .filterIsInstance<IrConstructor>()
        .single()
        .symbol
    val kotlinErrorFunction: IrSimpleFunctionSymbol = pluginContext.referenceFunctions(
        CallableId(FqName("kotlin"), Name.identifier("error")),
    ).single()

    fun serviceDefinitionType(contract: IrClass): IrType {
        return serviceDefinitionClass.typeWith(contract.defaultType)
    }

    fun contractKClassType(contract: IrClass): IrType {
        return pluginContext.irBuiltIns.kClassClass.typeWith(contract.defaultType)
    }

    fun definitionObjectName(contract: IrClass): Name {
        return Name.identifier("${contract.name.identifier}_WasmlineDefinition")
    }

    fun proxyClassName(contract: IrClass): Name {
        return Name.identifier("${contract.name.identifier}_WasmlineProxy")
    }

    fun adapterClassName(contract: IrClass): Name {
        return Name.identifier("${contract.name.identifier}_WasmlineAdapter")
    }

    private fun requireClass(className: String): IrClassSymbol {
        return pluginContext.referenceClass(
            ClassId(FqName(RUNTIME_PACKAGE), Name.identifier(className)),
        ) ?: error("Unable to resolve Wasmline runtime class $RUNTIME_PACKAGE.$className")
    }

    private fun requireProperty(ownerClass: IrClassSymbol, name: String): IrPropertySymbol {
        return ownerClass.owner.declarations
            .filterIsInstance<IrProperty>()
            .single { it.name.asString() == name }
            .symbol
    }

    private fun requireFunction(ownerClass: IrClassSymbol, name: String): IrSimpleFunctionSymbol {
        return ownerClass.owner.declarations
            .filterIsInstance<IrSimpleFunction>()
            .single { function ->
                function.name.asString() == name &&
                    function.parameters.count { it.kind == IrParameterKind.Regular } <= 2
            }
            .symbol
    }

    private companion object {
        const val RUNTIME_PACKAGE = "crow.wasmline"
    }
}




