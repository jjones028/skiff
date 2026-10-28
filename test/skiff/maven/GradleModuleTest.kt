package skiff.maven

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GradleModuleTest {
    private val dir: Path = Files.createTempDirectory("skiff-module-test")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun redirect(json: String): GradleModule.Redirect? {
        val file = dir.resolve("lib.module")
        Files.writeString(file, json)
        return GradleModule.jvmRedirect(file)
    }

    private fun jvmVariant(platform: String? = "jvm", usage: String = "java-runtime", target: String = "lib-jvm") =
        """{"attributes":{"org.gradle.usage":"$usage"${platform?.let { ""","org.jetbrains.kotlin.platform.type":"$it"""" } ?: ""}},
            "available-at":{"group":"g","module":"$target","version":"1.2"}}"""

    @Test
    fun findsTheJvmRuntimeVariant() {
        assertEquals(
            GradleModule.Redirect(ModuleId("g", "lib-jvm"), "1.2"),
            redirect("""{"variants":[${jvmVariant()}]}"""),
        )
    }

    @Test
    fun skipsVariantsForOtherPlatformsAndUsages() {
        val json = """{"variants":[
            ${jvmVariant(platform = "native", target = "lib-native")},
            ${jvmVariant(usage = "java-api", target = "lib-api")},
            ${jvmVariant(target = "lib-jvm")}]}"""
        assertEquals("lib-jvm", redirect(json)?.module?.artifact)
    }

    @Test
    fun acceptsRuntimeVariantsWithoutAPlatformAttribute() {
        assertEquals("lib-jvm", redirect("""{"variants":[${jvmVariant(platform = null)}]}""")?.module?.artifact)
    }

    @Test
    fun returnsNullWhenTheRuntimeVariantHasNoRedirect() {
        assertNull(redirect("""{"variants":[{"attributes":{"org.gradle.usage":"java-runtime"}}]}"""))
    }

    @Test
    fun returnsNullWithoutVariantsOrForOtherDocuments() {
        assertNull(redirect("""{"formatVersion":"1.1"}"""))
        assertNull(redirect("""{"variants":[]}"""))
        assertNull(redirect("[]"))
    }
}
