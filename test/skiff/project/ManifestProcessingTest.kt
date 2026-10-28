package skiff.project

import skiff.SkiffException
import skiff.maven.ModuleId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestProcessingTest {
    private val dir: Path = Files.createTempDirectory("skiff-processing-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun load(extra: String): Manifest {
        Files.writeString(dir.resolve(Manifest.FILE_NAME), "[package]\nname = \"demo\"\n\n$extra")
        return Manifest.load(dir)
    }

    private val processor = ModuleId("com.google.auto.service", "auto-service")

    @Test
    fun noProcessingByDefault() {
        val manifest = load("")
        assertNull(manifest.kapt)
        assertNull(manifest.ksp)
        assertEquals(emptyMap(), manifest.isolatedRoots())
    }

    @Test
    fun readsKaptProcessorsAndSettings() {
        val manifest = load(
            """
            [kapt-dependencies]
            "com.google.auto.service:auto-service" = "1.1.1"

            [kapt]
            correct-error-types = false
            options = { "room.schemaLocation" = "build/schemas", verbose = true, retries = 3 }
            """.trimIndent(),
        )
        assertEquals(
            KaptConfig(
                processors = mapOf(processor to "1.1.1"),
                options = mapOf("room.schemaLocation" to "build/schemas", "verbose" to "true", "retries" to "3"),
                correctErrorTypes = false,
            ),
            manifest.kapt,
        )
    }

    @Test
    fun kaptDefaultsToCorrectErrorTypes() {
        val manifest = load("[kapt-dependencies]\n\"com.google.auto.service:auto-service\" = \"1.1.1\"")
        assertEquals(KaptConfig(mapOf(processor to "1.1.1")), manifest.kapt)
        assertTrue(manifest.kapt!!.correctErrorTypes)
    }

    @Test
    fun readsKspProcessorsAndSettings() {
        val manifest = load(
            """
            [ksp-dependencies]
            "dev.zacsweers.autoservice:auto-service-ksp" = { version = "1.2.0" }

            [ksp]
            version = "2.3.5"
            options = { "autoservice.verify" = "true" }
            """.trimIndent(),
        )
        assertEquals(
            KspConfig(mapOf(ModuleId("dev.zacsweers.autoservice", "auto-service-ksp") to "1.2.0"), mapOf("autoservice.verify" to "true"), "2.3.5"),
            manifest.ksp,
        )
    }

    @Test
    fun kspVersionHasADefault() {
        val manifest = load("[ksp-dependencies]\n\"a:b\" = \"1\"")
        assertEquals(KspConfig.DEFAULT_VERSION, manifest.ksp!!.version)
    }

    @Test
    fun processorsAreResolvedAsIsolatedGroups() {
        val manifest = load(
            """
            [dependencies]
            "x:lib" = "1"

            [kapt-dependencies]
            "k:proc" = "2"

            [ksp-dependencies]
            "s:proc" = "3"

            [[kotlin.compiler-plugin]]
            dependency = "p:plugin:4"
            """.trimIndent(),
        )
        assertEquals(
            mapOf(
                Manifest.PLUGINS to mapOf(ModuleId("p", "plugin") to "4"),
                Manifest.KAPT to mapOf(ModuleId("k", "proc") to "2"),
                Manifest.KSP to mapOf(ModuleId("s", "proc") to "3"),
            ),
            manifest.isolatedRoots(),
        )
        // None of them is a dependency of the program itself.
        assertEquals(setOf(Manifest.KOTLIN_STDLIB, ModuleId("x", "lib")), manifest.allDependencies().keys)
        assertEquals(manifest.allDependencies(), manifest.testDependencies())
    }

    @Test
    fun processorsAndTheirSettingsChangeTheFingerprint() {
        val base = load("").resolutionFingerprint()
        val kapt = load("[kapt-dependencies]\n\"a:b\" = \"1\"").resolutionFingerprint()
        val ksp = load("[ksp-dependencies]\n\"a:b\" = \"1\"").resolutionFingerprint()
        assertEquals(3, setOf(base, kapt, ksp).size, "the same coordinates in different groups are different locks")
        assertNotEquals(kapt, load("[kapt-dependencies]\n\"a:b\" = \"2\"").resolutionFingerprint())
    }

    @Test
    fun rejectsSettingsWithoutProcessors() {
        for (section in listOf("kapt", "ksp")) {
            val error = assertFailsWith<SkiffException> { load("[$section]\noptions = { a = \"b\" }") }
            assertTrue("[$section] is configured but [$section-dependencies] lists no processors" in error.message!!, error.message)
        }
    }

    @Test
    fun rejectsMalformedProcessorSections() {
        val deps = "[kapt-dependencies]\n\"a:b\" = \"1\"\n"
        assertFailsWith<SkiffException> { load("$deps[kapt]\nbogus = 1") }
        assertFailsWith<SkiffException> { load("$deps[kapt]\ncorrect-error-types = \"yes\"") }
        assertFailsWith<SkiffException> { load("$deps[kapt]\noptions = \"x\"") }
        assertFailsWith<SkiffException> { load("$deps[kapt]\noptions = { a = [1] }") }
        assertFailsWith<SkiffException> { load("[ksp-dependencies]\n\"a:b\" = \"1\"\n[ksp]\nbogus = 1") }
        assertFailsWith<SkiffException> { load("[ksp-dependencies]\n\"a:b\" = \"1\"\n[ksp]\nversion = 3") }
        assertFailsWith<SkiffException> { load("[kapt-dependencies]\nnot-a-module = \"1\"") }
        assertFailsWith<SkiffException> { load("[ksp-dependencies]\n\"a:b\" = 1") }
    }
}
