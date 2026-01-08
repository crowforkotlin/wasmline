#pragma once

#include <string>
#include <string_view>

namespace wasmline {
    /**
     * 纯虚接口，代表宿主环境（Host）的处理能力。
     * Session 通过此接口与外部通信，不关心外部是 Android(JNI), iOS(ObjC), 还是其他 C++ 模块。
     */
    class OutboundHandler {
    public:
        virtual ~OutboundHandler() = default;

        /**
         * 发送请求给 Host
         * @param action 方法名
         * @param payload 参数数据
         * @return Host 执行后的结果数据
         */
        virtual std::string onOutboundInvoke(std::string_view action, std::string_view payload) = 0;
    };
}