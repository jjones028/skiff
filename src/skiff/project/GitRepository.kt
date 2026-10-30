package skiff.project

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Initializes a git repository for new projects, like `cargo new`; does nothing when git is missing or [dir] is already in a repository. */
object GitRepository {
    /** Returns true if a repository was created. */
    fun init(dir: Path): Boolean = !isInsideRepository(dir) && git(dir, "init")

    private fun isInsideRepository(dir: Path): Boolean = git(dir, "rev-parse", "--is-inside-work-tree")

    private fun git(dir: Path, vararg args: String): Boolean = try {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: java.io.IOException) {
        false
    }
}
