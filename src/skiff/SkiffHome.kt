package skiff

import java.nio.file.Path

/** Locations under the per-user skiff directory (`SKIFF_HOME`, or `~/.skiff`). */
object SkiffHome {
    val root: Path = System.getenv("SKIFF_HOME")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".skiff")

    /** Downloaded artifacts, in Maven repository layout. */
    val mavenCache: Path get() = root.resolve("cache").resolve("maven")

    /** Resolved compiler classpaths, one file per Kotlin version. */
    val toolchains: Path get() = root.resolve("toolchains")
}
