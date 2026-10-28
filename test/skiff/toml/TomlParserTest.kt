package skiff.toml

import skiff.SkiffException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TomlParserTest {
    private fun parse(text: String) = Toml.parse(text.trimIndent(), "test.toml")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.table(key: String) = getValue(key) as Map<String, Any?>

    @Test
    fun parsesScalars() {
        val toml = parse(
            """
            name = "skiff"
            literal = 'C:\path'
            count = 1_000
            negative = -7
            ratio = 1.5
            on = true
            off = false
            """,
        )
        assertEquals("skiff", toml["name"])
        assertEquals("C:\\path", toml["literal"])
        assertEquals(1000L, toml["count"])
        assertEquals(-7L, toml["negative"])
        assertEquals(1.5, toml["ratio"])
        assertEquals(true, toml["on"])
        assertEquals(false, toml["off"])
    }

    @Test
    fun parsesEscapesInBasicStrings() {
        val toml = Toml.parse("s = \"a\\tb\\n\\\"q\\\" \\\\ \\u00e9\"", "test.toml")
        assertEquals("a\tb\n\"q\" \\ \u00e9", toml["s"])
    }

    @Test
    fun parsesMultilineStrings() {
        val toml = Toml.parse("a = \"\"\"\nline1\nline2\"\"\"\nb = '''\nraw\\n'''", "test.toml")
        assertEquals("line1\nline2", toml["a"])
        assertEquals("raw\\n", toml["b"])
    }

    @Test
    fun parsesTables() {
        val toml = parse(
            """
            [package]
            name = "demo"

            [dependencies]
            "com.squareup.okhttp3:okhttp" = "4.12.0"
            """,
        )
        assertEquals("demo", toml.table("package")["name"])
        assertEquals("4.12.0", toml.table("dependencies")["com.squareup.okhttp3:okhttp"])
    }

    @Test
    fun parsesDottedKeysAndHeaders() {
        val toml = parse(
            """
            a.b.c = 1
            [x.y]
            z = 2
            """,
        )
        assertEquals(1L, toml.table("a").table("b")["c"])
        assertEquals(2L, toml.table("x").table("y")["z"])
    }

    @Test
    fun parsesArraysAcrossLines() {
        val toml = parse(
            """
            list = [
                "a", # comment
                "b",
            ]
            nested = [[1, 2], [3]]
            empty = []
            """,
        )
        assertEquals(listOf("a", "b"), toml["list"])
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), toml["nested"])
        assertEquals(emptyList<Any?>(), toml["empty"])
    }

    @Test
    fun parsesInlineTables() {
        val toml = parse("""dep = { version = "1.0", exclude = ["a:b"] }""")
        assertEquals(mapOf("version" to "1.0", "exclude" to listOf("a:b")), toml["dep"])
    }

    @Test
    fun parsesArraysOfTables() {
        val toml = parse(
            """
            [[package]]
            id = "a"

            [[package]]
            id = "b"
            """,
        )
        val packages = toml["package"] as List<*>
        assertEquals(listOf("a", "b"), packages.map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val toml = parse(
            """
            # leading comment

            a = 1 # trailing
            """,
        )
        assertEquals(mapOf<String, Any?>("a" to 1L), toml)
    }

    @Test
    fun handlesWindowsLineEndings() {
        val toml = Toml.parse("[t]\r\na = 1\r\nb = \"x\"\r\n", "test.toml")
        assertEquals(mapOf<String, Any?>("a" to 1L, "b" to "x"), toml.table("t"))
    }

    @Test
    fun rejectsDuplicateKeys() {
        assertFailsWith<SkiffException> { parse("a = 1\na = 2") }
    }

    @Test
    fun rejectsRedefiningAValueAsATable() {
        assertFailsWith<SkiffException> { parse("a = 1\n[a]\nb = 2") }
    }

    @Test
    fun rejectsUnterminatedStrings() {
        assertFailsWith<SkiffException> { Toml.parse("a = \"oops", "test.toml") }
        assertFailsWith<SkiffException> { Toml.parse("a = \"line\nbreak\"", "test.toml") }
    }

    @Test
    fun rejectsTrailingGarbage() {
        assertFailsWith<SkiffException> { parse("a = 1 b = 2") }
    }

    @Test
    fun errorsNameTheFileAndLine() {
        val error = assertFailsWith<SkiffException> { Toml.parse("a = 1\nb = ", "test.toml") }
        assertTrue(error.message!!.startsWith("test.toml:2:"), error.message)
    }

    @Test
    fun quoteRoundTripsThroughTheParser() {
        val original = "quote \" backslash \\ newline \n tab \t"
        assertEquals(original, Toml.parse("v = ${Toml.quote(original)}", "test.toml")["v"])
    }
}
