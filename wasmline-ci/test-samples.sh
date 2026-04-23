#!/bin/bash

cd "$(dirname "$0")/../wasmline-multiplatform" || exit

./gradlew :wasmline-sample:common:check :wasmline-sample:multiplatform:check --no-daemon
