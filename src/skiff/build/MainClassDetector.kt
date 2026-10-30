package skiff.build

import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the class holding a top-level `fun main`, preferring a file named `Main.kt`.
 * Kotlin compiles top-level functions in `Foo.kt` into a class `FooKt` in the file's package.
 */
object MainClassDetector {
    private val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val MAIN_FUNCTION = Regex("""^fun\s+main\s*\(""", RegexOption.MULTILINE)
    private val JVM_NAME = Regex("""@file:JvmName\(\s*"([^"]+)"\s*\)""")

    /** The source file holding the main function, or null. */
    fun findFile(sourceDir: Path, sources: List<Path>): Path? =
        sources.filter { MAIN_FUNCTION.containsMatchIn(Files.readString(it)) }
            .sortedWith(compareBy({ !it.fileName.toString().equals("Main.kt", ignoreCase = true) }, { sourceDir.relativize(it).nameCount }))
            .firstOrNull()

    fun detect(sourceDir: Path, sources: List<Path>): String? {
        val chosen = findFile(sourceDir, sources) ?: return null
        val text = Files.readString(chosen)
        val packageName = PACKAGE.find(text)?.groupValues?.get(1)
        val className = JVM_NAME.find(text)?.groupValues?.get(1)
            ?: (chosen.fileName.toString().removeSuffix(".kt").replaceFirstChar { it.uppercaseChar() } + "Kt")
        return if (packageName == null) className else "$packageName.$className"
    }
}
