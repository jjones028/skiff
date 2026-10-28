package skiff.project

import skiff.maven.ModuleId

/**
 * Annotation processing with kapt, from `[kapt-dependencies]` (the processors) and `[kapt]` (their settings).
 * [options] are passed to the processors as `-Akey=value`.
 */
data class KaptConfig(
    val processors: Map<ModuleId, String>,
    val options: Map<String, String> = emptyMap(),
    /** Replace generated or missing types in the stubs with the real ones (needed by Dagger, Room and friends). */
    val correctErrorTypes: Boolean = true,
)
