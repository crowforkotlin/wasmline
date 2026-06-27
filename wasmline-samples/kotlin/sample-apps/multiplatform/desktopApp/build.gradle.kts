import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.jvm)
}

compose.desktop {
    application {
        mainClass = "crow.wasmline.sample.MainKt"

        nativeDistributions {
            packageName = "Wasmline"
            packageVersion = project.findProperty("wasmline.version") as? String ?: "1.0.0"
            description = "Wasmline Sample Desktop Application"
            copyright = "© 2024 Wasmline. All rights reserved."
            vendor = "Wasmline"
            licenseFile.set(project.file("../../../../../LICENSE"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appIcons"))

            // Linux: x86_64 + aarch64
            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
                targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            }
            // macOS: x86_64 + aarch64
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "crow.wasmline.sample"
                targetFormats(TargetFormat.Dmg)
            }
            // Windows: x86_64 (aarch64 not yet supported by Compose Desktop)
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                dirChooser = true
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                targetFormats(TargetFormat.Msi)
            }
        }
    }
}

dependencies {
    implementation(projects.sampleApps.multiplatform.shared)
}