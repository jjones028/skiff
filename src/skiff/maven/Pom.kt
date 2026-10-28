package skiff.maven

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** The parts of a `pom.xml` that dependency resolution needs, as written: before inheritance and interpolation. */
data class Pom(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
    val packaging: String?,
    val parent: Parent?,
    val properties: Map<String, String>,
    val dependencies: List<Dependency>,
    val managedDependencies: List<Dependency>,
    /** Gradle writes a marker comment into POMs it publishes alongside a `.module` file. */
    val publishedWithGradleMetadata: Boolean,
) {
    data class Parent(val groupId: String, val artifactId: String, val version: String)

    data class Dependency(
        val groupId: String,
        val artifactId: String,
        val version: String?,
        val type: String?,
        val classifier: String?,
        val scope: String?,
        val optional: Boolean,
        val exclusions: List<ModuleId>,
    ) {
        val module: ModuleId get() = ModuleId(groupId, artifactId)

        fun mapStrings(transform: (String) -> String) = copy(
            groupId = transform(groupId),
            artifactId = transform(artifactId),
            version = version?.let(transform),
            type = type?.let(transform),
            classifier = classifier?.let(transform),
            scope = scope?.let(transform),
        )
    }

    companion object {
        fun parse(file: Path): Pom {
            val text = Files.readString(file)
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
            }
            val project = factory.newDocumentBuilder().parse(file.toFile()).documentElement
            val parent = project.child("parent")?.let {
                Parent(it.text("groupId").orEmpty(), it.text("artifactId").orEmpty(), it.text("version").orEmpty())
            }
            val properties = project.child("properties")?.childElements()
                ?.associate { it.tagName to it.textContent.trim() }
                .orEmpty()
            return Pom(
                groupId = project.text("groupId"),
                artifactId = project.text("artifactId").orEmpty(),
                version = project.text("version"),
                packaging = project.text("packaging"),
                parent = parent,
                properties = properties,
                dependencies = dependencies(project.child("dependencies")),
                managedDependencies = dependencies(project.child("dependencyManagement")?.child("dependencies")),
                publishedWithGradleMetadata = "published-with-gradle-metadata" in text,
            )
        }

        private fun dependencies(list: Element?): List<Dependency> =
            list?.childElements()?.filter { it.tagName == "dependency" }?.map { dependency ->
                Dependency(
                    groupId = dependency.text("groupId").orEmpty(),
                    artifactId = dependency.text("artifactId").orEmpty(),
                    version = dependency.text("version"),
                    type = dependency.text("type"),
                    classifier = dependency.text("classifier"),
                    scope = dependency.text("scope"),
                    optional = dependency.text("optional") == "true",
                    exclusions = dependency.child("exclusions")?.childElements()?.map {
                        ModuleId(it.text("groupId") ?: "*", it.text("artifactId") ?: "*")
                    }.orEmpty(),
                )
            }.orEmpty()

        private fun Element.childElements(): List<Element> =
            (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

        private fun Element.child(name: String): Element? = childElements().firstOrNull { it.tagName == name }

        private fun Element.text(name: String): String? = child(name)?.textContent?.trim()?.ifEmpty { null }
    }
}
