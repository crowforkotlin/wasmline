plugins {
    kotlin("multiplatform") version "2.3.20-Beta1"
    id("crow.wasmline") version "1.0.0"
}
kotlin {
    wasmWasi {
        nodejs()
        binaries.library()
    }
    sourceSets {
        wasmWasiMain.dependencies {
            implementation("crow.wasmline:wasmline:1.0.0")
        }
    }
}
