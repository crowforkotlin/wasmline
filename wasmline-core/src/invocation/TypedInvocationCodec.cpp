/**
 * Implements typed invocation values for native platform bridges.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#include "wasmline/invocation/TypedInvocationCodec.h"

#include <cstring>
#include <memory>
#include <utility>

namespace wasmline {
    namespace {
        constexpr uint32_t kMaxCollectionSize = 1'000'000;
        constexpr uint32_t kMaxStringSize = 16 * 1024 * 1024;
        constexpr uint32_t kMaxDepth = 64;

        /** Reads bounded little-endian values from an invocation payload. */
        class Reader {
        public:
            explicit Reader(std::string_view input) : data_(reinterpret_cast<const uint8_t*>(input.data())), size_(input.size()) {}

            bool readByte(uint8_t* value) {
                if (!value || position_ >= size_) return false;
                *value = data_[position_++];
                return true;
            }

            bool readU32(uint32_t* value) {
                if (!value || size_ - position_ < sizeof(uint32_t)) return false;
                *value = static_cast<uint32_t>(data_[position_]) | (static_cast<uint32_t>(data_[position_ + 1]) << 8) |
                         (static_cast<uint32_t>(data_[position_ + 2]) << 16) | (static_cast<uint32_t>(data_[position_ + 3]) << 24);
                position_ += sizeof(uint32_t);
                return true;
            }

            bool readU64(uint64_t* value) {
                if (!value || size_ - position_ < sizeof(uint64_t)) return false;
                *value = static_cast<uint64_t>(data_[position_]) | (static_cast<uint64_t>(data_[position_ + 1]) << 8) |
                         (static_cast<uint64_t>(data_[position_ + 2]) << 16) | (static_cast<uint64_t>(data_[position_ + 3]) << 24) |
                         (static_cast<uint64_t>(data_[position_ + 4]) << 32) | (static_cast<uint64_t>(data_[position_ + 5]) << 40) |
                         (static_cast<uint64_t>(data_[position_ + 6]) << 48) | (static_cast<uint64_t>(data_[position_ + 7]) << 56);
                position_ += sizeof(uint64_t);
                return true;
            }

            bool readBytes(size_t count, std::string* value) {
                if (!value || count > size_ - position_) return false;
                value->assign(reinterpret_cast<const char*>(data_ + position_), count);
                position_ += count;
                return true;
            }

            bool empty() const { return position_ == size_; }

        private:
            const uint8_t* data_;
            size_t size_;
            size_t position_ = 0;
        };

        /** Writes the bounded native invocation encoding. */
        class Writer {
        public:
            void byte(uint8_t value) {
                if (!valid_) return;
                data_.push_back(value);
            }

            void u32(uint32_t value) {
                if (!valid_) return;
                data_.push_back(static_cast<uint8_t>(value & 0xFFu));
                data_.push_back(static_cast<uint8_t>((value >> 8) & 0xFFu));
                data_.push_back(static_cast<uint8_t>((value >> 16) & 0xFFu));
                data_.push_back(static_cast<uint8_t>((value >> 24) & 0xFFu));
            }

            void u64(uint64_t value) {
                if (!valid_) return;
                for (size_t shift = 0; shift < 64; shift += 8) {
                    data_.push_back(static_cast<uint8_t>((value >> shift) & 0xFFu));
                }
            }

            void bytes(std::string_view value) {
                if (!valid_) return;
                if (value.size() > kMaxStringSize) {
                    valid_ = false;
                    return;
                }
                u32(static_cast<uint32_t>(value.size()));
                data_.insert(data_.end(), value.begin(), value.end());
            }

            void rawBytes(const std::vector<uint8_t>& value) {
                if (!valid_) return;
                if (value.size() > kMaxStringSize) {
                    valid_ = false;
                    return;
                }
                u32(static_cast<uint32_t>(value.size()));
                data_.insert(data_.end(), value.begin(), value.end());
            }

            void count(size_t value) {
                if (!valid_) return;
                if (value > kMaxCollectionSize) {
                    valid_ = false;
                    return;
                }
                u32(static_cast<uint32_t>(value));
            }

            bool valid() const { return valid_; }

            std::vector<uint8_t> take() { return std::move(data_); }

        private:
            std::vector<uint8_t> data_;
            bool valid_ = true;
        };

        /** Identifies a serialized invocation value. */
        enum class ValueTag : uint8_t {
            BOOL = 0,
            S8 = 1,
            U8 = 2,
            S16 = 3,
            U16 = 4,
            S32 = 5,
            U32 = 6,
            S64 = 7,
            U64 = 8,
            F32 = 9,
            F64 = 10,
            CHAR = 11,
            STRING = 12,
            LIST = 13,
            RECORD = 14,
            TUPLE = 15,
            VARIANT = 16,
            ENUM = 17,
            OPTION = 18,
            RESULT = 19,
            FLAGS = 20,
            MAP = 21,
            RESOURCE = 22,
        };

        /** Reads a length field and applies the protocol limit. */
        bool readLength(Reader& reader, uint32_t limit, uint32_t* length, std::string* error) {
            if (!reader.readU32(length) || *length > limit) {
                if (error) *error = "Typed invocation value length is invalid.";
                return false;
            }
            return true;
        }

        /** Reads a length-prefixed string. */
        bool readString(Reader& reader, std::string* value, std::string* error) {
            uint32_t length = 0;
            if (!readLength(reader, kMaxStringSize, &length, error) || !reader.readBytes(length, value)) {
                if (error) *error = "Typed invocation string is truncated.";
                return false;
            }
            return true;
        }

        bool decodeComponentValue(Reader& reader, ComponentValue* value, std::string* error, uint32_t depth);

        /** Reads a collection size and enforces the nesting limit. */
        bool decodeCollectionCount(Reader& reader, uint32_t* count, std::string* error, uint32_t depth) {
            if (depth > kMaxDepth || !readLength(reader, kMaxCollectionSize, count, error)) {
                if (error && depth > kMaxDepth) *error = "Typed invocation value nesting is too deep.";
                return false;
            }
            return true;
        }

        /** Decodes one recursive Component Model value. */
        bool decodeComponentValue(Reader& reader, ComponentValue* value, std::string* error, uint32_t depth) {
            if (!value || depth > kMaxDepth) {
                if (error) *error = "Typed invocation value nesting is too deep.";
                return false;
            }
            uint8_t tag = 0;
            if (!reader.readByte(&tag)) {
                if (error) *error = "Typed invocation value tag is missing.";
                return false;
            }
            uint32_t u32 = 0;
            uint64_t u64 = 0;
            switch (static_cast<ValueTag>(tag)) {
            case ValueTag::BOOL:
                if (!reader.readByte(&tag) || (tag != 0 && tag != 1)) return false;
                *value = ComponentValue::boolean(tag == 1);
                return true;
            case ValueTag::S8:
                if (!reader.readByte(&tag)) return false;
                *value = ComponentValue::s8(static_cast<int8_t>(tag));
                return true;
            case ValueTag::U8:
                if (!reader.readByte(&tag)) return false;
                *value = ComponentValue::u8(tag);
                return true;
            case ValueTag::S16:
            case ValueTag::U16:
            case ValueTag::S32:
            case ValueTag::U32:
                if (!reader.readU32(&u32)) return false;
                if (static_cast<ValueTag>(tag) == ValueTag::S16)
                    *value = ComponentValue::s16(static_cast<int16_t>(u32));
                else if (static_cast<ValueTag>(tag) == ValueTag::U16)
                    *value = ComponentValue::u16(static_cast<uint16_t>(u32));
                else if (static_cast<ValueTag>(tag) == ValueTag::S32)
                    *value = ComponentValue::s32(static_cast<int32_t>(u32));
                else
                    *value = ComponentValue::u32(u32);
                return true;
            case ValueTag::S64:
                if (!reader.readU64(&u64)) return false;
                *value = ComponentValue::s64(static_cast<int64_t>(u64));
                return true;
            case ValueTag::U64:
                if (!reader.readU64(&u64)) return false;
                *value = ComponentValue::u64(u64);
                return true;
            case ValueTag::F32: {
                if (!reader.readU32(&u32)) return false;
                float floatValue = 0;
                std::memcpy(&floatValue, &u32, sizeof(floatValue));
                *value = ComponentValue::f32(floatValue);
                return true;
            }
            case ValueTag::F64: {
                if (!reader.readU64(&u64)) return false;
                double doubleValue = 0;
                std::memcpy(&doubleValue, &u64, sizeof(doubleValue));
                *value = ComponentValue::f64(doubleValue);
                return true;
            }
            case ValueTag::CHAR:
                if (!reader.readU32(&u32)) return false;
                *value = ComponentValue::character(u32);
                return true;
            case ValueTag::STRING: {
                std::string stringValue;
                if (!readString(reader, &stringValue, error)) return false;
                *value = ComponentValue::string(std::move(stringValue));
                return true;
            }
            case ValueTag::LIST:
            case ValueTag::TUPLE: {
                uint32_t count = 0;
                if (!decodeCollectionCount(reader, &count, error, depth)) return false;
                ComponentList values;
                values.reserve(count);
                for (uint32_t index = 0; index < count; ++index) {
                    ComponentValue item;
                    if (!decodeComponentValue(reader, &item, error, depth + 1)) return false;
                    values.push_back(std::move(item));
                }
                *value = static_cast<ValueTag>(tag) == ValueTag::LIST ? ComponentValue::list(std::move(values))
                                                                      : ComponentValue::tuple(std::move(values));
                return true;
            }
            case ValueTag::RECORD: {
                uint32_t count = 0;
                if (!decodeCollectionCount(reader, &count, error, depth)) return false;
                ComponentRecord fields;
                fields.reserve(count);
                for (uint32_t index = 0; index < count; ++index) {
                    std::string name;
                    ComponentValue fieldValue;
                    if (!readString(reader, &name, error) || !decodeComponentValue(reader, &fieldValue, error, depth + 1)) {
                        return false;
                    }
                    fields.push_back(ComponentRecordField{std::move(name), std::move(fieldValue)});
                }
                *value = ComponentValue::record(std::move(fields));
                return true;
            }
            case ValueTag::VARIANT: {
                std::string name;
                if (!readString(reader, &name, error) || !reader.readByte(&tag)) return false;
                std::shared_ptr<ComponentValue> payload;
                if (tag == 1) {
                    payload = std::make_shared<ComponentValue>();
                    if (!decodeComponentValue(reader, payload.get(), error, depth + 1)) return false;
                } else if (tag != 0) {
                    return false;
                }
                *value = ComponentValue::variant(std::move(name), std::move(payload));
                return true;
            }
            case ValueTag::ENUM: {
                std::string name;
                if (!readString(reader, &name, error)) return false;
                *value = ComponentValue::enumeration(std::move(name));
                return true;
            }
            case ValueTag::OPTION: {
                if (!reader.readByte(&tag)) return false;
                std::shared_ptr<ComponentValue> inner;
                if (tag == 1) {
                    inner = std::make_shared<ComponentValue>();
                    if (!decodeComponentValue(reader, inner.get(), error, depth + 1)) return false;
                } else if (tag != 0) {
                    return false;
                }
                *value = ComponentValue::option(std::move(inner));
                return true;
            }
            case ValueTag::RESULT: {
                if (!reader.readByte(&tag)) return false;
                const bool isOk = tag == 1;
                if (!isOk && tag != 0) return false;
                if (!reader.readByte(&tag)) return false;
                std::shared_ptr<ComponentValue> inner;
                if (tag == 1) {
                    inner = std::make_shared<ComponentValue>();
                    if (!decodeComponentValue(reader, inner.get(), error, depth + 1)) return false;
                } else if (tag != 0) {
                    return false;
                }
                *value = ComponentValue::result(isOk, std::move(inner));
                return true;
            }
            case ValueTag::FLAGS: {
                uint32_t count = 0;
                if (!decodeCollectionCount(reader, &count, error, depth)) return false;
                ComponentFlags names;
                names.reserve(count);
                for (uint32_t index = 0; index < count; ++index) {
                    std::string name;
                    if (!readString(reader, &name, error)) return false;
                    names.push_back(std::move(name));
                }
                *value = ComponentValue::flags(std::move(names));
                return true;
            }
            case ValueTag::MAP: {
                uint32_t count = 0;
                if (!decodeCollectionCount(reader, &count, error, depth)) return false;
                ComponentMap entries;
                entries.reserve(count);
                for (uint32_t index = 0; index < count; ++index) {
                    ComponentValue key;
                    ComponentValue item;
                    if (!decodeComponentValue(reader, &key, error, depth + 1) || !decodeComponentValue(reader, &item, error, depth + 1)) {
                        return false;
                    }
                    entries.emplace_back(std::move(key), std::move(item));
                }
                *value = ComponentValue::map(std::move(entries));
                return true;
            }
            case ValueTag::RESOURCE: {
                std::string instanceKey;
                uint32_t typeId = 0;
                uint64_t handleId = 0;
                uint32_t generation = 0;
                uint8_t ownership = 0;
                uint8_t origin = 0;
                if (!readString(reader, &instanceKey, error) || !reader.readU32(&typeId) || !reader.readU64(&handleId) ||
                    !reader.readU32(&generation) || !reader.readByte(&ownership) || !reader.readByte(&origin) ||
                    (ownership != 0 && ownership != 1) || (origin != 0 && origin != 1) || instanceKey.empty() || typeId == 0 ||
                    handleId == 0 || generation == 0) {
                    if (error && error->empty()) *error = "Component resource reference is invalid.";
                    return false;
                }
                *value = ComponentValue::resource(ComponentResourceReference{
                    std::move(instanceKey),
                    typeId,
                    handleId,
                    generation,
                    ownership == 0 ? ComponentResourceOwnership::OWN : ComponentResourceOwnership::BORROW,
                    origin == 0 ? ComponentResourceOrigin::GUEST : ComponentResourceOrigin::HOST,
                });
                return true;
            }
            default:
                if (error) *error = "Typed invocation value tag is unknown.";
                return false;
            }
        }

        bool decodeHeader(Reader& reader, uint32_t* count, std::string* error) {
            return decodeCollectionCount(reader, count, error, 0);
        }

        bool readResultDetails(Reader& reader, std::vector<uint8_t>* details, std::string* error) {
            if (!details) return false;
            uint32_t length = 0;
            std::string bytes;
            if (!readLength(reader, kMaxStringSize, &length, error) || !reader.readBytes(length, &bytes)) {
                if (error) *error = "Typed invocation response details are truncated.";
                return false;
            }
            details->assign(bytes.begin(), bytes.end());
            return true;
        }

        void writeComponentValue(Writer& writer, const ComponentValue& value);

        void writeString(Writer& writer, const std::string& value) {
            writer.bytes(value);
        }

        /** Encodes one recursive Component Model value. */
        void writeComponentValue(Writer& writer, const ComponentValue& value) {
            using Kind = ComponentValue::Kind;
            switch (value.kind()) {
            case Kind::BOOL:
                writer.byte(static_cast<uint8_t>(ValueTag::BOOL));
                writer.byte(value.booleanValue() ? 1 : 0);
                break;
            case Kind::S8:
                writer.byte(static_cast<uint8_t>(ValueTag::S8));
                writer.byte(static_cast<uint8_t>(value.s8Value()));
                break;
            case Kind::U8:
                writer.byte(static_cast<uint8_t>(ValueTag::U8));
                writer.byte(value.u8Value());
                break;
            case Kind::S16:
                writer.byte(static_cast<uint8_t>(ValueTag::S16));
                writer.u32(static_cast<uint32_t>(static_cast<int32_t>(value.s16Value())));
                break;
            case Kind::U16:
                writer.byte(static_cast<uint8_t>(ValueTag::U16));
                writer.u32(value.u16Value());
                break;
            case Kind::S32:
                writer.byte(static_cast<uint8_t>(ValueTag::S32));
                writer.u32(static_cast<uint32_t>(value.s32Value()));
                break;
            case Kind::U32:
                writer.byte(static_cast<uint8_t>(ValueTag::U32));
                writer.u32(value.u32Value());
                break;
            case Kind::S64:
                writer.byte(static_cast<uint8_t>(ValueTag::S64));
                writer.u64(static_cast<uint64_t>(value.s64Value()));
                break;
            case Kind::U64:
                writer.byte(static_cast<uint8_t>(ValueTag::U64));
                writer.u64(value.u64Value());
                break;
            case Kind::F32: {
                writer.byte(static_cast<uint8_t>(ValueTag::F32));
                uint32_t bits = 0;
                const float floatValue = value.f32Value();
                std::memcpy(&bits, &floatValue, sizeof(bits));
                writer.u32(bits);
                break;
            }
            case Kind::F64: {
                writer.byte(static_cast<uint8_t>(ValueTag::F64));
                uint64_t bits = 0;
                const double doubleValue = value.f64Value();
                std::memcpy(&bits, &doubleValue, sizeof(bits));
                writer.u64(bits);
                break;
            }
            case Kind::CHAR:
                writer.byte(static_cast<uint8_t>(ValueTag::CHAR));
                writer.u32(value.characterValue());
                break;
            case Kind::STRING:
                writer.byte(static_cast<uint8_t>(ValueTag::STRING));
                writeString(writer, value.stringValue());
                break;
            case Kind::LIST:
            case Kind::TUPLE: {
                writer.byte(static_cast<uint8_t>(value.kind() == Kind::LIST ? ValueTag::LIST : ValueTag::TUPLE));
                const auto& values = value.kind() == Kind::LIST ? value.listValue() : value.tupleValue();
                writer.count(values.size());
                for (const auto& item : values)
                    writeComponentValue(writer, item);
                break;
            }
            case Kind::RECORD:
                writer.byte(static_cast<uint8_t>(ValueTag::RECORD));
                writer.count(value.recordValue().size());
                for (const auto& field : value.recordValue()) {
                    writeString(writer, field.name);
                    writeComponentValue(writer, field.value);
                }
                break;
            case Kind::VARIANT:
                writer.byte(static_cast<uint8_t>(ValueTag::VARIANT));
                writeString(writer, value.variantValue().discriminant);
                writer.byte(value.variantValue().value ? 1 : 0);
                if (value.variantValue().value) writeComponentValue(writer, *value.variantValue().value);
                break;
            case Kind::ENUM:
                writer.byte(static_cast<uint8_t>(ValueTag::ENUM));
                writeString(writer, value.enumValue());
                break;
            case Kind::OPTION:
                writer.byte(static_cast<uint8_t>(ValueTag::OPTION));
                writer.byte(value.optionValue() ? 1 : 0);
                if (value.optionValue()) writeComponentValue(writer, *value.optionValue());
                break;
            case Kind::RESULT:
                writer.byte(static_cast<uint8_t>(ValueTag::RESULT));
                writer.byte(value.resultValue().isOk ? 1 : 0);
                writer.byte(value.resultValue().value ? 1 : 0);
                if (value.resultValue().value) writeComponentValue(writer, *value.resultValue().value);
                break;
            case Kind::FLAGS:
                writer.byte(static_cast<uint8_t>(ValueTag::FLAGS));
                writer.count(value.flagsValue().size());
                for (const auto& name : value.flagsValue())
                    writeString(writer, name);
                break;
            case Kind::MAP:
                writer.byte(static_cast<uint8_t>(ValueTag::MAP));
                writer.count(value.mapValue().size());
                for (const auto& entry : value.mapValue()) {
                    writeComponentValue(writer, entry.first);
                    writeComponentValue(writer, entry.second);
                }
                break;
            case Kind::RESOURCE:
                writer.byte(static_cast<uint8_t>(ValueTag::RESOURCE));
                writeString(writer, value.resourceValue().instanceKey);
                writer.u32(value.resourceValue().typeId);
                writer.u64(value.resourceValue().handleId);
                writer.u32(value.resourceValue().generation);
                writer.byte(value.resourceValue().ownership == ComponentResourceOwnership::OWN ? 0 : 1);
                writer.byte(value.resourceValue().origin == ComponentResourceOrigin::GUEST ? 0 : 1);
                break;
            }
        }

        /** Encodes one Core Wasm scalar value. */
        void writeRawValue(Writer& writer, const RawValue& value) {
            switch (value.type) {
            case RawValue::Type::I32:
                writer.byte(0);
                writer.u32(static_cast<uint32_t>(value.data.i32));
                break;
            case RawValue::Type::I64:
                writer.byte(1);
                writer.u64(static_cast<uint64_t>(value.data.i64));
                break;
            case RawValue::Type::F32: {
                writer.byte(2);
                uint32_t bits = 0;
                std::memcpy(&bits, &value.data.f32, sizeof(bits));
                writer.u32(bits);
                break;
            }
            case RawValue::Type::F64: {
                writer.byte(3);
                uint64_t bits = 0;
                std::memcpy(&bits, &value.data.f64, sizeof(bits));
                writer.u64(bits);
                break;
            }
            }
        }

        /** Encodes the common result fields. */
        void writeInvocationResultHeader(Writer& writer, const InvocationResult& result, TypedInvocationKind kind) {
            writer.byte(result.isSuccess() ? 0 : 1);
            writer.byte(static_cast<uint8_t>(kind));
            writer.u32(result.isSuccess() ? 0u : static_cast<uint32_t>(result.errorCode()));
            writer.bytes(result.message());
            writer.rawBytes(result.details());
        }
    } // namespace

    std::vector<uint8_t> TypedInvocationCodec::encodeRawArguments(const std::vector<RawValue>& values) {
        Writer writer;
        writer.count(values.size());
        for (const auto& value : values)
            writeRawValue(writer, value);
        if (!writer.valid()) return {};
        return writer.take();
    }

    bool TypedInvocationCodec::decodeRawArguments(std::string_view input, std::vector<RawValue>* values, std::string* error) {
        if (!values) return false;
        Reader reader(input);
        uint32_t count = 0;
        if (!decodeHeader(reader, &count, error)) return false;
        values->clear();
        values->reserve(count);
        for (uint32_t index = 0; index < count; ++index) {
            uint8_t tag = 0;
            uint32_t u32 = 0;
            uint64_t u64 = 0;
            if (!reader.readByte(&tag)) return false;
            switch (tag) {
            case 0:
                if (!reader.readU32(&u32)) return false;
                values->push_back(RawValue::fromI32(static_cast<int32_t>(u32)));
                break;
            case 1:
                if (!reader.readU64(&u64)) return false;
                values->push_back(RawValue::fromI64(static_cast<int64_t>(u64)));
                break;
            case 2: {
                if (!reader.readU32(&u32)) return false;
                float value = 0;
                std::memcpy(&value, &u32, sizeof(value));
                values->push_back(RawValue::fromF32(value));
                break;
            }
            case 3: {
                if (!reader.readU64(&u64)) return false;
                double value = 0;
                std::memcpy(&value, &u64, sizeof(value));
                values->push_back(RawValue::fromF64(value));
                break;
            }
            default:
                if (error) *error = "Raw invocation value tag is unknown.";
                return false;
            }
        }
        if (!reader.empty()) {
            if (error) *error = "Raw invocation payload has trailing bytes.";
            return false;
        }
        return true;
    }

    bool TypedInvocationCodec::decodeComponentArguments(std::string_view input, std::vector<ComponentValue>* values, std::string* error) {
        if (!values) return false;
        Reader reader(input);
        uint32_t count = 0;
        if (!decodeHeader(reader, &count, error)) return false;
        values->clear();
        values->reserve(count);
        for (uint32_t index = 0; index < count; ++index) {
            ComponentValue value;
            if (!decodeComponentValue(reader, &value, error, 0)) return false;
            values->push_back(std::move(value));
        }
        if (!reader.empty()) {
            if (error) *error = "Component invocation payload has trailing bytes.";
            return false;
        }
        return true;
    }

    std::vector<uint8_t> TypedInvocationCodec::encodeComponentArguments(const std::vector<ComponentValue>& values) {
        Writer writer;
        writer.count(values.size());
        for (const auto& value : values)
            writeComponentValue(writer, value);
        if (!writer.valid()) return {};
        return writer.take();
    }

    bool TypedInvocationCodec::decodeRawResult(std::string_view input, InvocationResult* result, std::string* error) {
        if (!result) return false;

        Reader reader(input);
        uint8_t status = 0;
        uint8_t kind = 0;
        uint32_t rawCode = 0;
        std::string message;
        std::vector<uint8_t> details;
        uint32_t valueCount = 0;
        if (!reader.readByte(&status) || !reader.readByte(&kind) || !reader.readU32(&rawCode) || !readString(reader, &message, error) ||
            !readResultDetails(reader, &details, error) || !decodeHeader(reader, &valueCount, error)) {
            if (error && error->empty()) *error = "Typed raw invocation response is truncated.";
            return false;
        }
        if (kind != static_cast<uint8_t>(TypedInvocationKind::RAW) || (status != 0 && status != 1)) {
            if (error) *error = "Typed raw invocation response header is invalid.";
            return false;
        }
        if (status == 0 && (rawCode != 0 || !message.empty())) {
            if (error) *error = "Successful typed raw invocation response contains error fields.";
            return false;
        }
        if (status == 1) {
            if (rawCode == 0 || valueCount != 0 || !reader.empty()) {
                if (error) *error = "Failed typed raw invocation response contains values or trailing bytes.";
                return false;
            }
            *result = InvocationResult::failure(static_cast<WasmlineErrorCode>(rawCode), std::move(message), std::move(details));
            return true;
        }

        std::vector<RawValue> values;
        values.reserve(valueCount);
        for (uint32_t index = 0; index < valueCount; ++index) {
            uint8_t tag = 0;
            uint32_t u32 = 0;
            uint64_t u64 = 0;
            if (!reader.readByte(&tag)) return false;
            switch (tag) {
            case 0:
                if (!reader.readU32(&u32)) return false;
                values.push_back(RawValue::fromI32(static_cast<int32_t>(u32)));
                break;
            case 1:
                if (!reader.readU64(&u64)) return false;
                values.push_back(RawValue::fromI64(static_cast<int64_t>(u64)));
                break;
            case 2: {
                if (!reader.readU32(&u32)) return false;
                float value = 0;
                std::memcpy(&value, &u32, sizeof(value));
                values.push_back(RawValue::fromF32(value));
                break;
            }
            case 3: {
                if (!reader.readU64(&u64)) return false;
                double value = 0;
                std::memcpy(&value, &u64, sizeof(value));
                values.push_back(RawValue::fromF64(value));
                break;
            }
            default:
                if (error) *error = "Typed raw invocation result value tag is unknown.";
                return false;
            }
        }
        if (!reader.empty()) {
            if (error) *error = "Typed raw invocation response has trailing bytes.";
            return false;
        }
        *result = InvocationResult::success(std::move(values));
        return true;
    }

    bool TypedInvocationCodec::decodeComponentResult(std::string_view input, InvocationResult* result, std::string* error) {
        if (!result) return false;

        Reader reader(input);
        uint8_t status = 0;
        uint8_t kind = 0;
        uint32_t rawCode = 0;
        std::string message;
        std::vector<uint8_t> details;
        uint32_t valueCount = 0;
        if (!reader.readByte(&status) || !reader.readByte(&kind) || !reader.readU32(&rawCode) || !readString(reader, &message, error) ||
            !readResultDetails(reader, &details, error) || !decodeHeader(reader, &valueCount, error)) {
            if (error && error->empty()) *error = "Typed Component invocation response is truncated.";
            return false;
        }
        if (kind != static_cast<uint8_t>(TypedInvocationKind::COMPONENT)) {
            if (error) *error = "Typed Component invocation response value kind is invalid.";
            return false;
        }
        if (status != 0 && status != 1) {
            if (error) *error = "Typed Component invocation response status is invalid.";
            return false;
        }
        if (status == 0 && (rawCode != 0 || !message.empty())) {
            if (error) *error = "Successful typed Component invocation response contains error fields.";
            return false;
        }
        if (status == 1) {
            if (rawCode == 0 || valueCount != 0 || !reader.empty()) {
                if (error) *error = "Failed typed Component invocation response contains values or trailing bytes.";
                return false;
            }
            *result = InvocationResult::failure(static_cast<WasmlineErrorCode>(rawCode), std::move(message), std::move(details));
            return true;
        }

        std::vector<ComponentValue> values;
        values.reserve(valueCount);
        for (uint32_t index = 0; index < valueCount; ++index) {
            ComponentValue value;
            if (!decodeComponentValue(reader, &value, error, 0)) return false;
            values.push_back(std::move(value));
        }
        if (!reader.empty()) {
            if (error) *error = "Typed Component invocation response has trailing bytes.";
            return false;
        }
        *result = InvocationResult::successComponent(std::move(values));
        return true;
    }

    std::vector<uint8_t> TypedInvocationCodec::encodeResult(const InvocationResult& result, TypedInvocationKind kind) {
        Writer writer;
        writeInvocationResultHeader(writer, result, kind);
        if (kind == TypedInvocationKind::RAW) {
            writer.count(result.isSuccess() ? result.values().size() : 0);
            if (result.isSuccess()) {
                for (const auto& value : result.values())
                    writeRawValue(writer, value);
            }
        } else {
            writer.count(result.isSuccess() ? result.componentValues().size() : 0);
            if (result.isSuccess()) {
                for (const auto& value : result.componentValues())
                    writeComponentValue(writer, value);
            }
        }
        if (!writer.valid()) return {};
        return writer.take();
    }
} // namespace wasmline
