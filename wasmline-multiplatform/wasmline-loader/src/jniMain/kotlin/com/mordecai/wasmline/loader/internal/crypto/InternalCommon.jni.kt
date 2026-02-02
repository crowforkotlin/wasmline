package com.mordecai.wasmline.loader.internal.crypto

internal actual val systemEpochMsClock: () -> Long
    get() = System::currentTimeMillis