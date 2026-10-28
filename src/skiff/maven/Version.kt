package skiff.maven

/**
 * A Maven version, ordered roughly the way Maven and Gradle order them:
 * `1.0-alpha < 1.0-beta < 1.0-M1 < 1.0-rc1 < 1.0-SNAPSHOT < 1.0 < 1.0-sp1 < 1.0.1`.
 */
class Version(val text: String) : Comparable<Version> {
    private val items: List<Any> = tokenize(text)

    /** False for pre-releases (alpha, beta, milestone, rc, snapshot, eap, dev, preview). */
    val isStable: Boolean
        get() = items.none { it is String && (rank(it) < RELEASE_RANK || it in UNSTABLE_WORDS) }

    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(items.size, other.items.size)) {
            val result = compareItems(items.getOrNull(i), other.items.getOrNull(i))
            if (result != 0) return result
        }
        return 0
    }

    override fun equals(other: Any?) = other is Version && other.text == text

    override fun hashCode() = text.hashCode()

    override fun toString() = text

    private companion object {
        const val RELEASE_RANK = 5
        val UNSTABLE_WORDS = setOf("eap", "dev", "preview", "pre")

        fun tokenize(text: String): List<Any> {
            val items = mutableListOf<Any>()
            val buffer = StringBuilder()
            var digits = false
            fun flush() {
                if (buffer.isEmpty()) return
                val token = buffer.toString()
                items += if (digits) token.toLongOrNull() ?: token else normalize(token.lowercase())
                buffer.clear()
            }
            for (c in text) {
                if (c == '.' || c == '-' || c == '_' || c == '+') {
                    flush()
                    continue
                }
                if (buffer.isNotEmpty() && c.isDigit() != digits) flush()
                digits = c.isDigit()
                buffer.append(c)
            }
            flush()
            return items
        }

        fun normalize(qualifier: String): String = when (qualifier) {
            "a" -> "alpha"
            "b" -> "beta"
            "m" -> "milestone"
            "cr" -> "rc"
            "ga", "final", "release" -> ""
            else -> qualifier
        }

        fun rank(qualifier: String): Int = when (qualifier) {
            "alpha" -> 0
            "beta" -> 1
            "milestone" -> 2
            "rc" -> 3
            "snapshot" -> 4
            "" -> RELEASE_RANK
            "sp" -> 6
            else -> RELEASE_RANK
        }

        /** A missing item counts as `0` against a number and as a plain release against a qualifier. */
        fun compareItems(a: Any?, b: Any?): Int = when {
            a == null && b == null -> 0
            a == null -> -compareItems(b, null)
            a is Long -> when (b) {
                null -> a.compareTo(0L)
                is Long -> a.compareTo(b)
                else -> 1
            }
            b is Long -> -1
            else -> {
                val left = a as String
                val right = b as String?
                val byRank = rank(left).compareTo(if (right == null) RELEASE_RANK else rank(right))
                when {
                    byRank != 0 -> byRank
                    right == null -> if (left.isEmpty()) 0 else 1
                    else -> left.compareTo(right)
                }
            }
        }
    }
}
