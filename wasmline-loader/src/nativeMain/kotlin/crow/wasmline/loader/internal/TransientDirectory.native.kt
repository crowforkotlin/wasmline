package crow.wasmline.loader.internal

import okio.Path

/**
 * Native temporary directories are per-user on Apple platforms and Android.
 * The Linux native host keeps the platform directory mode.
 */
internal actual fun hardenTransientDirectory(directory: Path) = Unit
