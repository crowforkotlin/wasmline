package crow.wasmline.loader.internal

import crow.wasmline.loader.internal.crypto.EcdsaP256
import crow.wasmline.loader.internal.crypto.SignatureAlgorithm
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual val ecdsaP256: SignatureAlgorithm = EcdsaP256()

internal actual val systemEpochMsClock: () -> Long =
    { (NSDate().timeIntervalSince1970() * 1000).toLong() }