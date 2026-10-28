package skiff.maven

import skiff.SkiffException
import skiff.cli.Term

/**
 * Resolves the transitive runtime dependencies of a set of root modules.
 *
 * Conflicts are settled Gradle-style: the highest requested version of a module wins. Because raising a
 * version can change that module's own dependencies, the graph is walked repeatedly until nothing changes.
 * Only `compile` and `runtime` scopes are followed; optional dependencies are skipped.
 */
class DependencyResolver(private val store: ArtifactStore) {
    private val poms = PomResolver(store)
    private val nodes = HashMap<String, Node>()

    private data class Edge(val module: ModuleId, val version: String, val exclusions: List<ModuleId>)

    private data class Node(val hasJar: Boolean, val edges: List<Edge>)

    fun resolve(roots: Map<ModuleId, String>): List<ResolvedModule> {
        val selected = HashMap<ModuleId, Version>()
        for ((module, version) in roots) select(selected, module, Version(concreteVersion(module, version)))

        repeat(MAX_PASSES) {
            val result = LinkedHashMap<ModuleId, ResolvedModule>()
            var changed = false
            // Each queue entry carries the exclusions accumulated along the path that reached it.
            val queue = ArrayDeque(roots.keys.sorted().map { it to emptyList<ModuleId>() })
            while (queue.isNotEmpty()) {
                val (module, exclusions) = queue.removeFirst()
                if (module in result) continue
                val version = selected.getValue(module).text
                val node = node(module, version)
                val dependencies = mutableListOf<ModuleId>()
                for (edge in node.edges) {
                    if (exclusions.any { it.matches(edge.module) }) continue
                    if (select(selected, edge.module, Version(edge.version)) && edge.module in result) changed = true
                    dependencies += edge.module
                    queue += edge.module to (exclusions + edge.exclusions).distinct()
                }
                result[module] = ResolvedModule(module, version, node.hasJar, dependencies.distinct())
            }
            if (!changed) return result.values.sortedBy { it.module }
        }
        throw SkiffException("dependency resolution did not settle after $MAX_PASSES passes")
    }

    /** Records [version] for [module] if it is higher than what was selected before; returns true if it was raised. */
    private fun select(selected: MutableMap<ModuleId, Version>, module: ModuleId, version: Version): Boolean {
        val current = selected[module]
        if (current != null && current >= version) return false
        selected[module] = version
        return current != null
    }

    private fun node(module: ModuleId, version: String): Node = nodes.getOrPut("$module:$version") {
        val pom = poms.load(module, version)
        if (pom.publishedWithGradleMetadata) {
            val redirect = store.get(module.filePath(version, "module"))?.let(GradleModule::jvmRedirect)
            if (redirect != null && redirect.module != module) {
                return@getOrPut Node(hasJar = false, edges = listOf(Edge(redirect.module, redirect.version, emptyList())))
            }
        }
        val edges = pom.dependencies.mapNotNull { dependency ->
            if (dependency.optional || (dependency.scope ?: "compile") !in FOLLOWED_SCOPES) return@mapNotNull null
            if (dependency.classifier != null || (dependency.type ?: "jar") !in FOLLOWED_TYPES) {
                Term.warn("skipping ${dependency.module} (classifier/type not supported yet), needed by $module:$version")
                return@mapNotNull null
            }
            val requested = dependency.version
            if (requested == null || "\${" in requested) {
                Term.warn("skipping ${dependency.module}: no usable version in the POM of $module:$version")
                return@mapNotNull null
            }
            Edge(dependency.module, concreteVersion(dependency.module, requested), dependency.exclusions)
        }
        Node(hasJar = pom.packaging !in POM_ONLY_PACKAGING, edges = edges)
    }

    private fun concreteVersion(module: ModuleId, requested: String): String =
        if (VersionRange.isRange(requested)) MavenMetadata.highestIn(store, module, requested) else requested

    private companion object {
        const val MAX_PASSES = 25
        val FOLLOWED_SCOPES = setOf("compile", "runtime")
        val FOLLOWED_TYPES = setOf("jar", "bundle", "pom")
        val POM_ONLY_PACKAGING = setOf("pom")
    }
}
