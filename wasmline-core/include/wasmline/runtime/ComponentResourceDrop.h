/**
 * Defines the exact outcome of one Component Model resource drop.
 *
 * Date: 2026-10-22
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "wasmline/invocation/InvocationResult.h"
#include "wasmline/invocation/TypedInvocationCodec.h"
#include "wasmline/protocol/ErrorDefs.h"

namespace wasmline {
    /**
     * Reports whether one Component resource drop consumed its carrier.
     *
     * A rejected drop keeps the retained native entry, so the carrier can be
     * retried or used again. Both dropped and consumed-with-cleanup-failure
     * states remove the entry permanently; only the latter also reports a
     * destructor failure.
     */
    enum class ResourceDropStatus : uint8_t {
        REJECTED = 0,
        DROPPED = 1,
        CONSUMED_CLEANUP_FAILED = 2,
    };

    /**
     * Describes one Component resource drop.
     *
     * The code and message fields are meaningful only when the status is not
     * [ResourceDropStatus::DROPPED]. A cleanup failure keeps the original
     * destructor diagnostic so wrappers can report it after the entry is gone.
     */
    struct ResourceDropOutcome {
        ResourceDropStatus status = ResourceDropStatus::REJECTED;
        WasmlineErrorCode code = WasmlineErrorCode::COMPONENT_RESOURCE_INVALID;
        std::string message;

        /** Creates an outcome for a carrier rejected before consumption. */
        static ResourceDropOutcome rejected(WasmlineErrorCode code, std::string message) {
            return {ResourceDropStatus::REJECTED, code, std::move(message)};
        }

        /** Creates an outcome for a resource dropped without a cleanup failure. */
        static ResourceDropOutcome dropped() { return {ResourceDropStatus::DROPPED, WasmlineErrorCode::UNKNOWN, std::string()}; }

        /** Creates an outcome for a consumed resource whose destructor failed afterwards. */
        static ResourceDropOutcome cleanupFailed(WasmlineErrorCode code, std::string message) {
            return {ResourceDropStatus::CONSUMED_CLEANUP_FAILED, code, std::move(message)};
        }
    };

    /**
     * Marks a typed failure carrier as a consumed resource drop.
     *
     * Platform bridges carry the outcome through the existing typed Component
     * result encoding: a successful carrier means the resource was dropped
     * without a cleanup failure, and a failed carrier carries the diagnostic
     * code and message. The failure details of a consumed drop hold this single
     * byte so wrappers can distinguish terminal consumption from a rejection
     * that left the resource untouched.
     */
    constexpr uint8_t kResourceDropConsumedDetail = 1;

    /**
     * Encodes one drop outcome as the typed Component carrier used by bridges.
     *
     * Date: 2026-10-22
     * Author: crowforkotlin
     */
    inline std::vector<uint8_t> encodeResourceDropOutcome(const ResourceDropOutcome& outcome) {
        if (outcome.status == ResourceDropStatus::DROPPED) {
            return TypedInvocationCodec::encodeResult(InvocationResult::successComponent({}), TypedInvocationKind::COMPONENT);
        }
        const std::vector<uint8_t> details{
            outcome.status == ResourceDropStatus::CONSUMED_CLEANUP_FAILED ? kResourceDropConsumedDetail : static_cast<uint8_t>(0),
        };
        return TypedInvocationCodec::encodeResult(InvocationResult::failure(outcome.code, outcome.message, details),
                                                  TypedInvocationKind::COMPONENT);
    }
} // namespace wasmline
