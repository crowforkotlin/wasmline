import com.vanniktech.maven.publish.JavaPlatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

val constrainedWasmlineModules = listOf(
    "wasmline",
    "wasmline-loader",
    "wasmline-android",
    "wasmline-network-ktor",
    "wasmline-network-okhttp",
    "wasmline-kotlin-plugin",
    "wasmline-gradle-plugin",
    "wasmline-plugin-core",
    "wasmline-cli",
    "wasmline-engine-cranelift",
    "wasmline-engine-pulley",
)

dependencies {
    constraints {
        constrainedWasmlineModules.forEach { module ->
            api("${project.group}:$module:${project.version}") {
                version {
                    strictly(project.version.toString())
                }
            }
        }
    }
}

configure<MavenPublishBaseExtension> {
    configure(platform = JavaPlatform())
}

val verifyWasmlineBomConstraints by tasks.registering {
    group = "verification"
    description = "Verifies that every consumable Wasmline module is published with one strict release version."
    doLast {
        val expectedVersion = project.version.toString()
        val constraints = configurations.getByName("api").allDependencyConstraints
            .associateBy { it.name }
        check(constraints.keys == constrainedWasmlineModules.toSet()) {
            "Wasmline BOM constraints differ from the published module set: ${constraints.keys.sorted()}"
        }
        constraints.values.forEach { constraint ->
            check(constraint.group == project.group.toString()) {
                "Unexpected BOM constraint group for ${constraint.name}: ${constraint.group}"
            }
            check(constraint.versionConstraint.strictVersion == expectedVersion) {
                "BOM constraint ${constraint.name} must strictly require Wasmline $expectedVersion."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyWasmlineBomConstraints)
}
