package skiff.project

import skiff.json.JsonParser
import skiff.maven.Repository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VsCodeProjectTest {
    private val dir: Path = Files.createTempDirectory("skiff-vscode-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val manifest = Manifest(dir, "demo", "0.1.0", null, "2.4.21", "21", emptyMap(), emptyMap(), listOf(Repository.MAVEN_CENTRAL))

    private fun workspace(): Map<*, *> = JsonParser(Files.readString(dir.resolve("workspace.json"))).parse() as Map<*, *>

    @Test
    fun writesModuleSourceRootsAndLibraries() {
        Files.createDirectories(dir.resolve("src"))
        Files.createDirectories(dir.resolve("test"))
        val stdlib = dir.resolve("cache/a/kotlin-stdlib.jar")
        val junit = dir.resolve("cache/b/junit.jar")
        assertFalse(VsCodeProject.isGenerated(manifest))

        VsCodeProject.generate(manifest, listOf(stdlib), listOf(stdlib, junit))

        assertTrue(VsCodeProject.isGenerated(manifest))
        val module = (workspace()["modules"] as List<*>).single() as Map<*, *>
        assertEquals("demo", module["name"])
        val roots = ((module["contentRoots"] as List<*>).single() as Map<*, *>)["sourceRoots"] as List<*>
        assertEquals(listOf("src" to "java-source", "test" to "java-test"), roots.map { (it as Map<*, *>).let { r -> r["path"] to r["type"] } })
        val dependencies = (module["dependencies"] as List<*>).map { it as Map<*, *> }.filter { it["type"] == "library" }
        assertEquals(listOf("kotlin-stdlib" to "compile", "junit" to "test"), dependencies.map { it["name"] to it["scope"] })
        assertEquals(2, (workspace()["libraries"] as List<*>).size)
    }

    @Test
    fun givesJarsWithTheSameFileNameDistinctLibraryNames() {
        VsCodeProject.generate(manifest, listOf(dir.resolve("a/x.jar"), dir.resolve("b/x.jar")), emptyList())
        assertEquals(listOf("x", "x-2"), (workspace()["libraries"] as List<*>).map { (it as Map<*, *>)["name"] })
    }

    @Test
    fun writesLaunchConfigWithJavaExecOnlyOnce() {
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/Main.kt"), "fun main() {}")
        VsCodeProject.generate(manifest, emptyList(), emptyList())
        val launch = dir.resolve(".vscode/launch.json")
        val config = (JsonParser(Files.readString(launch)).parse() as Map<*, *>)["configurations"] as List<*>
        val entry = config.single() as Map<*, *>
        assertEquals("MainKt", entry["mainClass"])
        assertTrue((entry["javaExec"] as String).contains("java"))

        Files.writeString(launch, "custom")
        VsCodeProject.generate(manifest, emptyList(), emptyList())
        assertEquals("custom", Files.readString(launch))
    }
}
