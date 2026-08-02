/**
 * Defines the host callback interface for outbound calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <string_view>
#include <string>

namespace wasmline {
    /** Defines host handling for outbound calls. */
    class OutboundHandler {
    public:
        /** Releases the handler. */
        virtual ~OutboundHandler() = default;

        /** Sends an outbound request to the host runtime.
         *
         * @param action Action name.
         * @param payload Serialized payload.
         * @return Serialized host response.
         */
        virtual std::string onOutboundInvoke(std::string_view action, std::string_view payload) = 0;
    };
} // namespace wasmline
