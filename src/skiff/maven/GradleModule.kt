package skiff.maven

import skiff.json.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads Gradle Module Metadata (`.module` files). Kotlin Multiplatform libraries publish a root module
 * (e.g. `kotlinx-coroutines-core`) whose JVM variant lives in another module (`kotlinx-coroutines-core-jvm`).
 */
object GradleModule {
    /** Where the JVM runtime variant of a module lives, if the metadata points to a different module. */
    data class Redirect(val module: ModuleId, val version: String)

    fun jvmRedirect(file: Path): Redirect? {
        val root = JsonParser(Files.readString(file)).parse() as? Map<*, *> ?: return null
        val variants = root["variants"] as? List<*> ?: return null
        for (variant in variants) {
            if (variant !is Map<*, *>) continue
            val attributes = variant["attributes"] as? Map<*, *> ?: continue
            if (attributes["org.gradle.usage"] != "java-runtime") continue
            val platform = attributes["org.jetbrains.kotlin.platform.type"]
            if (platform != null && platform != "jvm") continue
            val availableAt = variant["available-at"] as? Map<*, *> ?: return null
            return Redirect(
                ModuleId(availableAt["group"] as String, availableAt["module"] as String),
                availableAt["version"] as String,
            )
        }
        return null
    }
}
