#include "plugin.h"

#include <stdlib.h>
#include <string.h>

static const char SUPPORTED_CODEC[] = "protobuf";
static const char ACTION_ECHO[] = "sample.echo";
static const char ACTION_CALLBACK[] = "sample.callback";
static const char ACTION_EMPTY[] = "sample.empty";
static const char ACTION_TRAP[] = "sample.trap";
static const char HOST_CALLBACK_ACTION[] = "sample.host.callback";

static bool string_equals(const plugin_string_t* value, const char* expected) {
    const size_t expected_length = strlen(expected);
    return value->len == expected_length && memcmp(value->ptr, expected, expected_length) == 0;
}

static bool return_error(plugin_rpc_error_t* error, const char* code, const char* message) {
    plugin_string_dup(&error->code, code);
    plugin_string_dup(&error->message, message);
    error->details.ptr = NULL;
    error->details.len = 0;
    return false;
}

static bool copy_payload(const plugin_list_u8_t* payload, plugin_list_u8_t* result) {
    result->len = payload->len;
    if (payload->len == 0) {
        result->ptr = NULL;
        return true;
    }

    result->ptr = malloc(payload->len);
    if (result->ptr == NULL) {
        __builtin_trap();
    }
    memcpy(result->ptr, payload->ptr, payload->len);
    return true;
}

static bool invoke_host(plugin_request_t* request, plugin_list_u8_t* result, plugin_rpc_error_t* error) {
    host_request_t host_request;
    plugin_string_set(&host_request.action, HOST_CALLBACK_ACTION);
    host_request.codec = request->codec;
    host_request.payload = request->payload;

    host_rpc_error_t host_error;
    if (host_invoke(&host_request, result, &host_error)) {
        return true;
    }

    error->code = host_error.code;
    error->message = host_error.message;
    error->details = host_error.details;
    return false;
}

bool plugin_invoke(plugin_request_t* request, plugin_list_u8_t* result, plugin_rpc_error_t* error) {
    if (!string_equals(&request->codec, SUPPORTED_CODEC)) {
        return return_error(error, "1005", "Unsupported codec. Expected protobuf.");
    }
    if (string_equals(&request->action, ACTION_ECHO)) {
        return copy_payload(&request->payload, result);
    }
    if (string_equals(&request->action, ACTION_CALLBACK)) {
        return invoke_host(request, result, error);
    }
    if (string_equals(&request->action, ACTION_EMPTY)) {
        result->ptr = NULL;
        result->len = 0;
        return true;
    }
    if (string_equals(&request->action, ACTION_TRAP)) {
        __builtin_trap();
    }
    return return_error(error, "1002", "Unknown C Component action.");
}
