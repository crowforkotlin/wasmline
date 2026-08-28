package crow.wasmline.loader.internal

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual val systemEpochMsClock: () -> Long =
    { (NSDate().timeIntervalSince1970 * 1000).toLong() }
