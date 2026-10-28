package skiff.project

/** A compiler plugin enabled in `Skiff.toml`, with the annotations it was configured for. */
data class PluginConfig(val plugin: KotlinPlugin, val annotations: List<String> = emptyList()) {
    /** The `-P` option values (`plugin:<id>:annotation=<name>`) for this plugin. */
    fun options(): List<String> = annotations.map { "plugin:${plugin.compilerPluginId}:annotation=$it" }
}
