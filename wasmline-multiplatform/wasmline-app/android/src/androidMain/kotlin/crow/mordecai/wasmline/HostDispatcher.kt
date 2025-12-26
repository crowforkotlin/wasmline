package crow.mordecai.wasmline

// 分发器接口
interface HostDispatcher {
    fun dispatch(action: String, payload: ByteArray): ByteArray
}