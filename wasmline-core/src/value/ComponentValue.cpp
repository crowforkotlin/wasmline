/**
 * Implements owned values for Component Model calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/value/ComponentValue.h"

#include <utility>

namespace wasmline {
    ComponentValue::ComponentValue() : kind_(Kind::BOOL), storage_(std::make_shared<Storage>(Storage::Data(false))) {}

    template <typename T> ComponentValue ComponentValue::create(Kind kind, T value) {
        return ComponentValue(kind, std::make_shared<Storage>(Storage::Data(std::move(value))));
    }

    ComponentValue::ComponentValue(Kind kind, std::shared_ptr<Storage> storage) : kind_(kind), storage_(std::move(storage)) {}

    ComponentValue ComponentValue::boolean(bool value) {
        return create(Kind::BOOL, value);
    }

    ComponentValue ComponentValue::s8(int8_t value) {
        return create(Kind::S8, value);
    }

    ComponentValue ComponentValue::u8(uint8_t value) {
        return create(Kind::U8, value);
    }

    ComponentValue ComponentValue::s16(int16_t value) {
        return create(Kind::S16, value);
    }

    ComponentValue ComponentValue::u16(uint16_t value) {
        return create(Kind::U16, value);
    }

    ComponentValue ComponentValue::s32(int32_t value) {
        return create(Kind::S32, value);
    }

    ComponentValue ComponentValue::u32(uint32_t value) {
        return create(Kind::U32, value);
    }

    ComponentValue ComponentValue::s64(int64_t value) {
        return create(Kind::S64, value);
    }

    ComponentValue ComponentValue::u64(uint64_t value) {
        return create(Kind::U64, value);
    }

    ComponentValue ComponentValue::f32(float value) {
        return create(Kind::F32, value);
    }

    ComponentValue ComponentValue::f64(double value) {
        return create(Kind::F64, value);
    }

    ComponentValue ComponentValue::character(uint32_t value) {
        return create(Kind::CHAR, ComponentCharacter{value});
    }

    ComponentValue ComponentValue::string(std::string value) {
        return create(Kind::STRING, std::move(value));
    }

    ComponentValue ComponentValue::list(ComponentList value) {
        return create(Kind::LIST, std::move(value));
    }

    ComponentValue ComponentValue::record(ComponentRecord value) {
        return create(Kind::RECORD, std::move(value));
    }

    ComponentValue ComponentValue::tuple(ComponentTuple value) {
        return create(Kind::TUPLE, ComponentTupleData{std::move(value)});
    }

    ComponentValue ComponentValue::variant(std::string discriminant, std::shared_ptr<ComponentValue> value) {
        return create(Kind::VARIANT, ComponentVariant{std::move(discriminant), std::move(value)});
    }

    ComponentValue ComponentValue::enumeration(std::string name) {
        return create(Kind::ENUM, ComponentEnumData{std::move(name)});
    }

    ComponentValue ComponentValue::option(std::shared_ptr<ComponentValue> value) {
        return create(Kind::OPTION, std::move(value));
    }

    ComponentValue ComponentValue::result(bool isOk, std::shared_ptr<ComponentValue> value) {
        return create(Kind::RESULT, ComponentResult{isOk, std::move(value)});
    }

    ComponentValue ComponentValue::flags(ComponentFlags value) {
        return create(Kind::FLAGS, std::move(value));
    }

    ComponentValue ComponentValue::map(ComponentMap value) {
        return create(Kind::MAP, std::move(value));
    }

    ComponentValue::Kind ComponentValue::kind() const {
        return kind_;
    }

    bool ComponentValue::booleanValue() const {
        return std::get<bool>(storage_->data);
    }

    int8_t ComponentValue::s8Value() const {
        return std::get<int8_t>(storage_->data);
    }

    uint8_t ComponentValue::u8Value() const {
        return std::get<uint8_t>(storage_->data);
    }

    int16_t ComponentValue::s16Value() const {
        return std::get<int16_t>(storage_->data);
    }

    uint16_t ComponentValue::u16Value() const {
        return std::get<uint16_t>(storage_->data);
    }

    int32_t ComponentValue::s32Value() const {
        return std::get<int32_t>(storage_->data);
    }

    uint32_t ComponentValue::u32Value() const {
        return std::get<uint32_t>(storage_->data);
    }

    int64_t ComponentValue::s64Value() const {
        return std::get<int64_t>(storage_->data);
    }

    uint64_t ComponentValue::u64Value() const {
        return std::get<uint64_t>(storage_->data);
    }

    float ComponentValue::f32Value() const {
        return std::get<float>(storage_->data);
    }

    double ComponentValue::f64Value() const {
        return std::get<double>(storage_->data);
    }

    uint32_t ComponentValue::characterValue() const {
        return std::get<ComponentCharacter>(storage_->data).value;
    }

    const std::string& ComponentValue::stringValue() const {
        return std::get<std::string>(storage_->data);
    }

    const ComponentList& ComponentValue::listValue() const {
        return std::get<ComponentList>(storage_->data);
    }

    const ComponentRecord& ComponentValue::recordValue() const {
        return std::get<ComponentRecord>(storage_->data);
    }

    const ComponentTuple& ComponentValue::tupleValue() const {
        return std::get<ComponentTupleData>(storage_->data).values;
    }

    const ComponentVariant& ComponentValue::variantValue() const {
        return std::get<ComponentVariant>(storage_->data);
    }

    const std::string& ComponentValue::enumValue() const {
        return std::get<ComponentEnumData>(storage_->data).name;
    }

    const std::shared_ptr<ComponentValue>& ComponentValue::optionValue() const {
        return std::get<std::shared_ptr<ComponentValue>>(storage_->data);
    }

    const ComponentResult& ComponentValue::resultValue() const {
        return std::get<ComponentResult>(storage_->data);
    }

    const ComponentFlags& ComponentValue::flagsValue() const {
        return std::get<ComponentFlags>(storage_->data);
    }

    const ComponentMap& ComponentValue::mapValue() const {
        return std::get<ComponentMap>(storage_->data);
    }
} // namespace wasmline
