package skiff.maven

import skiff.SkiffException

/**
 * Builds [EffectivePom]s: walks the parent chain, interpolates `${...}` properties in the child's context,
 * and applies `<dependencyManagement>` (including imported BOMs) to fill in missing versions and scopes.
 */
class PomResolver(private val store: ArtifactStore) {
    private val raw = HashMap<String, Pom>()
    private val effective = HashMap<String, EffectivePom>()
    private val inProgress = HashSet<String>()

    fun load(module: ModuleId, version: String): EffectivePom {
        val key = "$module:$version"
        effective[key]?.let { return it }
        if (!inProgress.add(key)) throw SkiffException("POM import cycle through $key")
        try {
            return build(module, version).also { effective[key] = it }
        } finally {
            inProgress -= key
        }
    }

    private fun rawPom(module: ModuleId, version: String): Pom = raw.getOrPut("$module:$version") {
        val file = store.get(module.filePath(version, "pom")) ?: throw store.notFound("$module:$version")
        try {
            Pom.parse(file)
        } catch (e: Exception) {
            throw SkiffException("could not read the POM of $module:$version: ${e.message}", e)
        }
    }

    private fun build(module: ModuleId, version: String): EffectivePom {
        // Child first, then its parents.
        val lineage = mutableListOf(rawPom(module, version))
        while (true) {
            val parent = lineage.last().parent ?: break
            if (lineage.size > MAX_PARENTS) throw SkiffException("POM parent chain of $module:$version is too long")
            lineage += rawPom(ModuleId(parent.groupId, parent.artifactId), parent.version)
        }
        val child = lineage.first()

        val properties = HashMap<String, String>()
        for (pom in lineage.asReversed()) properties.putAll(pom.properties)
        val groupId = child.groupId ?: child.parent?.groupId ?: module.group
        val projectVersion = child.version ?: child.parent?.version ?: version
        for (prefix in listOf("project.", "pom.", "")) {
            properties["${prefix}groupId"] = groupId
            properties["${prefix}artifactId"] = child.artifactId
            properties["${prefix}version"] = projectVersion
        }
        child.parent?.let {
            properties["project.parent.groupId"] = it.groupId
            properties["project.parent.version"] = it.version
            properties["parent.version"] = it.version
        }
        val interpolate = { value: String -> interpolate(value, properties) }

        // Explicit entries anywhere in the lineage win over entries imported from BOMs.
        val managed = LinkedHashMap<ModuleId, Pom.Dependency>()
        val imports = mutableListOf<Pom.Dependency>()
        for (pom in lineage) {
            for (entry in pom.managedDependencies.map { it.mapStrings(interpolate) }) {
                if (entry.scope == "import" && entry.type == "pom") imports += entry
                else managed.putIfAbsent(entry.module, entry)
            }
        }
        for (bom in imports) {
            val bomVersion = bom.version ?: continue
            load(bom.module, bomVersion).managed.forEach { (id, entry) -> managed.putIfAbsent(id, entry) }
        }

        val dependencies = lineage.flatMap { it.dependencies }
            .map { it.mapStrings(interpolate) }
            .distinctBy { Triple(it.module, it.classifier, it.type) }
            .map { dependency ->
                val management = managed[dependency.module]
                dependency.copy(
                    version = dependency.version ?: management?.version,
                    scope = dependency.scope ?: management?.scope,
                    exclusions = (dependency.exclusions + management?.exclusions.orEmpty()).distinct(),
                )
            }

        return EffectivePom(
            module = module,
            version = version,
            packaging = child.packaging?.let(interpolate) ?: "jar",
            dependencies = dependencies,
            managed = managed,
            publishedWithGradleMetadata = child.publishedWithGradleMetadata,
        )
    }

    private fun interpolate(value: String, properties: Map<String, String>): String {
        var result = value
        repeat(MAX_INTERPOLATION_DEPTH) {
            val next = PROPERTY.replace(result) { match -> properties[match.groupValues[1]] ?: match.value }
            if (next == result) return result
            result = next
        }
        return result
    }

    private companion object {
        const val MAX_PARENTS = 32
        const val MAX_INTERPOLATION_DEPTH = 10
        val PROPERTY = Regex("""\$\{([^}]+)}""")
    }
}
