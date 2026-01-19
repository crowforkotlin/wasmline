package crow.mordecai.wasmline.sample.extensions

import crow.wasmline.sample.bean.PlatformBean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// @formatter:off

actual fun getPlatformBean(): PlatformBean {
    return PlatformBean(
        platform = "Desktop",
        content = "Hello from desktop",
        timeStr = SimpleDateFormat( "yyyy/MM/dd HH:mm:ss", Locale.getDefault() ).format(Date(System.currentTimeMillis())),
        timeMs = System.currentTimeMillis()
    )
}