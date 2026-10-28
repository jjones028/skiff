package skiff.maven

import skiff.SkiffException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Resolves POMs from fixtures written into a throwaway offline cache, so no network is needed. */
class PomResolverTest {
    private val cache: Path = Files.createTempDirectory("skiff-pom-test")
    private val resolver = PomResolver(ArtifactStore(listOf(Repository.MAVEN_CENTRAL), offline = true, cacheDir = cache))

    @AfterTest
    fun cleanUp() {
        cache.toFile().deleteRecursively()
    }

    private fun pom(group: String, artifact: String, version: String, body: String) {
        val module = ModuleId(group, artifact)
        val file = cache.resolve(module.filePath(version, "pom"))
        Files.createDirectories(file.parent)
        Files.writeString(file, "<project><modelVersion>4.0.0</modelVersion>$body</project>")
    }

    private fun dependency(group: String, artifact: String, version: String? = null, extra: String = "") =
        "<dependency><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
            (version?.let { "<version>$it</version>" } ?: "") + extra + "</dependency>"

    private fun load(group: String, artifact: String, version: String) = resolver.load(ModuleId(group, artifact), version)

    @Test
    fun readsPlainDependencies() {
        pom(
            "g", "a", "1.0",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>" +
                "<dependencies>${dependency("x", "y", "2.0", "<scope>runtime</scope>")}</dependencies>",
        )
        val effective = load("g", "a", "1.0")
        assertEquals("jar", effective.packaging)
        val dependency = effective.dependencies.single()
        assertEquals(ModuleId("x", "y"), dependency.module)
        assertEquals("2.0", dependency.version)
        assertEquals("runtime", dependency.scope)
    }

    @Test
    fun interpolatesPropertiesAndProjectVersion() {
        pom(
            "g", "a", "1.0",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>" +
                "<properties><x.version>3.1</x.version></properties>" +
                "<dependencies>${dependency("x", "x", "\${x.version}")}${dependency("g", "sibling", "\${project.version}")}</dependencies>",
        )
        assertEquals(listOf("3.1", "1.0"), load("g", "a", "1.0").dependencies.map { it.version })
    }

    @Test
    fun inheritsFromTheParentChain() {
        pom(
            "g", "grandparent", "1",
            "<groupId>g</groupId><artifactId>grandparent</artifactId><version>1</version>" +
                "<properties><lib.version>9.9</lib.version></properties>" +
                "<dependencies>${dependency("inherited", "dep", "1")}</dependencies>",
        )
        pom(
            "g", "parent", "1",
            "<parent><groupId>g</groupId><artifactId>grandparent</artifactId><version>1</version></parent><artifactId>parent</artifactId>",
        )
        pom(
            "g", "child", "1",
            "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>child</artifactId>" +
                "<dependencies>${dependency("own", "dep", "\${lib.version}")}</dependencies>",
        )
        val dependencies = load("g", "child", "1").dependencies
        assertEquals(setOf(ModuleId("own", "dep"), ModuleId("inherited", "dep")), dependencies.map { it.module }.toSet())
        assertEquals("9.9", dependencies.first { it.module.group == "own" }.version)
    }

    @Test
    fun childPropertiesOverrideParentProperties() {
        pom(
            "g", "parent", "1",
            "<groupId>g</groupId><artifactId>parent</artifactId><version>1</version><properties><v>1.0</v></properties>",
        )
        pom(
            "g", "child", "1",
            "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>child</artifactId>" +
                "<properties><v>2.0</v></properties><dependencies>${dependency("x", "y", "\${v}")}</dependencies>",
        )
        assertEquals("2.0", load("g", "child", "1").dependencies.single().version)
    }

