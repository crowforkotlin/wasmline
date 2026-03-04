@file:SuppressLint("SetTextI18n")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import crow.wasmline.extensions.Data
import crow.wasmline.extensions.info
import crow.wasmline.sample.android.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.io.FileOutputStream
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val baseJson = Json { isLenient = true; prettyPrint = true; }
    private val baseProtobuf = ProtoBuf { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        init()
    }

    private fun init() {
        "[Android] Init wasmtime spend ${measureTimeMillis { Wasmline.init() }} ms".info()
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
                when(val loadState = Wasmline.load(wasmFile.absolutePath, cacheFile.absolutePath)) {
                    is WasmlineLoadState.Failure -> { loadState.cause.info() }
                    is WasmlineLoadState.Success -> {
                        if (loadState.code == WasmlineLoadState.CODE_SUCCESS_JIT) {
                            "[Wasmline] Load jit success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        } else if (loadState.code == WasmlineLoadState.CODE_SUCCESS_AOT) {
                            "[Wasmline] Load aot success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        }
                        val module = loadState.wasmline
                        startMs = System.currentTimeMillis()
                        module.setOutbound(dispatcher = { action, payload ->
                            "[Android] receive wasm action is : $action \t payload is $payload".info()
                            byteArrayOf()
                        })
//                        module.call("init", data)
                        val result = module.call("add", data)
                        val duration = System.currentTimeMillis() - startMs
                        "[Android] MainActivity --> spend time invokeInbound function --------> $duration ms.".info()
                        withContext(Dispatchers.Main) { binding.content.text = "Result : \n\n${baseJson.encodeToString(ProtoBuf.decodeFromByteArray<Data>(result))}\n\ncall function duration : $duration ms" }
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