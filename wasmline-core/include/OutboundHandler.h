#pragma once

#include <string>
#include <string_view>

namespace wasmline {
    /**
     * Pure virtual interface representing host-side outbound handling.
     * Session uses this interface without depending on the concrete host bridge.
     */
    class OutboundHandler {
    public:
        virtual ~OutboundHandler() = default;

        /**
         * Sends an outbound request to the host runtime.
         *
         * @param action Action name
         * @param payload Serialized payload data
         * @return Serialized host response payload
         */
        virtual std::string onOutboundInvoke(std::string_view action, std::string_view payload) = 0;
    };
}