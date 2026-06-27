import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.jvm)
}

compose.desktop {
    application {
        mainClass = "crow.wasmline.sample.MainKt"

        nativeDistributions {
            targetFormats(
                TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Msi,
                TargetFormat.AppImage, TargetFormat.Exe, TargetFormat.Pkg
            )
            packageName = "Wasmline"
            packageVersion = project.findProperty("wasmline.version") as? String ?: "1.0.0"
            description = "Wasmline Sample Desktop Application"
            copyright = "© 2026 Wasmline. All rights reserved."
            vendor = "Wasmline"
            licenseFile.set(project.file("../../../../../LICENSE"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appIcons"))

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "crow.wasmline.sample"
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
                dirChooser = true
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}

dependencies {
    implementation(projects.sampleApps.multiplatform.shared)
}