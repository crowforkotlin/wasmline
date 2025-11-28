@file:SuppressLint("SetTextI18n")

package crow.wasmtime.wasmline

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import crow.wasmtime.app.android.R
import crow.wasmtime.app.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val baseJson = Json {
    prettyPrint = true
    isLenient = true
}

@Serializable
data class Common(
    val id: Int,
    val datas: List<String>,
)

class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.load.setOnClickListener {
            binding.content.text = "Loading..."
            loadTest()
        }
    }


    private fun load() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val start = System.currentTimeMillis()
                // 1. 初始化 (全自动：有缓存读缓存，没缓存编译并存缓存)
                // 确保 assets 里放的是 plugin.wasm (源码)
                val engine = WasmEngine.loadFromAssets(
                    context = applicationContext,
                    assetName = "plugin.wasm",
                    cacheName = "plugin.cwasm"
                )

                // 2. 调用
                val result = engine.call("getUser", "{\"id\": 123}")
                "Result: $result".info()

                withContext(Dispatchers.Main) {
                    binding.content.text = "$result\n\n${System.currentTimeMillis() - start} MS"
                }
                // 3. 释放
                engine.close()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadTest() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 疯狂加载、销毁 50 次
            repeat(1000) { i ->
                Log.d("Test", "Cycle: $i")

                // 1. 加载
                val engine = WasmEngine.loadFromAssets(applicationContext, "plugin.wasm")
                engine.call("getUser", "...")
                engine.close() // 关闭句柄

                // 2. 销毁资源
                WasmEngine.freeAllResources()

                // 3. 稍微停顿一下（可选）
                delay(1)
            }
            "Test Finished".info()
        }
    }
}