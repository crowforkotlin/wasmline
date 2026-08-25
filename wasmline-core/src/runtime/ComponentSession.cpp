/**
 * Implements isolated execution state for a Component Model instance.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/runtime/ComponentSession.h"

#include <algorithm>
#include <cstddef>
#include <cstring>
#include <exception>
#include <functional>
#include <memory>
#include <utility>

#include "wasmline/internal/logging/NativeLogger.h"
#include "wasmline/internal/wasi/WasiConfig.h"
#include "wasmline/internal/wasmtime/WasmtimeMessage.h"
#include "wasmline/protocol/WasmlineProtocol.h"
#include "wasmline/runtime/ComponentHostHandler.h"
#include "wasmline/runtime/OutboundHandler.h"

namespace wasmline {
    namespace {
        struct ValueTypeGuard {
            wasmtime_component_valtype_t value{};
            bool active = false;

            ~ValueTypeGuard() {
                if (active) wasmtime_component_valtype_delete(&value);
            }
        };

        struct ComponentItemGuard {
            wasmtime_component_item_t value{};
            bool active = false;

            ~ComponentItemGuard() {
                if (active) wasmtime_component_item_delete(&value);
            }
        };

        struct ExportIndexGuard {
            std::vector<wasmtime_component_export_index_t*> values;

            void rollback(size_t size) {
                while (values.size() > size) {
                    wasmtime_component_export_index_delete(values.back());
                    values.pop_back();
                }
            }

            ~ExportIndexGuard() { rollback(0); }
        };

        constexpr size_t kMaxComponentInvocationDepth = 32;
        thread_local std::vector<const ComponentSession*> activeComponentSessions;
        thread_local ComponentSession* activeConversionSession = nullptr;
        thread_local std::vector<uint64_t>* activeTransientResources = nullptr;

        class ComponentConversionScope {
        public:
            ComponentConversionScope(ComponentSession* session, std::vector<uint64_t>* transient = nullptr)
                : previous_(activeConversionSession), previousTransient_(activeTransientResources) {
                activeConversionSession = session;
                activeTransientResources = transient;
            }

            ~ComponentConversionScope() {
                activeConversionSession = previous_;
                activeTransientResources = previousTransient_;
            }

        private:
            ComponentSession* previous_;
            std::vector<uint64_t>* previousTransient_;
        };

        class ComponentInvocationScope {
        public:
            explicit ComponentInvocationScope(const ComponentSession* session) { activeComponentSessions.push_back(session); }

            ~ComponentInvocationScope() { activeComponentSessions.pop_back(); }
        };

        std::string nameString(const char* data, size_t size) {
            return data ? std::string(data, size) : std::string();
        }

        std::string nameString(const wasm_name_t& name) {
            return name.data ? std::string(name.data, name.size) : std::string();
        }

        bool nameEquals(const wasm_name_t& actual, std::string_view expected) {
            return actual.size == expected.size() && actual.data && std::memcmp(actual.data, expected.data(), expected.size()) == 0;
        }

        bool isWasiImport(std::string_view importName) {
            return importName.size() >= 5 && importName.substr(0, 5) == "wasi:";
        }

        std::string componentHostFunctionLabel(std::string_view interfaceName, std::string_view functionName) {
            std::string label;
            label.reserve(interfaceName.size() + functionName.size() + 1);
            label.append(interfaceName);
            label.push_back('/');
            label.append(functionName);
            return label;
        }

        bool typeIsByteList(const wasmtime_component_valtype_t& type) {
            if (type.kind != WASMTIME_COMPONENT_VALTYPE_LIST || !type.of.list) return false;
            ValueTypeGuard elementType;
            wasmtime_component_list_type_element(type.of.list, &elementType.value);
            elementType.active = true;
            return elementType.value.kind == WASMTIME_COMPONENT_VALTYPE_U8;
        }

        bool recordFieldMatches(const wasmtime_component_record_type_t* record, size_t index, std::string_view expectedName,
                                bool (*typeMatches)(const wasmtime_component_valtype_t&)) {
            const char* fieldName = nullptr;
            size_t fieldNameSize = 0;
            ValueTypeGuard fieldType;
            if (!record || !typeMatches ||
                !wasmtime_component_record_type_field_nth(record, index, &fieldName, &fieldNameSize, &fieldType.value)) {
                return false;
            }
            fieldType.active = true;
            return nameString(fieldName, fieldNameSize) == expectedName && typeMatches(fieldType.value);
        }

        bool typeIsString(const wasmtime_component_valtype_t& type) {
            return type.kind == WASMTIME_COMPONENT_VALTYPE_STRING;
        }

        bool requestTypeMatches(const wasmtime_component_valtype_t& type) {
            return type.kind == WASMTIME_COMPONENT_VALTYPE_RECORD && type.of.record &&
                   wasmtime_component_record_type_field_count(type.of.record) == 3 &&
                   recordFieldMatches(type.of.record, 0, "action", typeIsString) &&
                   recordFieldMatches(type.of.record, 1, "codec", typeIsString) &&
                   recordFieldMatches(type.of.record, 2, "payload", typeIsByteList);
        }

        bool errorTypeMatches(const wasmtime_component_valtype_t& type) {
            return type.kind == WASMTIME_COMPONENT_VALTYPE_RECORD && type.of.record &&
                   wasmtime_component_record_type_field_count(type.of.record) == 3 &&
                   recordFieldMatches(type.of.record, 0, "code", typeIsString) &&
                   recordFieldMatches(type.of.record, 1, "message", typeIsString) &&
                   recordFieldMatches(type.of.record, 2, "details", typeIsByteList);
        }

        bool serviceFunctionTypeMatches(const wasmtime_component_func_type_t* type) {
            if (!type || wasmtime_component_func_type_param_count(type) != 1) return false;

            const char* parameterName = nullptr;
            size_t parameterNameSize = 0;
            ValueTypeGuard parameterType;
            if (!wasmtime_component_func_type_param_nth(type, 0, &parameterName, &parameterNameSize, &parameterType.value)) return false;
            parameterType.active = true;
            if (!requestTypeMatches(parameterType.value)) return false;

            ValueTypeGuard resultType;
            if (!wasmtime_component_func_type_result(type, &resultType.value)) return false;
            resultType.active = true;
            if (resultType.value.kind != WASMTIME_COMPONENT_VALTYPE_RESULT || !resultType.value.of.result) return false;

            ValueTypeGuard okType;
            ValueTypeGuard errorType;
            if (!wasmtime_component_result_type_ok(resultType.value.of.result, &okType.value)) return false;
            okType.active = true;
            if (!wasmtime_component_result_type_err(resultType.value.of.result, &errorType.value)) return false;
            errorType.active = true;
            return typeIsByteList(okType.value) && errorTypeMatches(errorType.value);
        }

        bool serviceInstanceTypeMatches(const wasmtime_component_instance_type_t* type, const wasm_engine_t* engine) {
            if (!type || !engine) return false;
            ComponentItemGuard invoke;
            if (!wasmtime_component_instance_type_export_get(type, engine, "invoke", 6, &invoke.value)) return false;
            invoke.active = true;
            return invoke.value.kind == WASMTIME_COMPONENT_ITEM_COMPONENT_FUNC &&
                   serviceFunctionTypeMatches(invoke.value.of.component_func);
        }

        const ComponentValue* recordField(const ComponentRecord& record, std::string_view name) {
            const auto field =
                std::find_if(record.begin(), record.end(), [name](const ComponentRecordField& item) { return item.name == name; });
            return field == record.end() ? nullptr : &field->value;
        }

        bool componentBytes(const ComponentValue& value, std::string* output) {
            if (!output || value.kind() != ComponentValue::Kind::LIST) return false;
            const auto& items = value.listValue();
            output->clear();
            output->reserve(items.size());
            for (const auto& item : items) {
                if (item.kind() != ComponentValue::Kind::U8) return false;
                output->push_back(static_cast<char>(item.u8Value()));
            }
            return true;
        }

        ComponentValue byteList(std::string_view bytes) {
            ComponentList values;
            values.reserve(bytes.size());
            for (const unsigned char byte : bytes) {
                values.push_back(ComponentValue::u8(byte));
            }
            return ComponentValue::list(std::move(values));
        }

        ComponentValue serviceSuccess(std::string_view payload) {
            return ComponentValue::result(true, std::make_shared<ComponentValue>(byteList(payload)));
        }

        ComponentValue serviceFailure(uint32_t code, std::string message, std::string_view details = {}) {
            ComponentRecord fields;
            fields.push_back(ComponentRecordField{"code", ComponentValue::string(std::to_string(code))});
            fields.push_back(ComponentRecordField{"message", ComponentValue::string(std::move(message))});
            fields.push_back(ComponentRecordField{"details", byteList(details)});
            return ComponentValue::result(false, std::make_shared<ComponentValue>(ComponentValue::record(std::move(fields))));
        }

        wasmtime_error_t* componentError(std::string message) {
            return wasmtime_error_new(message.c_str());
        }

        bool resolveExportPath(const wasmtime_component_instance_t* instance, wasmtime_context_t* context,
                               const wasmtime_component_export_index_t* parent, std::string_view path, ExportIndexGuard* guard) {
            if (!instance || !context || !guard || path.empty()) return false;

            auto* exact = wasmtime_component_instance_get_export_index(instance, context, parent, path.data(), path.size());
            if (exact) {
                guard->values.push_back(exact);
                return true;
            }

            // WIT interface exports use the unambiguous `interface-id#function-name` form in the Host facade.
            // The Component instance exposes these as an interface instance followed by its function export.
            const size_t interfaceSeparator = path.rfind('#');
            if (interfaceSeparator != std::string_view::npos && interfaceSeparator > 0 && interfaceSeparator + 1 < path.size()) {
                const std::string_view interfaceName = path.substr(0, interfaceSeparator);
                const std::string_view functionName = path.substr(interfaceSeparator + 1);
                auto* nested =
                    wasmtime_component_instance_get_export_index(instance, context, parent, interfaceName.data(), interfaceName.size());
                if (nested) {
                    const size_t checkpoint = guard->values.size();
                    guard->values.push_back(nested);
                    if (resolveExportPath(instance, context, nested, functionName, guard)) return true;
                    guard->rollback(checkpoint);
                }
            }

            size_t separator = path.rfind('/');
            while (separator != std::string_view::npos) {
                if (separator > 0 && separator + 1 < path.size()) {
                    const std::string_view prefix = path.substr(0, separator);
                    const std::string_view suffix = path.substr(separator + 1);
                    auto* nested = wasmtime_component_instance_get_export_index(instance, context, parent, prefix.data(), prefix.size());
                    if (nested) {
                        const size_t checkpoint = guard->values.size();
                        guard->values.push_back(nested);
                        if (resolveExportPath(instance, context, nested, suffix, guard)) return true;
                        guard->rollback(checkpoint);
                    }
                }
                if (separator == 0) break;
                separator = path.rfind('/', separator - 1);
            }
            return false;
        }

        bool enumContains(const wasmtime_component_enum_type_t* type, std::string_view name) {
            if (!type) return false;
            const size_t count = wasmtime_component_enum_type_names_count(type);
            for (size_t index = 0; index < count; ++index) {
                const char* item = nullptr;
                size_t itemSize = 0;
                if (wasmtime_component_enum_type_names_nth(type, index, &item, &itemSize) && item && itemSize == name.size() &&
                    std::memcmp(item, name.data(), name.size()) == 0) {
                    return true;
                }
            }
            return false;
        }

        bool flagContains(const wasmtime_component_flags_type_t* type, std::string_view name) {
            if (!type) return false;
            const size_t count = wasmtime_component_flags_type_names_count(type);
            for (size_t index = 0; index < count; ++index) {
                const char* item = nullptr;
                size_t itemSize = 0;
                if (wasmtime_component_flags_type_names_nth(type, index, &item, &itemSize) && item && itemSize == name.size() &&
                    std::memcmp(item, name.data(), name.size()) == 0) {
                    return true;
                }
            }
            return false;
        }

        bool hasDuplicate(const std::vector<std::string>& names, std::string_view name) {
            return std::find(names.begin(), names.end(), name) != names.end();
        }

        /** Finds a variant case and returns its payload type when present. */
        bool findVariantCase(const wasmtime_component_variant_type_t* type, std::string_view name, bool* hasPayload,
                             wasmtime_component_valtype_t* payload) {
            if (!type || !hasPayload || !payload) return false;
            const size_t count = wasmtime_component_variant_type_case_count(type);
            for (size_t index = 0; index < count; ++index) {
                const char* item = nullptr;
                size_t itemSize = 0;
                bool caseHasPayload = false;
                wasmtime_component_valtype_t casePayload{};
                if (!wasmtime_component_variant_type_case_nth(type, index, &item, &itemSize, &caseHasPayload, &casePayload)) {
                    continue;
                }
                if (item && itemSize == name.size() && std::memcmp(item, name.data(), name.size()) == 0) {
                    *hasPayload = caseHasPayload;
                    if (caseHasPayload) {
                        *payload = casePayload;
                    }
                    return true;
                }
                if (caseHasPayload) wasmtime_component_valtype_delete(&casePayload);
            }
            return false;
        }

        bool setName(wasm_name_t* output, std::string_view value) {
            if (!output) return false;
            wasm_name_new(output, value.size(), value.data());
            return value.empty() || output->data != nullptr;
        }

        void initializeValue(wasmtime_component_val_t* value) {
            if (value) *value = {};
        }

        void deleteComponentValue(wasmtime_component_val_t* value) {
            if (!value) return;
            wasmtime_component_val_delete(value);
            *value = {};
        }

        bool convertToWasmtime(const ComponentValue& value, const wasmtime_component_valtype_t& type, wasmtime_component_val_t* result);

        bool convertFromWasmtime(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type, ComponentValue* result);

        /** Converts a native component value to a Wasmtime value. */
        bool convertToWasmtime(const ComponentValue& value, const wasmtime_component_valtype_t& type, wasmtime_component_val_t* result) {
            if (!result) return false;
            initializeValue(result);

            switch (type.kind) {
            case WASMTIME_COMPONENT_VALTYPE_BOOL:
                if (value.kind() != ComponentValue::Kind::BOOL) return false;
                result->kind = WASMTIME_COMPONENT_BOOL;
                result->of.boolean = value.booleanValue();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S8:
                if (value.kind() != ComponentValue::Kind::S8) return false;
                result->kind = WASMTIME_COMPONENT_S8;
                result->of.s8 = value.s8Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U8:
                if (value.kind() != ComponentValue::Kind::U8) return false;
                result->kind = WASMTIME_COMPONENT_U8;
                result->of.u8 = value.u8Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S16:
                if (value.kind() != ComponentValue::Kind::S16) return false;
                result->kind = WASMTIME_COMPONENT_S16;
                result->of.s16 = value.s16Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U16:
                if (value.kind() != ComponentValue::Kind::U16) return false;
                result->kind = WASMTIME_COMPONENT_U16;
                result->of.u16 = value.u16Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S32:
                if (value.kind() != ComponentValue::Kind::S32) return false;
                result->kind = WASMTIME_COMPONENT_S32;
                result->of.s32 = value.s32Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U32:
                if (value.kind() != ComponentValue::Kind::U32) return false;
                result->kind = WASMTIME_COMPONENT_U32;
                result->of.u32 = value.u32Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S64:
                if (value.kind() != ComponentValue::Kind::S64) return false;
                result->kind = WASMTIME_COMPONENT_S64;
                result->of.s64 = value.s64Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U64:
                if (value.kind() != ComponentValue::Kind::U64) return false;
                result->kind = WASMTIME_COMPONENT_U64;
                result->of.u64 = value.u64Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_F32:
                if (value.kind() != ComponentValue::Kind::F32) return false;
                result->kind = WASMTIME_COMPONENT_F32;
                result->of.f32 = value.f32Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_F64:
                if (value.kind() != ComponentValue::Kind::F64) return false;
                result->kind = WASMTIME_COMPONENT_F64;
                result->of.f64 = value.f64Value();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_CHAR:
                if (value.kind() != ComponentValue::Kind::CHAR) return false;
                result->kind = WASMTIME_COMPONENT_CHAR;
                result->of.character = value.characterValue();
                return true;
            case WASMTIME_COMPONENT_VALTYPE_STRING:
                if (value.kind() != ComponentValue::Kind::STRING) return false;
                result->kind = WASMTIME_COMPONENT_STRING;
                if (!setName(&result->of.string, value.stringValue())) {
                    deleteComponentValue(result);
                    return false;
                }
                return true;
            case WASMTIME_COMPONENT_VALTYPE_LIST: {
                if (value.kind() != ComponentValue::Kind::LIST || !type.of.list) return false;
                const auto& values = value.listValue();
                result->kind = WASMTIME_COMPONENT_LIST;
                wasmtime_component_vallist_new_uninit(&result->of.list, values.size());
                for (size_t index = 0; index < values.size(); ++index)
                    initializeValue(&result->of.list.data[index]);
                ValueTypeGuard elementType;
                wasmtime_component_list_type_element(type.of.list, &elementType.value);
                elementType.active = true;
                for (size_t index = 0; index < values.size(); ++index) {
                    if (!convertToWasmtime(values[index], elementType.value, &result->of.list.data[index])) {
                        deleteComponentValue(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_RECORD: {
                if (value.kind() != ComponentValue::Kind::RECORD || !type.of.record) return false;
                const auto& fields = value.recordValue();
                const size_t count = wasmtime_component_record_type_field_count(type.of.record);
                if (fields.size() != count) return false;
                result->kind = WASMTIME_COMPONENT_RECORD;
                wasmtime_component_valrecord_new_uninit(&result->of.record, count);
                for (size_t index = 0; index < count; ++index) {
                    result->of.record.data[index].name = {};
                    initializeValue(&result->of.record.data[index].val);
                }
                for (size_t index = 0; index < count; ++index) {
                    const char* fieldName = nullptr;
                    size_t fieldNameSize = 0;
                    ValueTypeGuard fieldType;
                    if (!wasmtime_component_record_type_field_nth(type.of.record, index, &fieldName, &fieldNameSize, &fieldType.value) ||
                        fields[index].name != nameString(fieldName, fieldNameSize)) {
                        deleteComponentValue(result);
                        return false;
                    }
                    fieldType.active = true;
                    if (!setName(&result->of.record.data[index].name, fields[index].name) ||
                        !convertToWasmtime(fields[index].value, fieldType.value, &result->of.record.data[index].val)) {
                        deleteComponentValue(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_TUPLE: {
                if (value.kind() != ComponentValue::Kind::TUPLE || !type.of.tuple) return false;
                const auto& values = value.tupleValue();
                const size_t count = wasmtime_component_tuple_type_types_count(type.of.tuple);
                if (values.size() != count) return false;
                result->kind = WASMTIME_COMPONENT_TUPLE;
                wasmtime_component_valtuple_new_uninit(&result->of.tuple, count);
                for (size_t index = 0; index < count; ++index)
                    initializeValue(&result->of.tuple.data[index]);
                for (size_t index = 0; index < count; ++index) {
                    ValueTypeGuard itemType;
                    if (!wasmtime_component_tuple_type_types_nth(type.of.tuple, index, &itemType.value)) {
                        deleteComponentValue(result);
                        return false;
                    }
                    itemType.active = true;
                    if (!convertToWasmtime(values[index], itemType.value, &result->of.tuple.data[index])) {
                        deleteComponentValue(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_VARIANT: {
                if (value.kind() != ComponentValue::Kind::VARIANT || !type.of.variant) return false;
                const auto& variant = value.variantValue();
                bool hasPayload = false;
                ValueTypeGuard payloadType;
                if (!findVariantCase(type.of.variant, variant.discriminant, &hasPayload, &payloadType.value)) return false;
                payloadType.active = hasPayload;
                result->kind = WASMTIME_COMPONENT_VARIANT;
                if (!setName(&result->of.variant.discriminant, variant.discriminant)) {
                    deleteComponentValue(result);
                    return false;
                }
                if (hasPayload != static_cast<bool>(variant.value)) {
                    deleteComponentValue(result);
                    return false;
                }
                if (hasPayload) {
                    wasmtime_component_val_t payload{};
                    if (!convertToWasmtime(*variant.value, payloadType.value, &payload)) {
                        deleteComponentValue(result);
                        return false;
                    }
                    result->of.variant.val = wasmtime_component_val_new(&payload);
                    if (!result->of.variant.val) {
                        deleteComponentValue(&payload);
                        deleteComponentValue(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_ENUM:
                if (value.kind() != ComponentValue::Kind::ENUM || !enumContains(type.of.enum_, value.enumValue())) return false;
                result->kind = WASMTIME_COMPONENT_ENUM;
                if (!setName(&result->of.enumeration, value.enumValue())) {
                    deleteComponentValue(result);
                    return false;
                }
                return true;
            case WASMTIME_COMPONENT_VALTYPE_OPTION: {
                if (value.kind() != ComponentValue::Kind::OPTION || !type.of.option) return false;
                result->kind = WASMTIME_COMPONENT_OPTION;
                if (!value.optionValue()) return true;
                ValueTypeGuard innerType;
                wasmtime_component_option_type_ty(type.of.option, &innerType.value);
                innerType.active = true;
                wasmtime_component_val_t inner{};
                if (!convertToWasmtime(*value.optionValue(), innerType.value, &inner)) return false;
                result->of.option = wasmtime_component_val_new(&inner);
                if (!result->of.option) {
                    deleteComponentValue(&inner);
                    return false;
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_RESULT: {
                if (value.kind() != ComponentValue::Kind::RESULT || !type.of.result) return false;
                const auto& resultValue = value.resultValue();
                result->kind = WASMTIME_COMPONENT_RESULT;
                result->of.result.is_ok = resultValue.isOk;
                ValueTypeGuard innerType;
                const bool hasType = resultValue.isOk ? wasmtime_component_result_type_ok(type.of.result, &innerType.value)
                                                      : wasmtime_component_result_type_err(type.of.result, &innerType.value);
                if (!resultValue.value) return !hasType;
                if (!hasType) return false;
                innerType.active = true;
                wasmtime_component_val_t inner{};
                if (!convertToWasmtime(*resultValue.value, innerType.value, &inner)) return false;
                result->of.result.val = wasmtime_component_val_new(&inner);
                if (!result->of.result.val) {
                    deleteComponentValue(&inner);
                    return false;
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_FLAGS: {
                if (value.kind() != ComponentValue::Kind::FLAGS || !type.of.flags) return false;
                const auto& names = value.flagsValue();
                std::vector<std::string> checked;
                checked.reserve(names.size());
                for (const auto& name : names) {
                    if (!flagContains(type.of.flags, name) || hasDuplicate(checked, name)) return false;
                    checked.push_back(name);
                }
                result->kind = WASMTIME_COMPONENT_FLAGS;
                wasmtime_component_valflags_new_uninit(&result->of.flags, names.size());
                for (size_t index = 0; index < names.size(); ++index)
                    result->of.flags.data[index] = {};
                for (size_t index = 0; index < names.size(); ++index) {
                    if (!setName(&result->of.flags.data[index], names[index])) {
                        deleteComponentValue(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_MAP: {
                if (value.kind() != ComponentValue::Kind::MAP || !type.of.map) return false;
                const auto& entries = value.mapValue();
                result->kind = WASMTIME_COMPONENT_MAP;
                wasmtime_component_valmap_new_uninit(&result->of.map, entries.size());
                for (size_t index = 0; index < entries.size(); ++index) {
                    initializeValue(&result->of.map.data[index].key);
                    initializeValue(&result->of.map.data[index].value);
                }
                ValueTypeGuard keyType;
                ValueTypeGuard valueType;
                wasmtime_component_map_type_key(type.of.map, &keyType.value);
                wasmtime_component_map_type_value(type.of.map, &valueType.value);
                keyType.active = true;
                valueType.active = true;
                for (size_t index = 0; index < entries.size(); ++index) {
                    if (!convertToWasmtime(entries[index].first, keyType.value, &result->of.map.data[index].key) ||
                        !convertToWasmtime(entries[index].second, valueType.value, &result->of.map.data[index].value)) {
                        deleteComponentValue(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_OWN:
            case WASMTIME_COMPONENT_VALTYPE_BORROW:
                return activeConversionSession && activeConversionSession->lowerResource(value, type, result);
            default:
                return false;
            }
        }

        /** Converts a Wasmtime value to a native component value. */
        bool convertFromWasmtime(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type, ComponentValue* result) {
            if (!result) return false;
            switch (type.kind) {
            case WASMTIME_COMPONENT_VALTYPE_BOOL:
                if (value.kind != WASMTIME_COMPONENT_BOOL) return false;
                *result = ComponentValue::boolean(value.of.boolean);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S8:
                if (value.kind != WASMTIME_COMPONENT_S8) return false;
                *result = ComponentValue::s8(value.of.s8);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U8:
                if (value.kind != WASMTIME_COMPONENT_U8) return false;
                *result = ComponentValue::u8(value.of.u8);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S16:
                if (value.kind != WASMTIME_COMPONENT_S16) return false;
                *result = ComponentValue::s16(value.of.s16);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U16:
                if (value.kind != WASMTIME_COMPONENT_U16) return false;
                *result = ComponentValue::u16(value.of.u16);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S32:
                if (value.kind != WASMTIME_COMPONENT_S32) return false;
                *result = ComponentValue::s32(value.of.s32);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U32:
                if (value.kind != WASMTIME_COMPONENT_U32) return false;
                *result = ComponentValue::u32(value.of.u32);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_S64:
                if (value.kind != WASMTIME_COMPONENT_S64) return false;
                *result = ComponentValue::s64(value.of.s64);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_U64:
                if (value.kind != WASMTIME_COMPONENT_U64) return false;
                *result = ComponentValue::u64(value.of.u64);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_F32:
                if (value.kind != WASMTIME_COMPONENT_F32) return false;
                *result = ComponentValue::f32(value.of.f32);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_F64:
                if (value.kind != WASMTIME_COMPONENT_F64) return false;
                *result = ComponentValue::f64(value.of.f64);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_CHAR:
                if (value.kind != WASMTIME_COMPONENT_CHAR) return false;
                *result = ComponentValue::character(value.of.character);
                return true;
            case WASMTIME_COMPONENT_VALTYPE_STRING:
                if (value.kind != WASMTIME_COMPONENT_STRING) return false;
                *result = ComponentValue::string(nameString(value.of.string));
                return true;
            case WASMTIME_COMPONENT_VALTYPE_LIST: {
                if (value.kind != WASMTIME_COMPONENT_LIST || !type.of.list) return false;
                ValueTypeGuard elementType;
                wasmtime_component_list_type_element(type.of.list, &elementType.value);
                elementType.active = true;
                ComponentList values;
                values.reserve(value.of.list.size);
                for (size_t index = 0; index < value.of.list.size; ++index) {
                    ComponentValue item;
                    if (!convertFromWasmtime(value.of.list.data[index], elementType.value, &item)) return false;
                    values.push_back(std::move(item));
                }
                *result = ComponentValue::list(std::move(values));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_RECORD: {
                if (value.kind != WASMTIME_COMPONENT_RECORD || !type.of.record) return false;
                const size_t count = wasmtime_component_record_type_field_count(type.of.record);
                if (value.of.record.size != count) return false;
                ComponentRecord fields;
                fields.reserve(count);
                for (size_t index = 0; index < count; ++index) {
                    const char* fieldName = nullptr;
                    size_t fieldNameSize = 0;
                    ValueTypeGuard fieldType;
                    if (!wasmtime_component_record_type_field_nth(type.of.record, index, &fieldName, &fieldNameSize, &fieldType.value) ||
                        !nameEquals(value.of.record.data[index].name, nameString(fieldName, fieldNameSize))) {
                        return false;
                    }
                    fieldType.active = true;
                    ComponentValue fieldValue;
                    if (!convertFromWasmtime(value.of.record.data[index].val, fieldType.value, &fieldValue)) return false;
                    fields.push_back(ComponentRecordField{nameString(fieldName, fieldNameSize), std::move(fieldValue)});
                }
                *result = ComponentValue::record(std::move(fields));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_TUPLE: {
                if (value.kind != WASMTIME_COMPONENT_TUPLE || !type.of.tuple) return false;
                const size_t count = wasmtime_component_tuple_type_types_count(type.of.tuple);
                if (value.of.tuple.size != count) return false;
                ComponentTuple values;
                values.reserve(count);
                for (size_t index = 0; index < count; ++index) {
                    ValueTypeGuard itemType;
                    if (!wasmtime_component_tuple_type_types_nth(type.of.tuple, index, &itemType.value)) return false;
                    itemType.active = true;
                    ComponentValue item;
                    if (!convertFromWasmtime(value.of.tuple.data[index], itemType.value, &item)) return false;
                    values.push_back(std::move(item));
                }
                *result = ComponentValue::tuple(std::move(values));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_VARIANT: {
                if (value.kind != WASMTIME_COMPONENT_VARIANT || !type.of.variant) return false;
                const std::string discriminant = nameString(value.of.variant.discriminant);
                bool hasPayload = false;
                ValueTypeGuard payloadType;
                if (!findVariantCase(type.of.variant, discriminant, &hasPayload, &payloadType.value) ||
                    hasPayload != static_cast<bool>(value.of.variant.val)) {
                    return false;
                }
                payloadType.active = hasPayload;
                std::shared_ptr<ComponentValue> payload;
                if (hasPayload) {
                    payload = std::make_shared<ComponentValue>();
                    if (!convertFromWasmtime(*value.of.variant.val, payloadType.value, payload.get())) return false;
                }
                *result = ComponentValue::variant(discriminant, std::move(payload));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_ENUM: {
                if (value.kind != WASMTIME_COMPONENT_ENUM || !type.of.enum_) return false;
                const std::string name = nameString(value.of.enumeration);
                if (!enumContains(type.of.enum_, name)) return false;
                *result = ComponentValue::enumeration(name);
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_OPTION: {
                if (value.kind != WASMTIME_COMPONENT_OPTION || !type.of.option) return false;
                if (!value.of.option) {
                    *result = ComponentValue::option();
                    return true;
                }
                ValueTypeGuard innerType;
                wasmtime_component_option_type_ty(type.of.option, &innerType.value);
                innerType.active = true;
                auto inner = std::make_shared<ComponentValue>();
                if (!convertFromWasmtime(*value.of.option, innerType.value, inner.get())) return false;
                *result = ComponentValue::option(std::move(inner));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_RESULT: {
                if (value.kind != WASMTIME_COMPONENT_RESULT || !type.of.result) return false;
                const bool isOk = value.of.result.is_ok;
                ValueTypeGuard innerType;
                const bool hasType = isOk ? wasmtime_component_result_type_ok(type.of.result, &innerType.value)
                                          : wasmtime_component_result_type_err(type.of.result, &innerType.value);
                if (!value.of.result.val) {
                    *result = ComponentValue::result(isOk);
                    return !hasType;
                }
                if (!hasType) return false;
                innerType.active = true;
                auto inner = std::make_shared<ComponentValue>();
                if (!convertFromWasmtime(*value.of.result.val, innerType.value, inner.get())) return false;
                *result = ComponentValue::result(isOk, std::move(inner));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_FLAGS: {
                if (value.kind != WASMTIME_COMPONENT_FLAGS || !type.of.flags) return false;
                ComponentFlags names;
                names.reserve(value.of.flags.size);
                for (size_t index = 0; index < value.of.flags.size; ++index) {
                    const std::string name = nameString(value.of.flags.data[index]);
                    if (!flagContains(type.of.flags, name)) return false;
                    names.push_back(name);
                }
                *result = ComponentValue::flags(std::move(names));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_MAP: {
                if (value.kind != WASMTIME_COMPONENT_MAP || !type.of.map) return false;
                ValueTypeGuard keyType;
                ValueTypeGuard valueType;
                wasmtime_component_map_type_key(type.of.map, &keyType.value);
                wasmtime_component_map_type_value(type.of.map, &valueType.value);
                keyType.active = true;
                valueType.active = true;
                ComponentMap entries;
                entries.reserve(value.of.map.size);
                for (size_t index = 0; index < value.of.map.size; ++index) {
                    ComponentValue key;
                    ComponentValue item;
                    if (!convertFromWasmtime(value.of.map.data[index].key, keyType.value, &key) ||
                        !convertFromWasmtime(value.of.map.data[index].value, valueType.value, &item)) {
                        return false;
                    }
                    entries.emplace_back(std::move(key), std::move(item));
                }
                *result = ComponentValue::map(std::move(entries));
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_OWN:
            case WASMTIME_COMPONENT_VALTYPE_BORROW:
                return activeConversionSession && activeConversionSession->liftResource(value, type, result, activeTransientResources);
            default:
                return false;
            }
        }
    } // namespace

    ComponentSession::ComponentSession(wasm_engine_t* engine, wasmtime_component_t* component, std::string key)
        : key_(std::move(key)), engine_(engine ? wasmtime_engine_clone(engine) : nullptr),
          component_(component ? wasmtime_component_clone(component) : nullptr) {
        if (!engine_) {
            LOGE("[Wasmtime] ComponentSession -> Engine is null: %s", key_.c_str());
            return;
        }
        store_ = wasmtime_store_new(engine_, this, nullptr);
        if (!store_) {
            LOGE("[Wasmtime] ComponentSession -> Failed to create store: %s", key_.c_str());
            return;
        }
        context_ = wasmtime_store_context(store_);
        linker_ = wasmtime_component_linker_new(engine_);
        if (!context_ || !linker_) {
            LOGE("[Wasmtime] ComponentSession -> Failed to create runtime state: %s", key_.c_str());
        }
    }

    ComponentSession::~ComponentSession() {
        std::lock_guard<std::mutex> lock(mutex_);
        clearResources();
        if (linker_) wasmtime_component_linker_delete(linker_);
        linker_ = nullptr;
        if (store_) wasmtime_store_delete(store_);
        store_ = nullptr;
        context_ = nullptr;
        clearHostRepresentations();
        for (auto& item : resourceTypes_) {
            if (item.second) wasmtime_component_resource_type_delete(item.second);
        }
        resourceTypes_.clear();
        for (auto& binding : importedResourceBindings_) {
            if (binding->type) {
                wasmtime_component_resource_type_delete(binding->type);
                binding->type = nullptr;
            }
        }
        importedResourceBindings_.clear();
        if (component_) wasmtime_component_delete(component_);
        if (engine_) wasm_engine_delete(engine_);
    }

    bool ComponentSession::registerResource(wasmtime_component_resource_any_t* value, ComponentResourceOwnership ownership,
                                            ComponentResourceOrigin origin, ComponentResourceReference* reference) {
        if (!value || !reference || !context_) return false;
        std::unique_ptr<wasmtime_component_resource_type_t, decltype(&wasmtime_component_resource_type_delete)> type(
            wasmtime_component_resource_any_type(value), &wasmtime_component_resource_type_delete);
        if (!type) return false;

        uint32_t typeId = 0;
        for (const auto& item : resourceTypes_) {
            if (wasmtime_component_resource_type_equal(item.second, type.get())) {
                typeId = item.first;
                break;
            }
        }
        if (typeId == 0) {
            typeId = nextResourceType_++;
            if (typeId == 0) return false;
            resourceTypes_.emplace(typeId, type.release());
        }

        const uint64_t handleId = nextResourceHandle_++;
        const uint32_t generation = nextResourceGeneration_++;
        if (handleId == 0 || generation == 0) return false;
        ResourceEntry entry;
        entry.typeId = typeId;
        entry.handleId = handleId;
        entry.generation = generation;
        entry.ownership = ownership;
        entry.origin = origin;
        entry.value = value;
        entry.type = resourceTypes_.at(typeId);
        resources_.emplace(handleId, entry);
        reference->instanceKey = key_;
        reference->typeId = typeId;
        reference->handleId = handleId;
        reference->generation = generation;
        reference->ownership = ownership;
        reference->origin = origin;
        return true;
    }

    bool ComponentSession::dropResourceUnlocked(const ComponentResourceReference& reference) {
        if (reference.instanceKey != key_ || reference.handleId == 0 || reference.typeId == 0 || reference.generation == 0 ||
            reference.ownership != ComponentResourceOwnership::OWN) {
            return false;
        }
        const auto found = resources_.find(reference.handleId);
        if (found == resources_.end()) return false;
        const ResourceEntry entry = found->second;
        if (entry.typeId != reference.typeId || entry.generation != reference.generation || entry.ownership != reference.ownership ||
            entry.origin != reference.origin || !entry.value) {
            return false;
        }
        resources_.erase(found);
        ComponentInvocationScope invocationScope(this);
        wasmtime_error_t* error = wasmtime_component_resource_any_drop(context_, entry.value);
        if (error) {
            wasmtime_error_delete(error);
            wasmtime_component_resource_any_delete(entry.value);
            return false;
        }
        wasmtime_component_resource_any_delete(entry.value);
        if (entry.origin == ComponentResourceOrigin::HOST && !notifyHostResourceDrop(entry.hostRepresentation, entry.hostType)) {
            return false;
        }
        return true;
    }

    bool ComponentSession::dropResource(const ComponentResourceReference& reference) {
        if (std::find(activeComponentSessions.begin(), activeComponentSessions.end(), this) != activeComponentSessions.end()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return dropResourceUnlocked(reference);
    }

    void ComponentSession::clearTransientResources(const std::vector<uint64_t>& handles) {
        for (const uint64_t handle : handles) {
            const auto found = resources_.find(handle);
            if (found == resources_.end()) continue;
            const ResourceEntry entry = found->second;
            if (entry.ownership == ComponentResourceOwnership::BORROW && entry.value) {
                resources_.erase(found);
                wasmtime_error_t* error = context_ ? wasmtime_component_resource_any_drop(context_, entry.value) : nullptr;
                if (error) wasmtime_error_delete(error);
                wasmtime_component_resource_any_delete(entry.value);
            }
        }
    }

    void ComponentSession::clearResources() {
        while (!resources_.empty()) {
            const auto found = resources_.begin();
            const ResourceEntry entry = found->second;
            resources_.erase(found);
            if (entry.value) {
                if (context_) {
                    ComponentInvocationScope invocationScope(this);
                    wasmtime_error_t* error = wasmtime_component_resource_any_drop(context_, entry.value);
                    if (error) wasmtime_error_delete(error);
                }
                wasmtime_component_resource_any_delete(entry.value);
            }
            if (entry.origin == ComponentResourceOrigin::HOST) {
                notifyHostResourceDrop(entry.hostRepresentation, entry.hostType);
            }
        }
    }

    bool ComponentSession::notifyHostResourceDrop(uint32_t representation, uint32_t hostType) {
        const auto trusted = hostRepresentations_.find(representation);
        if (trusted == hostRepresentations_.end()) return true;
        const auto binding =
            std::find_if(importedResourceBindings_.begin(), importedResourceBindings_.end(), [hostType, &trusted, this](const auto& item) {
                if (hostType != 0 && item->hostType != hostType) return false;
                const auto type = resourceTypes_.find(trusted->second.typeId);
                return type != resourceTypes_.end() && item->type && wasmtime_component_resource_type_equal(item->type, type->second);
            });
        if (binding == importedResourceBindings_.end() || !componentHostHandler_) return false;
        InvocationResult result =
            componentHostHandler_->onComponentHostResourceDrop((*binding)->interfaceName, (*binding)->resourceName, representation);
        if (!result.isSuccess()) return false;
        hostRepresentations_.erase(trusted);
        return true;
    }

    void ComponentSession::clearHostRepresentations() {
        std::vector<uint32_t> representations;
        representations.reserve(hostRepresentations_.size());
        for (const auto& item : hostRepresentations_)
            representations.push_back(item.first);
        for (const uint32_t representation : representations) {
            if (!notifyHostResourceDrop(representation)) {
                LOGE("[Wasmtime] ComponentSession -> Failed to release Host resource representation %u: %s", representation, key_.c_str());
                hostRepresentations_.erase(representation);
            }
        }
    }

    bool ComponentSession::lowerResource(const ComponentValue& value, const wasmtime_component_valtype_t& type,
                                         wasmtime_component_val_t* result) {
        const auto invalid = [this]() {
            resourceConversionInvalid_ = true;
            return false;
        };
        if (!result || value.kind() != ComponentValue::Kind::RESOURCE || !context_ ||
            (type.kind != WASMTIME_COMPONENT_VALTYPE_OWN && type.kind != WASMTIME_COMPONENT_VALTYPE_BORROW)) {
            return invalid();
        }
        const ComponentResourceReference& reference = value.resourceValue();
        if (reference.instanceKey != key_ || reference.handleId == 0 || reference.typeId == 0 || reference.generation == 0)
            return invalid();
        const auto* expectedType = type.kind == WASMTIME_COMPONENT_VALTYPE_OWN ? type.of.own : type.of.borrow;
        const auto found = resources_.find(reference.handleId);
        if (found == resources_.end() && reference.origin == ComponentResourceOrigin::HOST &&
            type.kind == WASMTIME_COMPONENT_VALTYPE_BORROW) {
            const auto trusted = std::find_if(hostRepresentations_.begin(), hostRepresentations_.end(), [&reference](const auto& item) {
                return item.second.typeId == reference.typeId && item.second.handleId == reference.handleId &&
                       item.second.generation == reference.generation;
            });
            if (trusted == hostRepresentations_.end()) return invalid();
            const auto binding =
                std::find_if(importedResourceBindings_.begin(), importedResourceBindings_.end(),
                             [expectedType](const auto& item) { return wasmtime_component_resource_type_equal(item->type, expectedType); });
            if (binding == importedResourceBindings_.end()) return invalid();
            wasmtime_component_resource_host_t* host = wasmtime_component_resource_host_new(false, trusted->first, (*binding)->hostType);
            if (!host) return invalid();
            wasmtime_component_resource_any_t* any = nullptr;
            wasmtime_error_t* error = wasmtime_component_resource_host_to_any(context_, host, &any);
            wasmtime_component_resource_host_delete(host);
            if (error) {
                wasmtime_error_delete(error);
                return invalid();
            }
            initializeValue(result);
            result->kind = WASMTIME_COMPONENT_RESOURCE;
            result->of.resource = any;
            return any != nullptr;
        }
        if (found == resources_.end()) return invalid();
        const ResourceEntry& entry = found->second;
        if (!expectedType || entry.typeId != reference.typeId || entry.generation != reference.generation ||
            entry.ownership != reference.ownership || entry.origin != reference.origin || !entry.value ||
            !wasmtime_component_resource_type_equal(entry.type, expectedType)) {
            return invalid();
        }
        if (type.kind == WASMTIME_COMPONENT_VALTYPE_OWN && reference.ownership != ComponentResourceOwnership::OWN) return invalid();
        if (type.kind == WASMTIME_COMPONENT_VALTYPE_BORROW && reference.ownership != ComponentResourceOwnership::BORROW &&
            reference.ownership != ComponentResourceOwnership::OWN)
            return invalid();
        wasmtime_component_resource_any_t* clone = wasmtime_component_resource_any_clone(entry.value);
        if (!clone) return invalid();
        initializeValue(result);
        result->kind = WASMTIME_COMPONENT_RESOURCE;
        result->of.resource = clone;
        if (type.kind == WASMTIME_COMPONENT_VALTYPE_OWN) {
            if (found->second.origin == ComponentResourceOrigin::HOST) {
                hostRepresentations_[found->second.hostRepresentation] = reference;
            }
            wasmtime_component_resource_any_delete(found->second.value);
            resources_.erase(found);
        }
        return true;
    }

    bool ComponentSession::liftResource(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type,
                                        ComponentValue* result, std::vector<uint64_t>* transientBorrowed) {
        if (!result || value.kind != WASMTIME_COMPONENT_RESOURCE || !value.of.resource ||
            (type.kind != WASMTIME_COMPONENT_VALTYPE_OWN && type.kind != WASMTIME_COMPONENT_VALTYPE_BORROW)) {
            return false;
        }
        const auto expectedType = type.kind == WASMTIME_COMPONENT_VALTYPE_OWN ? type.of.own : type.of.borrow;
        if (!expectedType) return false;
        const bool owned = type.kind == WASMTIME_COMPONENT_VALTYPE_OWN;
        if (wasmtime_component_resource_any_owned(value.of.resource) != owned) return false;
        wasmtime_component_resource_host_t* host = nullptr;
        wasmtime_error_t* hostError = wasmtime_component_resource_any_to_host(context_, value.of.resource, &host);
        if (!hostError && host) {
            const uint32_t representation = wasmtime_component_resource_host_rep(host);
            const uint32_t hostType = wasmtime_component_resource_host_type(host);
            const auto trusted = hostRepresentations_.find(representation);
            if (trusted == hostRepresentations_.end()) {
                wasmtime_component_resource_host_delete(host);
                return false;
            }
            const auto entry = resources_.find(trusted->second.handleId);
            if (entry != resources_.end()) {
                if (!wasmtime_component_resource_type_equal(entry->second.type, expectedType) || entry->second.hostType != hostType) {
                    wasmtime_component_resource_host_delete(host);
                    return false;
                }
            } else {
                const auto binding = std::find_if(importedResourceBindings_.begin(), importedResourceBindings_.end(),
                                                  [hostType](const auto& item) { return item->hostType == hostType; });
                if (binding == importedResourceBindings_.end() || !wasmtime_component_resource_type_equal((*binding)->type, expectedType)) {
                    wasmtime_component_resource_host_delete(host);
                    return false;
                }
                if (owned) {
                    wasmtime_component_resource_any_t* restored = nullptr;
                    wasmtime_error_t* restoreError = wasmtime_component_resource_host_to_any(context_, host, &restored);
                    if (restoreError) wasmtime_error_delete(restoreError);
                    if (restoreError || !restored) {
                        wasmtime_component_resource_host_delete(host);
                        return false;
                    }
                    ResourceEntry restoredEntry;
                    restoredEntry.typeId = trusted->second.typeId;
                    restoredEntry.handleId = trusted->second.handleId;
                    restoredEntry.generation = trusted->second.generation;
                    restoredEntry.ownership = ComponentResourceOwnership::OWN;
                    restoredEntry.origin = ComponentResourceOrigin::HOST;
                    restoredEntry.value = restored;
                    restoredEntry.type = resourceTypes_.at(trusted->second.typeId);
                    restoredEntry.hostRepresentation = representation;
                    restoredEntry.hostType = hostType;
                    resources_.emplace(restoredEntry.handleId, restoredEntry);
                }
            }
            wasmtime_component_resource_host_delete(host);
            *result = ComponentValue::resource(ComponentResourceReference{
                key_,
                trusted->second.typeId,
                trusted->second.handleId,
                trusted->second.generation,
                owned ? ComponentResourceOwnership::OWN : ComponentResourceOwnership::BORROW,
                ComponentResourceOrigin::HOST,
            });
            return true;
        }
        if (hostError) wasmtime_error_delete(hostError);
        wasmtime_component_resource_any_t* clone = wasmtime_component_resource_any_clone(value.of.resource);
        if (!clone) return false;
        ComponentResourceReference reference;
        const ComponentResourceOwnership ownership = owned ? ComponentResourceOwnership::OWN : ComponentResourceOwnership::BORROW;
        if (!registerResource(clone, ownership, ComponentResourceOrigin::GUEST, &reference)) {
            wasmtime_component_resource_any_delete(clone);
            return false;
        }
        if (!wasmtime_component_resource_type_equal(resourceTypes_.at(reference.typeId), expectedType)) {
            dropResourceUnlocked(reference);
            return false;
        }
        if (!owned && transientBorrowed) transientBorrowed->push_back(reference.handleId);
        *result = ComponentValue::resource(std::move(reference));
        return true;
    }

    void ComponentSession::setOutboundHandler(std::unique_ptr<OutboundHandler> handler, std::string codec) {
        std::lock_guard<std::mutex> lock(mutex_);
        outboundHandler_ = std::move(handler);
        codec_ = std::move(codec);
    }

    void ComponentSession::setComponentHostHandler(std::unique_ptr<ComponentHostHandler> handler) {
        std::lock_guard<std::mutex> lock(mutex_);
        componentHostHandler_ = std::move(handler);
    }

    struct ComponentSession::ComponentHostBinding {
        ComponentSession* session = nullptr;
        std::string interfaceName;
        std::string functionName;
    };

    bool ComponentSession::createHostResource(std::string_view interfaceName, std::string_view resourceName, uint32_t representation,
                                              ComponentResourceReference* reference) {
        if (std::find(activeComponentSessions.begin(), activeComponentSessions.end(), this) != activeComponentSessions.end()) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (!initialized_ || !reference || representation == 0) return false;
        const auto binding = std::find_if(importedResourceBindings_.begin(), importedResourceBindings_.end(),
                                          [interfaceName, resourceName](const auto& item) {
                                              return item->interfaceName == interfaceName && item->resourceName == resourceName;
                                          });
        if (binding == importedResourceBindings_.end()) return false;
        wasmtime_component_resource_host_t* host = wasmtime_component_resource_host_new(true, representation, (*binding)->hostType);
        if (!host) return false;
        wasmtime_component_resource_any_t* any = nullptr;
        wasmtime_error_t* error = wasmtime_component_resource_host_to_any(context_, host, &any);
        wasmtime_component_resource_host_delete(host);
        if (error) {
            wasmtime_error_delete(error);
            return false;
        }
        if (!any) return false;
        uint32_t typeId = 0;
        for (const auto& item : resourceTypes_) {
            if (wasmtime_component_resource_type_equal(item.second, (*binding)->type)) {
                typeId = item.first;
                break;
            }
        }
        if (typeId == 0) {
            typeId = nextResourceType_++;
            resourceTypes_.emplace(typeId, wasmtime_component_resource_type_clone((*binding)->type));
        }
        const uint64_t handleId = nextResourceHandle_++;
        const uint32_t generation = nextResourceGeneration_++;
        if (typeId == 0 || handleId == 0 || generation == 0) {
            wasmtime_component_resource_any_delete(any);
            return false;
        }
        ResourceEntry entry;
        entry.typeId = typeId;
        entry.handleId = handleId;
        entry.generation = generation;
        entry.ownership = ComponentResourceOwnership::OWN;
        entry.origin = ComponentResourceOrigin::HOST;
        entry.value = any;
        entry.type = resourceTypes_.at(typeId);
        entry.hostRepresentation = representation;
        entry.hostType = (*binding)->hostType;
        resources_.emplace(handleId, entry);
        *reference =
            ComponentResourceReference{key_, typeId, handleId, generation, ComponentResourceOwnership::OWN, ComponentResourceOrigin::HOST};
        hostRepresentations_[representation] = *reference;
        return true;
    }

    bool ComponentSession::registerServiceImports() {
        wasmtime_component_type_t* componentType = wasmtime_component_type(component_);
        if (!componentType) {
            LOGE("[Wasmtime] ComponentSession -> Component type is unavailable: %s", key_.c_str());
            return false;
        }

        const size_t importCount = wasmtime_component_type_import_count(componentType, engine_);
        for (size_t index = 0; index < importCount; ++index) {
            const char* importName = nullptr;
            size_t importNameSize = 0;
            ComponentItemGuard import;
            if (!wasmtime_component_type_import_nth(componentType, engine_, index, &importName, &importNameSize, &import.value)) {
                wasmtime_component_type_delete(componentType);
                LOGE("[Wasmtime] ComponentSession -> Component import type is unavailable: %s", key_.c_str());
                return false;
            }
            import.active = true;
            const std::string importNameText = nameString(importName, importNameSize);
            if (import.value.kind != WASMTIME_COMPONENT_ITEM_COMPONENT_INSTANCE ||
                !serviceInstanceTypeMatches(import.value.of.component_instance, engine_)) {
                continue;
            }

            wasmtime_component_linker_instance_t* root = wasmtime_component_linker_root(linker_);
            if (!root) {
                wasmtime_component_type_delete(componentType);
                LOGE("[Wasmtime] ComponentSession -> Component linker root is unavailable: %s", key_.c_str());
                return false;
            }

            wasmtime_component_linker_instance_t* serviceInstance = nullptr;
            wasmtime_error_t* error = wasmtime_component_linker_instance_add_instance(root, importName, importNameSize, &serviceInstance);
            if (!error && serviceInstance) {
                error = wasmtime_component_linker_instance_add_func(serviceInstance, "invoke", 6, invokeHost, this, nullptr);
            } else if (!error) {
                error = componentError("Wasmline Service linker instance is unavailable.");
            }
            if (serviceInstance) wasmtime_component_linker_instance_delete(serviceInstance);
            wasmtime_component_linker_instance_delete(root);

            if (error) {
                const std::string message = wasmtime::errorMessage(error);
                wasmtime_error_delete(error);
                wasmtime_component_type_delete(componentType);
                LOGE("[Wasmtime] ComponentSession -> Failed to register Service import '%s': %s", importNameText.c_str(), message.c_str());
                return false;
            }
            LOGI("[Wasmtime] ComponentSession -> Registered Service import: %s", importNameText.c_str());
        }

        wasmtime_component_type_delete(componentType);
        return true;
    }

    bool ComponentSession::registerComponentHostImports() {
        wasmtime_component_type_t* componentType = wasmtime_component_type(component_);
        if (!componentType) {
            LOGE("[Wasmtime] ComponentSession -> Component type is unavailable: %s", key_.c_str());
            return false;
        }

        const size_t importCount = wasmtime_component_type_import_count(componentType, engine_);
        for (size_t importIndex = 0; importIndex < importCount; ++importIndex) {
            const char* importName = nullptr;
            size_t importNameSize = 0;
            ComponentItemGuard import;
            if (!wasmtime_component_type_import_nth(componentType, engine_, importIndex, &importName, &importNameSize, &import.value)) {
                wasmtime_component_type_delete(componentType);
                LOGE("[Wasmtime] ComponentSession -> Component import type is unavailable: %s", key_.c_str());
                return false;
            }
            import.active = true;

            const std::string interfaceName = nameString(importName, importNameSize);
            if (isWasiImport(interfaceName) || import.value.kind != WASMTIME_COMPONENT_ITEM_COMPONENT_INSTANCE ||
                serviceInstanceTypeMatches(import.value.of.component_instance, engine_)) {
                continue;
            }

            wasmtime_component_linker_instance_t* root = wasmtime_component_linker_root(linker_);
            if (!root) {
                wasmtime_component_type_delete(componentType);
                LOGE("[Wasmtime] ComponentSession -> Component linker root is unavailable: %s", key_.c_str());
                return false;
            }

            wasmtime_component_linker_instance_t* hostInstance = nullptr;
            wasmtime_error_t* instanceError =
                wasmtime_component_linker_instance_add_instance(root, importName, importNameSize, &hostInstance);
            wasmtime_component_linker_instance_delete(root);
            if (instanceError || !hostInstance) {
                const std::string message =
                    instanceError ? wasmtime::errorMessage(instanceError) : "Typed Component linker instance is unavailable.";
                if (instanceError) wasmtime_error_delete(instanceError);
                wasmtime_component_type_delete(componentType);
                LOGE("[Wasmtime] ComponentSession -> Failed to register typed import '%s': %s", interfaceName.c_str(), message.c_str());
                return false;
            }

            const size_t functionCount = wasmtime_component_instance_type_export_count(import.value.of.component_instance, engine_);
            for (size_t functionIndex = 0; functionIndex < functionCount; ++functionIndex) {
                const char* functionName = nullptr;
                size_t functionNameSize = 0;
                ComponentItemGuard function;
                if (!wasmtime_component_instance_type_export_nth(import.value.of.component_instance, engine_, functionIndex, &functionName,
                                                                 &functionNameSize, &function.value)) {
                    wasmtime_component_linker_instance_delete(hostInstance);
                    wasmtime_component_type_delete(componentType);
                    LOGE("[Wasmtime] ComponentSession -> Component import function type is unavailable: %s", interfaceName.c_str());
                    return false;
                }
                function.active = true;
                if (function.value.kind == WASMTIME_COMPONENT_ITEM_RESOURCE) {
                    auto binding = std::make_unique<ImportedResourceBinding>();
                    binding->session = this;
                    binding->interfaceName = interfaceName;
                    binding->resourceName = nameString(functionName, functionNameSize);
                    binding->hostType = nextHostResourceType_++;
                    binding->type = wasmtime_component_resource_type_new_host(binding->hostType);
                    if (!binding->type) {
                        wasmtime_component_linker_instance_delete(hostInstance);
                        wasmtime_component_type_delete(componentType);
                        return false;
                    }
                    wasmtime_error_t* resourceError = wasmtime_component_linker_instance_add_resource(
                        hostInstance, functionName, functionNameSize, binding->type, dropImportedResource, binding.get(), nullptr);
                    if (resourceError) {
                        const std::string message = wasmtime::errorMessage(resourceError);
                        wasmtime_error_delete(resourceError);
                        wasmtime_component_linker_instance_delete(hostInstance);
                        wasmtime_component_type_delete(componentType);
                        LOGE("[Wasmtime] ComponentSession -> Failed to register imported resource '%s/%s': %s", interfaceName.c_str(),
                             binding->resourceName.c_str(), message.c_str());
                        return false;
                    }
                    importedResourceBindings_.push_back(std::move(binding));
                    continue;
                }
                if (function.value.kind != WASMTIME_COMPONENT_ITEM_COMPONENT_FUNC) continue;

                const std::string functionNameText = nameString(functionName, functionNameSize);
                const std::string functionLabel = componentHostFunctionLabel(interfaceName, functionNameText);
                if (wasmtime_component_func_type_async(function.value.of.component_func)) {
                    wasmtime_component_linker_instance_delete(hostInstance);
                    wasmtime_component_type_delete(componentType);
                    LOGE("[Wasmtime] ComponentSession -> Async typed import is unsupported: %s", functionLabel.c_str());
                    return false;
                }

                auto binding = std::make_unique<ComponentHostBinding>();
                binding->session = this;
                binding->interfaceName = interfaceName;
                binding->functionName = functionNameText;
                wasmtime_error_t* functionError = wasmtime_component_linker_instance_add_func(hostInstance, functionName, functionNameSize,
                                                                                              invokeComponentHost, binding.get(), nullptr);
                if (functionError) {
                    const std::string message = wasmtime::errorMessage(functionError);
                    wasmtime_error_delete(functionError);
                    wasmtime_component_linker_instance_delete(hostInstance);
                    wasmtime_component_type_delete(componentType);
                    LOGE("[Wasmtime] ComponentSession -> Failed to register typed import '%s': %s", functionLabel.c_str(), message.c_str());
                    return false;
                }
                componentHostBindings_.push_back(std::move(binding));
                LOGI("[Wasmtime] ComponentSession -> Registered typed import: %s", functionLabel.c_str());
            }
            wasmtime_component_linker_instance_delete(hostInstance);
        }

        wasmtime_component_type_delete(componentType);
        return true;
    }

    wasmtime_error_t* ComponentSession::invokeHost(void* data, wasmtime_context_t* context,
                                                   const wasmtime_component_func_type_t* functionType, wasmtime_component_val_t* arguments,
                                                   size_t argumentCount, wasmtime_component_val_t* results, size_t resultCount) {
        auto* session = static_cast<ComponentSession*>(data);
        if (!session || context != session->context_) return componentError("Wasmline Service callback has no active component session.");
        return session->handleHostInvoke(functionType, arguments, argumentCount, results, resultCount);
    }

    wasmtime_error_t* ComponentSession::handleHostInvoke(const wasmtime_component_func_type_t* functionType,
                                                         wasmtime_component_val_t* arguments, size_t argumentCount,
                                                         wasmtime_component_val_t* results, size_t resultCount) {
        if (!serviceFunctionTypeMatches(functionType) || argumentCount != 1 || resultCount != 1 || !arguments || !results) {
            return componentError("Wasmline Service host.invoke signature does not match wasmline:service@1.0.0.");
        }

        const char* parameterName = nullptr;
        size_t parameterNameSize = 0;
        ValueTypeGuard parameterType;
        if (!wasmtime_component_func_type_param_nth(functionType, 0, &parameterName, &parameterNameSize, &parameterType.value)) {
            return componentError("Wasmline Service request type is unavailable.");
        }
        parameterType.active = true;

        ComponentValue request;
        std::vector<uint64_t> transientBorrowed;
        ComponentConversionScope conversionScope(this, &transientBorrowed);
        if (!fromWasmtimeValue(arguments[0], parameterType.value, &request) || request.kind() != ComponentValue::Kind::RECORD) {
            clearTransientResources(transientBorrowed);
            return componentError("Wasmline Service request value is invalid.");
        }

        const auto& requestFields = request.recordValue();
        const ComponentValue* actionValue = recordField(requestFields, "action");
        const ComponentValue* codecValue = recordField(requestFields, "codec");
        const ComponentValue* payloadValue = recordField(requestFields, "payload");
        std::string payload;
        if (!actionValue || actionValue->kind() != ComponentValue::Kind::STRING || !codecValue ||
            codecValue->kind() != ComponentValue::Kind::STRING || !payloadValue || !componentBytes(*payloadValue, &payload)) {
            clearTransientResources(transientBorrowed);
            return componentError("Wasmline Service request fields are invalid.");
        }

        ComponentValue response;
        if (!codec_.empty() && codecValue->stringValue() != codec_) {
            response = serviceFailure(static_cast<uint32_t>(WasmlineErrorCode::SERIALIZATION_FAILED),
                                      "Wasmline Service codec mismatch. Expected '" + codec_ + "' but received '" +
                                          codecValue->stringValue() + "'.");
        } else if (!outboundHandler_) {
            response = serviceFailure(static_cast<uint32_t>(WasmlineErrorCode::ACTION_NOT_BOUND), "No Wasmline outbound action is bound.");
        } else {
            std::string encodedResponse;
            try {
                encodedResponse = outboundHandler_->onOutboundInvoke(actionValue->stringValue(), payload);
            } catch (const std::exception& error) {
                encodedResponse = WasmlineResponseCodec::failure(WasmlineErrorCode::HANDLER_FAILED, error.what());
            } catch (...) {
                encodedResponse =
                    WasmlineResponseCodec::failure(WasmlineErrorCode::HANDLER_FAILED, "Wasmline outbound action handler failed.");
            }

            WasmlineResponseFrame frame;
            std::string decodeError;
            if (!WasmlineResponseCodec::decode(encodedResponse, &frame, &decodeError)) {
                response = serviceFailure(static_cast<uint32_t>(WasmlineErrorCode::RESPONSE_MALFORMED), std::move(decodeError));
            } else if (frame.isSuccess) {
                response = serviceSuccess(frame.payload);
            } else {
                response = serviceFailure(frame.errorCode, std::move(frame.message), frame.payload);
            }
        }

        ValueTypeGuard resultType;
        if (!wasmtime_component_func_type_result(functionType, &resultType.value)) {
            return componentError("Wasmline Service result type is unavailable.");
        }
        resultType.active = true;
        if (!toWasmtimeValue(response, resultType.value, &results[0])) {
            clearTransientResources(transientBorrowed);
            return componentError("Wasmline Service response could not be lowered to the component result type.");
        }
        clearTransientResources(transientBorrowed);
        return nullptr;
    }

    wasmtime_error_t* ComponentSession::invokeComponentHost(void* data, wasmtime_context_t* context,
                                                            const wasmtime_component_func_type_t* functionType,
                                                            wasmtime_component_val_t* arguments, size_t argumentCount,
                                                            wasmtime_component_val_t* results, size_t resultCount) {
        auto* binding = static_cast<ComponentHostBinding*>(data);
        if (!binding || !binding->session || context != binding->session->context_) {
            return componentError("Typed Component host callback has no active component session.");
        }
        return binding->session->handleComponentHostInvoke(*binding, functionType, arguments, argumentCount, results, resultCount);
    }

    wasmtime_error_t* ComponentSession::dropImportedResource(void* data, wasmtime_context_t* context, uint32_t representation) {
        auto* binding = static_cast<ImportedResourceBinding*>(data);
        if (!binding || !binding->session || context != binding->session->context_) {
            return componentError("Imported Component resource drop has no active session.");
        }
        return binding->session->handleImportedResourceDrop(*binding, representation);
    }

    wasmtime_error_t* ComponentSession::handleImportedResourceDrop(const ImportedResourceBinding& binding, uint32_t representation) {
        if (!notifyHostResourceDrop(representation, binding.hostType))
            return componentError("Typed Component host resource is stale or its drop handler failed.");
        return nullptr;
    }

    wasmtime_error_t* ComponentSession::handleComponentHostInvoke(const ComponentHostBinding& binding,
                                                                  const wasmtime_component_func_type_t* functionType,
                                                                  wasmtime_component_val_t* arguments, size_t argumentCount,
                                                                  wasmtime_component_val_t* results, size_t resultCount) {
        const std::string functionLabel = componentHostFunctionLabel(binding.interfaceName, binding.functionName);
        if (!functionType || (argumentCount > 0 && !arguments) || (resultCount > 0 && !results) ||
            wasmtime_component_func_type_param_count(functionType) != argumentCount) {
            return componentError("Typed Component host import '" + functionLabel + "' received a mismatched argument count.");
        }

        std::vector<ComponentValue> convertedArguments;
        convertedArguments.reserve(argumentCount);
        std::vector<uint64_t> transientBorrowed;
        ComponentConversionScope conversionScope(this, &transientBorrowed);
        for (size_t index = 0; index < argumentCount; ++index) {
            const char* argumentName = nullptr;
            size_t argumentNameSize = 0;
            ValueTypeGuard argumentType;
            if (!wasmtime_component_func_type_param_nth(functionType, index, &argumentName, &argumentNameSize, &argumentType.value)) {
                return componentError("Typed Component host import '" + functionLabel + "' argument type is unavailable.");
            }
            argumentType.active = true;
            ComponentValue argument;
            if (!fromWasmtimeValue(arguments[index], argumentType.value, &argument)) {
                clearTransientResources(transientBorrowed);
                return componentError("Typed Component host import '" + functionLabel + "' argument " + std::to_string(index) +
                                      " does not match its Component type.");
            }
            convertedArguments.push_back(std::move(argument));
        }

        if (!componentHostHandler_) {
            clearTransientResources(transientBorrowed);
            return componentError("No typed Component host adapter is registered for '" + functionLabel + "'.");
        }

        InvocationResult invocationResult = [&]() {
            try {
                return componentHostHandler_->onComponentHostInvoke(binding.interfaceName, binding.functionName, convertedArguments);
            } catch (const std::exception& error) {
                return InvocationResult::failure(WasmlineErrorCode::HANDLER_FAILED, error.what());
            } catch (...) {
                return InvocationResult::failure(WasmlineErrorCode::HANDLER_FAILED, "Typed Component host adapter failed.");
            }
        }();
        if (!invocationResult.isSuccess()) {
            clearTransientResources(transientBorrowed);
            if (invocationResult.errorCode() == WasmlineErrorCode::ACTION_NOT_BOUND) {
                return componentError("No typed Component host adapter is registered for '" + functionLabel + "'.");
            }
            return componentError("Typed Component host adapter for '" + functionLabel + "' failed [" +
                                  std::to_string(static_cast<int32_t>(invocationResult.errorCode())) + "]: " + invocationResult.message());
        }

        wasmtime_component_valtype_t resultType{};
        const bool hasResult = wasmtime_component_func_type_result(functionType, &resultType);
        if (resultCount != (hasResult ? 1u : 0u)) {
            if (hasResult) wasmtime_component_valtype_delete(&resultType);
            return componentError("Typed Component host import '" + functionLabel + "' received a mismatched result count.");
        }

        const auto& values = invocationResult.componentValues();
        if (values.size() != resultCount) {
            if (hasResult) wasmtime_component_valtype_delete(&resultType);
            return componentError("Typed Component host adapter for '" + functionLabel + "' returned " + std::to_string(values.size()) +
                                  " value(s); expected " + std::to_string(resultCount) + ".");
        }
        if (!hasResult) return nullptr;

        const bool converted = toWasmtimeValue(values[0], resultType, &results[0]);
        wasmtime_component_valtype_delete(&resultType);
        clearTransientResources(transientBorrowed);
        if (!converted) {
            return componentError("Typed Component host adapter for '" + functionLabel +
                                  "' returned a value that does not match its Component type.");
        }
        return nullptr;
    }

    bool ComponentSession::initialize() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (initialized_) return true;
        if (!store_ || !context_ || !linker_ || !component_) {
            LOGE("[Wasmtime] ComponentSession -> Invalid state: %s", key_.c_str());
            return false;
        }

#ifdef WASMTIME_FEATURE_WASI
        wasi_config_t* wasi = wasi_config_new();
        if (!wasi) return false;
        wasi::configure(wasi, "[Wasmtime-Wasi] component logger");
        wasmtime_error_t* wasiError = wasmtime_context_set_wasi(context_, wasi);
        if (wasiError) {
            LOGE("[Wasmtime] ComponentSession -> Failed to configure WASI: %s", wasmtime::errorMessage(wasiError).c_str());
            wasmtime_error_delete(wasiError);
            wasi_config_delete(wasi);
            return false;
        }
        wasmtime_error_t* linkerError = wasmtime_component_linker_add_wasip2(linker_);
        if (linkerError) {
            LOGE("[Wasmtime] ComponentSession -> Failed to add WASI Preview 2: %s", wasmtime::errorMessage(linkerError).c_str());
            wasmtime_error_delete(linkerError);
            return false;
        }
#endif

        if (!registerServiceImports() || !registerComponentHostImports()) return false;

        wasmtime_error_t* instantiateError = wasmtime_component_linker_instantiate(linker_, context_, component_, &instance_);
        if (instantiateError) {
            LOGE("[Wasmtime] ComponentSession -> Instantiation failed: %s", wasmtime::errorMessage(instantiateError).c_str());
            wasmtime_error_delete(instantiateError);
            return false;
        }
        initialized_ = true;
        return true;
    }

    InvocationResult ComponentSession::invoke(std::string_view exportName, const std::vector<ComponentValue>& arguments) {
        if (std::find(activeComponentSessions.begin(), activeComponentSessions.end(), this) != activeComponentSessions.end()) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_CALL_FAILED,
                                             "Recursive invocation of the same Component session is not supported.");
        }
        if (activeComponentSessions.size() >= kMaxComponentInvocationDepth) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_CALL_FAILED, "Component invocation depth limit was exceeded.");
        }

        std::unique_lock<std::mutex> lock(mutex_, std::defer_lock);
        if (activeComponentSessions.empty()) {
            lock.lock();
        } else if (!lock.try_lock()) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_CALL_FAILED,
                                             "Nested Component session is already active on another thread.");
        }
        if (!initialized_) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Component session is not initialized.");
        }
        if (exportName.empty()) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_EXPORT_NOT_FOUND, "Component export name is empty.");
        }

        ExportIndexGuard exportIndices;
        if (!resolveExportPath(&instance_, context_, nullptr, exportName, &exportIndices)) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_EXPORT_NOT_FOUND, "Component export is not available.");
        }

        wasmtime_component_func_t function{};
        const bool found = wasmtime_component_instance_get_func(&instance_, context_, exportIndices.values.back(), &function);
        if (!found) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_EXPORT_NOT_FOUND, "Component export is not a function.");
        }

        wasmtime_component_func_type_t* functionType = wasmtime_component_func_type(&function, context_);
        if (!functionType) {
            return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Component function type is not available.");
        }

        const size_t parameterCount = wasmtime_component_func_type_param_count(functionType);
        if (parameterCount != arguments.size()) {
            wasmtime_component_func_type_delete(functionType);
            return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Component parameter count does not match.");
        }

        std::vector<wasmtime_component_valtype_t> parameterTypes(parameterCount);
        std::vector<bool> parameterTypeOwned(parameterCount, false);
        std::vector<wasmtime_component_val_t> callArguments(parameterCount);
        std::vector<uint64_t> transientBorrowed;
        ComponentConversionScope conversionScope(this, &transientBorrowed);
        resourceConversionInvalid_ = false;
        for (size_t index = 0; index < parameterCount; ++index) {
            const char* parameterName = nullptr;
            size_t parameterNameSize = 0;
            if (!wasmtime_component_func_type_param_nth(functionType, index, &parameterName, &parameterNameSize, &parameterTypes[index])) {
                for (size_t typeIndex = 0; typeIndex < index; ++typeIndex) {
                    if (parameterTypeOwned[typeIndex]) wasmtime_component_valtype_delete(&parameterTypes[typeIndex]);
                    wasmtime_component_val_delete(&callArguments[typeIndex]);
                }
                wasmtime_component_func_type_delete(functionType);
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Component parameter type is not available.");
            }
            parameterTypeOwned[index] = true;
            if (!convertToWasmtime(arguments[index], parameterTypes[index], &callArguments[index])) {
                for (size_t typeIndex = 0; typeIndex < parameterCount; ++typeIndex) {
                    if (parameterTypeOwned[typeIndex]) wasmtime_component_valtype_delete(&parameterTypes[typeIndex]);
                    if (typeIndex <= index) wasmtime_component_val_delete(&callArguments[typeIndex]);
                }
                wasmtime_component_func_type_delete(functionType);
                const bool invalidResource = resourceConversionInvalid_;
                resourceConversionInvalid_ = false;
                return InvocationResult::failure(
                    invalidResource ? WasmlineErrorCode::COMPONENT_RESOURCE_INVALID : WasmlineErrorCode::INVALID_PAYLOAD,
                    invalidResource ? "Component resource is stale or has the wrong instance, type, generation, or ownership."
                                    : "Component parameter value does not match its type.");
            }
        }
        resourceConversionInvalid_ = false;

        wasmtime_component_valtype_t resultType{};
        const bool hasResult = wasmtime_component_func_type_result(functionType, &resultType);
        std::vector<wasmtime_component_val_t> callResults(hasResult ? 1 : 0);
        ComponentInvocationScope invocationScope(this);
        wasmtime_error_t* callError = wasmtime_component_func_call(&function, context_, callArguments.data(), callArguments.size(),
                                                                   callResults.data(), callResults.size());
        for (size_t index = 0; index < parameterCount; ++index) {
            if (parameterTypeOwned[index]) wasmtime_component_valtype_delete(&parameterTypes[index]);
            wasmtime_component_val_delete(&callArguments[index]);
        }
        wasmtime_component_func_type_delete(functionType);

        if (callError) {
            const std::string message = wasmtime::errorMessage(callError);
            const bool trap = hasWasmTrace(callError);
            wasmtime_error_delete(callError);
            for (auto& value : callResults)
                wasmtime_component_val_delete(&value);
            if (hasResult) wasmtime_component_valtype_delete(&resultType);
            clearTransientResources(transientBorrowed);
            return InvocationResult::failure(trap ? WasmlineErrorCode::COMPONENT_TRAP : WasmlineErrorCode::COMPONENT_CALL_FAILED, message);
        }

        std::vector<ComponentValue> values;
        if (hasResult) {
            ComponentValue value;
            const bool converted = convertFromWasmtime(callResults[0], resultType, &value);
            wasmtime_component_val_delete(&callResults[0]);
            wasmtime_component_valtype_delete(&resultType);
            clearTransientResources(transientBorrowed);
            if (!converted) {
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Component result value does not match its type.");
            }
            values.push_back(std::move(value));
        }
        clearTransientResources(transientBorrowed);
        return InvocationResult::successComponent(std::move(values));
    }

    bool ComponentSession::toWasmtimeValue(const ComponentValue& value, const wasmtime_component_valtype_t& type,
                                           wasmtime_component_val_t* result) {
        ComponentConversionScope scope(this);
        return convertToWasmtime(value, type, result);
    }

    bool ComponentSession::fromWasmtimeValue(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type,
                                             ComponentValue* result, std::vector<uint64_t>* transientBorrowed) {
        ComponentConversionScope scope(this, transientBorrowed);
        return convertFromWasmtime(value, type, result);
    }

    bool ComponentSession::hasWasmTrace(const wasmtime_error_t* error) {
        if (!error) return false;
        wasm_frame_vec_t trace{};
        wasmtime_error_wasm_trace(error, &trace);
        const bool hasTrace = trace.size > 0;
        wasm_frame_vec_delete(&trace);
        return hasTrace;
    }
} // namespace wasmline
