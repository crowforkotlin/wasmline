package crow.wasmline.gradle.tasks

import org.gradle.api.provider.ListProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskSuccessResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configures the task paths observed by one assemble state service.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal interface WasmlineAssembleStateParameters : BuildServiceParameters {
    val assembleTaskPaths: ListProperty<String>
}

/**
 * Tracks whether a Wasmline assemble task completed successfully in this Gradle invocation.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal abstract class WasmlineAssembleStateService :
    BuildService<WasmlineAssembleStateParameters>,
    OperationCompletionListener,
    AutoCloseable {
    private val attemptedAssemble = AtomicInteger(0)
    private val successfulAssemble = AtomicBoolean(false)

    /** Records an assemble invocation before its build dependencies execute. */
    fun markAttempted() {
        attemptedAssemble.incrementAndGet()
    }

    /** Records a completed assemble after its package transaction has been committed. */
    fun markSuccessful() {
        successfulAssemble.set(true)
    }

    /** Returns whether at least one assemble task completed successfully in this invocation. */
    fun hasSuccessfulAssemble(): Boolean = successfulAssemble.get()

    /** Returns whether an automatic finalizer should run for this invocation. */
    fun shouldRunAutomaticCheck(): Boolean = attemptedAssemble.get() == 0 || hasSuccessfulAssemble()

    override fun onFinish(event: FinishEvent) {
        val taskFinish = event as? TaskFinishEvent ?: return
        if (taskFinish.descriptor.taskPath !in parameters.assembleTaskPaths.get()) return
        if (taskFinish.result is TaskSuccessResult) markSuccessful()
    }

    override fun close() = Unit
}
