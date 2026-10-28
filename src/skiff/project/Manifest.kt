package skiff.project

import skiff.Hashing
import skiff.SkiffException
import skiff.maven.ModuleId
import skiff.maven.Repository
import skiff.toml.Toml
import java.nio.file.Files
import java.nio.file.Path

/** A parsed `Skiff.toml`. */
data class Manifest(
    val dir: Path,
    val name: String,
    val version: String,
    /** Fully qualified main class; detected from the sources when not set. */
    val main: String?,
    val kotlinVersion: String,
    val jvmTarget: String,
    val dependencies: Map<ModuleId, String>,
    val repositories: List<Repository>,
) {
    val file: Path get() = dir.resolve(FILE_NAME)
    val lockFile: Path get() = dir.resolve(Lockfile.FILE_NAME)
    val sourceDir: Path get() = dir.resolve("src")
    val resourceDir: Path get() = dir.resolve("resources")
    val targetDir: Path get() = dir.resolve("target")

    /** Everything the project compiles against: its dependencies plus the Kotlin standard library. */
    fun allDependencies(): Map<ModuleId, String> = mapOf(KOTLIN_STDLIB to kotlinVersion) + dependencies

    /** Changes whenever anything that affects resolution changes, so a stale lockfile can be detected. */
    fun resolutionFingerprint(): String = Hashing.sha256(buildString {
        allDependencies().toSortedMap().forEach { (module, version) -> append("$module=$version\n") }
        repositories.forEach { append("repository=${it.url}\n") }
    })

    companion object {
        const val FILE_NAME = "Skiff.toml"
        const val DEFAULT_KOTLIN_VERSION = "2.4.21"
        const val DEFAULT_JVM_TARGET = "21"
        val KOTLIN_STDLIB = ModuleId("org.jetbrains.kotlin", "kotlin-stdlib")
        private val NAME = Regex("[A-Za-z0-9_-]+")

        /** Loads the manifest of the project containing [start], searching parent directories like Cargo does. */
        fun find(start: Path): Manifest {
            var dir: Path? = start.toAbsolutePath()
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve(FILE_NAME))) return load(dir)
                dir = dir.parent
            }
            throw SkiffException("could not find $FILE_NAME in $start or any parent directory")
        }

        fun load(dir: Path): Manifest {
            val file = dir.resolve(FILE_NAME)
            val toml = Toml.parse(Files.readString(file), FILE_NAME)
            val pkg = toml.table("package") ?: throw SkiffException("$FILE_NAME: missing [package] table")
            val kotlin = toml.table("kotlin").orEmpty()

            val name = pkg.string("name", "package") ?: throw SkiffException("$FILE_NAME: missing package.name")
            if (!NAME.matches(name)) {
                throw SkiffException("$FILE_NAME: package.name '$name' may only contain letters, digits, '-' and '_'")
            }
            val dependencies = toml.table("dependencies").orEmpty().entries.associate { (key, value) ->
                val version = when (value) {
                    is String -> value
                    is Map<*, *> -> value["version"] as? String
                    else -> null
                } ?: throw SkiffException("$FILE_NAME: dependency '$key' needs a version string")
                ModuleId.parse(key) to version
            }
            val extraRepositories = toml.table("repositories").orEmpty().map { (key, value) ->
                Repository(key, value as? String ?: throw SkiffException("$FILE_NAME: repository '$key' needs a URL"))
            }
            return Manifest(
                dir = dir,
                name = name,
                version = pkg.string("version", "package") ?: "0.1.0",
                main = pkg.string("main", "package"),
                kotlinVersion = kotlin.string("version", "kotlin") ?: DEFAULT_KOTLIN_VERSION,
                jvmTarget = kotlin.string("jvm-target", "kotlin") ?: DEFAULT_JVM_TARGET,
                dependencies = dependencies,
                repositories = listOf(Repository.MAVEN_CENTRAL) + extraRepositories,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.table(key: String): Map<String, Any?>? = when (val value = this[key]) {
            null -> null
            is Map<*, *> -> value as Map<String, Any?>
            else -> throw SkiffException("$FILE_NAME: '$key' must be a table")
        }

        private fun Map<String, Any?>.string(key: String, table: String): String? = when (val value = this[key]) {
            null -> null
            is String -> value
            else -> throw SkiffException("$FILE_NAME: $table.$key must be a string")
        }
    }
}
