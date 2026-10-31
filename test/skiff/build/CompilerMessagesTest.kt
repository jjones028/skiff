package skiff.build

import skiff.json.JsonParser
import skiff.json.JsonWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompilerMessagesTest {
    @Test
    fun readsPositionsAndUnescapesMessages() {
        val output = """
            WARNING: something from the JVM
            <MESSAGES><error path="/p/src/Main.kt" line="2" column="16">Expected &#39;Int&#39;, actual &#39;String&#39;.</error>

            <warning path="/p/src/Main.kt" line="5" column="1">Unused &lt;x&gt;</warning>
            <logging>noise</logging>
            <error>no position</error>
            </MESSAGES>
        """.trimIndent()
        val (diagnostics, rest) = CompilerMessages.parse(output, "app")
        assertEquals("WARNING: something from the JVM", rest)
        assertEquals(
            listOf(
                Diagnostic("error", "Expected 'Int', actual 'String'.", "/p/src/Main.kt", 2, 16, "app"),
                Diagnostic("warning", "Unused <x>", "/p/src/Main.kt", 5, 1, "app"),
                Diagnostic("error", "no position", null, null, null, "app"),
            ),
            diagnostics,
        )
    }

    @Test
    fun rendersLikeTheCompiler() {
        assertEquals("/p/A.kt:2:16: error: boom", Diagnostic("error", "boom", "/p/A.kt", 2, 16, "app").render())
        assertEquals("error: boom", Diagnostic("error", "boom", null, null, null, "app").render())
    }

    @Test
    fun jsonLinesAreSingleLineAndKeepNulls() {
        val line = JsonWriter.writeCompact(Diagnostic("error", "a \"b\"\nc", null, null, null, "app").toJson())
        assertEquals(false, '\n' in line)
        val parsed = JsonParser(line).parse() as Map<*, *>
        assertEquals("compiler-message", parsed["reason"])
        assertEquals("a \"b\"\nc", parsed["message"])
        assertNull(parsed["path"])
    }
}
