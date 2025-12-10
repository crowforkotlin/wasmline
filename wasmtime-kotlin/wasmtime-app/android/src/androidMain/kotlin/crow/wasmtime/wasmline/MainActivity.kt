@file:SuppressLint("SetTextI18n")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmtime.wasmline

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import crow.wasmtime.Wasmline
import crow.wasmtime.WasmlineLoadState
import crow.wasmtime.app.android.R
import crow.wasmtime.app.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.FileOutputStream
import kotlin.system.measureTimeMillis

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
        init()
    }

    private fun init() {
        "[Wasmline] Init wasmtime spend ${measureTimeMillis { Wasmline.init() }} ms".info()
        binding.load.setOnClickListener {
            binding.content.text = "Loading..."
            runWasm()
        }
    }

    private fun runWasm() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                "==============================================".info()
                val data = ProtoBuf.encodeToByteArray(Data(1, "CrowF", "DataKey"))
                val wasmFile = File(cacheDir, "plugin.wasm")
                val cacheFile = File(cacheDir, "plugin.cwasm")
                "[Android] Wasm file : ${wasmFile.name}    ||    wasm file exits : ${wasmFile.exists()}    ||    wasm file path :  ${wasmFile.absolutePath}".info()
                "[Android] Cwasm cache file : ${cacheFile.name}    ||    cache file exits : ${cacheFile.exists()}    ||    cache file path :  ${cacheFile.absolutePath}".info()
                if (!wasmFile.exists()) {
                    assets.open(wasmFile.name).use { input ->
                        FileOutputStream(wasmFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                var startMs = System.currentTimeMillis()
                when(val loadState = Wasmline.load(wasmFile, cacheFile)) {
                    is WasmlineLoadState.Failure -> { loadState.cause.info() }
                    is WasmlineLoadState.Success -> {
                        if (loadState.code == WasmlineLoadState.CODE_SUCCESS_JIT) {
                            "[Wasmline] Load jit success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        } else if (loadState.code == WasmlineLoadState.CODE_SUCCESS_AOT) {
                            "[Wasmline] Load aot success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        }
                        loadState.wasmLine
//                start = System.currentTimeMillis()
//                val result = module.call("getUser", data)
//                val duration = System.currentTimeMillis() - start
//                "[android] MainActivity --> spend time call function --------> $duration ms.".info()
//                withContext(Dispatchers.Main) { binding.content.text = "Result: $result\n call function duration : ${duration} ms" }
                        // 4. (可选) 释放模块
//                 module.release()

                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.content.text = "Error: ${e.message}"
                }
            }
        }
    }
}