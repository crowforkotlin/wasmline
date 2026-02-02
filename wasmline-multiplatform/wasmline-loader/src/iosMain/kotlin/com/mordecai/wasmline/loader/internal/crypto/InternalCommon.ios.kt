package com.mordecai.wasmline.loader.internal.crypto

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual val systemEpochMsClock: () -> Long
    get() = { (NSDate().timeIntervalSince1970() * 1000).toLong() }