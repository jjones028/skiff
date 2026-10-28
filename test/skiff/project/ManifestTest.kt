package skiff.project

import skiff.SkiffException
import skiff.maven.ModuleId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ManifestTest {
    private val dir: Path = Files.createTempDirectory("skiff-manifest-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun load(toml: String): Manifest {
        Files.writeString(dir.resolve(Manifest.FILE_NAME), toml.trimIndent())
        return Manifest.load(dir)
    }

    private val okhttp = ModuleId("com.squareup.okhttp3", "okhttp")

    @Test
    fun appliesDefaults() {
        val manifest = load(
            """
            [package]
            name = "demo"
            """,
        )
        assertEquals("demo", manifest.name)
        assertEquals("0.1.0", manifest.version)
        assertEquals(Manifest.DEFAULT_KOTLIN_VERSION, manifest.kotlinVersion)
        assertEquals(Manifest.DEFAULT_JVM_TARGET, manifest.jvmTarget)
        assertEquals(null, manifest.main)
        assertTrue(manifest.dependencies.isEmpty())
    }

    @Test
    fun readsDependenciesInStringAndTableForm() {
        val manifest = load(
            """
            [package]
            name = "demo"
            [dependencies]
            "com.squareup.okhttp3:okhttp" = "4.12.0"
            "org.jetbrains.kotlinx:kotlinx-coroutines-core" = { version = "1.8.0" }
            [dev-dependencies]
            "org.mockito:mockito-core" = "5.0.0"
            """,
        )
        assertEquals("4.12.0", manifest.dependencies[okhttp])
        assertEquals("1.8.0", manifest.dependencies[ModuleId("org.jetbrains.kotlinx", "kotlinx-coroutines-core")])
        assertEquals(mapOf(ModuleId("org.mockito", "mockito-core") to "5.0.0"), manifest.devDependencies)
    }

    @Test
    fun addsMavenCentralFirstThenExtraRepositories() {
        val manifest = load(
            """
            [package]
            name = "demo"
            [repositories]
            internal = "https://nexus.example.com/repo"
            """,
        )
        assertEquals(listOf("maven-central", "internal"), manifest.repositories.map { it.name })
    }

    @Test
    fun stdlibIsAnImplicitDependency() {
        val manifest = load("[package]\nname = \"demo\"\n[kotlin]\nversion = \"2.0.0\"")
        assertEquals(mapOf(Manifest.KOTLIN_STDLIB to "2.0.0"), manifest.allDependencies())
    }

    @Test
    fun rejectsInvalidManifests() {
        assertFailsWith<SkiffException> { load("[kotlin]\nversion = \"2.0.0\"") }
        assertFailsWith<SkiffException> { load("[package]\nversion = \"1\"") }
        assertFailsWith<SkiffException> { load("[package]\nname = \"bad name!\"") }
        assertFailsWith<SkiffException> { load("[package]\nname = 3") }
        assertFailsWith<SkiffException> { load("package = 1") }
        assertFailsWith<SkiffException> { load("[package]\nname = \"x\"\n[dependencies]\n\"a:b\" = 1") }
        assertFailsWith<SkiffException> { load("[package]\nname = \"x\"\n[dependencies]\nnot-a-module = \"1\"") }
    }

    @Test
    fun fingerprintTracksDependenciesKotlinAndRepositories() {
        val base = "[package]\nname = \"demo\"\n"
        val fingerprint = load(base).resolutionFingerprint()
        assertEquals(fingerprint, load(base + "# comment\n").resolutionFingerprint())
        assertNotEquals(fingerprint, load(base + "[dependencies]\n\"a:b\" = \"1\"").resolutionFingerprint())
        assertNotEquals(fingerprint, load(base + "[dev-dependencies]\n\"a:b\" = \"1\"").resolutionFingerprint())
        assertNotEquals(fingerprint, load(base + "[kotlin]\nversion = \"2.0.0\"").resolutionFingerprint())
        assertNotEquals(fingerprint, load(base + "[repositories]\nx = \"https://x.example\"").resolutionFingerprint())
        assertEquals(fingerprint, load("[package]\nname = \"demo\"\nversion = \"9.9.9\"").resolutionFingerprint())
    }

    @Test
    fun testRunnerIsOnlyAddedWhenThereAreTestSources() {
        val manifest = load("[package]\nname = \"demo\"")
        assertFalse(manifest.hasTests)
        assertEquals(manifest.allDependencies(), manifest.testDependencies())
        val withoutTests = manifest.resolutionFingerprint()

        Files.createDirectories(dir.resolve("test"))
        Files.writeString(dir.resolve("test/FooTest.kt"), "class FooTest")
        assertTrue(manifest.hasTests)
        val runner = manifest.testDependencies().keys - manifest.allDependencies().keys
        assertEquals(
            setOf("kotlin-test-junit5", "junit-jupiter-engine", "junit-platform-console"),
            runner.map { it.artifact }.toSet(),
        )
        assertNotEquals(withoutTests, manifest.resolutionFingerprint())
    }

    @Test
    fun findSearchesParentDirectories() {
        Files.writeString(dir.resolve(Manifest.FILE_NAME), "[package]\nname = \"demo\"")
        val nested = Files.createDirectories(dir.resolve("src/deep/er"))
        assertEquals("demo", Manifest.find(nested).name)
        assertEquals(dir.toRealPath(), Manifest.find(nested).dir.toRealPath())
    }
}
