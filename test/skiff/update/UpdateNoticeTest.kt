package skiff.update

import skiff.SkiffException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateNoticeTest {
    private val dir: Path = Files.createTempDirectory("skf-notice")
    private val file = dir.resolve("sub/update-check")
    private var clock = Instant.parse("2026-01-01T00:00:00Z")
    private var fetches = 0
    private var latest: String? = "0.2.0"

    private val notice = UpdateNotice(
        file,
        releases = {
            object : Releases("unused") {
                override fun latest(): Release {
                    fetches++
                    val version = latest ?: throw SkiffException("offline")
                    return Release(version, "j", "c")
                }
            }
        },
        now = { clock },
    )

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun announcesANewerReleaseAndChecksOnlyOncePerDay() {
        assertTrue("v0.2.0 is available" in notice.message("0.1.0")!!)
        clock = clock.plus(Duration.ofHours(23))
        assertTrue(notice.message("0.1.0") != null)
        assertEquals(1, fetches)
        clock = clock.plus(Duration.ofHours(2))
        notice.message("0.1.0")
        assertEquals(2, fetches)
    }

    @Test
    fun saysNothingWhenCurrentOrOffline() {
        assertNull(notice.message("0.2.0"))
        latest = null
        clock = clock.plus(Duration.ofDays(2))
        assertNull(notice.message("0.2.0"))
        assertNull(notice.message("0.2.0"), "a failed check is remembered")
        assertEquals(2, fetches)
    }
}
