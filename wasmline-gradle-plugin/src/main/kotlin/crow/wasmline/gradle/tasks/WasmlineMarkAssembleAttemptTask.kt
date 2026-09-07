package crow.wasmline.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Marks an assemble invocation before its compilation dependencies are evaluated.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@DisableCachingByDefault(because = "The task records per-invocation execution state.")
internal abstract class WasmlineMarkAssembleAttemptTask : DefaultTask() {
    @get:Internal
    abstract val stateService: Property<WasmlineAssembleStateService>

    init {
        group = "wasmline"
        description = "Records a Wasmline assemble invocation for the compatibility checker"
        outputs.upToDateWhen { false }
    }

    /** Records the attempt in the shared Gradle invocation service. */
    @TaskAction
    fun markAttempt() {
        stateService.get().markAttempted()
    }
}
