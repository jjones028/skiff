package skiff.maven

/** One module selected by dependency resolution. */
data class ResolvedModule(
    val module: ModuleId,
    val version: String,
    /** False for POM-only modules (BOMs, parents) and multiplatform roots that redirect to a JVM module. */
    val hasJar: Boolean,
    val dependencies: List<ModuleId>,
) {
    val jarPath: String get() = module.filePath(version, "jar")
}
