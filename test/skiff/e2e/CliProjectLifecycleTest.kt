package skiff.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives a whole project through the CLI: create, run, add a dependency, lock, build a fat jar, test, generate
 * IDE files, remove. Needs the network the first time (compiler, gson and the JUnit jars land in `~/.skiff`).
 * The steps share one project because every compile costs seconds.
 */
class CliProjectLifecycleTest {
    private val dir: Path = Files.createTempDirectory("skf-e2e")
    private val skf = Skf(dir)
    private val project = dir.resolve("app")
    private val app = Skf(project)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun java(vararg args: String): String {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(java, *args).directory(project.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, process.waitFor(), output)
        return output.trim()
    }

    @Test
    fun buildsRunsLocksAndTestsAProject() {
        skf.succeed("new", "app")

        // Run, then run again without changes: the second build must be skipped.
        val first = app.succeed("run", "--", "a", "b")
        assertEquals("Hello, world!", first.stdout.trim())
        assertTrue("Compiling" in first.stderr && "Finished" in first.stderr, first.stderr)
        assertTrue("Running `app a b`" in first.stderr, first.stderr)
        assertTrue("Fresh" in app.succeed("run").stderr)
        assertTrue(Files.isRegularFile(project.resolve("Skiff.lock")))
        assertTrue(Files.isRegularFile(project.resolve("target/app-0.1.0.jar")))

        // Add a dependency and use it.
        val added = app.succeed("add", "com.google.code.gson:gson@2.11.0")
        assertTrue("Adding com.google.code.gson:gson v2.11.0" in added.stderr, added.stderr)
        assertTrue("\"com.google.code.gson:gson\" = \"2.11.0\"" in Files.readString(project.resolve("Skiff.toml")))
        Files.writeString(
            project.resolve("src/Main.kt"),
            "fun main(args: Array<String>) {\n    println(com.google.gson.Gson().toJson(mapOf(\"args\" to args.size)))\n}\n",
        )
        assertEquals("{\"args\":2}", app.succeed("run", "--", "x", "y").stdout.trim())

        // The tree and the classpath agree with the lockfile.
        val tree = app.succeed("tree").stdout
        assertTrue(tree.startsWith("app v0.1.0"), tree)
        assertTrue("com.google.code.gson:gson v2.11.0" in tree, tree)
        assertTrue("org.jetbrains.kotlin:kotlin-stdlib" in tree, tree)
        val classpath = app.succeed("classpath").stdout.trim().split(java.io.File.pathSeparator)
        assertTrue(classpath.any { it.endsWith("gson-2.11.0.jar") }, classpath.toString())

        // --locked passes while the lockfile is current, fails when the manifest moves on, and nothing changes.
        app.succeed("install", "--locked")
        val lock = Files.readString(project.resolve("Skiff.lock"))
        val manifest = Files.readString(project.resolve("Skiff.toml"))
        Files.writeString(project.resolve("Skiff.toml"), manifest.replace("2.11.0", "2.10.1"))
        val stale = app.run("install", "--locked")
        assertEquals(1, stale.exitCode)
        assertTrue("needs to be updated but --locked was given" in stale.stderr, stale.stderr)
        assertEquals(lock, Files.readString(project.resolve("Skiff.lock")))
        Files.writeString(project.resolve("Skiff.toml"), manifest)

        // A fat jar runs on its own, and `run --offline` works from the cache.
        app.succeed("build", "--fat")
        assertEquals("{\"args\":0}", java("-jar", "target/app-0.1.0-all.jar"))
        assertEquals("{\"args\":1}", app.succeed("run", "--offline", "--", "z").stdout.trim())

        // Tests: a passing run, then a failing one that must fail the command.
        Files.createDirectories(project.resolve("test"))
        Files.writeString(
            project.resolve("test/MainTest.kt"),
            "import kotlin.test.Test\nimport kotlin.test.assertEquals\n\nclass MainTest {\n" +
                "    @Test fun gsonIsOnTheTestClasspath() = assertEquals(\"[1]\", com.google.gson.Gson().toJson(listOf(1)))\n}\n",
        )
        val passing = app.succeed("test")
        assertTrue("gsonIsOnTheTestClasspath" in passing.stdout, passing.stdout)
        assertTrue("1 tests successful" in passing.stdout, passing.stdout)
        // Building for release must not have pulled the test runner into the fat jar.
        app.succeed("build", "--fat")
        java.util.zip.ZipFile(project.resolve("target/app-0.1.0-all.jar").toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()
            assertTrue(entries.any { it.startsWith("com/google/gson/") })
            assertFalse(entries.any { it.startsWith("org/junit/") }, "the fat jar must not contain the test runner")
        }

        Files.writeString(
            project.resolve("test/FailingTest.kt"),
            "import kotlin.test.Test\nimport kotlin.test.assertEquals\n\nclass FailingTest {\n    @Test fun broken() = assertEquals(1, 2)\n}\n",
        )
        val failing = app.run("test")
        assertEquals(1, failing.exitCode, failing.stdout)
        assertTrue("1 tests failed" in failing.stdout, failing.stdout)
        Files.delete(project.resolve("test/FailingTest.kt"))

        // IDE files list the test-only jars with TEST scope and none of the main jars that way.
        app.succeed("idea")
        val module = Files.readString(project.resolve("app.iml"))
        assertTrue("gson-2.11.0.jar" in module)
        assertTrue(Regex("scope=\"TEST\">\\s*<library name=\"junit-platform-console").containsMatchIn(module), module)
        assertFalse(Regex("scope=\"TEST\">\\s*<library name=\"gson").containsMatchIn(module))

        // Remove the dependency again: the lockfile forgets it.
        app.succeed("remove", "com.google.code.gson:gson")
        assertFalse("gson" in Files.readString(project.resolve("Skiff.toml")))
        assertFalse("gson" in Files.readString(project.resolve("Skiff.lock")))
    }
}
