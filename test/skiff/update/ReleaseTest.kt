package skiff.update

import skiff.SkiffException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseTest {
    private fun json(vararg assets: String) = """
        {"tag_name": "v1.2.3", "assets": [${assets.joinToString(",") { """{"name": "$it", "browser_download_url": "https://x/$it"}""" }}]}
    """.trimIndent()

    @Test
    fun picksTheFatJarAndItsChecksum() {
        val release = Release.parse(json("skf-1.2.3.msi", "skiff-1.2.3-all.jar", "skiff-1.2.3-all.jar.sha256"))
        assertEquals("1.2.3", release.version)
        assertEquals("https://x/skiff-1.2.3-all.jar", release.jarUrl)
        assertEquals("https://x/skiff-1.2.3-all.jar.sha256", release.checksumUrl)
    }

    @Test
    fun rejectsReleasesWithoutAJarOrChecksum() {
        assertFailsWith<SkiffException> { Release.parse(json("skf-1.2.3.msi")) }
        assertFailsWith<SkiffException> { Release.parse(json("skiff-1.2.3-all.jar")) }
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(SelfUpdate.isNewer("0.10.0", "0.9.0"))
        assertFalse(SelfUpdate.isNewer("0.1.0", "0.1.0"))
        assertFalse(SelfUpdate.isNewer("0.1.0", "0.2.0"))
    }
}
