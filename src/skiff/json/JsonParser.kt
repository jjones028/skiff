package skiff.json

import skiff.SkiffException

/**
 * A small JSON parser, used to read Gradle Module Metadata (`.module` files).
 * Objects become `Map<String, Any?>`, arrays `List<Any?>`, numbers `Long` or `Double`.
 */
class JsonParser(private val text: String) {
    private var pos = 0

    fun parse(): Any? {
        val value = parseValue()
        skipWhitespace()
        if (pos != text.length) fail("unexpected trailing characters")
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        val c = peek()
        return when {
            c == '{' -> parseObject()
            c == '[' -> parseArray()
            c == '"' -> parseString()
            c == 't' -> literal("true", true)
            c == 'f' -> literal("false", false)
            c == 'n' -> literal("null", null)
            c == '-' || c.isDigit() -> parseNumber()
            else -> fail("unexpected character '$c'")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        pos++
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (peek() == ',') pos++ else {
                expect('}')
                return result
            }
        }
    }

    private fun parseArray(): List<Any?> {
        pos++
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            pos++
            return result
        }
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (peek() == ',') pos++ else {
                expect(']')
                return result
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (pos >= text.length) fail("unterminated string")
                    val escaped = text[pos++]
                    when (escaped) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            val code = text.substring(pos, minOf(pos + 4, text.length)).takeIf { it.length == 4 }?.toIntOrNull(16)
                                ?: fail("invalid unicode escape")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        val token = text.substring(start, pos)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("invalid number '$token'")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, pos)) fail("unexpected token")
        pos += word.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else '\u0000'

    private fun fail(message: String): Nothing = throw SkiffException("invalid JSON at offset $pos: $message")
}
