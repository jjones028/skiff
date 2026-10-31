package skiff.update

import skiff.BuildInfo
import skiff.SkiffHome
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The "new version available" hint. Asks GitHub at most once per [interval], remembering the answer in [file]
 * (`<epoch seconds> <latest version>`), and swallows every failure: a hint must never break a command.
 */
class UpdateNotice(
    private val file: Path = SkiffHome.root.resolve("update-check"),
    private val releases: () -> Releases = { Releases(timeout = Duration.ofSeconds(2)) },
    private val interval: Duration = Duration.ofDays(1),
    private val now: () -> Instant = Instant::now,
) {
    /** The hint to print, or null when skiff is current or the check could not be made. */
    fun message(current: String = BuildInfo.VERSION): String? = try {
        val latest = latestVersion()
        if (latest != null && SelfUpdate.isNewer(latest, current)) {
            "skiff v$latest is available (installed: v$current); run `skf upgrade`"
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    private fun latestVersion(): String? {
        val cached = read()
        if (cached != null && Duration.between(cached.first, now()) < interval) return cached.second
        val fetched = try {
            releases().latest().version
        } catch (e: Exception) {
            null
        }
        // Remember failures too, so an offline machine does not retry on every command.
        val version = fetched ?: cached?.second
        Files.createDirectories(file.parent)
        Files.writeString(file, "${now().epochSecond} ${version.orEmpty()}")
        return version
    }

    private fun read(): Pair<Instant, String?>? {
        if (!Files.isRegularFile(file)) return null
        val parts = Files.readString(file).trim().split(' ')
        val at = parts[0].toLongOrNull() ?: return null
        return Instant.ofEpochSecond(at) to parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }
}
