package crow.mordecai.wasmline

import App
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.window.ComposeUIViewController
import crow.mordecai.wasmline.extensions.info
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.toProtoBean
import crow.wasmline.sample.extensions.toProtoBytes
import crow.mordecai.wasmline.native.WasmlineBridge
import crow.wasmline.sample.extensions.toJsonString
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import kotlin.time.Clock

fun MainViewController() = ComposeUIViewController {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("Value is empty!") }
    var wasmline: Wasmline? by remember { mutableStateOf(null) }
    val wasmlineCall = {
        wasmline?.also { wl ->
            scope.launch {
                val start = Clock.System.now().toEpochMilliseconds()
                val callResult = wl.call(action = "timeSync", inputBytes = toProtoBytes(value = PlatformBean(
                    platform = "ios",
                    content = "IOS ^^^",
                    timeStr = Clock.System.now().toString(),
                    timeMs = Clock.System.now().toEpochMilliseconds()
                )))
                "spend time --> ${Clock.System.now().toEpochMilliseconds() - start} ms".info()
                val bean = toProtoBean<PlatformBean>(bytes = callResult)
                value = toJsonString(value = bean)
            }
        }
    }
    MaterialTheme {
        App(value) {
            WasmlineBridge.init()
            scope.launch {
                println("scope launch")
                val path = NSBundle.mainBundle.pathForResource("plugin", "cwasm") ?: return@launch
                println("path is : $path")
                val docDir = (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String)
                val cachePath = "$docDir/test.cwasm"
                println("----> $docDir \t cachePath : $cachePath \t path : $path")
                val state = Wasmline.load(path, path, true)
                if (state is WasmlineLoadState.Success) {
                    wasmline = state.wasmline
                    wasmlineCall.invoke()
                }
                println("Wasm State: $state")
            }
        }
    }
}



fun loadMyWasmOnIos() {
    // 1. 获取 Bundle 中的 .wasm 文件路径 (只读源文件)
    // 对应 Xcode 里的 test.wasm
    val wasmPath = NSBundle.mainBundle.pathForResource("test", "wasm")

    if (wasmPath == null) {
        println("❌ 错误：在 Bundle 中找不到 test.wasm，请检查 Build Phases -> Copy Bundle Resources")
        return
    }

    // 2. 准备缓存路径 (可写目录)
    // iOS 不允许在 Bundle 里写文件，所以 .cwasm 必须存到 Documents 目录
    val documentsPath = NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory,
        NSUserDomainMask,
        true
    ).first() as String

    val cachePath = "$documentsPath/test.cwasm"

    println("📂 Wasm 路径: $wasmPath")
    println("💾 缓存 路径: $cachePath")

    // 3. 调用加载
    // 这里需要在一个协程里调用，因为它被标记为 suspend
    // 实际使用时建议在 ViewModel 或 LaunchedEffect 里调用
    /*
    scope.launch {
        val result = Wasmline.load(
            filepath = wasmPath,
            cacheFilepath = cachePath, // 传 null 则不使用 AOT 缓存
            threadSafe = true
        )

        when (result) {
            is WasmlineLoadState.Success -> {
                println("✅ 加载成功！Engine Ready.")
                val output = result.wasmline.call("add", byteArrayOf(...))
            }
            is WasmlineLoadState.Failure -> {
                println("❌ 加载失败: ${result.cause}")
            }
        }
    }
    */
}