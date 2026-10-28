package skiff.project

import skiff.maven.ModuleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManifestEditorTest {
    private val okhttp = ModuleId("com.squareup.okhttp3", "okhttp")
    private val coroutines = ModuleId("org.jetbrains.kotlinx", "kotlinx-coroutines-core")

    private val manifest = """
        [package]
        name = "demo"

        # Libraries
        [dependencies]
        "com.squareup.okhttp3:okhttp" = "4.11.0" # http client

        [kotlin]
        version = "2.4.21"
    """.trimIndent()

    @Test
    fun replacesTheVersionOfAnExistingDependency() {
        val edited = ManifestEditor.setDependency(manifest, okhttp, "4.12.0")
        assertEquals(manifest.replace("\"4.11.0\" # http client", "\"4.12.0\""), edited)
    }

    @Test
    fun appendsAfterTheLastEntryOfTheSection() {
        val edited = ManifestEditor.setDependency(manifest, coroutines, "1.8.0")
        val expected = manifest.replace(
            "# http client\n",
            "# http client\n\"org.jetbrains.kotlinx:kotlinx-coroutines-core\" = \"1.8.0\"\n",
        )
        assertEquals(expected, edited)
    }

    @Test
    fun addsToAnEmptySectionRightBelowItsHeader() {
        val text = "[dependencies]\n\n[kotlin]\nversion = \"2.4.21\""
        assertEquals(
            "[dependencies]\n\"org.jetbrains.kotlinx:kotlinx-coroutines-core\" = \"1.8.0\"\n\n[kotlin]\nversion = \"2.4.21\"",
            ManifestEditor.setDependency(text, coroutines, "1.8.0"),
        )
    }

    @Test
    fun createsTheSectionWhenMissing() {
        val text = "[package]\nname = \"demo\"\n\n"
        assertEquals(
            "[package]\nname = \"demo\"\n\n[dependencies]\n\"com.squareup.okhttp3:okhttp\" = \"4.12.0\"\n",
            ManifestEditor.setDependency(text, okhttp, "4.12.0"),
        )
    }

    @Test
    fun matchesQuotedAndBareKeys() {
        val quoted = "[dependencies]\n'com.squareup.okhttp3:okhttp' = \"1\"\n"
        assertEquals(
            "[dependencies]\n\"com.squareup.okhttp3:okhttp\" = \"2\"\n",
            ManifestEditor.setDependency(quoted, okhttp, "2"),
        )
        val bare = "[dependencies]\ncom.squareup.okhttp3:okhttp = \"1\"\n"
        assertEquals("[dependencies]\n", ManifestEditor.removeDependency(bare, okhttp))
    }

    @Test
    fun doesNotTouchOtherSectionsWithTheSameKey() {
        val text = "[dev-dependencies]\n\"com.squareup.okhttp3:okhttp\" = \"1\"\n"
        val edited = ManifestEditor.setDependency(text, okhttp, "2")
        assertEquals(text + "\n[dependencies]\n\"com.squareup.okhttp3:okhttp\" = \"2\"\n", edited)
    }

    @Test
    fun removesADependency() {
        val edited = ManifestEditor.removeDependency(manifest, okhttp)
        assertEquals(manifest.replace("\"com.squareup.okhttp3:okhttp\" = \"4.11.0\" # http client\n", ""), edited)
    }

    @Test
    fun removeReturnsNullWhenNotListed() {
        assertNull(ManifestEditor.removeDependency(manifest, coroutines))
        assertNull(ManifestEditor.removeDependency("[package]\nname = \"x\"", okhttp))
    }

    @Test
    fun keepsWindowsLineEndings() {
        val text = "[dependencies]\r\n\"a:b\" = \"1\"\r\n"
        val edited = ManifestEditor.setDependency(text, ModuleId("c", "d"), "2")
        assertEquals("[dependencies]\r\n\"a:b\" = \"1\"\r\n\"c:d\" = \"2\"\r\n", edited)
    }
}
