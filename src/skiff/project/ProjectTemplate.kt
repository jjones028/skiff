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
        if (Files.list(dir.resolve("src")).use { it.findAny().isEmpty }) {
            writeIfAbsent(
                dir.resolve("src").resolve("Main.kt"),
                """
                fun main() {
                    println("Hello, world!")
                }

                """.trimIndent(),
            )
        }
        writeIfAbsent(dir.resolve(".gitignore"), "/target\n/.idea\n*.iml\n")
    }

    private fun writeIfAbsent(file: Path, content: String) {
        if (!Files.exists(file)) Files.writeString(file, content)
    }
}
