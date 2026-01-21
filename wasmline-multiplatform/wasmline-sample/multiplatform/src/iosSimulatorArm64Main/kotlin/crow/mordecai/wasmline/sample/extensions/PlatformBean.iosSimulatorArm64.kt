package crow.mordecai.wasmline.sample.extensions

import crow.wasmline.sample.bean.PlatformBean
import kotlin.time.Clock

actual fun getPlatformBean(): PlatformBean {
    return PlatformBean(
        platform = "IOS",
        content = "Hello from ios",
        timeStr = Clock.System.now().toString(),
        timeMs = Clock.System.now().toEpochMilliseconds()
    )
}