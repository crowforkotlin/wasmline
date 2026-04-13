import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.library.kmp) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.jetbrains.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.zipline) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.github.fourlastor.construo) apply false
    alias(libs.plugins.conveyor) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.maven.publish) apply false
}

if (gradle.extra["wasmlineAvailable"] as? Boolean == true) {
    apply(plugin = "crow.wasmline")
}



allprojects {
    group = "crow.wasmline"
    version = project.property("wasmline.version") as String

    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        configure<MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            signAllPublications()
            pom {
                description.set("Wasmline")
                name.set("Wasmline")
                url.set("https://github.com/crowforkotlin/wasmline/")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("crowforkotlin")
                        name.set("wuya")
                        email.set("crowforkotlin@gmail.com")
                    }
                }
                scm {
                    url.set("https://github.com/crowforkotlin/wasmline/")
                    connection.set("scm:git:https://github.com/crowforkotlin/wasmline.git")
                    developerConnection.set("scm:git:ssh://git@github.com:crowforkotlin/wasmline.git")
                }
            }
        }
    }
}