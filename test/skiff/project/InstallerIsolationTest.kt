package skiff.project

import skiff.maven.ArtifactStore
import skiff.maven.ModuleId
import skiff.maven.Repository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Resolves fixture POMs and fake jars from an offline cache, so no network is needed. */
class InstallerIsolationTest {
    private val cache: Path = Files.createTempDirectory("skiff-installer-cache")
    private val project: Path = Files.createTempDirectory("skiff-installer-project")
    private val store = ArtifactStore(listOf(Repository.MAVEN_CENTRAL), offline = true, cacheDir = cache)

    @AfterTest
    fun cleanUp() {
        cache.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    private fun module(coordinates: String, vararg dependencies: String) {
        val (group, artifact, version) = coordinates.split(':')
        val id = ModuleId(group, artifact)
        val pom = cache.resolve(id.filePath(version, "pom"))
        Files.createDirectories(pom.parent)
        val deps = dependencies.joinToString("") {
            val (g, a, v) = it.split(':')
            "<dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency>"
        }
        Files.writeString(
            pom,
            "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
                "<version>$version</version><dependencies>$deps</dependencies></project>",
        )
        Files.writeString(cache.resolve(id.filePath(version, "jar")), "jar of $coordinates")
    }

    private fun manifest(plugin: ExternalPlugin?) = Manifest(
        dir = project,
        name = "demo",
        version = "0.1.0",
        main = null,
        kotlinVersion = "2.4.21",
        jvmTarget = "21",
        dependencies = mapOf(ModuleId("x", "lib") to "1"),
        devDependencies = emptyMap(),
        repositories = listOf(Repository.MAVEN_CENTRAL),
        externalPlugins = listOfNotNull(plugin),
    )

    private fun names(jars: List<Path>) = jars.map { it.fileName.toString() }.sorted()

    @Test
    fun aPluginsDependenciesNeverChangeTheProjectsVersions() {
        module("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        module("x:lib:1")
        module("x:lib:2")
        module("p:plugin:1", "x:lib:2", "org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        val manifest = manifest(ExternalPlugin(ModuleId("p", "plugin"), "1"))
        val installer = Installer(manifest, store)

        // The plugin wants lib 2, the project wants lib 1: each side keeps its own version.
        assertEquals(listOf("kotlin-stdlib-2.4.21.jar", "lib-1.jar"), names(installer.install()))
        assertEquals(listOf("lib-2.jar", "plugin-1.jar"), names(installer.pluginClasspath()))

        val lock = Lockfile.read(manifest.lockFile)!!
        assertEquals("1", lock.packages.first { it.module == ModuleId("x", "lib") }.version)
        assertEquals("2", lock.isolated.getValue(Manifest.PLUGINS).first { it.module == ModuleId("x", "lib") }.version)
    }

    @Test
    fun theIsolatedGroupIsPartOfTheFingerprintAndReusedWhileCurrent() {
        module("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
        module("x:lib:1")
        module("p:plugin:1")
        module("p:plugin:2")
        val withPlugin = manifest(ExternalPlugin(ModuleId("p", "plugin"), "1"))
        Installer(withPlugin, store).install()
        val lockText = Files.readString(withPlugin.lockFile)

        // Same manifest: the lockfile is not rewritten. A new plugin version or no plugin at all: it is.
        Installer(withPlugin, store).pluginClasspath(locked = true)
        assertEquals(lockText, Files.readString(withPlugin.lockFile))
        val newer = manifest(ExternalPlugin(ModuleId("p", "plugin"), "2"))
        assertEquals(listOf("plugin-2.jar"), names(Installer(newer, store).pluginClasspath()))
        Installer(manifest(null), store).install()
        assertEquals(emptyMap(), Lockfile.read(withPlugin.lockFile)!!.isolated)
        assertEquals(emptyList(), Installer(manifest(null), store).pluginClasspath())
    }
}
