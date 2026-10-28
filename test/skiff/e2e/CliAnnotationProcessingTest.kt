package skiff.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Builds projects that use kapt and KSP processors from Maven Central. Needs the network the first time. */
class CliAnnotationProcessingTest {
    private val dir: Path = Files.createTempDirectory("skf-processing")
    private val project = dir.resolve("app")
    private val app = Skf(project)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val serviceMain = """
        import com.google.auto.service.AutoService
        import java.util.ServiceLoader

        interface Greeter { fun greet(): String }

        @AutoService(Greeter::class)
        class Hello : Greeter {
            override fun greet() = "hi"
        }

        fun main() {
            println(ServiceLoader.load(Greeter::class.java).map { it.greet() })
        }
    """.trimIndent()

    private fun setUp(manifest: String, main: String) {
        Skf(dir).succeed("new", "app")
        Files.writeString(project.resolve("Skiff.toml"), "[package]\nname = \"app\"\n\n[kotlin]\nversion = \"2.4.21\"\n\n" + manifest.trimIndent() + "\n")
        Files.writeString(project.resolve("src/Main.kt"), main)
    }

    @Test
    fun runsKaptProcessors() {
        setUp(
            """
            [dependencies]
            "com.google.auto.service:auto-service-annotations" = "1.1.1"

            [kapt-dependencies]
            "com.google.auto.service:auto-service" = "1.1.1"
            """,
            serviceMain,
        )
        val run = app.succeed("run")
        assertEquals("[hi]", run.stdout.trim())
        assertTrue("Processing annotations with kapt" in run.stderr, run.stderr)
        // The IDE module lists kapt's output directories as generated source roots (and no KSP ones).
        app.succeed("idea")
        val module = Files.readString(project.resolve("app.iml"))
        assertTrue("target/generated/kapt/sources\" isTestSource=\"false\" generated=\"true\"" in module, module)
        assertFalse("target/generated/ksp" in module, module)
        // The service file the processor wrote ended up in the jar.
        java.util.zip.ZipFile(project.resolve("target/app-0.1.0.jar").toFile()).use { jar ->
            assertTrue(jar.getEntry("META-INF/services/Greeter") != null)
        }
        assertTrue("Fresh" in app.succeed("build").stderr)
    }

    @Test
    fun compilesJavaThatKaptGenerates() {
        setUp(
            """
            [dependencies]
            "com.google.auto.factory:auto-factory" = "1.1.0"
            "javax.inject:javax.inject" = "1"

            [kapt-dependencies]
            "com.google.auto.factory:auto-factory" = "1.1.0"
            """,
            """
            import com.google.auto.factory.AutoFactory

            @AutoFactory
            class Widget(val name: String)

            fun main() {
                println("made " + WidgetFactory().create("gizmo").name)
            }
            """.trimIndent(),
        )
        assertEquals("made gizmo", app.succeed("run").stdout.trim())
        assertTrue(Files.isRegularFile(project.resolve("target/generated/kapt/sources/WidgetFactory.java")))
    }

    @Test
    fun runsKspProcessors() {
        setUp(
            """
            [dependencies]
            "com.google.auto.service:auto-service-annotations" = "1.1.1"

            [ksp-dependencies]
            "dev.zacsweers.autoservice:auto-service-ksp" = "1.2.0"
            """,
            serviceMain,
        )
        val run = app.succeed("run")
        assertEquals("[hi]", run.stdout.trim())
        assertTrue("Processing annotations with KSP" in run.stderr, run.stderr)
        java.util.zip.ZipFile(project.resolve("target/app-0.1.0.jar").toFile()).use { jar ->
            assertTrue(jar.getEntry("META-INF/services/Greeter") != null)
        }
    }

    @Test
    fun processorsAreLockedSeparatelyAndStayOffTheClasspath() {
        setUp(
            """
            [dependencies]
            "com.google.auto.service:auto-service-annotations" = "1.1.1"

            [ksp-dependencies]
            "dev.zacsweers.autoservice:auto-service-ksp" = "1.2.0"
            """,
            serviceMain,
        )
        app.succeed("build")
        val lock = Files.readString(project.resolve("Skiff.lock"))
        // The processor needs kotlin-stdlib 2.0.0 while the project uses 2.4.21: both are locked, in their own groups.
        assertTrue("configuration = \"ksp\"" in lock, lock)
        assertTrue(Regex("id = \"org.jetbrains.kotlin:kotlin-stdlib\"\\s+version = \"2.4.21\"").containsMatchIn(lock), lock)
        assertTrue(Regex("configuration = \"ksp\"\\s+id = \"org.jetbrains.kotlin:kotlin-stdlib\"\\s+version = \"2.0.0\"").containsMatchIn(lock), lock)

        val classpath = app.succeed("classpath").stdout
        assertFalse("kotlinpoet" in classpath || "auto-service-ksp" in classpath, classpath)
        assertFalse("kotlinpoet" in app.succeed("tree").stdout)
        app.succeed("install", "--locked")
    }

    @Test
    fun changingProcessorSettingsRebuilds() {
        setUp(
            """
            [dependencies]
            "com.google.auto.service:auto-service-annotations" = "1.1.1"

            [kapt-dependencies]
            "com.google.auto.service:auto-service" = "1.1.1"
            """,
            serviceMain,
        )
        app.succeed("build")
        Files.writeString(project.resolve("Skiff.toml"), Files.readString(project.resolve("Skiff.toml")) + "\n[kapt]\noptions = { debug = \"true\" }\n")
        assertTrue("Compiling" in app.succeed("build").stderr)
    }

    @Test
    fun processorErrorsFailTheBuild() {
        setUp(
            """
            [dependencies]
            "com.google.auto.service:auto-service-annotations" = "1.1.1"

            [kapt-dependencies]
            "com.google.auto.service:auto-service" = "1.1.1"
            """,
            // AutoService needs a class that actually implements the service.
            serviceMain.replace("class Hello : Greeter", "class Hello"),
        )
        val result = app.run("build")
        assertEquals(1, result.exitCode)
        assertTrue("could not compile" in result.stderr, result.stderr)
    }
}
