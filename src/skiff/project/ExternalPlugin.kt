package skiff.project

import skiff.maven.ModuleId

/**
 * A compiler plugin given by Maven coordinates (`[[kotlin.compiler-plugin]]`), for plugins skiff has no
 * [KotlinPlugin] shorthand for. [options] are passed to the compiler verbatim as `-P <option>`.
 */
data class ExternalPlugin(val module: ModuleId, val version: String, val options: List<String> = emptyList())
