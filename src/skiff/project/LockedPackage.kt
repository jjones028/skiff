package skiff.project

import skiff.maven.ModuleId

/** One entry in `Skiff.lock`. [sha256] is null for modules without a jar (BOMs, multiplatform roots). */
data class LockedPackage(
    val module: ModuleId,
    val version: String,
    val sha256: String?,
    val dependencies: List<ModuleId>,
) {
    val jarPath: String get() = module.filePath(version, "jar")
}
