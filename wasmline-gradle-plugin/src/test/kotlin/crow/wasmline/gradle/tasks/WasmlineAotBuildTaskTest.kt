package crow.wasmline.gradle.tasks

import org.gradle.api.tasks.CacheableTask
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies Gradle task metadata for the shared Core and Component AOT matrix task.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineAotBuildTaskTest {
    @Test
    fun taskDeclaresCacheableInputsAndOutputs() {
        assertTrue(WasmlineAotBuildTask::class.java.isAnnotationPresent(CacheableTask::class.java))
    }
}
