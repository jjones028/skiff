package skiff.build

import skiff.maven.ArtifactStore
import skiff.maven.Repository
import skiff.project.Manifest
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationProcessingTest {
    private val dir: Path = Files.createTempDirectory("skiff-ap-test")
    private val cache: Path = Files.createTempDirectory("skiff-ap-cache")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
        cache.toFile().deleteRecursively()
    }

    private val processing: AnnotationProcessing by lazy {
        val manifest = Manifest(dir, "demo", "0.1.0", null, "2.4.21", "21", emptyMap(), emptyMap(), listOf(Repository.MAVEN_CENTRAL))
        AnnotationProcessing(manifest, Toolchain(ArtifactStore(listOf(Repository.MAVEN_CENTRAL), offline = true, cacheDir = cache)))
    }

    /** Reads the encoding back the way kapt does. */
    private fun decode(encoded: String): Map<String, String> {
        val input = ObjectInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded)))
        return buildMap { repeat(input.readInt()) { put(input.readUTF(), input.readUTF()) } }
    }

    @Test
    fun encodesProcessorOptionsTheWayKaptReadsThem() {
        val options = linkedMapOf("kapt.kotlin.generated" to "C:\\out\\kotlin", "room.schemaLocation" to "schemas dir", "empty" to "", "ünï" to "çödé")
        assertEquals(options, decode(processing.encodeOptions(options)))
    }

    @Test
    fun encodesNoOptions() {
        assertEquals(emptyMap(), decode(processing.encodeOptions(emptyMap())))
    }

    @Test
    fun keepsGeneratedFilesUnderTheTargetDirectory() {
        assertEquals(dir.resolve("target/generated/kapt-stage"), processing.kaptStageDir)
        assertEquals(dir.resolve("target/generated/kapt/kotlin"), processing.kaptKotlinDir)
    }
}
