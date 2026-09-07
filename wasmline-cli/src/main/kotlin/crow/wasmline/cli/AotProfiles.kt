package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog

/**
 * Lists immutable AOT compatibility profiles available to CLI selectors.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal class AotProfilesList : CliktCommand(name = "list") {
    override fun run() {
        AotCompatibilityCatalog.profiles().forEach { profile ->
            echo(
                "${profile.wasmtimeVersion}\t${profile.artifactBackend}\t" +
                    "${profile.introducedInWasmlineVersion}\t${profile.id}",
            )
        }
    }
}

/**
 * Describes one exact backend-specific AOT compatibility profile.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal class AotProfilesDescribe : CliktCommand(name = "describe") {
    private val profileId by argument(name = "profile-id")

    override fun run() {
        val profile = AotCompatibilityCatalog.requireProfile(profileId)
        echo("id: ${profile.id}")
        echo("artifact backend: ${profile.artifactBackend}")
        echo("Wasmtime version: ${profile.wasmtimeVersion}")
        echo("Wasmtime distribution version: ${profile.wasmtimeDistributionVersion}")
        echo("Wasmtime source revision: ${profile.wasmtimeSourceRevision}")
        echo("serialized artifact format: ${profile.serializedArtifactFormatIdentity}")
        echo("compile profile schema: ${profile.compileProfileSchemaVersion}")
        echo("introduced in Wasmline: ${profile.introducedInWasmlineVersion}")
        echo("build hosts: ${AotCompatibilityCatalog.buildHosts(profile.id).joinToString()}")
    }
}
