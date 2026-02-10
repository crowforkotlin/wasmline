package com.mordecai.wasmline.loader.internal

import com.mordecai.wasmline.loader.internal.crypto.EcdsaP256
import com.mordecai.wasmline.loader.internal.crypto.SignatureAlgorithm
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual val ecdsaP256: SignatureAlgorithm = EcdsaP256()

internal actual val systemEpochMsClock: () -> Long =
    { (NSDate().timeIntervalSince1970() * 1000).toLong() }