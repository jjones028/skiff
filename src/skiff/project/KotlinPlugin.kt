package skiff.project

import skiff.maven.ModuleId

/**
 * The Kotlin compiler plugins skiff can enable with `[kotlin] plugins = [...]`.
 * [takesAnnotations] plugins do nothing until told which annotations to act on (`[kotlin.<name>] annotations`).
 */
enum class KotlinPlugin(val id: String, artifact: String, val compilerPluginId: String, val takesAnnotations: Boolean) {
    SERIALIZATION("serialization", "kotlin-serialization-compiler-plugin-embeddable", "org.jetbrains.kotlinx.serialization", false),
    ALLOPEN("allopen", "kotlin-allopen-compiler-plugin-embeddable", "org.jetbrains.kotlin.allopen", true),
    NOARG("noarg", "kotlin-noarg-compiler-plugin-embeddable", "org.jetbrains.kotlin.noarg", true),
    SAM_WITH_RECEIVER("sam-with-receiver", "kotlin-sam-with-receiver-compiler-plugin-embeddable", "org.jetbrains.kotlin.samWithReceiver", true),
    COMPOSE("compose", "kotlin-compose-compiler-plugin-embeddable", "androidx.compose.compiler.plugins.kotlin", false);

    /** The plugin's jar is published next to the compiler, with the same version and no dependencies. */
    val module = ModuleId("org.jetbrains.kotlin", artifact)

    companion object {
        fun byId(id: String): KotlinPlugin? = entries.firstOrNull { it.id == id }
    }
}
