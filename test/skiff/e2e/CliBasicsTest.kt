package skiff.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI behaviour that needs neither the network nor a compiler. */
class CliBasicsTest {
    private val dir: Path = Files.createTempDirectory("skf-basics")
    private val skf = Skf(dir)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun printsTheVersion() {
        val result = skf.succeed("--version")
        assertTrue(result.stdout.startsWith("skiff "), result.stdout)
    }

    @Test
    fun helpListsTheCommands() {
        val result = skf.succeed("help")
        for (command in listOf("new", "init", "add", "remove", "install", "update", "build", "run", "test", "tree", "classpath", "clean", "idea")) {
            assertTrue(Regex("^\\s+$command\\b", RegexOption.MULTILINE).containsMatchIn(result.stdout), "help should list `$command`")
        }
        assertEquals(result.stdout, skf.succeed().stdout, "no arguments shows the help")
    }

    @Test
    fun unknownCommandsAndFlagsFailWithoutAStackTrace() {
        val command = skf.run("frobnicate")
        assertEquals(1, command.exitCode)
        assertTrue("error: no such command: `frobnicate`" in command.stderr, command.stderr)

        val flag = skf.run("--bogus")
        assertEquals(1, flag.exitCode)
        assertTrue("unknown option '--bogus'" in flag.stderr, flag.stderr)
        assertFalse("Exception" in flag.stderr + command.stderr)
    }

    @Test
    fun newCreatesAProjectAndRefusesToOverwriteIt() {
        val created = skf.succeed("new", "hello")
        assertTrue("Created" in created.stderr, created.stderr)

        val project = dir.resolve("hello")
        val manifest = Files.readString(project.resolve("Skiff.toml"))
        assertTrue("name = \"hello\"" in manifest, manifest)
        assertTrue(Files.isRegularFile(project.resolve("src/Main.kt")))
        assertEquals(listOf("/target", "/.idea", "*.iml", "/workspace.json"), Files.readAllLines(project.resolve(".gitignore")))

        val again = skf.run("new", "hello")
        assertEquals(1, again.exitCode)
        assertTrue("already exists" in again.stderr, again.stderr)
    }

    @Test
    fun initUsesTheCurrentDirectory() {
        val project = Files.createDirectory(dir.resolve("my project"))
        val result = Skf(project).succeed("init")
        assertTrue("`my-project`" in result.stderr, result.stderr)
        assertTrue("name = \"my-project\"" in Files.readString(project.resolve("Skiff.toml")))
        assertEquals(1, Skf(project).run("init").exitCode)
    }

    @Test
    fun commandsOutsideAProjectExplainWhatIsMissing() {
        val result = skf.run("build")
        assertEquals(1, result.exitCode)
        assertTrue("could not find Skiff.toml" in result.stderr, result.stderr)
    }

    @Test
    fun addAndRemoveValidateTheirArguments() {
        skf.succeed("new", "app")
        val app = Skf(dir.resolve("app"))
        assertTrue("usage: skf add" in app.run("add").stderr)
        assertTrue("usage: skf remove" in app.run("remove").stderr)
        val missing = app.run("remove", "g:not-there")
        assertEquals(1, missing.exitCode)
        assertTrue("`g:not-there` is not a dependency of `app`" in missing.stderr, missing.stderr)
        assertTrue("invalid dependency" in app.run("add", "nonsense").stderr)
    }

    @Test
    fun cleanRemovesTheTargetDirectory() {
        skf.succeed("new", "app")
        val project = dir.resolve("app")
        Files.createDirectories(project.resolve("target/classes"))
        Files.writeString(project.resolve("target/classes/x"), "x")
        val result = Skf(project).succeed("clean")
        assertTrue("Removed" in result.stderr, result.stderr)
        assertFalse(Files.exists(project.resolve("target")))
    }

    @Test
    fun brokenManifestsAreReportedWithTheFileName() {
        skf.succeed("new", "app")
        val project = dir.resolve("app")
        Files.writeString(project.resolve("Skiff.toml"), "[package]\nname = ")
        val result = Skf(project).run("tree")
        assertEquals(1, result.exitCode)
        assertTrue("Skiff.toml:2:" in result.stderr, result.stderr)
    }
}
