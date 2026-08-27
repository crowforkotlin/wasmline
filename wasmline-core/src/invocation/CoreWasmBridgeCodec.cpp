/**
 * Implements private native bridge records for Core Wasm module sessions.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */

#include "wasmline/invocation/CoreWasmBridgeCodec.h"

#include <cstdint>
#include <limits>
#include <utility>

namespace wasmline {
    namespace {
        constexpr uint32_t kMaxCount = 1'000'000;
        constexpr uint32_t kMaxBytes = 16 * 1024 * 1024;

        /**
         * Reads bounded scalar and byte fields from a native bridge record.
         *
         * Date: 2026-08-25
         * Author: crowforkotlin
         */
        class Reader {
        public:
            explicit Reader(std::string_view input) : data_(reinterpret_cast<const uint8_t*>(input.data())), size_(input.size()) {}

            bool byte(uint8_t* value) {
                if (!value || position_ >= size_) return false;
                *value = data_[position_++];
                return true;
            }

            bool u32(uint32_t* value) {
                if (!value || size_ - position_ < 4) return false;
                *value = static_cast<uint32_t>(data_[position_]) | (static_cast<uint32_t>(data_[position_ + 1]) << 8) |
                         (static_cast<uint32_t>(data_[position_ + 2]) << 16) | (static_cast<uint32_t>(data_[position_ + 3]) << 24);
                position_ += 4;
                return true;
            }

            bool text(std::string* value) {
                uint32_t length = 0;
                if (!value || !u32(&length) || length > kMaxBytes || length > size_ - position_) return false;
                value->assign(reinterpret_cast<const char*>(data_ + position_), length);
                position_ += length;
                return true;
            }

            bool empty() const { return position_ == size_; }

        private:
            const uint8_t* data_;
            size_t size_;
            size_t position_ = 0;
        };

        /**
         * Writes bounded scalar and byte fields to a native bridge record.
         *
         * Date: 2026-08-25
         * Author: crowforkotlin
         */
        class Writer {
        public:
            void byte(uint8_t value) { data_.push_back(value); }

            void u32(uint32_t value) {
                for (int shift = 0; shift < 32; shift += 8)
                    data_.push_back(static_cast<uint8_t>((value >> shift) & 0xff));
            }

            void bytes(std::string_view value) {
                if (value.size() > kMaxBytes) {
                    valid_ = false;
                    return;
                }
                u32(static_cast<uint32_t>(value.size()));
                data_.insert(data_.end(), value.begin(), value.end());
            }

            void bytes(const std::vector<uint8_t>& value) {
                if (value.size() > kMaxBytes) {
                    valid_ = false;
                    return;
                }
                u32(static_cast<uint32_t>(value.size()));
                data_.insert(data_.end(), value.begin(), value.end());
            }

            void count(size_t value) {
                if (value > kMaxCount) {
                    valid_ = false;
                    return;
                }
                u32(static_cast<uint32_t>(value));
            }

            std::vector<uint8_t> finish() { return valid_ ? std::move(data_) : std::vector<uint8_t>(); }

        private:
            std::vector<uint8_t> data_;
            bool valid_ = true;
        };

        bool readType(Reader& reader, RawValue::Type* type) {
            uint8_t tag = 0;
            if (!reader.byte(&tag) || tag > static_cast<uint8_t>(RawValue::Type::F64) || !type) return false;
            *type = static_cast<RawValue::Type>(tag);
            return true;
        }

        bool readSignature(Reader& reader, RawFunctionSignature* signature) {
            if (!signature) return false;
            uint32_t count = 0;
            if (!reader.u32(&count) || count > kMaxCount) return false;
            signature->parameters.reserve(count);
            for (uint32_t index = 0; index < count; ++index) {
                RawValue::Type type;
                if (!readType(reader, &type)) return false;
                signature->parameters.push_back(type);
            }
            if (!reader.u32(&count) || count > kMaxCount) return false;
            signature->results.reserve(count);
            for (uint32_t index = 0; index < count; ++index) {
                RawValue::Type type;
                if (!readType(reader, &type)) return false;
                signature->results.push_back(type);
            }
            return true;
        }

        void writeSignature(Writer& writer, const RawFunctionSignature& signature) {
            writer.count(signature.parameters.size());
            for (const auto type : signature.parameters)
                writer.byte(static_cast<uint8_t>(type));
            writer.count(signature.results.size());
            for (const auto type : signature.results)
                writer.byte(static_cast<uint8_t>(type));
        }
    } // namespace

    bool CoreWasmBridgeCodec::decodeImports(std::string_view input, std::vector<RawImportDefinition>* imports, std::string* error) {
        if (!imports) return false;
        Reader reader(input);
        uint32_t count = 0;
        if (!reader.u32(&count) || count > kMaxCount) {
            if (error) *error = "Raw import collection is invalid.";
            return false;
        }
        imports->clear();
        imports->reserve(count);
        for (uint32_t index = 0; index < count; ++index) {
            RawImportDefinition definition;
            if (!reader.text(&definition.module) || !reader.text(&definition.name) || definition.module.empty() ||
                definition.name.empty() || !readSignature(reader, &definition.signature)) {
                if (error) *error = "Raw import declaration is invalid or truncated.";
                return false;
            }
            imports->push_back(std::move(definition));
        }
        if (!reader.empty()) {
            if (error) *error = "Raw import metadata has trailing bytes.";
            return false;
        }
        return true;
    }

    std::vector<uint8_t> CoreWasmBridgeCodec::encodeExports(const std::vector<RawExportDefinition>& exports) {
        Writer writer;
        writer.count(exports.size());
        for (const auto& definition : exports) {
            writer.bytes(definition.name);
            writer.byte(definition.kind);
            writer.byte(definition.hasSignature ? 1 : 0);
            if (definition.hasSignature) writeSignature(writer, definition.signature);
        }
        return writer.finish();
    }
} // namespace wasmline
