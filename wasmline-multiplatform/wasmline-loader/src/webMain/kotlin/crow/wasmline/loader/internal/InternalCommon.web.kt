package crow.wasmline.loader.internal

import kotlin.time.Clock

internal val browserSystemEpochMsClock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
