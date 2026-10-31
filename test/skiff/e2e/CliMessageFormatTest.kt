package skiff.e2e

import skiff.json.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `skf build --message-format=json` on a project with and without compile errors. Needs the network the first time. */
class CliMessageFormatTest {
    private val dir: Path = Files.createTempDirectory("skf-messages")
    private val skf = Skf(dir)
    private val project = dir.resolve("app")
    private val app = Skf(project)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun events(stdout: String): List<Map<*, *>> =
        stdout.lines().filter { it.isNotBlank() }.map { JsonParser(it).parse() as Map<*, *> }

    @Test
    fun reportsDiagnosticsAndTheResultAsJsonLines() {
        skf.succeed("new", "app", "--no-git")
        Files.writeString(project.resolve("src/Main.kt"), "fun main() {\n    val x: Int = \"hello\"\n}\n")

        val failed = app.run("build", "--message-format=json")
        assertEquals(1, failed.exitCode)
        val failedEvents = events(failed.stdout)
        val error = failedEvents.first { it["reason"] == "compiler-message" }
        assertEquals("error", error["severity"])
        assertEquals(2L, error["line"])
        assertTrue((error["path"] as String).endsWith("src/Main.kt"), error.toString())
        assertEquals(false, failedEvents.last()["success"])
        assertEquals("build-finished", failedEvents.last()["reason"])

        Files.writeString(project.resolve("src/Main.kt"), "fun main() {}\n")
        val ok = app.succeed("build", "--message-format=json")
        val finished = events(ok.stdout).single()
        assertEquals(true, finished["success"])
        assertTrue((finished["jar"] as String).endsWith("target/app-0.1.0.jar"), finished.toString())
    }

    @Test
    fun otherCommandsRefuseTheFlag() {
        val result = skf.run("run", "--message-format=json")
        assertEquals(1, result.exitCode)
        assertTrue("only supported by `skf build`" in result.stderr, result.stderr)
    }
}
