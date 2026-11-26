package crow.wasmline.loader.internal

import okio.Path

/** The browser loads artifacts from its in-memory cache and creates no transient directory. */
internal actual fun hardenTransientDirectory(directory: Path) = Unit
