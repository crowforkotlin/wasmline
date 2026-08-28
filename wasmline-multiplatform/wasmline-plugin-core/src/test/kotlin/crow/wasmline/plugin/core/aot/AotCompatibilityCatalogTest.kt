package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies immutable AOT catalog selection and backend-specific identities.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class AotCompatibilityCatalogTest {
    @Test
    fun resolvesThreeWasmtimeVersionsAcrossBothBackends() {
        val profiles = AotCompatibilityCatalog.resolveProfiles(
            wasmtimeVersions = listOf("48.0.0", "47.0.3", "47.0.4"),
            profileIds = emptyList(),
            artifactBackends = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
        )

        assertEquals(6, profiles.size)
        assertEquals(setOf("47.0.3", "47.0.4", "48.0.0"), profiles.map { it.wasmtimeVersion }.toSet())
        assertEquals(setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY), profiles.map { it.artifactBackend }.toSet())
    }

    @Test
    fun createsDifferentCompatibilityIdsForDifferentBackends() {
        val cranelift = AotCompatibilityCatalog.resolveProfiles(
            wasmtimeVersions = listOf("48.0.0"),
            profileIds = emptyList(),
            artifactBackends = setOf(WasmlineEngineKind.CRANELIFT),
        ).single()
        val pulley = cranelift.copy(artifactBackend = WasmlineEngineKind.PULLEY)

        assertNotEquals(
            AotCompatibilityCatalog.calculateCompatibilityId(cranelift),
            AotCompatibilityCatalog.calculateCompatibilityId(pulley),
        )
    }

    @Test
    fun resolvesOnlyFullCompilerDistributions() {
        AotCompatibilityCatalog.profiles().forEach { profile ->
            AotCompatibilityCatalog.buildHosts(profile.id).forEach { buildHost ->
                assertEquals(
                    "FULL",
                    AotCompatibilityCatalog.requireCompilerAsset(profile.id, buildHost).distribution,
                )
            }
        }
    }

    @Test
    fun rejectsVersionWithoutRequestedBackendProfile() {
        val failure = assertFailsWith<IllegalStateException> {
            AotCompatibilityCatalog.resolveProfiles(
                wasmtimeVersions = listOf("12.3.4"),
                profileIds = emptyList(),
                artifactBackends = setOf(WasmlineEngineKind.CRANELIFT),
            )
        }

        assertTrue(failure.message.orEmpty().contains("no CRANELIFT profile"))
    }
}
