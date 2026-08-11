package crow.wasmline.plugin.core.component

import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.FileDigest
import crow.wasmline.plugin.core.toolchain.ToolExecutionResult
import java.io.File

/** Executes the full Wasmtime CLI to create native Component AOT artifacts. */
class ComponentCompiler internal constructor(private val runner: ComponentCompilerToolRunner) {
    constructor(logger: (String) -> Unit = {}) : this(ExternalComponentCompilerToolRunner(logger))

    /** Compiles every requested native target after verifying the exact compiler version. */
    fun compile(request: ComponentAotCompileRequest): ComponentAotCompileResult {
        validateRequest(request)
        verifyCompiler(request.wasmtimeCompiler, request.wasmtimeVersion)

        val outputs = request.targets.map { target -> compileTarget(request, target) }
        return ComponentAotCompileResult(
            inputComponent = request.inputComponent,
            inputComponentSha256 = FileDigest.sha256Hex(request.inputComponent),
            wasmtimeVersion = request.wasmtimeVersion,
            engineOptions = request.engineOptions,
            artifactMetadata = request.artifactMetadata,
            outputs = outputs,
        )
    }

    private fun validateRequest(request: ComponentAotCompileRequest) {
        require(request.wasmtimeCompiler.isFile) {
            "Wasmtime compiler does not exist: " + request.wasmtimeCompiler.absolutePath
        }
        require(request.wasmtimeCompiler.canExecute()) {
            "Wasmtime compiler is not executable: " + request.wasmtimeCompiler.absolutePath
        }
        require(!request.wasmtimeCompiler.nameWithoutExtension.equals("wasmtime-min", ignoreCase = true)) {
            "Component AOT compilation requires the full Wasmtime CLI; wasmtime-min is runtime-only."
        }
        require(request.inputComponent.isFile && request.inputComponent.length() > 0) {
            "Component Wasm input does not exist or is empty: " + request.inputComponent.absolutePath
        }
        val normalizedTargets = mutableSetOf<String>()
        request.targets.forEach { target ->
            val normalizedTarget = WasmtimeCompiler.normalizeTarget(target.target)
            require(normalizedTargets.add(normalizedTarget)) {
                "Duplicate Component AOT target after normalization: $normalizedTarget."
            }
            val targetCpu = normalizedTarget.substringBefore('-')
            val targetOs = WasmtimeCompiler().parseTarget(normalizedTarget).second
            require(targetOs != "ios") {
                "iOS Component artifacts must use portable pulley64 PWASM; use target 'pulley64' instead of '${target.target}'."
            }
            val isPulley = targetCpu == "pulley32" || targetCpu == "pulley64"
            when (target.backend) {
                ComponentAotBackend.CRANELIFT -> require(!targetCpu.startsWith("pulley")) {
                    "Cranelift Component targets cannot use a Pulley target: ${target.target}."
                }

                ComponentAotBackend.PULLEY -> require(isPulley) {
                    "Pulley Component targets must use pulley32 or pulley64: ${target.target}."
                }
            }
        }
    }

    private fun verifyCompiler(executable: File, expectedVersion: String) {
        val versionOutput = runChecked(executable, listOf("--version")).output
        verifyToolVersion("wasmtime", versionOutput, expectedVersion)
    }

    private fun compileTarget(request: ComponentAotCompileRequest, target: ComponentAotTarget): ComponentAotCompileOutput {
        val normalizedTarget = WasmtimeCompiler.normalizeTarget(target.target)
        target.outputFile.parentFile?.let { outputDirectory ->
            check(outputDirectory.exists() || outputDirectory.mkdirs()) {
                "Unable to create Component AOT output directory: " + outputDirectory.absolutePath
            }
        }
        if (target.outputFile.exists()) {
            check(target.outputFile.delete()) {
                "Unable to remove stale Component AOT output: " + target.outputFile.absolutePath
            }
        }

        runChecked(
            request.wasmtimeCompiler,
            compileArguments(request, target, normalizedTarget),
        )
        check(target.outputFile.isFile && target.outputFile.length() > 0) {
            "Wasmtime completed without producing Component AOT output: " + target.outputFile.absolutePath
        }

        val (targetCpu, targetOs) = WasmtimeCompiler().parseTarget(normalizedTarget)
        val artifact = WasmlineArtifact(
            type = target.backend.artifactType,
            url = target.outputFile.name,
            sha256 = FileDigest.sha256Hex(target.outputFile),
            targetCpu = targetCpu,
            targetOs = targetOs,
            targetCompilerVersion = "wasmtime-${request.wasmtimeVersion}",
            is64Bit = targetCpu.contains("64"),
            executionModel = request.artifactMetadata.executionModel,
            invocationProtocol = request.artifactMetadata.invocationProtocol,
            exportName = request.artifactMetadata.exportName,
            contractMetadata = request.artifactMetadata.contractMetadata,
        )
        return ComponentAotCompileOutput(
            requestedTarget = target.target,
            normalizedTarget = normalizedTarget,
            backend = target.backend,
            outputFile = target.outputFile,
            artifact = artifact,
        )
    }

    private fun compileArguments(request: ComponentAotCompileRequest, target: ComponentAotTarget, normalizedTarget: String): List<String> {
        val options = request.engineOptions
        return listOf(
            "compile",
            request.inputComponent.absolutePath,
            "-o",
            target.outputFile.absolutePath,
            "--target",
            normalizedTarget,
            "-W",
            "component-model=${options.componentModel.yesNo()}",
            "-C",
            "collector=${options.collector}",
            "-W",
            "gc=${options.gc.yesNo()}",
            "-W",
            "gc-support=${options.gcSupport.yesNo()}",
            "-W",
            "reference-types=${options.referenceTypes.yesNo()}",
            "-W",
            "function-references=${options.functionReferences.yesNo()}",
            "-W",
            "exceptions=${options.exceptions.yesNo()}",
            "-W",
            "threads=${options.threads.yesNo()}",
            "-W",
            "simd=${options.simd.yesNo()}",
            "-W",
            "relaxed-simd=${options.relaxedSimd.yesNo()}",
            "-W",
            "concurrency-support=${options.concurrencySupport.yesNo()}",
            "-W",
            "max-wasm-stack=${options.maxWasmStack}",
            "-O",
            "memory-guard-size=${options.memoryGuardSize}",
            "-O",
            "signals-based-traps=${options.signalsBasedTraps.yesNo()}",
            "-O",
            "opt-level=${options.optimizationLevel}",
            "-C",
            "cranelift-debug-verifier=${options.craneliftDebugVerifier.yesNo(trueValue = "yes", falseValue = "no")}",
        )
    }

    private fun Boolean.yesNo(trueValue: String = "y", falseValue: String = "n"): String = if (this) trueValue else falseValue

    private fun runChecked(executable: File, arguments: List<String>): ToolExecutionResult {
        val result = runner.run(executable, arguments)
        check(result.exitCode == 0) {
            "Wasmtime exited with code ${result.exitCode}: " + result.command.joinToString(" ") +
                if (result.output.isBlank()) "" else System.lineSeparator() + result.output
        }
        return result
    }
}

internal fun interface ComponentCompilerToolRunner {
    fun run(executable: File, arguments: List<String>): ToolExecutionResult
}

private class ExternalComponentCompilerToolRunner(logger: (String) -> Unit) : ComponentCompilerToolRunner {
    private val delegate = ExternalToolRunner(logger = logger)

    override fun run(executable: File, arguments: List<String>): ToolExecutionResult = delegate.run(executable, arguments)
}
