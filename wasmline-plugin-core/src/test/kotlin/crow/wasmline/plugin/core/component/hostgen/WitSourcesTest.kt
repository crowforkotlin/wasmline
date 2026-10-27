package crow.wasmline.plugin.core.component.hostgen

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WitSourcesTest {
    private val source = "package wasmline:service@1.0.0;\nworld plugin {}\n"

    @Test
    fun hashesOneFileAndItsContainingDirectoryIdentically() {
        val root = createTempDirectory("wasmline-wit-digest").toFile()
        try {
            val wit = File(root, "wasmline.wit").apply { writeText(source) }

            assertEquals(WitSources.load(wit).sha256, WitSources.load(root).sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normalizesLineEndingsBeforeHashing() {
        val root = createTempDirectory("wasmline-wit-newlines").toFile()
        try {
            val unix = File(root, "unix").apply {
                mkdirs()
                File(this, "wasmline.wit").writeText(source)
            }
            val windows = File(root, "windows").apply {
                mkdirs()
                File(this, "wasmline.wit").writeText(source.replace("\n", "\r\n"))
            }

            assertEquals(WitSources.load(unix).sha256, WitSources.load(windows).sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun includesRelativePathsOfDependencyFiles() {
        val root = createTempDirectory("wasmline-wit-paths").toFile()
        try {
            val rootPackage = "package wasmline:service@1.0.0;\nworld plugin {}\n"
            val dependency = "package wasmline:dependency@1.0.0;\n"
            val first = File(root, "first").apply {
                mkdirs()
                File(this, "wasmline.wit").writeText(rootPackage)
                File(this, "deps").mkdirs()
                File(this, "deps/dependency.wit").writeText(dependency)
            }
            val second = File(root, "second").apply {
                mkdirs()
                File(this, "wasmline.wit").writeText(rootPackage)
                File(this, "deps").mkdirs()
                File(this, "deps/nested").mkdirs()
                File(this, "deps/nested/dependency.wit").writeText(dependency)
            }

            assertNotEquals(WitSources.load(first).sha256, WitSources.load(second).sha256)
        } finally {
            root.deleteRecursively()
        }
    }
}
