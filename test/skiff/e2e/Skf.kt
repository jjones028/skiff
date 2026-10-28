package skiff.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the real `skf` CLI (`skiff.MainKt`) in a child JVM with [dir] as its working directory, like a user would.
 * It uses the shared `~/.skiff` cache, so the first run needs network access to download the Kotlin compiler.
 */
class Skf(private val dir: Path) {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(vararg args: String): Result {
        val out = Files.createTempFile("skf-out", ".txt")
        val err = Files.createTempFile("skf-err", ".txt")
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "skiff.MainKt", *args)
                .directory(dir.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile())
                .apply { environment()["NO_COLOR"] = "1" }
                .start()
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                throw AssertionError("skf ${args.joinToString(" ")} did not finish within $TIMEOUT_MINUTES minutes")
            }
            return Result(process.exitValue(), Files.readString(out), Files.readString(err))
        } finally {
            Files.deleteIfExists(out)
            Files.deleteIfExists(err)
        }
    }

    /** Like [run], but fails the test with the command's output unless it exits with 0. */
    fun succeed(vararg args: String): Result = run(*args).also {
        if (it.exitCode != 0) throw AssertionError("skf ${args.joinToString(" ")} exited with ${it.exitCode}\n${it.stdout}${it.stderr}")
    }

    private companion object {
        const val TIMEOUT_MINUTES = 5L
    }
}
