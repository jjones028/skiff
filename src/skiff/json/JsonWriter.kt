package skiff.json

/**
 * Pretty-prints the values [JsonParser] produces (`Map`, `List`, `String`, numbers, `Boolean`, `null`),
 * two spaces per level. Map order is kept.
 */
object JsonWriter {
    fun write(value: Any?): String = buildString { append(value, 0) }.plus("\n")

    private fun StringBuilder.append(value: Any?, depth: Int) {
        when (value) {
            null -> append("null")
            is String -> appendString(value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    append("{}")
                    return
                }
                append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    append("  ".repeat(depth + 1)).appendString(k.toString()).append(": ")
                    append(v, depth + 1)
                    append(if (i < value.size - 1) ",\n" else "\n")
                }
                append("  ".repeat(depth)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    append("[]")
                    return
                }
                append("[\n")
                value.forEachIndexed { i, v ->
                    append("  ".repeat(depth + 1))
                    append(v, depth + 1)
                    append(if (i < value.size - 1) ",\n" else "\n")
                }
                append("  ".repeat(depth)).append(']')
            }
            else -> append(value.toString())
        }
    }

    private fun StringBuilder.appendString(s: String): StringBuilder {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        return append('"')
    }
}
