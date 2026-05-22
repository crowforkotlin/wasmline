package crow.wasmline.sample.extensions

import crow.wasmline.sample.bean.PlatformBean
import kotlin.time.Clock

// @formatter:off

actual fun getPlatformBean(): PlatformBean {
    return PlatformBean(
        platform = "Web JS",
        content = "Hello from browser js",
        timeStr = Clock.System.now().toString(),
        timeMs = Clock.System.now().toEpochMilliseconds()
    )
}