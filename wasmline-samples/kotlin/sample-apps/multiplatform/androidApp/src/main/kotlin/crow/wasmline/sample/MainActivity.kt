@file:SuppressLint("SetTextI18n")
@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("SpellCheckingInspection")

package crow.wasmline.sample

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import crow.wasmline.sample.App
import crow.wasmline.Wasmline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.io.FileOutputStream
import kotlin.collections.get
import kotlin.time.measureTimedValue

class MainActivity : BaseActivity() {
    @Composable
    override fun composeContent() {
        MaterialTheme { AndroidApp() }
    }
}

fun test() {
    println("=== 开始 Ed25519 性能测试 ===")

    // 1. 生成密钥对计时
    val (keyPair, genTime) = measureTimedValue {
        Wasmline.nativeGenerateKeyPair()
    }
    val pubKey = keyPair[0] // 32 bytes
    val privKey = keyPair[1] // 64 bytes

    println("1. 密钥生成成功")
    println("   耗时: ${genTime.inWholeMilliseconds} ms (${genTime.inWholeMicroseconds} us)")
    // Base64 打印建议只在调试时开启，大量测试时可注释掉以观察纯算法耗时
    // println("   Public Key: ${Base64.encodeToString(pubKey, Base64.NO_WRAP)}")

    // 2. 准备数据
    val messageStr = "Wasmline AOT Manifest v1.0"
    val messageBytes = messageStr.encodeToByteArray()

    // 3. 签名计时 (关键性能点)
    val (signature, signTime) = measureTimedValue {
        Wasmline.nativeSign(privKey, messageBytes)
    }
    println("2. 签名生成成功")
    println("   耗时: ${signTime.inWholeMilliseconds} ms (${signTime.inWholeMicroseconds} us)")
    // println("   Signature: ${Base64.encodeToString(signature, Base64.NO_WRAP)}")

    // 4. 正向验证计时 (关键性能点)
    val (isValid, verifyTime) = measureTimedValue {
        Wasmline.nativeVerify(pubKey, messageBytes, signature)
    }
    println("3. 验证结果 (应为 true): $isValid")
    println("   耗时: ${verifyTime.inWholeMilliseconds} ms (${verifyTime.inWholeMicroseconds} us)")

    if (!isValid) {
        throw RuntimeException("严重错误：刚签名的据验证失败！")
    }

    // 5. 验证 - 反向测试 (篡改数据)
    val fakeMessage = "Wasmline AOT Manifest v2.0".encodeToByteArray()
    val (isFakeValid, fakeVerifyTime) = measureTimedValue {
        Wasmline.nativeVerify(pubKey, fakeMessage, signature)
    }
    println("4. 篡改数据验证 (应为 false): $isFakeValid")
    println("   耗时: ${fakeVerifyTime.inWholeMilliseconds} ms (${fakeVerifyTime.inWholeMicroseconds} us)")

    // 6. 验证 - 反向测试 (篡改签名)
    val fakeSig = signature.copyOf()
    fakeSig[0] = (fakeSig[0].toInt() xor 0xFF).toByte()
    val (isSigBroken, sigBrokenTime) = measureTimedValue {
        Wasmline.nativeVerify(pubKey, messageBytes, fakeSig)
    }
    println("5. 篡改签名验证 (应为 false): $isSigBroken")
    println("   耗时: ${sigBrokenTime.inWholeMilliseconds} ms (${sigBrokenTime.inWholeMicroseconds} us)")

    println("=== 测试结束 ===")
}


@Composable
fun AndroidApp() {
    val context = LocalContext.current
    Box(modifier = Modifier.statusBarsPadding()) {
        Button(onClick = {
            test()
        }) { }
        App(
            wasmPath = File(context.cacheDir, "plugin.pwasm").absolutePath,
        )
    }

    LaunchedEffect(Unit) {
        val wasmFilename = "plugin.pwasm"
        val wasmFile = File(context.cacheDir, wasmFilename)
        val cwasmFile = File(context.cacheDir, "plugin.pwasm")
        if (!wasmFile.exists()) {
            withContext(Dispatchers.IO) {
                context.assets.open(wasmFilename).use { input ->
                    FileOutputStream(wasmFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}