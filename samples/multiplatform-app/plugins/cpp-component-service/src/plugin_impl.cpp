#include "plugin_cpp.h"

#include <string_view>
#include <utility>

namespace plugin_api = exports::wasmline::service;
namespace host_api = ::wasmline::service;

namespace {

    using InvokeResult = std::expected<wit::vector<uint8_t>, plugin_api::ServiceError>;

    constexpr std::string_view SUPPORTED_CODEC = "protobuf";
    constexpr std::string_view ACTION_ECHO = "sample.echo";
    constexpr std::string_view ACTION_CALLBACK = "sample.callback";
    constexpr std::string_view ACTION_EMPTY = "sample.empty";
    constexpr std::string_view ACTION_TRAP = "sample.trap";
    constexpr std::string_view HOST_CALLBACK_ACTION = "sample.host.callback";

    InvokeResult failure(std::string_view code, std::string_view message) {
        return std::unexpected<plugin_api::ServiceError>(plugin_api::ServiceError{
            wit::string::from_view(code),
            wit::string::from_view(message),
            wit::vector<uint8_t>{},
        });
    }

    InvokeResult invoke_host(plugin_api::Request request) {
        auto result = host_api::Invoke(host_api::Request{
            wit::string::from_view(HOST_CALLBACK_ACTION),
            std::move(request.codec),
            std::move(request.payload),
        });
        if (result.has_value()) {
            return *std::move(result);
        }

        auto error = std::move(result).error();
        return std::unexpected<plugin_api::ServiceError>(plugin_api::ServiceError{
            std::move(error.code),
            std::move(error.message),
            std::move(error.details),
        });
    }

} // namespace

namespace exports::wasmline::service {

    std::expected<wit::vector<uint8_t>, ServiceError> Invoke(Request request) {
        if (request.codec.get_view() != SUPPORTED_CODEC) {
            return failure("1005", "Unsupported codec. Expected protobuf.");
        }

        const std::string_view action = request.action.get_view();
        if (action == ACTION_ECHO) {
            return std::move(request.payload);
        }
        if (action == ACTION_CALLBACK) {
            return invoke_host(std::move(request));
        }
        if (action == ACTION_EMPTY) {
            return wit::vector<uint8_t>{};
        }
        if (action == ACTION_TRAP) {
            __builtin_trap();
        }
        return failure("1002", "Unknown C++ Component action.");
    }

} // namespace exports::wasmline::service
