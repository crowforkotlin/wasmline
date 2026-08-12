/**
 * Defines owned values for Component Model calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */

#pragma once

#include <cstdint>
#include <string>
#include <memory>
#include <variant>
#include <utility>
#include <vector>

namespace wasmline {
    class ComponentValue;
    struct ComponentRecordField;
    struct ComponentVariant;
    struct ComponentResult;
    struct ComponentCharacter;
    struct ComponentTupleData;
    struct ComponentEnumData;
    struct ComponentResourceReference;

    using ComponentList = std::vector<ComponentValue>;
    using ComponentTuple = std::vector<ComponentValue>;
    using ComponentRecord = std::vector<ComponentRecordField>;
    using ComponentFlags = std::vector<std::string>;
    using ComponentMap = std::vector<std::pair<ComponentValue, ComponentValue>>;

    /** Stores a value from the Component Model type system. */
    class ComponentValue {
    public:
        /** Identifies the stored Component Model type. */
        enum class Kind : uint8_t {
            BOOL,
            S8,
            U8,
            S16,
            U16,
            S32,
            U32,
            S64,
            U64,
            F32,
            F64,
            CHAR,
            STRING,
            LIST,
            RECORD,
            TUPLE,
            VARIANT,
            ENUM,
            OPTION,
            RESULT,
            FLAGS,
            MAP,
            RESOURCE,
        };

        /** Creates an empty value. */
        ComponentValue();

        /** Creates a boolean value. */
        static ComponentValue boolean(bool value);
        /** Creates a signed 8-bit value. */
        static ComponentValue s8(int8_t value);
        /** Creates an unsigned 8-bit value. */
        static ComponentValue u8(uint8_t value);
        /** Creates a signed 16-bit value. */
        static ComponentValue s16(int16_t value);
        /** Creates an unsigned 16-bit value. */
        static ComponentValue u16(uint16_t value);
        /** Creates a signed 32-bit value. */
        static ComponentValue s32(int32_t value);
        /** Creates an unsigned 32-bit value. */
        static ComponentValue u32(uint32_t value);
        /** Creates a signed 64-bit value. */
        static ComponentValue s64(int64_t value);
        /** Creates an unsigned 64-bit value. */
        static ComponentValue u64(uint64_t value);
        /** Creates an f32 value. */
        static ComponentValue f32(float value);
        /** Creates an f64 value. */
        static ComponentValue f64(double value);
        /** Creates a character value. */
        static ComponentValue character(uint32_t value);
        /** Creates a string value. */
        static ComponentValue string(std::string value);
        /** Creates a list value. */
        static ComponentValue list(ComponentList value);
        /** Creates a record value. */
        static ComponentValue record(ComponentRecord value);
        /** Creates a tuple value. */
        static ComponentValue tuple(ComponentTuple value);
        /** Creates a variant value. */
        static ComponentValue variant(std::string discriminant, std::shared_ptr<ComponentValue> value = nullptr);
        /** Creates an enum value. */
        static ComponentValue enumeration(std::string name);
        /** Creates an option value. */
        static ComponentValue option(std::shared_ptr<ComponentValue> value = nullptr);
        /** Creates a result value. */
        static ComponentValue result(bool isOk, std::shared_ptr<ComponentValue> value = nullptr);
        /** Creates a flags value. */
        static ComponentValue flags(ComponentFlags value);
        /** Creates a map value. */
        static ComponentValue map(ComponentMap value);
        /** Creates a session-scoped resource reference. */
        static ComponentValue resource(ComponentResourceReference value);

        /** Returns the stored value kind. */
        Kind kind() const;

        /** Returns the stored boolean. */
        bool booleanValue() const;
        /** Returns the stored signed 8-bit value. */
        int8_t s8Value() const;
        /** Returns the stored unsigned 8-bit value. */
        uint8_t u8Value() const;
        /** Returns the stored signed 16-bit value. */
        int16_t s16Value() const;
        /** Returns the stored unsigned 16-bit value. */
        uint16_t u16Value() const;
        /** Returns the stored signed 32-bit value. */
        int32_t s32Value() const;
        /** Returns the stored unsigned 32-bit value. */
        uint32_t u32Value() const;
        /** Returns the stored signed 64-bit value. */
        int64_t s64Value() const;
        /** Returns the stored unsigned 64-bit value. */
        uint64_t u64Value() const;
        /** Returns the stored f32 value. */
        float f32Value() const;
        /** Returns the stored f64 value. */
        double f64Value() const;
        /** Returns the stored character. */
        uint32_t characterValue() const;
        /** Returns the stored string. */
        const std::string& stringValue() const;
        /** Returns the stored list. */
        const ComponentList& listValue() const;
        /** Returns the stored record. */
        const ComponentRecord& recordValue() const;
        /** Returns the stored tuple. */
        const ComponentTuple& tupleValue() const;
        /** Returns the stored variant. */
        const ComponentVariant& variantValue() const;
        /** Returns the stored enum name. */
        const std::string& enumValue() const;
        /** Returns the stored option. */
        const std::shared_ptr<ComponentValue>& optionValue() const;
        /** Returns the stored result. */
        const ComponentResult& resultValue() const;
        /** Returns the stored flags. */
        const ComponentFlags& flagsValue() const;
        /** Returns the stored map. */
        const ComponentMap& mapValue() const;
        /** Returns the stored resource reference. */
        const ComponentResourceReference& resourceValue() const;

    private:
        struct Storage;

        ComponentValue(Kind kind, std::shared_ptr<Storage> storage);

        template <typename T> static ComponentValue create(Kind kind, T value);

        Kind kind_;
        std::shared_ptr<Storage> storage_;
    };

    /** Stores one named field in a component record. */
    struct ComponentRecordField {
        std::string name;
        ComponentValue value;
    };

    /** Stores a component variant value. */
    struct ComponentVariant {
        std::string discriminant;
        std::shared_ptr<ComponentValue> value;
    };

    /** Stores a component result value. */
    struct ComponentResult {
        bool isOk;
        std::shared_ptr<ComponentValue> value;
    };

    /** Stores a component character value. */
    struct ComponentCharacter {
        uint32_t value;
    };

    /** Stores component tuple data. */
    struct ComponentTupleData {
        ComponentTuple values;
    };

    /** Stores a component enum value. */
    struct ComponentEnumData {
        std::string name;
    };

    enum class ComponentResourceOwnership : uint8_t { OWN, BORROW };

    enum class ComponentResourceOrigin : uint8_t { GUEST, HOST };

    struct ComponentResourceReference {
        std::string instanceKey;
        uint32_t typeId;
        uint64_t handleId;
        uint32_t generation;
        ComponentResourceOwnership ownership;
        ComponentResourceOrigin origin;
    };

    struct ComponentValue::Storage {
        using Data =
            std::variant<bool, int8_t, uint8_t, int16_t, uint16_t, int32_t, uint32_t, int64_t, uint64_t, float, double, ComponentCharacter,
                         std::string, ComponentList, ComponentRecord, ComponentTupleData, ComponentVariant, ComponentEnumData,
                         std::shared_ptr<ComponentValue>, ComponentResult, ComponentFlags, ComponentMap, ComponentResourceReference>;

        explicit Storage(Data data) : data(std::move(data)) {}

        Data data;
    };
} // namespace wasmline
