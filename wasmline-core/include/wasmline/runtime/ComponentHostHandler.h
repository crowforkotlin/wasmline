/**
 * Defines the typed host callback interface for Component Model imports.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */

#pragma once

#include <string_view>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"

namespace wasmline {
    /**
     * Handles one synchronous typed Component Model host import.
     *
     * Implementations must preserve the import and function text as supplied by
     * the Component. A failure becomes a Component host-callback error because
     * arbitrary WIT functions do not necessarily expose a result error arm.
     */
    class ComponentHostHandler {
    public:
        /** Releases the handler. */
        virtual ~ComponentHostHandler() = default;

        /** Dispatches an imported Component function. */
        virtual InvocationResult onComponentHostInvoke(std::string_view interfaceName, std::string_view functionName,
                                                       const std::vector<ComponentValue>& arguments) = 0;
    };
} // namespace wasmline
