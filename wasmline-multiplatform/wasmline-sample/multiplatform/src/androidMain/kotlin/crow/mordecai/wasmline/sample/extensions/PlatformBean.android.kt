package crow.mordecai.wasmline.sample.extensions

import crow.wasmline.sample.bean.PlatformBean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun getPlatformBean(): PlatformBean {
    return PlatformBean(
        platform = "Android",
        content = "Hello from android",
        timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(System.currentTimeMillis())),
        timeMs = System.currentTimeMillis()
    )
}