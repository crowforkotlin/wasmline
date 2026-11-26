#pragma once

#include <cstdint>
#include <jni.h>
#include <limits>
#include <memory>
#include <string>
#include <vector>

/**
 * Converts standard UTF-8 to a Java string.
 *
 * Returns null for malformed UTF-8 so callers fail closed instead of passing
 * JNI modified UTF-8 to a Java API that does not tolerate it.
 */
inline jstring newJavaStringFromUtf8(JNIEnv* env, const char* data, size_t size) {
    if (!env || (!data && size != 0)) return nullptr;
    std::vector<jchar> units;
    units.reserve(size);
    size_t index = 0;
    while (index < size) {
        const auto first = static_cast<uint8_t>(data[index]);
        uint32_t scalar = 0;
        size_t length = 0;
        if (first < 0x80) {
            scalar = first;
            length = 1;
        } else if ((first & 0xE0) == 0xC0) {
            scalar = first & 0x1F;
            length = 2;
        } else if ((first & 0xF0) == 0xE0) {
            scalar = first & 0x0F;
            length = 3;
        } else if ((first & 0xF8) == 0xF0) {
            scalar = first & 0x07;
            length = 4;
        } else {
            return nullptr;
        }
        if (index + length > size) return nullptr;
        for (size_t offset = 1; offset < length; ++offset) {
            const auto next = static_cast<uint8_t>(data[index + offset]);
            if ((next & 0xC0) != 0x80) return nullptr;
            scalar = (scalar << 6) | (next & 0x3F);
        }
        if ((length == 2 && scalar < 0x80) || (length == 3 && scalar < 0x800) || (length == 4 && scalar < 0x10000)) {
            return nullptr;
        }
        if (scalar > 0x10FFFF || (scalar >= 0xD800 && scalar <= 0xDFFF)) return nullptr;
        if (scalar >= 0x10000) {
            scalar -= 0x10000;
            units.push_back(static_cast<jchar>(0xD800 + (scalar >> 10)));
            units.push_back(static_cast<jchar>(0xDC00 + (scalar & 0x3FF)));
        } else {
            units.push_back(static_cast<jchar>(scalar));
        }
        index += length;
    }
    if (units.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    if (units.empty()) return env->NewStringUTF("");
    return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

/** Converts standard UTF-8 to a Java string; returns null for malformed input. */
inline jstring newJavaStringFromUtf8(JNIEnv* env, const std::string& value) {
    return newJavaStringFromUtf8(env, value.data(), value.size());
}

/** Converts Java UTF-16 to standard UTF-8, not JNI Modified UTF-8. */
class JniUtf8String final {
public:
    JniUtf8String(JNIEnv* env, jstring value) {
        if (!env || !value) return;
        const jsize length = env->GetStringLength(value);
        const jchar* chars = env->GetStringChars(value, nullptr);
        if (!chars) return;
        const auto release = [env, value](const jchar* data) { env->ReleaseStringChars(value, data); };
        const std::unique_ptr<const jchar, decltype(release)> owner(chars, release);
        valid_ = true;
        for (jsize index = 0; index < length; ++index) {
            uint32_t scalar = chars[index];
            if (scalar >= 0xD800 && scalar <= 0xDBFF) {
                if (index + 1 == length || chars[index + 1] < 0xDC00 || chars[index + 1] > 0xDFFF) {
                    valid_ = false;
                    break;
                }
                scalar = 0x10000 + ((scalar - 0xD800) << 10) + (chars[++index] - 0xDC00);
            } else if (scalar >= 0xDC00 && scalar <= 0xDFFF) {
                valid_ = false;
                break;
            }
            if (scalar < 0x80) {
                bytes_ += static_cast<char>(scalar);
            } else if (scalar < 0x800) {
                bytes_ += static_cast<char>(0xC0 | (scalar >> 6));
                bytes_ += static_cast<char>(0x80 | (scalar & 0x3F));
            } else if (scalar < 0x10000) {
                bytes_ += static_cast<char>(0xE0 | (scalar >> 12));
                bytes_ += static_cast<char>(0x80 | ((scalar >> 6) & 0x3F));
                bytes_ += static_cast<char>(0x80 | (scalar & 0x3F));
            } else {
                bytes_ += static_cast<char>(0xF0 | (scalar >> 18));
                bytes_ += static_cast<char>(0x80 | ((scalar >> 12) & 0x3F));
                bytes_ += static_cast<char>(0x80 | ((scalar >> 6) & 0x3F));
                bytes_ += static_cast<char>(0x80 | (scalar & 0x3F));
            }
        }
    }

    const char* data() const noexcept { return valid_ ? bytes_.data() : nullptr; }
    const char* cString() const noexcept { return bytes_.find('\0') == std::string::npos ? data() : nullptr; }
    size_t size() const noexcept { return bytes_.size(); }

private:
    std::string bytes_;
    bool valid_ = false;
};
