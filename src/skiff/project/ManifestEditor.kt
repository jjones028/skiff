package skiff.project

import skiff.maven.ModuleId
import skiff.toml.Toml

/** Edits the `[dependencies]` table of a `Skiff.toml` as text, so comments and formatting survive. */
object ManifestEditor {
    private val DEPENDENCIES_HEADER = Regex("""^\s*\[\s*dependencies\s*]\s*(#.*)?$""")
    private val ANY_HEADER = Regex("""^\s*\[""")
    private val KEY = Regex("""^\s*(?:"([^"]+)"|'([^']+)'|([A-Za-z0-9_.:-]+))\s*=""")

    fun setDependency(text: String, module: ModuleId, version: String): String {
        val lines = text.lines().toMutableList()
        val entry = "${Toml.quote(module.toString())} = ${Toml.quote(version)}"
        val section = findSection(lines)
        if (section == null) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += listOf("", "[dependencies]", entry, "")
        } else {
            val existing = section.firstOrNull { keyOf(lines[it]) == module.toString() }
            if (existing != null) {
                lines[existing] = entry
            } else {
                val lastEntry = section.lastOrNull { keyOf(lines[it]) != null } ?: (section.first - 1)
                lines.add(lastEntry + 1, entry)
            }
        }
        return lines.joinToString(newlineOf(text))
    }

    /** Returns the edited text, or null if [module] is not listed. */
    fun removeDependency(text: String, module: ModuleId): String? {
        val lines = text.lines().toMutableList()
        val section = findSection(lines) ?: return null
        val index = section.firstOrNull { keyOf(lines[it]) == module.toString() } ?: return null
        lines.removeAt(index)
        return lines.joinToString(newlineOf(text))
    }

    /** Line indexes of the entries under `[dependencies]` (excluding the header). */
    private fun findSection(lines: List<String>): IntRange? {
        val header = lines.indexOfFirst { DEPENDENCIES_HEADER.matches(it) }
        if (header < 0) return null
        val next = (header + 1 until lines.size).firstOrNull { ANY_HEADER.containsMatchIn(lines[it]) } ?: lines.size
        return header + 1 until next
    }

    private fun keyOf(line: String): String? =
        KEY.find(line)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    private fun newlineOf(text: String) = if ("\r\n" in text) "\r\n" else "\n"
}
