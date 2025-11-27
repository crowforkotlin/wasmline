package crow.wasmtime.wasmline

import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import crow.wasmtime.app.android.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("wasmline")
        }
    }

    // 定义 Native 方法
    // 传入 wasm 文件的字节数组，返回计算结果
    private external fun runWasmAdd(wasmBytes: ByteArray): Int

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 从 assets 目录读取 add.wasm
                // 确保你把 add.wasm 放到了 src/main/assets/ 目录下
                val inputStream = assets.open("add.wasm")
                val wasmBytes = inputStream.readBytes()
                inputStream.close()

                Log.d("WasmHost", "Wasm file read, size: ${wasmBytes.size} bytes")

                // 2. 调用 Native 执行
                val result = runWasmAdd(wasmBytes)

                Log.d("WasmHost", "Execution Result: $result")

            } catch (e: IOException) {
                Log.e("WasmHost", "Failed to read wasm file", e)
            } catch (e: Exception) {
                Log.e("WasmHost", "Native execution failed", e)
            }
        }
    }
}