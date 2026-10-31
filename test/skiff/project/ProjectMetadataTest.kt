package skiff.project

import skiff.json.JsonParser
import skiff.json.JsonWriter
import skiff.maven.ModuleId
import skiff.maven.Repository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectMetadataTest {
    private val dir: Path = Files.createTempDirectory("skiff-metadata-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val stdlib = ModuleId("org.jetbrains.kotlin", "kotlin-stdlib")
    private val gson = ModuleId("com.google.code.gson", "gson")
    private val junit = ModuleId("org.junit.jupiter", "junit-jupiter-api")
    private val opentest = ModuleId("org.opentest4j", "opentest4j")

    private val manifest = Manifest(
        dir, "demo", "1.2.3", null, "2.4.21", "21",
        dependencies = mapOf(gson to "2.11.0"),
        devDependencies = mapOf(junit to "5.11.4"),
        repositories = listOf(Repository.MAVEN_CENTRAL),
    )

    private val lock = Lockfile(
        "x",
        listOf(
            LockedPackage(gson, "2.11.0", "a", listOf(stdlib)),
            LockedPackage(junit, "5.11.4", "b", listOf(opentest)),
            LockedPackage(opentest, "1.3.0", "c", emptyList()),
            LockedPackage(stdlib, "2.4.21", "d", emptyList()),
        ),
    )

    private fun metadata(): Map<*, *> {
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/Main.kt"), "fun main() {}\n")
        val jars = lock.packages.associate { it.module to dir.resolve("cache/${it.module.artifact}.jar") }
        // Through the writer and parser, so the test also proves the result is plain JSON.
        return JsonParser(JsonWriter.write(ProjectMetadata.build(manifest, lock, jars, emptyList(), emptyList(), emptyList()))).parse() as Map<*, *>
    }

    @Test
    fun describesThePackageKotlinSettingsAndRoots() {
        val metadata = metadata()
        assertEquals(1L, metadata["format-version"])
        val pkg = metadata["package"] as Map<*, *>
        assertEquals("demo", pkg["name"])
        assertEquals("1.2.3", pkg["version"])
        assertEquals("MainKt", pkg["main-class"])
        assertEquals("$dir/Skiff.toml".replace('\\', '/'), pkg["manifest"])
        val kotlin = metadata["kotlin"] as Map<*, *>
        assertEquals("2.4.21", kotlin["version"])
        assertEquals("21", kotlin["jvm-target"])
        val roots = (metadata["roots"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("source" to true, "resource" to false, "test-source" to false), roots.map { it["kind"] to it["exists"] })
    }

    @Test
    fun marksScopeAndDirectDependencies() {
        val dependencies = (metadata()["dependencies"] as List<*>).map { it as Map<*, *> }.associateBy { it["id"] }
        fun flags(module: ModuleId) = dependencies.getValue(module.toString()).let { it["scope"] to it["direct"] }
        assertEquals("compile" to true, flags(gson))
        assertEquals("compile" to true, flags(stdlib))
        assertEquals("test" to true, flags(junit))
        assertEquals("test" to false, flags(opentest))
        assertEquals(listOf("org.jetbrains.kotlin:kotlin-stdlib"), dependencies.getValue(gson.toString())["dependencies"])
        assertEquals("$dir/cache/gson.jar".replace('\\', '/'), dependencies.getValue(gson.toString())["jar"])
    }

    @Test
    fun leavesOutAnnotationProcessingThatIsNotConfigured() {
        val processing = metadata()["annotation-processing"] as Map<*, *>
        assertNull(processing["kapt"])
        assertNull(processing["ksp"])
    }
}
