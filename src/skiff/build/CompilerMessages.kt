package skiff.build

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the output of the Kotlin compiler when it runs with the `XML` message renderer
 * (`-Dorg.jetbrains.kotlin.cliMessageRenderer=XML`): `<MESSAGES><error path=".." line=".." column="..">text</error>...`.
 * The renderer gives positions as a start line and column only.
 */
object CompilerMessages {
    const val RENDERER_PROPERTY = "-Dorg.jetbrains.kotlin.cliMessageRenderer=XML"

    private val BLOCK = Regex("<MESSAGES>.*?</MESSAGES>", RegexOption.DOT_MATCHES_ALL)

    /** The diagnostics in [output] and whatever else the process printed (stack traces, JVM warnings). */
    fun parse(output: String, module: String): Pair<List<Diagnostic>, String> {
        val diagnostics = BLOCK.findAll(output).flatMap { block(it.value, module) }.toList()
        return diagnostics to BLOCK.replace(output, "").trim()
    }

    private fun block(xml: String, module: String): Sequence<Diagnostic> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(xml.byteInputStream()).documentElement
        val children = root.childNodes
        return (0 until children.length).asSequence().mapNotNull { children.item(it) as? Element }.mapNotNull { element ->
            val severity = when (element.tagName.lowercase()) {
                "error", "exception" -> "error"
                "warning", "strong_warning" -> "warning"
                "info" -> "info"
                else -> return@mapNotNull null // logging
            }
            Diagnostic(
                severity,
                element.textContent.trim(),
                element.getAttribute("path").ifEmpty { null },
                element.getAttribute("line").toIntOrNull()?.takeIf { it > 0 },
                element.getAttribute("column").toIntOrNull()?.takeIf { it > 0 },
                module,
            )
        }
    }
}
