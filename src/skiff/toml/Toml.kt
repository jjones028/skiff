package skiff.toml

object Toml {
    fun parse(text: String, fileName: String): Map<String, Any?> = TomlParser(text, fileName).parse()

    /** Renders [value] as a TOML basic string. */
    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
        append('"')
    }
}
