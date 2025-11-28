#pragma once

#include <chrono>
#include <string>
#include <android/log.h>

// 如果你已经有了 WasmLog.h，可以直接 include 它
// #include "WasmLog.h" 

// 如果没有引用 WasmLog.h，这里做一个简单的定义防止报错
#ifndef LOGI
#define TIMER_TAG "WasmPerformance"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TIMER_TAG, __VA_ARGS__)
#endif

namespace crow {

    /**
     * 作用域计时器
     * 原理：构造时记录时间，析构时计算差值并打印
     */
    class AutoTimer {
    public:
        // 构造函数：开始计时
        explicit AutoTimer(std::string name) : taskName(std::move(name)) {
            startTime = std::chrono::high_resolution_clock::now();
        }

        // 析构函数：结束计时并打印
        ~AutoTimer() {
            auto endTime = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime).count();
            
            // 打印格式：[Timer] 任务名 : 耗时 ms
            LOGI("[Timer] %s : %lld ms", taskName.c_str(), (long long)duration);
        }

    private:
        std::string taskName;
        std::chrono::time_point<std::chrono::high_resolution_clock> startTime;
    };

}

// 定义一个宏，让调用更简单
// 用法: MEASURE_BLOCK("加载模块");
#define MEASURE_BLOCK(name) crow::AutoTimer _timer_##__LINE__(name)