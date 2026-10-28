package skiff.project

import skiff.SkiffException
import skiff.maven.ModuleId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestPluginsTest {
    private val dir: Path = Files.createTempDirectory("skiff-plugins-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun load(kotlinSection: String): Manifest {
        Files.writeString(dir.resolve(Manifest.FILE_NAME), "[package]\nname = \"demo\"\n\n[kotlin]\n$kotlinSection")
        return Manifest.load(dir)
    }

    @Test
    fun noPluginsByDefault() {
        assertEquals(emptyList(), load("version = \"2.4.21\"").plugins)
    }

    @Test
    fun readsPluginsAndTheirAnnotations() {
        val manifest = load(
            """
            plugins = ["serialization", "allopen", "noarg", "sam-with-receiver"]

            [kotlin.allopen]
            annotations = ["com.example.Open", "com.example.Service"]
            [kotlin.noarg]
            annotations = ["com.example.Entity"]
            [kotlin.sam-with-receiver]
            annotations = ["com.example.Dsl"]
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                PluginConfig(KotlinPlugin.SERIALIZATION),
                PluginConfig(KotlinPlugin.ALLOPEN, listOf("com.example.Open", "com.example.Service")),
                PluginConfig(KotlinPlugin.NOARG, listOf("com.example.Entity")),
                PluginConfig(KotlinPlugin.SAM_WITH_RECEIVER, listOf("com.example.Dsl")),
            ),
            manifest.plugins,
        )
    }

    @Test
    fun turnsAnnotationsIntoCompilerOptions() {
        val config = PluginConfig(KotlinPlugin.ALLOPEN, listOf("a.Open", "b.Open"))
        assertEquals(
            listOf("plugin:org.jetbrains.kotlin.allopen:annotation=a.Open", "plugin:org.jetbrains.kotlin.allopen:annotation=b.Open"),
            config.options(),
        )
        assertEquals(emptyList(), PluginConfig(KotlinPlugin.SERIALIZATION).options())
    }

    @Test
    fun duplicatePluginNamesAreCollapsed() {
        assertEquals(1, load("plugins = [\"serialization\", \"serialization\"]").plugins.size)
    }

    @Test
    fun composeNeedsNoOptions() {
        assertEquals(listOf(PluginConfig(KotlinPlugin.COMPOSE)), load("plugins = [\"compose\"]").plugins)
        assertEquals("androidx.compose.compiler.plugins.kotlin", KotlinPlugin.COMPOSE.compilerPluginId)
    }

    @Test
    fun readsExternalPlugins() {
        val manifest = load(
            """
            [[kotlin.compiler-plugin]]
            dependency = "dev.example:my-plugin:1.2.3"
            options = ["plugin:dev.example.my:flag=true", "plugin:dev.example.my:name=x"]

            [[kotlin.compiler-plugin]]
            dependency = "dev.example:other-plugin:2.0"
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ExternalPlugin(ModuleId("dev.example", "my-plugin"), "1.2.3", listOf("plugin:dev.example.my:flag=true", "plugin:dev.example.my:name=x")),
                ExternalPlugin(ModuleId("dev.example", "other-plugin"), "2.0"),
            ),
            manifest.externalPlugins,
        )
        assertEquals(
            mapOf(ModuleId("dev.example", "my-plugin") to "1.2.3", ModuleId("dev.example", "other-plugin") to "2.0"),
            manifest.pluginDependencies(),
        )
    }

    @Test
    fun externalPluginsAreLockedButNotOnTheClasspath() {
        val plain = load("version = \"2.4.21\"")
        val withPlugin = load("[[kotlin.compiler-plugin]]\ndependency = \"dev.example:my-plugin:1.2.3\"")
        val module = ModuleId("dev.example", "my-plugin")
        assertEquals(false, module in withPlugin.allDependencies())
        assertEquals(false, module in withPlugin.testDependencies())
        assertEquals("1.2.3", withPlugin.lockRoots()[module])
        assertEquals(plain.allDependencies(), withPlugin.allDependencies())
        assertTrue(plain.resolutionFingerprint() != withPlugin.resolutionFingerprint())
    }

    @Test
    fun rejectsMalformedExternalPlugins() {
        for (section in listOf(
            "[[kotlin.compiler-plugin]]\noptions = [\"a\"]",
            "[[kotlin.compiler-plugin]]\ndependency = \"g:a\"",
            "[[kotlin.compiler-plugin]]\ndependency = \"g:a:\"",
            "[[kotlin.compiler-plugin]]\ndependency = 3",
            "[[kotlin.compiler-plugin]]\ndependency = \"g:a:1\"\noptions = \"x\"",
            "[[kotlin.compiler-plugin]]\ndependency = \"g:a:1\"\noptions = [1]",
            "[[kotlin.compiler-plugin]]\ndependency = \"g:a:1\"\nbogus = 1",
        )) {
            assertFailsWith<SkiffException>(section) { load(section) }
        }
        assertFailsWith<SkiffException> { load("compiler-plugin = \"g:a:1\"") }
    }

    @Test
    fun pluginArtifactsLiveNextToTheCompiler() {
        assertEquals(
            "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable",
            KotlinPlugin.SERIALIZATION.module.toString(),
        )
        assertEquals(KotlinPlugin.NOARG, KotlinPlugin.byId("noarg"))
        assertEquals(null, KotlinPlugin.byId("nope"))
    }

    @Test
    fun rejectsUnknownPluginsAndListsTheSupportedOnes() {
        val error = assertFailsWith<SkiffException> { load("plugins = [\"lombok\"]") }
        assertTrue("unknown compiler plugin 'lombok'" in error.message!!, error.message)
        assertTrue("serialization, allopen, noarg, sam-with-receiver, compose" in error.message!!, error.message)
    }

    @Test
    fun annotationPluginsNeedAnnotations() {
        val error = assertFailsWith<SkiffException> { load("plugins = [\"allopen\"]") }
        assertTrue("needs `annotations" in error.message!!, error.message)
    }

    @Test
    fun rejectsOptionTablesForPluginsThatAreNotEnabled() {
        val error = assertFailsWith<SkiffException> {
            load("plugins = [\"serialization\"]\n[kotlin.noarg]\nannotations = [\"a.B\"]")
        }
        assertTrue("[kotlin.noarg] is configured but 'noarg' is not listed" in error.message!!, error.message)
    }

    @Test
    fun rejectsUnknownOptionsAndWrongTypes() {
        assertFailsWith<SkiffException> { load("plugins = [\"serialization\"]\n[kotlin.serialization]\nannotations = [\"a.B\"]") }
        assertFailsWith<SkiffException> { load("plugins = [\"noarg\"]\n[kotlin.noarg]\nannotations = [\"a.B\"]\nfoo = 1") }
        assertFailsWith<SkiffException> { load("plugins = \"serialization\"") }
        assertFailsWith<SkiffException> { load("plugins = [1]") }
        assertFailsWith<SkiffException> { load("plugins = [\"noarg\"]\n[kotlin.noarg]\nannotations = \"a.B\"") }
    }
}
