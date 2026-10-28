package skiff.maven

/** A remote Maven repository. */
data class Repository(val name: String, val url: String) {
    val baseUrl: String get() = url.trimEnd('/')

    companion object {
        val MAVEN_CENTRAL = Repository("maven-central", "https://repo.maven.apache.org/maven2")
    }
}
