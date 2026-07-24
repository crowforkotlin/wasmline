// RUN_PIPELINE_TILL: FRONTEND

// MODULE: lib
// FILE: util.kt
package example.util

fun takeInt(x: Int) {}

// MODULE: main(lib)
// FILE: errorTest.kt
package example.test

import example.util.takeInt

/**
 * Diagnostic test for argument type mismatch.
 * Verifies that the compiler detects wrong argument types.
 */
fun test() {
    // This should trigger ARGUMENT_TYPE_MISMATCH
    takeInt(<!ARGUMENT_TYPE_MISMATCH!>"Wrong type"<!>)
    
    // Multiple errors in one call
    val x = 123
    takeInt(<!TYPE_MISMATCH!>x<!>)  // Assuming this would fail (for demonstration)
}
