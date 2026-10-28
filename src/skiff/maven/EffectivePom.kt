package skiff.maven

/** A POM after parent inheritance, property interpolation and dependency management have been applied. */
data class EffectivePom(
    val module: ModuleId,
    val version: String,
    val packaging: String,
    val dependencies: List<Pom.Dependency>,
    val managed: Map<ModuleId, Pom.Dependency>,
    val publishedWithGradleMetadata: Boolean,
)
