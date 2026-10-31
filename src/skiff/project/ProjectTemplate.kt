package skiff.project

import java.nio.file.Files
import java.nio.file.Path

/** Files written by `skf new` and `skf init`. Existing files are never overwritten. */
object ProjectTemplate {
    fun create(dir: Path, name: String) {
        Files.createDirectories(dir.resolve("src"))
        writeIfAbsent(
            dir.resolve(Manifest.FILE_NAME),
            """
            [package]
            name = "$name"
            version = "0.1.0"

            [kotlin]
            version = "${Manifest.DEFAULT_KOTLIN_VERSION}"
            jvm-target = "${Manifest.DEFAULT_JVM_TARGET}"

            [dependencies]

            """.trimIndent(),
        )
        // The sample test exercises the sample source, so both are written only into an empty project.
        val test = dir.resolve("test")
        if (isEmpty(dir.resolve("src")) && isEmpty(test)) {
            writeIfAbsent(
                dir.resolve("src").resolve("Main.kt"),
                """
                fun greeting() = "Hello, world!"

                fun main() {
                    println(greeting())
                }

                """.trimIndent(),
            )
            Files.createDirectories(test)
            writeIfAbsent(
                test.resolve("MainTest.kt"),
                """
                import kotlin.test.Test
                import kotlin.test.assertEquals

                class MainTest {
                    @Test
                    fun greets() = assertEquals("Hello, world!", greeting())
                }

                """.trimIndent(),
            )
        }
        writeIfAbsent(dir.resolve(".gitignore"), "/target\n/.idea\n*.iml\n/workspace.json\n/.vscode\n")
    }

    private fun isEmpty(dir: Path): Boolean = !Files.exists(dir) || Files.list(dir).use { it.findAny().isEmpty }

    private fun writeIfAbsent(file: Path, content: String) {
        if (!Files.exists(file)) Files.writeString(file, content)
    }
}
