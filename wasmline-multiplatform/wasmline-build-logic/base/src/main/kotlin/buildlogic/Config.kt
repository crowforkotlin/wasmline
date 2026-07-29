@file:Suppress("SpellCheckingInspection", "ConstPropertyName")

package buildlogic

import org.gradle.api.Project

object Config {

    const val Group = "crow"
    const val ApplicationId = "$Group.wasmline"
    const val ApplicationName = "wasmline"

    /**
     * mordecaix-test/
     * ├── android-compose/
     * └── android/
     * getBaseName ... => TestCompose、TestAndroid
     */
    fun getBaseName(project: Project): String {
        var baseName = ""
        var p: Project? = project
        while (p != null && p != project.rootProject) {
            baseName = p.name.substringAfterLast("-").replaceFirstChar { it.uppercaseChar() } + baseName
            p = p.parent
        }
        return baseName
    }

    /**
     * mordecaix-test/
     * ├── android-compose/
     * └── android/
     * Extracts the last segment after splitting module name by '-'
     *
     * getNamespace... => $GROUP$namespace => crow.wasmline.test.compose, crow.wasmline.test.android
     */
    fun getNamespace(project: Project): String {
        var namespace = ""
        var p: Project? = project
        while (p != null && p != project.rootProject) {
            namespace = ".${p.name.substringAfterLast("-")}$namespace"
            p = p.parent
        }
        return "$Group$namespace"
    }
}
