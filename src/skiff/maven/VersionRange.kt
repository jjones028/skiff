package skiff.maven

import skiff.SkiffException

/** A Maven version range such as `[1.0,2.0)`. Only the first interval of a multi-interval range is used. */
class VersionRange private constructor(
    private val lower: Version?,
    private val lowerInclusive: Boolean,
    private val upper: Version?,
    private val upperInclusive: Boolean,
) {
    operator fun contains(version: Version): Boolean {
        if (lower != null) {
            val c = version.compareTo(lower)
            if (c < 0 || (c == 0 && !lowerInclusive)) return false
        }
        if (upper != null) {
            val c = version.compareTo(upper)
            if (c > 0 || (c == 0 && !upperInclusive)) return false
        }
        return true
    }

    companion object {
        fun isRange(text: String): Boolean = text.startsWith("[") || text.startsWith("(")

        fun parse(text: String): VersionRange {
            val end = text.indexOfFirst { it == ']' || it == ')' }
            if (end < 0) throw SkiffException("invalid version range '$text'")
            val body = text.substring(1, end)
            if (',' !in body) {
                val exact = Version(body.trim())
                return VersionRange(exact, true, exact, true)
            }
            val (low, high) = body.split(',', limit = 2).map { it.trim() }
            return VersionRange(
                low.ifEmpty { null }?.let(::Version), text[0] == '[',
                high.ifEmpty { null }?.let(::Version), text[end] == ']',
            )
        }
    }
}
