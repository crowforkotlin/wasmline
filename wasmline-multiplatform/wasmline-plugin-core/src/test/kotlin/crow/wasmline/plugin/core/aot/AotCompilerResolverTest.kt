package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies compiler asset deduplication and complete offline diagnostics.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class AotCompilerResolverTest {
    @Test
    fun reportsEveryMissingProfileBeforeOfflineFailure() = runBlocking {
        withCompilerCacheDirectory { root ->
            val profiles = currentProfiles()
            val resolver = AotCompilerResolver(AotCompilerCache(root))
            try {
                val failure = assertFailsWith<IllegalStateException> {
                    resolver.resolveAll(
                        profiles = profiles,
                        buildHost = "x86_64-linux",
                        autoDownload = false,
                    )
                }

                profiles.forEach { profile -> assertTrue(failure.message.orEmpty().contains(profile.id)) }
            } finally {
                resolver.close()
            }
        }
    }

    @Test
    fun backendProfilesReuseOneCompilerAssetPerWasmtimeRelease() {
        currentProfiles().groupBy { it.wasmtimeVersion }.values.forEach { profiles ->
            val digests = profiles.map { profile ->
                AotCompatibilityCatalog.requireCompilerAsset(profile.id, "x86_64-linux").archiveSha256
            }.toSet()

            assertEquals(1, digests.size)
        }
    }

    @Test
    fun rejectsAnUnboundedDownloadConfiguration() = runBlocking {
        withCompilerCacheDirectory { root ->
            AotCompilerResolver(AotCompilerCache(root)).use { resolver ->
                assertFailsWith<IllegalArgumentException> {
                    resolver.resolveAll(
                        profiles = currentProfiles(),
                        buildHost = "x86_64-linux",
                        autoDownload = true,
                        maxParallelDownloads = 0,
                    )
                }
            }
        }
    }

    private fun currentProfiles(): List<AotCompatibilityProfileSpec> = AotCompatibilityCatalog.profiles()
        .filter { it.artifactBackend in setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY) }
}

private inline fun withCompilerCacheDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-aot-compiler-cache-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
