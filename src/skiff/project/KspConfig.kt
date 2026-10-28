package skiff.project

import skiff.maven.ModuleId

/**
 * Symbol processing with KSP, from `[ksp-dependencies]` (the processors) and `[ksp]` (their settings).
 * [version] is the version of KSP itself, which is versioned separately from Kotlin.
 */
data class KspConfig(
    val processors: Map<ModuleId, String>,
    val options: Map<String, String> = emptyMap(),
    val version: String = DEFAULT_VERSION,
) {
    companion object {
        const val DEFAULT_VERSION = "2.3.12"
    }
}
