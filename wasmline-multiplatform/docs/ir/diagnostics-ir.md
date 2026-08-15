# Diagnostic IR Test Status

Diagnostic IR tests are not currently active.

The repository contains `test-fixtures/crow/wasmline/kotlin/runners/AbstractJvmDiagnosticTest.kt`, but the diagnostic model is commented out in `GenerateTests.kt`. There is no `testData/diagnostics/` directory and no generated `JvmDiagnosticsTestGenerated` runner.

To introduce diagnostic tests in a future change, update these parts together:

1. Enable the diagnostic model in `GenerateTests.kt`.
2. Add focused fixtures under `testData/diagnostics/`.
3. Generate and review the diagnostic runner.
4. Add the suite to local and CI verification commands.
5. Update this documentation with the verified directives and command.

Do not run or document a `JvmDiagnosticsTestGenerated` command before that runner exists.
