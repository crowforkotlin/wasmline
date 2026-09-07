if(NOT DEFINED SOURCE_FILE OR NOT EXISTS "${SOURCE_FILE}")
    message(FATAL_ERROR "Generated C++ source does not exist: ${SOURCE_FILE}")
endif()

file(READ "${SOURCE_FILE}" generated_source)
set(problematic_expression "std::move(result3).value()")
set(compatible_expression "*std::move(result3)")
string(FIND "${generated_source}" "${problematic_expression}" expression_offset)
if(expression_offset EQUAL -1)
    message(FATAL_ERROR "wit-bindgen 0.57.1 expected compatibility expression was not found")
endif()

string(REPLACE "${problematic_expression}" "${compatible_expression}" patched_source "${generated_source}")
file(WRITE "${SOURCE_FILE}" "${patched_source}")
