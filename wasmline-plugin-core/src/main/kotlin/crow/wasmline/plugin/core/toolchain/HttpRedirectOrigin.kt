package crow.wasmline.plugin.core.toolchain

import java.net.URI

/** Credentials may follow redirects only within the original HTTPS origin. */
internal fun sameHttpOrigin(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host != null && first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second)

private fun effectivePort(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
