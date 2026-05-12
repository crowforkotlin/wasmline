set -euo pipefail

./gradlew wasmline-kotlin-plugin:publishToMavenLocal :wasmline-gradle-plugin:publishToMavenLocal

(cd ./wasmline && zig build --release=small -p src/jvmMain/resources)

rm -rf ./wasmline/build/kotlin/compileKotlinWasmWasi
rm -rf ./wasmline/build/classes/kotlin/wasmWasi
rm -rf ./wasmline-sample/plugin/build

./gradlew wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
# Desktop sample only needs the pulley64 artifact that gets copied into resources below.
./gradlew wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-x86_64-linux -a pulley64"
PLUGIN_OUTPUT_DIR="./wasmline-cli/build/wasmline/output/wasmline-multiplatform-wasmline-sample-plugin-1.0.0"
DESKTOP_RESOURCE_DIR="./wasmline-sample/multiplatform/desktopApp/src/main/resources"

mkdir -p "$DESKTOP_RESOURCE_DIR"
cp "$PLUGIN_OUTPUT_DIR/wasmline-multiplatform-wasmline-sample-plugin-pulley64.pwasm" "$DESKTOP_RESOURCE_DIR/plugin.pwasm"
./gradlew wasmline-sample:multiplatform:desktopApp:run
