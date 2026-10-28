package skiff.maven

import skiff.SkiffException
import javax.xml.parsers.DocumentBuilderFactory

/** Reads a module's `maven-metadata.xml` to find which versions are published. */
object MavenMetadata {
    fun versions(store: ArtifactStore, module: ModuleId): List<Version> {
        val file = store.refresh("${module.repositoryPath()}/maven-metadata.xml") ?: throw store.notFound("$module")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile())
        val nodes = document.getElementsByTagName("version")
        return (0 until nodes.length).map { Version(nodes.item(it).textContent.trim()) }
    }

    /** The newest stable version, or the newest of any kind if the module has no stable release. */
    fun latest(store: ArtifactStore, module: ModuleId): String {
        val versions = versions(store, module)
        return (versions.filter { it.isStable }.maxOrNull() ?: versions.maxOrNull())?.text
            ?: throw SkiffException("$module has no published versions")
    }

    /** The newest version inside [range]. */
    fun highestIn(store: ArtifactStore, module: ModuleId, range: String): String {
        val parsed = VersionRange.parse(range)
        return versions(store, module).filter { it in parsed }.maxOrNull()?.text
            ?: throw SkiffException("no version of $module matches $range")
    }
}
