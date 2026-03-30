// WITH_STDLIB

@file:JvmName("BindAsSelectsRequestedContractBox")

package test.box

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineService
import crow.wasmline.bindAs
import java.io.DataInputStream

interface AlphaService : WasmlineService {
    fun alpha(payload: ByteArray): ByteArray
}

interface BetaService : WasmlineService {
    fun beta(payload: ByteArray): ByteArray
}

private class MultiServiceImpl : AlphaService, BetaService {
    override fun alpha(payload: ByteArray): ByteArray = payload

    override fun beta(payload: ByteArray): ByteArray = payload
}

private object AlphaBinding {
    @Suppress("unused")
    suspend fun install(wasmline: Wasmline, implementation: MultiServiceImpl) {
        wasmline.bindAs<AlphaService>(implementation)
    }
}

private object BetaBinding {
    @Suppress("unused")
    suspend fun install(wasmline: Wasmline, implementation: MultiServiceImpl) {
        wasmline.bindAs<BetaService>(implementation)
    }
}

fun box(): String {
    if (runCatching { Class.forName("test.box.AlphaService_WasmlineDefinition") }.isSuccess) {
        return "Fail alphaLegacyDefinitionStillExists"
    }
    if (runCatching { Class.forName("test.box.BetaService_WasmlineDefinition") }.isSuccess) {
        return "Fail betaLegacyDefinitionStillExists"
    }

    val alphaBridge = Class.forName("test.box.AlphaService_WasmlineBridge")
    if (!AlphaService::class.java.isAssignableFrom(alphaBridge)) {
        return "Fail alphaBridgeContractType=${alphaBridge.name}"
    }
    if (alphaBridge.declaredConstructors.none { ctor -> ctor.parameterTypes.singleOrNull() == AlphaService::class.java }) {
        return "Fail alphaMissingImplementationCtor"
    }

    val betaBridge = Class.forName("test.box.BetaService_WasmlineBridge")
    if (!BetaService::class.java.isAssignableFrom(betaBridge)) {
        return "Fail betaBridgeContractType=${betaBridge.name}"
    }
    if (betaBridge.declaredConstructors.none { ctor -> ctor.parameterTypes.singleOrNull() == BetaService::class.java }) {
        return "Fail betaMissingImplementationCtor"
    }

    val alphaConstants = classUtf8Constants(AlphaBinding::class.java)
    if ("bindGenerated" !in alphaConstants) {
        return "Fail alphaMissingBindGenerated"
    }
    if ("bindAs" in alphaConstants) {
        return "Fail alphaStillReferencesBindAs"
    }
    if (alphaConstants.none { it.contains("AlphaService_WasmlineBridge") }) {
        return "Fail alphaMissingBridgeReference"
    }
    if (alphaConstants.any { it.contains("BetaService_WasmlineBridge") }) {
        return "Fail alphaReferencedWrongBridge"
    }

    val betaConstants = classUtf8Constants(BetaBinding::class.java)
    if ("bindGenerated" !in betaConstants) {
        return "Fail betaMissingBindGenerated"
    }
    if ("bindAs" in betaConstants) {
        return "Fail betaStillReferencesBindAs"
    }
    if (betaConstants.none { it.contains("BetaService_WasmlineBridge") }) {
        return "Fail betaMissingBridgeReference"
    }
    if (betaConstants.any { it.contains("AlphaService_WasmlineBridge") }) {
        return "Fail betaReferencedWrongBridge"
    }

    return "OK"
}

private fun classUtf8Constants(type: Class<*>): Set<String> {
    val resourcePath = type.name.replace('.', '/') + ".class"
    val stream = type.classLoader.getResourceAsStream(resourcePath)
        ?: error("Unable to read class bytes for ${type.name}")
    DataInputStream(stream.buffered()).use { input ->
        val magic = input.readInt()
        check(magic == 0xCAFEBABE.toInt()) { "Unexpected class file magic for ${type.name}: $magic" }
        input.readUnsignedShort()
        input.readUnsignedShort()
        val constantPoolCount = input.readUnsignedShort()
        val utf8Entries = linkedSetOf<String>()
        var index = 1
        while (index < constantPoolCount) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8Entries += input.readUTF()
                3, 4 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    index += 1
                }
                7, 8, 16, 19, 20 -> input.skipBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipBytes(4)
                15 -> input.skipBytes(3)
                else -> error("Unsupported constant-pool tag $tag for ${type.name}")
            }
            index += 1
        }
        return utf8Entries
    }
}