    @Test
    fun dependencyManagementFillsInVersionAndScope() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>" +
                "<dependencyManagement><dependencies>${dependency("x", "y", "4.0", "<scope>runtime</scope>")}</dependencies></dependencyManagement>" +
                "<dependencies>${dependency("x", "y")}</dependencies>",
        )
        val dependency = load("g", "a", "1").dependencies.single()
        assertEquals("4.0", dependency.version)
        assertEquals("runtime", dependency.scope)
    }

    @Test
    fun explicitVersionBeatsDependencyManagement() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>" +
                "<dependencyManagement><dependencies>${dependency("x", "y", "4.0")}</dependencies></dependencyManagement>" +
                "<dependencies>${dependency("x", "y", "5.0")}</dependencies>",
        )
        assertEquals("5.0", load("g", "a", "1").dependencies.single().version)
    }

    @Test
    fun importedBomsSupplyManagedVersions() {
        pom(
            "g", "bom", "1",
            "<groupId>g</groupId><artifactId>bom</artifactId><version>1</version><packaging>pom</packaging>" +
                "<dependencyManagement><dependencies>${dependency("x", "y", "7.0")}${dependency("x", "z", "8.0")}</dependencies></dependencyManagement>",
        )
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>" +
                "<dependencyManagement><dependencies>" +
                dependency("x", "z", "8.5") +
                dependency("g", "bom", "1", "<type>pom</type><scope>import</scope>") +
                "</dependencies></dependencyManagement>" +
                "<dependencies>${dependency("x", "y")}${dependency("x", "z")}</dependencies>",
        )
        val versions = load("g", "a", "1").dependencies.associate { it.module.artifact to it.version }
        assertEquals("7.0", versions["y"], "version comes from the BOM")
        assertEquals("8.5", versions["z"], "explicit management beats the BOM")
    }

    @Test
    fun managedExclusionsAreMergedIntoDependencies() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>" +
                "<dependencyManagement><dependencies>" +
                dependency("x", "y", "1", "<exclusions><exclusion><groupId>bad</groupId><artifactId>lib</artifactId></exclusion></exclusions>") +
                "</dependencies></dependencyManagement><dependencies>${dependency("x", "y")}</dependencies>",
        )
        assertEquals(listOf(ModuleId("bad", "lib")), load("g", "a", "1").dependencies.single().exclusions)
    }

    @Test
    fun exclusionsWithoutGroupOrArtifactBecomeWildcards() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencies>" +
                dependency("x", "y", "1", "<exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>") +
                "</dependencies>",
        )
        val exclusion = load("g", "a", "1").dependencies.single().exclusions.single()
        assertTrue(exclusion.matches(ModuleId("anything", "at-all")))
    }

    @Test
    fun readsOptionalFlagAndPackaging() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version><packaging>pom</packaging>" +
                "<dependencies>${dependency("x", "y", "1", "<optional>true</optional>")}</dependencies>",
        )
        val effective = load("g", "a", "1")
        assertEquals("pom", effective.packaging)
        assertTrue(effective.dependencies.single().optional)
    }

    @Test
    fun detectsGradleMetadataMarker() {
        pom("g", "a", "1", "<!-- do_not_remove: published-with-gradle-metadata --><groupId>g</groupId><artifactId>a</artifactId><version>1</version>")
        assertTrue(load("g", "a", "1").publishedWithGradleMetadata)
        pom("g", "b", "1", "<groupId>g</groupId><artifactId>b</artifactId><version>1</version>")
        assertEquals(false, load("g", "b", "1").publishedWithGradleMetadata)
    }

    @Test
    fun leavesUnknownPropertiesUninterpolated() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencies>${dependency("x", "y", "\${nope}")}</dependencies>",
        )
        assertEquals("\${nope}", load("g", "a", "1").dependencies.single().version)
    }

    @Test
    fun detectsImportCycles() {
        pom(
            "g", "a", "1",
            "<groupId>g</groupId><artifactId>a</artifactId><version>1</version><dependencyManagement><dependencies>" +
                dependency("g", "b", "1", "<type>pom</type><scope>import</scope>") + "</dependencies></dependencyManagement>",
        )
        pom(
            "g", "b", "1",
            "<groupId>g</groupId><artifactId>b</artifactId><version>1</version><dependencyManagement><dependencies>" +
                dependency("g", "a", "1", "<type>pom</type><scope>import</scope>") + "</dependencies></dependencyManagement>",
        )
        val error = assertFailsWith<SkiffException> { load("g", "a", "1") }
        assertTrue("cycle" in error.message!!, error.message)
    }

    @Test
    fun reportsMissingPomsWhenOffline() {
        val error = assertFailsWith<SkiffException> { load("g", "missing", "1") }
        assertTrue("g:missing:1" in error.message!!, error.message)
        assertTrue("offline" in error.message!!, error.message)
    }

    @Test
    fun resultsAreCached() {
        pom("g", "a", "1", "<groupId>g</groupId><artifactId>a</artifactId><version>1</version>")
        val first = load("g", "a", "1")
        Files.delete(cache.resolve(ModuleId("g", "a").filePath("1", "pom")))
        assertEquals(first, load("g", "a", "1"))
        assertNull(first.managed[ModuleId("x", "y")])
    }
}
