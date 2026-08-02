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
#include <utility>

#include "logging/NativeLogger.h"
#include "wasi/WasiConfig.h"
#include "wasmtime/WasmtimeMessage.h"

namespace wasmline {
    namespace {
        struct ValueTypeGuard {
            wasmtime_component_valtype_t value{};
            bool active = false;

            ~ValueTypeGuard() {
                if (active) wasmtime_component_valtype_delete(&value);
            }
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
                    wasmtime_component_val_delete(result);
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
                        wasmtime_component_val_delete(result);
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
                        wasmtime_component_val_delete(result);
                        return false;
                    }
                    fieldType.active = true;
                    if (!setName(&result->of.record.data[index].name, fields[index].name) ||
                        !convertToWasmtime(fields[index].value, fieldType.value, &result->of.record.data[index].val)) {
                        wasmtime_component_val_delete(result);
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
                        wasmtime_component_val_delete(result);
                        return false;
                    }
                    itemType.active = true;
                    if (!convertToWasmtime(values[index], itemType.value, &result->of.tuple.data[index])) {
                        wasmtime_component_val_delete(result);
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
                    wasmtime_component_val_delete(result);
                    return false;
                }
                if (hasPayload != static_cast<bool>(variant.value)) return false;
                if (hasPayload) {
                    wasmtime_component_val_t payload{};
                    if (!convertToWasmtime(*variant.value, payloadType.value, &payload)) {
                        wasmtime_component_val_delete(result);
                        return false;
                    }
                    result->of.variant.val = wasmtime_component_val_new(&payload);
                    if (!result->of.variant.val) {
                        wasmtime_component_val_delete(&payload);
                        wasmtime_component_val_delete(result);
                        return false;
                    }
                }
                return true;
            }
            case WASMTIME_COMPONENT_VALTYPE_ENUM:
                if (value.kind() != ComponentValue::Kind::ENUM || !enumContains(type.of.enum_, value.enumValue())) return false;
                result->kind = WASMTIME_COMPONENT_ENUM;
                if (!setName(&result->of.enumeration, value.enumValue())) {
                    wasmtime_component_val_delete(result);
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
                    wasmtime_component_val_delete(&inner);
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
                    wasmtime_component_val_delete(&inner);
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
                        wasmtime_component_val_delete(result);
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
                        wasmtime_component_val_delete(result);
                        return false;
                    }
                }
                return true;
            }
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
            default:
                return false;
            }
        }
    } // namespace

    ComponentSession::ComponentSession(wasm_engine_t* engine, wasmtime_component_t* component, std::string key)
        : key_(std::move(key)), engine_(engine), component_(component) {
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
        if (linker_) wasmtime_component_linker_delete(linker_);
        if (store_) wasmtime_store_delete(store_);
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
        std::lock_guard<std::mutex> lock(mutex_);
        if (!initialized_) {
            return InvocationResult::failure(WasmlineErrorCode::ENGINE_NOT_INITIALIZED, "Component session is not initialized.");
        }
        if (exportName.empty()) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_EXPORT_NOT_FOUND, "Component export name is empty.");
        }

        wasmtime_component_export_index_t* exportIndex =
            wasmtime_component_instance_get_export_index(&instance_, context_, nullptr, exportName.data(), exportName.size());
        if (!exportIndex) {
            return InvocationResult::failure(WasmlineErrorCode::COMPONENT_EXPORT_NOT_FOUND, "Component export is not available.");
        }

        wasmtime_component_func_t function{};
        const bool found = wasmtime_component_instance_get_func(&instance_, context_, exportIndex, &function);
        wasmtime_component_export_index_delete(exportIndex);
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
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Component parameter value does not match its type.");
            }
        }

        wasmtime_component_valtype_t resultType{};
        const bool hasResult = wasmtime_component_func_type_result(functionType, &resultType);
        std::vector<wasmtime_component_val_t> callResults(hasResult ? 1 : 0);
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
            return InvocationResult::failure(trap ? WasmlineErrorCode::COMPONENT_TRAP : WasmlineErrorCode::COMPONENT_CALL_FAILED, message);
        }

        std::vector<ComponentValue> values;
        if (hasResult) {
            ComponentValue value;
            const bool converted = convertFromWasmtime(callResults[0], resultType, &value);
            wasmtime_component_val_delete(&callResults[0]);
            wasmtime_component_valtype_delete(&resultType);
            if (!converted) {
                return InvocationResult::failure(WasmlineErrorCode::INVALID_PAYLOAD, "Component result value does not match its type.");
            }
            values.push_back(std::move(value));
        }
        return InvocationResult::successComponent(std::move(values));
    }

    bool ComponentSession::toWasmtimeValue(const ComponentValue& value, const wasmtime_component_valtype_t& type,
                                           wasmtime_component_val_t* result) {
        return convertToWasmtime(value, type, result);
    }

    bool ComponentSession::fromWasmtimeValue(const wasmtime_component_val_t& value, const wasmtime_component_valtype_t& type,
                                             ComponentValue* result) {
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
