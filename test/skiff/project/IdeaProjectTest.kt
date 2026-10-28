package skiff.project

import skiff.maven.Repository
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeaProjectTest {
    private val dir: Path = Files.createTempDirectory("skiff-idea-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val manifest = Manifest(dir, "demo", "0.1.0", null, "2.4.21", "21", emptyMap(), emptyMap(), listOf(Repository.MAVEN_CENTRAL))

    private fun xml(file: Path) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).documentElement

    private fun libraryUrls(root: org.w3c.dom.Element, scope: String?): List<String> {
        val entries = root.getElementsByTagName("orderEntry")
        return (0 until entries.length).map { entries.item(it) as org.w3c.dom.Element }
            .filter { it.getAttribute("type") == "module-library" && it.getAttribute("scope") == (scope ?: "") }
            .map { (it.getElementsByTagName("root").item(0) as org.w3c.dom.Element).getAttribute("url") }
    }

    @Test
    fun writesAWellFormedModuleWithSourceRootsAndLibraries() {
        val stdlib = dir.resolve("cache/kotlin-stdlib-2.4.21.jar")
        val junit = dir.resolve("cache/junit & co.jar")
        val written = IdeaProject.generate(manifest, listOf(stdlib), listOf(stdlib, junit))

        assertEquals(listOf(dir.resolve("demo.iml"), dir.resolve(".idea/modules.xml")), written)
        val module = xml(dir.resolve("demo.iml"))
        assertEquals("JAVA_MODULE", module.getAttribute("type"))
        val sources = module.getElementsByTagName("sourceFolder")
        val roots = (0 until sources.length).associate { (sources.item(it) as org.w3c.dom.Element).let { e -> e.getAttribute("url") to e.getAttribute("isTestSource") } }
        assertEquals("false", roots["file://\$MODULE_DIR\$/src"])
        assertEquals("true", roots["file://\$MODULE_DIR\$/test"])

        assertEquals(listOf("jar://" + stdlib.toString().replace('\\', '/') + "!/"), libraryUrls(module, null))
        assertEquals(listOf("jar://" + junit.toString().replace('\\', '/') + "!/"), libraryUrls(module, "TEST"))
    }

    @Test
    fun registersTheModuleOnce() {
        IdeaProject.generate(manifest, emptyList(), emptyList())
        val second = IdeaProject.generate(manifest, emptyList(), emptyList())
        assertEquals(listOf(dir.resolve("demo.iml")), second)
        val text = Files.readString(dir.resolve(".idea/modules.xml"))
        assertEquals(1, Regex("demo\\.iml\" filepath").findAll(text).count())
        xml(dir.resolve(".idea/modules.xml"))
    }

    @Test
    fun addsToAnExistingModulesFileWithoutDroppingOtherModules() {
        Files.createDirectories(dir.resolve(".idea"))
        Files.writeString(
            dir.resolve(".idea/modules.xml"),
            "<project version=\"4\"><component name=\"ProjectModuleManager\"><modules>" +
                "<module fileurl=\"file://\$PROJECT_DIR\$/other.iml\" filepath=\"\$PROJECT_DIR\$/other.iml\" />" +
                "</modules></component></project>",
        )
        IdeaProject.generate(manifest, emptyList(), emptyList())
        val text = Files.readString(dir.resolve(".idea/modules.xml"))
        assertTrue("other.iml" in text && "demo.iml" in text)
        assertFalse(xml(dir.resolve(".idea/modules.xml")).getElementsByTagName("module").length != 2)
    }
}
