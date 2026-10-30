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
    /** Only on the classpath of `test/`; see [testDependencies]. */
    val devDependencies: Map<ModuleId, String>,
    val repositories: List<Repository>,
    val plugins: List<PluginConfig> = emptyList(),
    val externalPlugins: List<ExternalPlugin> = emptyList(),
    val kapt: KaptConfig? = null,
    val ksp: KspConfig? = null,
) {
    val file: Path get() = dir.resolve(FILE_NAME)
    val lockFile: Path get() = dir.resolve(Lockfile.FILE_NAME)
    val sourceDir: Path get() = dir.resolve("src")
    val resourceDir: Path get() = dir.resolve("resources")
    val testDir: Path get() = dir.resolve("test")
    val targetDir: Path get() = dir.resolve("target")

    /** True if `test/` holds Kotlin sources. Only then are the test runner's own libraries resolved. */
    val hasTests: Boolean
        get() = Files.isDirectory(testDir) && Files.walk(testDir).use { paths -> paths.anyMatch { it.toString().endsWith(".kt") } }

    /** Everything the project compiles against: its dependencies plus the Kotlin standard library. */
    fun allDependencies(): Map<ModuleId, String> = mapOf(KOTLIN_STDLIB to kotlinVersion) + dependencies

    /** The roots of the test classpath: everything in [allDependencies], the dev-dependencies and the test runner. */
    fun testDependencies(): Map<ModuleId, String> = allDependencies() + devDependencies + testRunnerDependencies()

    /**
     * Roots that are resolved and locked on their own, per configuration, so they never change the versions the
     * project itself uses: the `[[kotlin.compiler-plugin]]` artifacts under [PLUGINS] and the annotation processors
     * under [KAPT] and [KSP]. Empty groups are left out.
     */
    fun isolatedRoots(): Map<String, Map<ModuleId, String>> = mapOf(
        PLUGINS to externalPlugins.associate { it.module to it.version },
        KAPT to kapt?.processors.orEmpty(),
        KSP to ksp?.processors.orEmpty(),
    ).filterValues { it.isNotEmpty() }

    /** Roots that exist only for testing, locked next to the real dependencies but kept off the build classpath. */
    private fun testRunnerDependencies(): Map<ModuleId, String> =
        if (!hasTests) emptyMap()
        else mapOf(KOTLIN_TEST_JUNIT5 to kotlinVersion, JUNIT_ENGINE to JUNIT_JUPITER_VERSION, JUNIT_CONSOLE to JUNIT_PLATFORM_VERSION)

    /** Changes whenever anything that affects resolution changes, so a stale lockfile can be detected. */
    fun resolutionFingerprint(): String = Hashing.sha256(buildString {
        allDependencies().toSortedMap().forEach { (module, version) -> append("$module=$version\n") }
        testDependencies().filter { (module, version) -> allDependencies()[module] != version }.toSortedMap().forEach { (module, version) -> append("dev:$module=$version\n") }
        for ((configuration, roots) in isolatedRoots().toSortedMap()) {
            roots.toSortedMap().forEach { (module, version) -> append("$configuration:$module=$version\n") }
        }
        repositories.forEach { append("repository=${it.url}\n") }
    })

    companion object {
        const val FILE_NAME = "Skiff.toml"
        const val DEFAULT_KOTLIN_VERSION = "2.4.21"
        const val DEFAULT_JVM_TARGET = "25"
        /** The lockfile configuration of the `[[kotlin.compiler-plugin]]` artifacts. */
        const val PLUGINS = "plugins"

        /** The lockfile configurations of the kapt and KSP annotation processors. */
        const val KAPT = "kapt"
        const val KSP = "ksp"
        const val JUNIT_JUPITER_VERSION = "5.11.4"
        const val JUNIT_PLATFORM_VERSION = "1.11.4"
        val KOTLIN_STDLIB = ModuleId("org.jetbrains.kotlin", "kotlin-stdlib")
        private val KOTLIN_TEST_JUNIT5 = ModuleId("org.jetbrains.kotlin", "kotlin-test-junit5")
        private val JUNIT_ENGINE = ModuleId("org.junit.jupiter", "junit-jupiter-engine")
        private val JUNIT_CONSOLE = ModuleId("org.junit.platform", "junit-platform-console")
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
            val dependencies = dependencyTable(toml, "dependencies")
            val devDependencies = dependencyTable(toml, "dev-dependencies")
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
                devDependencies = devDependencies,
                repositories = listOf(Repository.MAVEN_CENTRAL) + extraRepositories,
                plugins = plugins(kotlin),
                externalPlugins = externalPlugins(kotlin),
                kapt = kapt(toml),
                ksp = ksp(toml),
            )
        }

        /** Reads `[kapt-dependencies]` and `[kapt]`; null when no processors are listed. */
        private fun kapt(toml: Map<String, Any?>): KaptConfig? {
            val processors = dependencyTable(toml, "kapt-dependencies")
            val settings = toml.table("kapt").orEmpty()
            val unknown = settings.keys - setOf("options", "correct-error-types")
            if (unknown.isNotEmpty()) throw SkiffException("$FILE_NAME: unknown option '${unknown.first()}' in [kapt]")
            if (processors.isEmpty()) {
                if (settings.isNotEmpty()) throw SkiffException("$FILE_NAME: [kapt] is configured but [kapt-dependencies] lists no processors")
                return null
            }
            val correctErrorTypes = when (val value = settings["correct-error-types"]) {
                null -> true
                is Boolean -> value
                else -> throw SkiffException("$FILE_NAME: kapt.correct-error-types must be true or false")
            }
            return KaptConfig(processors, options(settings, "kapt"), correctErrorTypes)
        }

        /** Reads `[ksp-dependencies]` and `[ksp]`; null when no processors are listed. */
        private fun ksp(toml: Map<String, Any?>): KspConfig? {
            val processors = dependencyTable(toml, "ksp-dependencies")
            val settings = toml.table("ksp").orEmpty()
            val unknown = settings.keys - setOf("options", "version")
            if (unknown.isNotEmpty()) throw SkiffException("$FILE_NAME: unknown option '${unknown.first()}' in [ksp]")
            if (processors.isEmpty()) {
                if (settings.isNotEmpty()) throw SkiffException("$FILE_NAME: [ksp] is configured but [ksp-dependencies] lists no processors")
                return null
            }
            return KspConfig(processors, options(settings, "ksp"), settings.string("version", "ksp") ?: KspConfig.DEFAULT_VERSION)
        }

        /** Reads the `options = { key = "value" }` table of a processor section. */
        private fun options(settings: Map<String, Any?>, section: String): Map<String, String> = when (val value = settings["options"]) {
            null -> emptyMap()
            is Map<*, *> -> value.entries.associate { (key, option) ->
                if (option !is String && option !is Boolean && option !is Number) {
                    throw SkiffException("$FILE_NAME: $section.options.$key must be a string, number or boolean")
                }
                key.toString() to option.toString()
            }
            else -> throw SkiffException("$FILE_NAME: $section.options must be a table")
        }

        /** Reads the `[[kotlin.compiler-plugin]]` entries: `dependency = "group:artifact:version"` and optional `options`. */
        private fun externalPlugins(kotlin: Map<String, Any?>): List<ExternalPlugin> {
            val entries = when (val value = kotlin["compiler-plugin"]) {
                null -> return emptyList()
                is List<*> -> value
                else -> throw SkiffException("$FILE_NAME: kotlin.compiler-plugin must be an array of tables ([[kotlin.compiler-plugin]])")
            }
            return entries.map { entry ->
                @Suppress("UNCHECKED_CAST")
                val table = entry as? Map<String, Any?> ?: throw SkiffException("$FILE_NAME: kotlin.compiler-plugin must be an array of tables")
                val unknown = table.keys - setOf("dependency", "options")
                if (unknown.isNotEmpty()) throw SkiffException("$FILE_NAME: unknown option '${unknown.first()}' in [[kotlin.compiler-plugin]]")
                val dependency = table["dependency"] as? String
                    ?: throw SkiffException("$FILE_NAME: [[kotlin.compiler-plugin]] needs dependency = \"group:artifact:version\"")
                val parts = dependency.split(':')
                if (parts.size != 3 || parts.any { it.isBlank() }) {
                    throw SkiffException("$FILE_NAME: compiler plugin '$dependency' must look like group:artifact:version")
                }
                val options = when (val value = table["options"]) {
                    null -> emptyList()
                    is List<*> -> value.map { it as? String ?: throw SkiffException("$FILE_NAME: compiler-plugin options must be a list of strings") }
                    else -> throw SkiffException("$FILE_NAME: compiler-plugin options must be a list of strings")
                }
                ExternalPlugin(ModuleId(parts[0], parts[1]), parts[2], options)
            }
        }

        /** Reads `plugins = [...]` and the `[kotlin.<plugin>]` option tables of the `[kotlin]` table. */
        private fun plugins(kotlin: Map<String, Any?>): List<PluginConfig> {
            val names = when (val value = kotlin["plugins"]) {
                null -> emptyList()
                is List<*> -> value.map { it as? String ?: throw SkiffException("$FILE_NAME: kotlin.plugins must be a list of strings") }
                else -> throw SkiffException("$FILE_NAME: kotlin.plugins must be a list of strings")
            }
            val plugins = names.distinct().map { name ->
                val plugin = KotlinPlugin.byId(name) ?: throw SkiffException(
                    "$FILE_NAME: unknown compiler plugin '$name' (supported: ${KotlinPlugin.entries.joinToString { it.id }})",
                )
                val options = kotlin.table(name).orEmpty()
                val unknown = options.keys - if (plugin.takesAnnotations) setOf("annotations") else emptySet()
                if (unknown.isNotEmpty()) throw SkiffException("$FILE_NAME: unknown option '${unknown.first()}' in [kotlin.$name]")
                val annotations = when (val value = options["annotations"]) {
                    null -> emptyList()
                    is List<*> -> value.map { it as? String ?: throw SkiffException("$FILE_NAME: kotlin.$name.annotations must be a list of strings") }
                    else -> throw SkiffException("$FILE_NAME: kotlin.$name.annotations must be a list of strings")
                }
                if (plugin.takesAnnotations && annotations.isEmpty()) {
                    throw SkiffException("$FILE_NAME: the '$name' plugin needs `annotations = [...]` under [kotlin.$name]")
                }
                PluginConfig(plugin, annotations)
            }
            for (key in kotlin.keys) {
                if (KotlinPlugin.byId(key) != null && plugins.none { it.plugin.id == key }) {
                    throw SkiffException("$FILE_NAME: [kotlin.$key] is configured but '$key' is not listed in kotlin.plugins")
                }
            }
            return plugins
        }

        private fun dependencyTable(toml: Map<String, Any?>, name: String): Map<ModuleId, String> =
            toml.table(name).orEmpty().entries.associate { (key, value) ->
                val version = when (value) {
                    is String -> value
                    is Map<*, *> -> value["version"] as? String
                    else -> null
                } ?: throw SkiffException("$FILE_NAME: dependency '$key' needs a version string")
                ModuleId.parse(key) to version
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
