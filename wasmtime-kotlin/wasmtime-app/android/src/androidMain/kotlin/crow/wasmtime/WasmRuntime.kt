package crow.wasmtime

object WasmRuntime {
    init {
        System.loadLibrary("wasmline")
    }

    /**
     * 初始化全局 Engine。
     * 建议在 Application onCreate 中调用。
     */
    fun init() {
        nativeInit()
    }

    /**
     * 释放全局 Engine 和所有缓存的 Module。
     * 建议在确定不再使用 Wasm 时调用，或者 Activity onDestroy。
     */
    fun release() {
        nativeRelease()
    }

    private external fun nativeInit()
    private external fun nativeRelease()
}