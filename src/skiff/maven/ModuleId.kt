package skiff.maven

import skiff.SkiffException

/** A Maven module, `group:artifact`, without a version. `*` in either part is a wildcard (for exclusions). */
data class ModuleId(val group: String, val artifact: String) : Comparable<ModuleId> {
    override fun toString() = "$group:$artifact"

    override fun compareTo(other: ModuleId): Int = compareValuesBy(this, other, { it.group }, { it.artifact })

    /** True if this id, treated as an exclusion pattern, matches [other]. */
    fun matches(other: ModuleId): Boolean =
        (group == "*" || group == other.group) && (artifact == "*" || artifact == other.artifact)

    /** This module's directory in a Maven repository layout. */
    fun repositoryPath(): String = group.replace('.', '/') + "/" + artifact

    fun filePath(version: String, extension: String): String =
        "${repositoryPath()}/$version/$artifact-$version.$extension"

    companion object {
        fun parse(text: String): ModuleId {
            val parts = text.trim().split(':')
            if (parts.size != 2 || parts.any { it.isBlank() }) {
                throw SkiffException("invalid package id '$text', expected 'group:artifact'")
            }
            return ModuleId(parts[0], parts[1])
        }
    }
}
