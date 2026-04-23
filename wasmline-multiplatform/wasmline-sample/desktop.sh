set -euo pipefail

./gradlew wasmline-kotlin-plugin:publishToMavenLocal :wasmline-gradle-plugin:publishToMavenLocal

(cd ./wasmline && zig build --release=small -p src/jvmMain/resources)

rm -rf ./wasmline/build/kotlin/compileKotlinWasmWasi
rm -rf ./wasmline/build/classes/kotlin/wasmWasi
rm -rf ./wasmline-sample/plugin/build

./gradlew wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
./gradlew wasmline-cli:run --args="build -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-aarch64-macos --key build/wasmline/keys/ed25519_private.key"
cp -r ./wasmline-cli/build/wasmline/output/wasmline-multiplatform-wasmline-sample-plugin-1.0.0/wasmline-multiplatform-wasmline-sample-plugin-pulley64.pwasm ./wasmline-sample/multiplatform/desktopApp/src/main/resources/plugin.pwasm
./gradlew wasmline-sample:multiplatform:desktopApp:run
