package skiff.build

/** One compiler message with its source position, as `skf build --message-format=json` reports it. */
data class Diagnostic(
    /** `error`, `warning` or `info`. */
    val severity: String,
    val message: String,
    /** Absolute path of the source file, or null for messages that belong to no file. */
    val path: String?,
    /** 1-based; null when the compiler gave no position. */
    val line: Int?,
    val column: Int?,
    val module: String,
) {
    /** The `path:line:col: severity: message` form for people. */
    fun render(): String = buildString {
        if (path != null) {
            append(path)
            if (line != null) append(':').append(line)
            if (line != null && column != null) append(':').append(column)
            append(": ")
        }
        append(severity).append(": ").append(message)
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "reason" to "compiler-message",
        "module" to module,
        "severity" to severity,
        "path" to path?.replace('\\', '/'),
        "line" to line,
        "column" to column,
        "message" to message,
    )
}
