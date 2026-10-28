package skiff.maven

import skiff.SkiffException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Resolves fixture POMs from a throwaway offline cache, so no network is needed. */
class DependencyResolverTest {
    private val cache: Path = Files.createTempDirectory("skiff-resolver-test")
    private val store = ArtifactStore(listOf(Repository.MAVEN_CENTRAL), offline = true, cacheDir = cache)

    @AfterTest
    fun cleanUp() {
        cache.toFile().deleteRecursively()
    }

    private fun id(text: String) = ModuleId.parse(text)

    /** A dependency written as `group:artifact:version`, with extra XML (scope, exclusions...) appended. */
    private fun dep(coordinates: String, extra: String = ""): String {
        val (group, artifact, version) = coordinates.split(':')
        return "<dependency><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version>$extra</dependency>"
    }

    private fun exclusion(coordinates: String): String {
        val (group, artifact) = coordinates.split(':')
        return "<exclusions><exclusion><groupId>$group</groupId><artifactId>$artifact</artifactId></exclusion></exclusions>"
    }

    private fun write(module: ModuleId, version: String, extension: String, content: String) {
        val file = cache.resolve(module.filePath(version, extension))
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun pom(coordinates: String, vararg dependencies: String, extra: String = "") {
        val (group, artifact, version) = coordinates.split(':')
        write(
            ModuleId(group, artifact), version, "pom",
            "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
                "<version>$version</version>$extra<dependencies>${dependencies.joinToString("")}</dependencies></project>",
        )
    }

    private fun resolve(vararg roots: String): List<ResolvedModule> =
        DependencyResolver(store).resolve(roots.associate { spec -> spec.substringBeforeLast(':').let(::id) to spec.substringAfterLast(':') })

    private fun List<ResolvedModule>.versions() = associate { it.module.toString() to it.version }

    @Test
    fun resolvesTransitiveDependencies() {
        pom("g:a:1", dep("g:b:1"))
        pom("g:b:1", dep("g:c:1"))
        pom("g:c:1")
        val result = resolve("g:a:1")
        assertEquals(mapOf("g:a" to "1", "g:b" to "1", "g:c" to "1"), result.versions())
        assertEquals(listOf(id("g:b")), result.first { it.module == id("g:a") }.dependencies)
        assertEquals(emptyList(), result.first { it.module == id("g:c") }.dependencies)
    }

    @Test
    fun resultIsSortedByModule() {
        pom("z:z:1")
        pom("a:a:1")
        pom("m:m:1")
        assertEquals(listOf("a:a", "m:m", "z:z"), resolve("z:z:1", "a:a:1", "m:m:1").map { it.module.toString() })
    }

    @Test
    fun highestVersionWinsOverNearest() {
        pom("g:app:1", dep("g:lib:1"), dep("g:mid:1"))
        pom("g:mid:1", dep("g:deep:1"))
        pom("g:deep:1", dep("g:lib:3"))
        pom("g:lib:1")
        pom("g:lib:3")
        assertEquals("3", resolve("g:app:1").versions().getValue("g:lib"))
    }

    @Test
    fun rootVersionCanBeRaisedByATransitiveRequest() {
        pom("g:a:1", dep("g:b:2"))
        pom("g:b:1")
        pom("g:b:2")
        assertEquals("2", resolve("g:a:1", "g:b:1").versions().getValue("g:b"))
    }

    @Test
    fun raisingAVersionReplacesItsOldDependencies() {
        pom("g:a:1", dep("g:b:1"), dep("g:d:1"))
        pom("g:d:1", dep("g:b:2"))
        pom("g:b:1", dep("g:old:1"))
        pom("g:b:2", dep("g:new:1"))
        pom("g:old:1")
        pom("g:new:1")
        val versions = resolve("g:a:1").versions()
        assertEquals("2", versions["g:b"])
        assertTrue("g:new" in versions)
        assertFalse("g:old" in versions, "dependencies of the replaced version must not linger")
    }

    @Test
    fun onlyCompileAndRuntimeScopesAreFollowed() {
        pom(
            "g:a:1",
            dep("g:compile:1"),
            dep("g:runtime:1", "<scope>runtime</scope>"),
            dep("g:test:1", "<scope>test</scope>"),
            dep("g:provided:1", "<scope>provided</scope>"),
            dep("g:system:1", "<scope>system</scope>"),
        )
        listOf("compile", "runtime", "test", "provided", "system").forEach { pom("g:$it:1") }
        assertEquals(setOf("g:a", "g:compile", "g:runtime"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun optionalDependenciesAreSkipped() {
        pom("g:a:1", dep("g:opt:1", "<optional>true</optional>"), dep("g:req:1"))
        pom("g:opt:1")
        pom("g:req:1")
        assertEquals(setOf("g:a", "g:req"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun exclusionRemovesTheModuleAndItsSubtree() {
        pom("g:a:1", dep("g:b:1", exclusion("g:c")))
        pom("g:b:1", dep("g:c:1"))
        pom("g:c:1", dep("g:d:1"))
        pom("g:d:1")
        assertEquals(setOf("g:a", "g:b"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun wildcardExclusionRemovesEveryTransitiveDependency() {
        pom("g:a:1", dep("g:b:1", exclusion("*:*")))
        pom("g:b:1", dep("g:c:1"))
        pom("g:c:1")
        assertEquals(setOf("g:a", "g:b"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun exclusionsAccumulateAlongThePath() {
        pom("g:a:1", dep("g:b:1", exclusion("g:x")))
        pom("g:b:1", dep("g:c:1", exclusion("g:y")))
        pom("g:c:1", dep("g:x:1"), dep("g:y:1"), dep("g:z:1"))
        listOf("x", "y", "z").forEach { pom("g:$it:1") }
        assertEquals(setOf("g:a", "g:b", "g:c", "g:z"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun exclusionOnOnePathDoesNotHideAnotherPath() {
        pom("g:a:1", dep("g:b:1", exclusion("g:c")), dep("g:d:1"))
        pom("g:b:1", dep("g:c:1"))
        pom("g:d:1", dep("g:c:1"))
        pom("g:c:1")
        assertTrue("g:c" in resolve("g:a:1").versions())
    }

    @Test
    fun dependencyCyclesTerminate() {
        pom("g:a:1", dep("g:b:1"))
        pom("g:b:1", dep("g:a:1"))
        assertEquals(setOf("g:a", "g:b"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun versionRangesPickTheHighestPublishedVersionInside() {
        pom("g:a:1", dep("g:lib:[1.0,2.0)"))
        val metadata = cache.resolve("g/lib/maven-metadata.xml")
        Files.createDirectories(metadata.parent)
        Files.writeString(
            metadata,
            "<metadata><versioning><versions>" +
                listOf("0.9", "1.0", "1.5", "2.0").joinToString("") { "<version>$it</version>" } +
                "</versions></versioning></metadata>",
        )
        pom("g:lib:1.5")
        assertEquals("1.5", resolve("g:a:1").versions().getValue("g:lib"))
    }

    @Test
    fun skipsClassifierAndNonJarDependencies() {
        pom(
            "g:a:1",
            dep("g:tests:1", "<classifier>tests</classifier>"),
            dep("g:war:1", "<type>war</type>"),
            dep("g:ok:1"),
        )
        pom("g:ok:1")
        assertEquals(setOf("g:a", "g:ok"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun skipsDependenciesWithoutAUsableVersion() {
        pom(
            "g:a:1",
            "<dependency><groupId>g</groupId><artifactId>unversioned</artifactId></dependency>",
            dep("g:unresolved:\${nope}"),
            dep("g:ok:1"),
        )
        pom("g:ok:1")
        assertEquals(setOf("g:a", "g:ok"), resolve("g:a:1").map { it.module.toString() }.toSet())
    }

    @Test
    fun pomPackagingModulesHaveNoJar() {
        pom("g:bom:1", extra = "<packaging>pom</packaging>")
        pom("g:a:1", dep("g:bom:1"))
        val result = resolve("g:a:1")
        assertFalse(result.first { it.module == id("g:bom") }.hasJar)
        assertTrue(result.first { it.module == id("g:a") }.hasJar)
    }

    @Test
    fun multiplatformRootsRedirectToTheirJvmModule() {
        pom("g:lib:1", dep("g:common:1"), extra = "<!-- do_not_remove: published-with-gradle-metadata -->")
        write(
            id("g:lib"), "1", "module",
            """{"variants":[
                {"attributes":{"org.gradle.usage":"java-api","org.jetbrains.kotlin.platform.type":"native"}},
                {"attributes":{"org.gradle.usage":"java-runtime","org.jetbrains.kotlin.platform.type":"jvm"},
                 "available-at":{"group":"g","module":"lib-jvm","version":"1"}}]}""",
        )
        pom("g:lib-jvm:1", dep("g:jvmonly:1"))
        pom("g:jvmonly:1")
        pom("g:common:1")
        val result = resolve("g:lib:1")
        val root = result.first { it.module == id("g:lib") }
        assertFalse(root.hasJar)
        assertEquals(listOf(id("g:lib-jvm")), root.dependencies)
        assertEquals(setOf("g:lib", "g:lib-jvm", "g:jvmonly"), result.map { it.module.toString() }.toSet())
    }

    @Test
    fun gradleMetadataWithoutARedirectUsesThePomDependencies() {
        pom("g:lib:1", dep("g:b:1"), extra = "<!-- published-with-gradle-metadata -->")
        write(id("g:lib"), "1", "module", """{"variants":[{"attributes":{"org.gradle.usage":"java-runtime"}}]}""")
        pom("g:b:1")
        val result = resolve("g:lib:1")
        assertTrue(result.first { it.module == id("g:lib") }.hasJar)
        assertEquals(setOf("g:lib", "g:b"), result.map { it.module.toString() }.toSet())
        assertNull(result.firstOrNull { it.module == id("g:lib-jvm") })
    }

    @Test
    fun missingPomsAreReported() {
        pom("g:a:1", dep("g:missing:1"))
        val error = assertFailsWith<SkiffException> { resolve("g:a:1") }
        assertTrue("g:missing:1" in error.message!!, error.message)
    }
}
