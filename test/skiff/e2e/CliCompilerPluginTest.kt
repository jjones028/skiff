package skiff.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds a project that needs the serialization and allopen compiler plugins. Needs the network the first time. */
class CliCompilerPluginTest {
    private val dir: Path = Files.createTempDirectory("skf-plugins")
    private val project = dir.resolve("app")
    private val app = Skf(project)

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val manifest = """
        [package]
        name = "app"
        version = "0.1.0"

        [kotlin]
        version = "2.4.21"
        plugins = ["serialization", "allopen"]

        [kotlin.allopen]
        annotations = ["Open"]

        [dependencies]
        "org.jetbrains.kotlinx:kotlinx-serialization-json" = "1.7.3"
    """.trimIndent()

    private val main = """
        import kotlinx.serialization.Serializable
        import kotlinx.serialization.encodeToString
        import kotlinx.serialization.json.Json

        annotation class Open

        @Serializable
        data class User(val name: String, val age: Int)

        @Open
        class Base

        fun main() {
            println(Json.encodeToString(User("ada", 36)))
            println("base final: " + java.lang.reflect.Modifier.isFinal(Base::class.java.modifiers))
        }
    """.trimIndent()

    @Test
    fun compilesWithSerializationAndAllopen() {
        Skf(dir).succeed("new", "app")
        Files.writeString(project.resolve("Skiff.toml"), manifest)
        Files.writeString(project.resolve("src/Main.kt"), main)

        val run = app.succeed("run")
        assertEquals(listOf("{\"name\":\"ada\",\"age\":36}", "base final: false"), run.stdout.trim().lines().map { it.trim() })

        // Tests are compiled with the same plugins.
        Files.createDirectories(project.resolve("test"))
        Files.writeString(
            project.resolve("test/UserTest.kt"),
            """
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.decodeFromString
            import kotlin.test.Test
            import kotlin.test.assertEquals

            class UserTest {
                @Test fun roundTrips() = assertEquals(User("bob", 7), Json.decodeFromString<User>("{\"name\":\"bob\",\"age\":7}"))
            }
            """.trimIndent(),
        )
        val test = app.succeed("test")
        assertTrue("1 tests successful" in test.stdout, test.stdout)

        // Changing the plugin configuration must trigger a rebuild rather than reuse stale classes.
        Files.writeString(project.resolve("Skiff.toml"), manifest.replace("\"allopen\"", "\"allopen\", \"noarg\"").replace("[dependencies]", "[kotlin.noarg]\nannotations = [\"Open\"]\n\n[dependencies]"))
        assertTrue("Compiling" in app.succeed("build").stderr)
    }

    @Test
    fun appliesPluginsGivenByCoordinates() {
        Skf(dir).succeed("new", "app")
        Files.writeString(
            project.resolve("Skiff.toml"),
            """
            [package]
            name = "app"

            [kotlin]
            version = "2.4.21"

            [[kotlin.compiler-plugin]]
            dependency = "org.jetbrains.kotlin:kotlin-allopen-compiler-plugin-embeddable:2.4.21"
            options = ["plugin:org.jetbrains.kotlin.allopen:annotation=Open"]
            """.trimIndent(),
        )
        Files.writeString(
            project.resolve("src/Main.kt"),
            """
            annotation class Open

            @Open
            class Base

            fun main() {
                println("base final: " + java.lang.reflect.Modifier.isFinal(Base::class.java.modifiers))
            }
            """.trimIndent(),
        )
        assertEquals("base final: false", app.succeed("run").stdout.trim())

        // The plugin is locked, so --locked works, but it is not a dependency of the program itself.
        app.succeed("install", "--locked")
        assertTrue("kotlin-allopen-compiler-plugin-embeddable" in Files.readString(project.resolve("Skiff.lock")))
        assertTrue("allopen" !in app.succeed("classpath").stdout)
        assertTrue("allopen" !in app.succeed("tree").stdout)

        // Changing the options rebuilds.
        Files.writeString(project.resolve("Skiff.toml"), Files.readString(project.resolve("Skiff.toml")).replace("=Open", "=Other"))
        assertTrue("Compiling" in app.succeed("build").stderr)
    }

    @Test
    fun compilesComposeFunctions() {
        Skf(dir).succeed("new", "app")
        Files.writeString(
            project.resolve("Skiff.toml"),
            """
            [package]
            name = "app"

            [kotlin]
            version = "2.4.21"
            plugins = ["compose"]

            [dependencies]
            "org.jetbrains.compose.runtime:runtime" = "1.7.3"

            [repositories]
            google = "https://maven.google.com"
            """.trimIndent(),
        )
        Files.writeString(
            project.resolve("src/Main.kt"),
            """
            import androidx.compose.runtime.Composable

            @Composable
            fun Greeting(name: String) {}

            fun main() {
                val method = Class.forName("MainKt").declaredMethods.first { it.name == "Greeting" }
                println("Greeting parameters: " + method.parameterCount)
            }
            """.trimIndent(),
        )
        // The compose compiler adds the Composer and a change flag to every composable.
        assertEquals("Greeting parameters: 3", app.succeed("run").stdout.trim())
    }

    @Test
    fun reportsManifestMistakesBeforeCompiling() {
        Skf(dir).succeed("new", "app")
        Files.writeString(project.resolve("Skiff.toml"), manifest.replace("\"serialization\"", "\"lombok\""))
        val result = app.run("build")
        assertEquals(1, result.exitCode)
        assertTrue("unknown compiler plugin 'lombok'" in result.stderr, result.stderr)
    }
}
