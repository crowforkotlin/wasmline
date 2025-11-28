@file:SuppressLint("SetTextI18n")

package crow.wasmtime.wasmline

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import crow.wasmtime.WasmModule
import crow.wasmtime.WasmRuntime
import crow.wasmtime.app.android.R
import crow.wasmtime.app.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
        WasmRuntime.init()
        binding.load.setOnClickListener {
            binding.content.text = "Loading..."
            runWasm()
        }
    }


    private fun runWasm() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 准备文件：将 assets 里的 plugin.wasm 拷贝到 cache 目录
                // 因为我们的 C++ 层现在只接受文件路径，防止 OOM
                val wasmFile = File(cacheDir, "plugin.wasm")
                val cacheFile = File(cacheDir, "plugin.cwasm") // 编译后的缓存文件

                // 模拟拷贝 (如果文件不存在)
                "wasm file ${wasmFile.exists()}".info()
                if (!wasmFile.exists()) {
                    assets.open("plugin.wasm").use { input ->
                        FileOutputStream(wasmFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                val start = System.currentTimeMillis()

                // 2. 加载模块
                // 第一次运行会编译源码并生成 cwasm
                // 第二次运行直接加载 cwasm，速度极快
                val module = WasmModule.load(wasmFile, cacheFile)

                // 3. 执行调用
                val result = module.call("getUser", "{\"id\": 1001}")

                withContext(Dispatchers.Main) {
                    binding.content.text = "Result: $result\nTime: ${System.currentTimeMillis() - start}ms"
                }

                // 4. (可选) 释放模块
                // module.release()

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.content.text = "Error: ${e.message}"
                }
            }
        }
    }

}