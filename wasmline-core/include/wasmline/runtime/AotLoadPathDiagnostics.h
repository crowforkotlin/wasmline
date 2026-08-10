/**
 * Records the native AOT load entrypoints used by Core and Component artifacts.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */

#pragma once

#include <atomic>
#include <cstdint>

#include <wasmtime.h>

namespace wasmline {
    /** Internal diagnostics used to prove native AOT loads stay on deserialize-only paths. */
    class AotLoadPathDiagnostics final {
    public:
        static void reset() noexcept {
            coreDeserializeSuccesses().store(0, std::memory_order_relaxed);
            componentDeserializeSuccesses().store(0, std::memory_order_relaxed);
            moduleNewCalls().store(0, std::memory_order_relaxed);
            componentNewCalls().store(0, std::memory_order_relaxed);
        }

        static void recordCoreDeserializeSuccess() noexcept { coreDeserializeSuccesses().fetch_add(1, std::memory_order_relaxed); }

        static void recordComponentDeserializeSuccess() noexcept {
            componentDeserializeSuccesses().fetch_add(1, std::memory_order_relaxed);
        }

        /** Packs four 16-bit counters: Core deserialize, Component deserialize, Core new, Component new. */
        static std::uint64_t snapshot() noexcept {
            return counterField(coreDeserializeSuccesses()) | (counterField(componentDeserializeSuccesses()) << 16) |
                   (counterField(moduleNewCalls()) << 32) | (counterField(componentNewCalls()) << 48);
        }

        /** Converts a forbidden raw Core compilation call into a controlled load failure. */
        template <typename... Args> static wasmtime_error_t* rejectModuleNew(Args&&...) {
            moduleNewCalls().fetch_add(1, std::memory_order_relaxed);
            return wasmtime_error_new("Wasmline native Core raw compilation is forbidden. Use CWASM/PWASM.");
        }

        /** Converts a forbidden raw Component compilation call into a controlled load failure. */
        template <typename... Args> static wasmtime_error_t* rejectComponentNew(Args&&...) {
            componentNewCalls().fetch_add(1, std::memory_order_relaxed);
            return wasmtime_error_new("Wasmline native Component raw compilation is forbidden. Use CWASM/PWASM.");
        }

    private:
        static constexpr std::uint64_t COUNTER_MASK = 0xffff;

        static std::atomic<std::uint32_t>& coreDeserializeSuccesses() noexcept {
            static std::atomic<std::uint32_t> count{0};
            return count;
        }

        static std::atomic<std::uint32_t>& componentDeserializeSuccesses() noexcept {
            static std::atomic<std::uint32_t> count{0};
            return count;
        }

        static std::atomic<std::uint32_t>& moduleNewCalls() noexcept {
            static std::atomic<std::uint32_t> count{0};
            return count;
        }

        static std::atomic<std::uint32_t>& componentNewCalls() noexcept {
            static std::atomic<std::uint32_t> count{0};
            return count;
        }

        static std::uint64_t counterField(const std::atomic<std::uint32_t>& counter) noexcept {
            const std::uint32_t count = counter.load(std::memory_order_relaxed);
            return count > COUNTER_MASK ? COUNTER_MASK : static_cast<std::uint64_t>(count);
        }
    };
} // namespace wasmline
