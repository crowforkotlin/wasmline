#pragma once

#include <cstdint>
#include <jni.h>
#include <memory>
#include <string>

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
