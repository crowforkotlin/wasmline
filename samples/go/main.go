package main

/*
// =================================================================================
// CGO 跨平台编译配置
// =================================================================================

// 1. 通用设置: 包含 C++ 源码和头文件
// ---------------------------------------------------------------------------------
#cgo CXXFLAGS: -std=c++17 -DWASM_LOGS_ENABLED=1
#cgo CXXFLAGS: -I${SRCDIR}/../../wasmtime-cpp/include
#cgo CXXFLAGS: -I${SRCDIR}/../../wasmtime-cpp/src

// 2. Windows (amd64/x64)
// ---------------------------------------------------------------------------------
// 头文件在 platforms/windows/x64/include
#cgo windows,amd64 CXXFLAGS: -I${SRCDIR}/../../platforms/windows/x64/include
// 链接库: MinGW 能够识别 .dll，直接链接 -lwasmtime.dll
#cgo windows,amd64 LDFLAGS: -L${SRCDIR}/../../platforms/windows/x64/lib -lwasmtime.dll

// 3. MacOS (Apple Silicon / arm64)
// ---------------------------------------------------------------------------------
#cgo darwin,arm64 CXXFLAGS: -I${SRCDIR}/../../platforms/mac/aarch64/include
#cgo darwin,arm64 LDFLAGS: -L${SRCDIR}/../../platforms/mac/aarch64/lib -lwasmtime

// 4. MacOS (Intel / x86_64)
// ---------------------------------------------------------------------------------
#cgo darwin,amd64 CXXFLAGS: -I${SRCDIR}/../../platforms/mac/x86_64/include
#cgo darwin,amd64 LDFLAGS: -L${SRCDIR}/../../platforms/mac/x86_64/lib -lwasmtime

// 5. Linux (amd64)
// ---------------------------------------------------------------------------------
#cgo linux,amd64 CXXFLAGS: -I${SRCDIR}/../../platforms/linux/x86_64/include
#cgo linux,amd64 LDFLAGS: -L${SRCDIR}/../../platforms/linux/x86_64/lib -lwasmtime -lpthread -ldl

#include <stdlib.h>
#include "GoBridge.h"
*/
import "C"

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
	"unsafe"
)

func main() {
	// 1. 初始化引擎
	C.Bridge_InitEngine()
	defer C.Bridge_ReleaseEngine()

	// 2. 准备参数
	targetWasm := "plugin.wasm"
	if len(os.Args) > 1 {
		targetWasm = os.Args[1]
	}

	// 模拟数据 {1, 2}
	inputData := []byte{1, 2}

	fmt.Println("=== [PASS 1] First Run (Expect JIT + Save Cache) ===")
	runWasmLogic(targetWasm, "add", inputData)

	fmt.Println("\n=== [PASS 2] Second Run (Expect AOT Cache Hit) ===")

	// 强制释放内存中的 Module，确保下次是从文件系统加载
	cKey := C.CString(targetWasm)
	C.Bridge_ReleaseModule(cKey)
	C.free(unsafe.Pointer(cKey))

	runWasmLogic(targetWasm, "add", inputData)
}

func runWasmLogic(wasmFile string, action string, inputData []byte) {
	cwd, _ := os.Getwd()

	// 绝对路径
	wasmPath := filepath.Join(cwd, wasmFile)

	// 推导 .cwasm 缓存路径
	// 变量 cachePath 声明后必须被使用
	ext := filepath.Ext(wasmFile)
	cacheFile := wasmFile
	if ext != "" {
		cacheFile = wasmFile[0:len(wasmFile)-len(ext)] + ".cwasm"
	} else {
		cacheFile += ".cwasm"
	}
	cachePath := filepath.Join(cwd, cacheFile)

	// Key 使用文件名
	key := wasmFile

	fmt.Println("------------------------------------------------")
	fmt.Printf("Target: %s\n", key)

	isLoaded := false
	startLoad := time.Now()

	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))

	// 1. 尝试加载缓存 (AOT)
	// 【注意】这里使用了 cachePath，解决了 "declared and not used" 错误
	if _, err := os.Stat(cachePath); err == nil {
		fmt.Printf(">> Finding Cache: YES (%s)\n", cacheFile)
		cCachePath := C.CString(cachePath)

		// 调用 C++ LoadModule(..., false) 表示加载缓存
		if C.Bridge_LoadModule(cKey, cCachePath, false) {
			isLoaded = true
			fmt.Println(">> Mode: AOT (Loaded from Cache)")
		} else {
			fmt.Println(">> Mode: AOT Failed (Cache invalid)")
			os.Remove(cachePath)
		}
		C.free(unsafe.Pointer(cCachePath))
	} else {
		fmt.Println(">> Finding Cache: NO")
	}

	// 2. 如果没加载，走 JIT
	if !isLoaded {
		if _, err := os.Stat(wasmPath); os.IsNotExist(err) {
			fmt.Printf("[Error] Source file not found: %s\n", wasmPath)
			return
		}

		fmt.Println(">> Mode: JIT (Compiling from Source)")
		cWasmPath := C.CString(wasmPath)

		// 调用 C++ LoadModule(..., true) 表示 JIT 编译
		if C.Bridge_LoadModule(cKey, cWasmPath, true) {
			isLoaded = true
			// 编译成功后，立即保存缓存
			// 【注意】这里也使用了 cachePath
			cCachePath := C.CString(cachePath)
			if C.Bridge_SaveModuleCache(cKey, cCachePath) {
				fmt.Printf(">> Cache Saved: %s\n", cacheFile)
			}
			C.free(unsafe.Pointer(cCachePath))
		}
		C.free(unsafe.Pointer(cWasmPath))
	}

	loadTime := time.Since(startLoad)

	if !isLoaded {
		fmt.Println("[Error] Failed to load module.")
		return
	}

	// 3. 执行调用
	startCall := time.Now()

	cAction := C.CString(action)

	// 转换 inputData 为 C 指针
	var cData *C.char
	var cLen C.int
	if len(inputData) > 0 {
		cData = (*C.char)(unsafe.Pointer(&inputData[0]))
		cLen = C.int(len(inputData))
	}

	// 调用 Bridge_Call
	cResult := C.Bridge_Call(cKey, cAction, cData, cLen)

	callTime := time.Since(startCall)

	// 处理结果
	var resultVal int
	if cResult != nil {
		goRes := C.GoString(cResult)
		if len(goRes) > 0 {
			resultVal = int(goRes[0])
		}
		// 释放 C++ 返回的字符串内存
		C.Bridge_FreeString(cResult)
	}
	C.free(unsafe.Pointer(cAction))

	fmt.Printf(">> Load Time: %.3f ms\n", float64(loadTime.Microseconds())/1000.0)
	fmt.Printf(">> Call Time: %.3f ms\n", float64(callTime.Microseconds())/1000.0)
	fmt.Printf(">> Result   : %d\n", resultVal)
	fmt.Println("------------------------------------------------")
}
