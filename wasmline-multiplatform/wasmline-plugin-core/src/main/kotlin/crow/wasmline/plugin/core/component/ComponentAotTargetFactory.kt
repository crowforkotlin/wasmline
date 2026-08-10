package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import java.io.File

/** Creates deterministic native Component outputs for Gradle and CLI adapters. */
object ComponentAotTargetFactory {
    fun create(outputDirectory: File, productName: String, targets: Collection<String>): List<ComponentAotTarget> {
        require(productName.matches(SAFE_NAME)) {
            "Component AOT product name may contain only letters, digits, dot, underscore and dash."
        }
        val effectiveTargets = targets.ifEmpty {
            WasmtimeCompiler.defaultTargets.filterNot(::isIosTarget)
        }
        return effectiveTargets.map { target ->
            require(target.matches(SAFE_NAME)) {
                "Component AOT target may contain only letters, digits, dot, underscore and dash: $target"
            }
            require(!isIosTarget(target)) {
                "iOS Component AOT uses portable pulley64 PWASM; direct iOS CWASM target '$target' is not supported."
            }
            val targetCpu = WasmtimeCompiler.normalizeTarget(target).substringBefore('-')
            val backend = when (targetCpu) {
                "pulley32", "pulley64" -> ComponentAotBackend.PULLEY

                else -> {
                    require(!targetCpu.startsWith("pulley")) {
                        "Unsupported Pulley Component target: $target"
                    }
                    ComponentAotBackend.CRANELIFT
                }
            }
            ComponentAotTarget(
                target = target,
                backend = backend,
                outputFile = File(outputDirectory, "$productName-$target.${backend.fileExtension}"),
            )
        }
    }

    private fun isIosTarget(target: String): Boolean = WasmtimeCompiler().parseTarget(target).second == "ios"

    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
}
