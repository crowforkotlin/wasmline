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
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
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
                val artifactFile = File(cacheDir, "plugin.pwasm")
                "[Android] Artifact file : ${artifactFile.name}    ||    exists : ${artifactFile.exists()}    ||    path : ${artifactFile.absolutePath}".info()
                if (!artifactFile.exists()) {
                    assets.open(artifactFile.name).use { input ->
                        FileOutputStream(artifactFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                var startMs = System.currentTimeMillis()
                when(val loadState = Wasmline.load(artifactFile.absolutePath)) {
                    is WasmlineLoadState.Failure -> { loadState.cause.info() }
                    is WasmlineLoadState.Success -> {
                        if (loadState.code == WasmlineLoadState.CODE_SUCCESS_PULLEY) {
                            "[Wasmline] Load pulley success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        } else if (loadState.code == WasmlineLoadState.CODE_SUCCESS_AOT) {
                            "[Wasmline] Load aot success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        }
                        val module = loadState.wasmline
                        module.bind(object : EchoService {
                            override fun echo() {
                                "[Android] Plugin invoked host echo()".info()
                            }
                        })
                        startMs = System.currentTimeMillis()
                        val result = module.link<TimeSyncService>().timeSync(data)
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