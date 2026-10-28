package skiff.project

import org.w3c.dom.Element
import skiff.maven.ModuleId
import skiff.maven.Repository
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Kotlin facet (language level, compiler plugins) and generated source roots of the IDE module. */
class IdeaProjectFacetTest {
    private val dir: Path = Files.createTempDirectory("skiff-idea-facet-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val base = Manifest(dir, "demo", "0.1.0", null, "2.4.21", "21", emptyMap(), emptyMap(), listOf(Repository.MAVEN_CENTRAL))

    private fun module(manifest: Manifest, pluginJars: List<Path> = emptyList()): Element {
        IdeaProject.generate(manifest, emptyList(), emptyList(), pluginJars)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(dir.resolve("demo.iml").toFile()).documentElement
    }

    private fun Element.all(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    private fun Element.facetString(name: String) = all("stringArg").firstOrNull { it.getAttribute("name") == name }?.getAttribute("arg")

    /** The values of an array argument: the element text for one value, the `<arg>` children for several. */
    private fun Element.facetArray(name: String): List<String>? {
        val array = all("arrayArg").firstOrNull { it.getAttribute("name") == name } ?: return null
        val args = array.all("args").single()
        val children = args.all("arg")
        return if (children.isEmpty()) listOf(args.textContent) else children.map { it.textContent }
    }

    @Test
    fun writesTheKotlinFacetWithLanguageLevelAndJvmTarget() {
        val module = module(base)
        val facet = module.all("facet").single()
        assertEquals("kotlin-language", facet.getAttribute("type"))
        val configuration = facet.all("configuration").single()
        assertEquals("5", configuration.getAttribute("version"))
        assertEquals("JVM 21", configuration.getAttribute("platform"))
        assertEquals("false", configuration.getAttribute("useProjectSettings"))
        assertEquals("21", module.facetString("jvmTarget"))
        assertEquals("2.4", module.facetString("languageVersion"))
        assertEquals("2.4", module.facetString("apiVersion"))
        assertEquals(null, module.facetArray("pluginClasspaths"), "no plugins, no plugin arguments")
        assertEquals(0, module.all("arrayArguments").size)
    }

    @Test
    fun listsCompilerPluginJarsAndOptions() {
        val manifest = base.copy(
            plugins = listOf(PluginConfig(KotlinPlugin.SERIALIZATION), PluginConfig(KotlinPlugin.ALLOPEN, listOf("a.Open", "b.Open"))),
            externalPlugins = listOf(ExternalPlugin(ModuleId("dev.example", "p"), "1", listOf("plugin:dev.example:flag=true"))),
        )
        val jars = listOf(dir.resolve("cache/serialization.jar"), dir.resolve("cache/allopen.jar"), dir.resolve("cache/p.jar"))
        val module = module(manifest, jars)
        assertEquals(jars.map { it.toString().replace('\\', '/') }, module.facetArray("pluginClasspaths"))
        assertEquals(
            listOf(
                "plugin:org.jetbrains.kotlin.allopen:annotation=a.Open",
                "plugin:org.jetbrains.kotlin.allopen:annotation=b.Open",
                "plugin:dev.example:flag=true",
            ),
            module.facetArray("pluginOptions"),
        )
    }

    @Test
    fun aSingleValueIsWrittenAsElementText() {
        val manifest = base.copy(plugins = listOf(PluginConfig(KotlinPlugin.COMPOSE)))
        val jar = dir.resolve("cache/compose.jar")
        val module = module(manifest, listOf(jar))
        val args = module.all("arrayArg").single().all("args").single()
        assertEquals(0, args.all("arg").size)
        assertEquals(listOf(jar.toString().replace('\\', '/')), module.facetArray("pluginClasspaths"))
        assertEquals(null, module.facetArray("pluginOptions"), "compose has no options")
    }

    @Test
    fun escapesXmlInOptions() {
        val manifest = base.copy(externalPlugins = listOf(ExternalPlugin(ModuleId("g", "a"), "1", listOf("plugin:x:expr=a<b&\"c\""))))
        assertEquals(listOf("plugin:x:expr=a<b&\"c\""), module(manifest, listOf(dir.resolve("a.jar"))).facetArray("pluginOptions"))
    }

    private fun sourceRoots(module: Element) = module.all("sourceFolder").associate {
        it.getAttribute("url").removePrefix("file://\$MODULE_DIR\$/") to it
    }

    @Test
    fun addsKspAndKaptOutputsAsGeneratedSourceRoots() {
        val manifest = base.copy(
            ksp = KspConfig(mapOf(ModuleId("k", "p") to "1")),
            kapt = KaptConfig(mapOf(ModuleId("a", "p") to "1")),
        )
        val roots = sourceRoots(module(manifest))
        for (generated in listOf("target/generated/ksp/kotlin", "target/generated/ksp/java", "target/generated/kapt/sources", "target/generated/kapt/kotlin")) {
            val root = assertNotNull(roots[generated], generated)
            assertEquals("true", root.getAttribute("generated"), generated)
            assertEquals("false", root.getAttribute("isTestSource"), generated)
            assertTrue(Files.isDirectory(dir.resolve(generated)), "$generated exists so the IDE picks it up")
        }
        assertEquals("false", roots.getValue("src").getAttribute("generated").ifEmpty { "false" })
        assertTrue(module(manifest).all("excludeFolder").any { it.getAttribute("url").endsWith("/target") }, "target stays excluded")
    }

    @Test
    fun onlyAddsTheRootsOfTheProcessorsInUse() {
        val kspOnly = sourceRoots(module(base.copy(ksp = KspConfig(mapOf(ModuleId("k", "p") to "1"))))).keys
        assertTrue("target/generated/ksp/kotlin" in kspOnly && "target/generated/kapt/sources" !in kspOnly)
        val none = sourceRoots(module(base)).keys
        assertEquals(setOf("src", "resources", "test"), none)
        assertFalse(Files.exists(dir.resolve("target/generated/kapt")), "kapt directories are not created without kapt")
    }
}
