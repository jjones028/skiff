package skiff.update

import skiff.BuildInfo
import skiff.SkiffException
import skiff.cli.Term
import skiff.maven.Version
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** `skf self-update`: replaces the jar skiff is running from with the latest release's fat jar. */
class SelfUpdate(private val releases: Releases = Releases()) {
    fun run(checkOnly: Boolean): Int {
        val release = releases.latest()
        if (!isNewer(release.version, BuildInfo.VERSION)) {
            Term.status("Up to date", "skiff v${BuildInfo.VERSION}")
            return 0
        }
        if (checkOnly) {
            Term.status("Available", "skiff v${release.version} (installed: v${BuildInfo.VERSION}); run `skf self-update`")
            return 0
        }
        val jar = currentJar()
        val download = jar.resolveSibling("${jar.fileName}.new")
        try {
            Term.status("Downloading", "skiff v${release.version}")
            releases.downloadJar(release, download)
            if (replace(jar, download)) {
                Term.status("Updated", "skiff v${BuildInfo.VERSION} -> v${release.version}")
            } else {
                Term.status("Updating", "to skiff v${release.version} as soon as this command exits")
            }
        } catch (e: IOException) {
            Files.deleteIfExists(download)
            throw SkiffException("could not replace $jar: ${e.message ?: e.javaClass.simpleName} (is it writable?)", e)
        }
        return 0
    }

    companion object {
        fun isNewer(candidate: String, current: String): Boolean = Version(candidate) > Version(current)

        /** The jar this process runs from; fails for a classes directory or an unpacked layout. */
        fun currentJar(): Path {
            val location = SelfUpdate::class.java.protectionDomain.codeSource?.location
            val path = location?.let { Path.of(it.toURI()) }
            if (path == null || !Files.isRegularFile(path) || !path.fileName.toString().endsWith(".jar")) {
                throw SkiffException("skiff is not running from a jar, so it cannot update itself")
            }
            return path
        }

        /**
         * Moves [fresh] over [jar]. Returns false when the swap is deferred until this JVM exits,
         * which Windows needs because it locks the running jar.
         */
        fun replace(jar: Path, fresh: Path): Boolean {
            if (System.getProperty("os.name").startsWith("Windows")) {
                ProcessBuilder("cmd", "/c", "ping", "-n", "3", "127.0.0.1", "&", "move", "/y", fresh.toString(), jar.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                return false
            }
            Files.move(fresh, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        }
    }
}
