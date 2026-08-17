import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters

plugins {
    alias(libs.plugins.app.base.multiplatform.library) apply false
    alias(libs.plugins.app.base.library) apply false
    alias(libs.plugins.app.base.android) apply false

    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.library.kmp) apply false
    alias(libs.plugins.jetbrains.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.github.fourlastor.construo) apply false
    alias(libs.plugins.conveyor) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.ktlint)
}

dependencies {
    listOf(
        ":wasmline",
        ":wasmline-loader",
        ":wasmline-network-ktor",
        ":wasmline-network-okhttp",
        ":wasmline-engine-pulley",
        ":wasmline-engine-cranelift",
        ":wasmline-kotlin-plugin",
        ":wasmline-gradle-plugin",
        ":wasmline-cli",
    ).forEach { add("dokka", project(it)) }
}

allprojects {
    group = "crow.wasmline"
    version = project.property("wasmline.version") as String

    pluginManager.withPlugin("org.jetbrains.dokka") {
        configure<DokkaExtension> {
            dokkaSourceSets.configureEach {
                documentedVisibilities(VisibilityModifier.Public)
                perPackageOption {
                    matchingRegex.set(".*\\.internal(\\..*)?")
                    suppress.set(true)
                }
            }
            pluginsConfiguration.withType(DokkaHtmlPluginParameters::class.java).configureEach {
                customStyleSheets.from(rootProject.file("dokka/wasmline.css"))
                customAssets.from(
                    rootProject.file("../docs/assets/fonts/MapleMono-NF-CN-SemiBold.woff2"),
                    rootProject.file("../docs/assets/fonts/MapleMono-NF-CN-Bold.woff2"),
                    rootProject.file("../docs/assets/fonts/OFL.txt"),
                )
                footerMessage.set("Wasmline API documentation")
                homepageLink.set("https://github.com/crowforkotlin/wasmline")
            }
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // Exclude auto-generated sources from KtLint checks
    ktlint {
        filter {
            exclude { it.file.path.contains("/build/generated/") }
        }
    }

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
