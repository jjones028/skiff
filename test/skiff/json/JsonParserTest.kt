package skiff.json

import skiff.SkiffException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonParserTest {
    private fun parse(text: String) = JsonParser(text).parse()

    @Test
    fun parsesScalars() {
        assertEquals("text", parse("\"text\""))
        assertEquals(42L, parse("42"))
        assertEquals(-7L, parse("-7"))
        assertEquals(1.5, parse("1.5"))
        assertEquals(1000.0, parse("1e3"))
        assertEquals(true, parse("true"))
        assertEquals(false, parse("false"))
        assertNull(parse("null"))
    }

    @Test
    fun parsesNestedObjectsAndArrays() {
        val value = parse("""{"a": [1, 2, {"b": null}], "c": {"d": "e"}, "f": [], "g": {}}""")
        assertEquals(
            mapOf(
                "a" to listOf(1L, 2L, mapOf("b" to null)),
                "c" to mapOf("d" to "e"),
                "f" to emptyList<Any?>(),
                "g" to emptyMap<String, Any?>(),
            ),
            value,
        )
    }

    @Test
    fun keepsObjectKeyOrder() {
        val value = parse("""{"z": 1, "a": 2, "m": 3}""") as Map<*, *>
        assertEquals(listOf("z", "a", "m"), value.keys.toList())
    }

    @Test
    fun toleratesWhitespaceEverywhere() {
        assertEquals(mapOf("a" to listOf(1L, 2L)), parse(" \n{ \"a\" :\t[ 1 ,\r\n 2 ] }\n "))
    }

    @Test
    fun parsesEscapes() {
        assertEquals("a\nb\tc\"d\\e/f\u00e9", parse("\"a\\nb\\tc\\\"d\\\\e\\/f\\u00e9\""))
    }

    @Test
    fun rejectsTrailingCharacters() {
        assertFailsWith<SkiffException> { parse("{} x") }
        assertFailsWith<SkiffException> { parse("1 2") }
    }

    @Test
    fun rejectsMalformedDocuments() {
        for (text in listOf("", "{", "[1,", "{\"a\"}", "{\"a\":}", "{a: 1}", "tru", "nul", "-", "\"open", "[1 2]", "{\"a\":1 \"b\":2}")) {
            assertFailsWith<SkiffException>(text) { parse(text) }
        }
    }

    @Test
    fun rejectsBrokenEscapes() {
        assertFailsWith<SkiffException> { parse("\"bad \\") }
        assertFailsWith<SkiffException> { parse("\"bad \\u12\"") }
        assertFailsWith<SkiffException> { parse("\"bad \\uZZZZ\"") }
    }

    @Test
    fun errorsMentionTheOffset() {
        val error = assertFailsWith<SkiffException> { parse("[1, ?]") }
        assertTrue("offset 4" in error.message!!, error.message)
    }
}
