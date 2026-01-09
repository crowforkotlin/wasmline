@file:OptIn(ExperimentalForeignApi::class)

package crow.mordecai.wasmline.native

// 导入生成的 CInterop 包
import crow.mordecai.wasmline.native.c.*
import kotlinx.cinterop.*
import platform.posix.* // 如果需要 malloc/free 等

fun testCLibrary() {
    // 1. 直接调用简单函数
    val sum = native_add(10, 20)
    println("C Sum: $sum")

    // 2. 处理结构体
    // C: MyPoint make_point(int x, int y);
    // Kotlin Native 里的 C 结构体通常是通过 CValue 传递（值传递）或 CPointer（引用）
    val point: CValue<MyPoint> = make_point(100, 200)

    // 使用 useContents 读取结构体内容
    point.useContents {
        println("Point from C: x=$x, y=$y")
    }

    // 3. 处理指针和内存 (最硬核的部分)
    // C: void native_greet(const char* name, char* buffer, int buffer_size);

    // memScoped 是自动内存管理块，出了这个块，内存自动释放 (相当于栈内存)
    memScoped {
        // 将 Kotlin String 转为 C 指针 (const char*)
        val cName = "Kotlin Native".cstr.ptr

        // 分配一段 C 的 buffer (char buffer[128])
        val bufferSize = 128
        val buffer = allocArray<ByteVar>(bufferSize)

        // 调用 C 函数
        native_greet("nasmeas", buffer, bufferSize)

        // 将 C 的结果 (char*) 转回 Kotlin String
        val resultString = buffer.toKString()
        println("Result from C: $resultString")
    }
}