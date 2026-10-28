package skiff.toml

import skiff.SkiffException

/**
 * Parses the subset of TOML that skiff's files use: tables, arrays of tables, dotted keys,
 * strings, integers, floats, booleans, arrays and inline tables. Dates and times are not supported.
 *
 * Tables become `Map<String, Any?>`, arrays become `List<Any?>`, integers `Long` and floats `Double`.
 */
class TomlParser(private val text: String, private val fileName: String) {
    private var pos = 0

    fun parse(): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        var current: MutableMap<String, Any?> = root
        while (true) {
            skipBlank(acrossLines = true)
            if (pos >= text.length) break
            if (peek() == '[') {
                val isArray = peek(1) == '['
                pos += if (isArray) 2 else 1
                val path = parseKeyPath()
                expect(']')
                if (isArray) expect(']')
                current = if (isArray) appendTable(root, path) else tableAt(root, path)
            } else {
                val path = parseKeyPath()
                expect('=')
                skipBlank()
                assign(current, path, parseValue())
            }
            endLine()
        }
        return root
    }

    private fun parseKeyPath(): List<String> {
        val parts = mutableListOf<String>()
        while (true) {
            skipBlank()
            parts += when (peek()) {
                '"' -> parseBasicString()
                '\'' -> parseLiteralString()
                else -> parseBareKey()
            }
            skipBlank()
            if (peek() == '.') pos++ else return parts
        }
    }

    private fun parseBareKey(): String {
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '-')) pos++
        if (start == pos) fail("expected a key")
        return text.substring(start, pos)
    }

    private fun parseValue(): Any? {
        val c = peek()
        return when {
            c == '"' -> parseBasicString()
            c == '\'' -> parseLiteralString()
            c == '[' -> parseArray()
            c == '{' -> parseInlineTable()
            c == 't' || c == 'f' -> parseBoolean()
            c == '+' || c == '-' || c.isDigit() -> parseNumber()
            else -> fail("expected a value")
        }
    }

    private fun parseBasicString(): String {
        val multiline = text.startsWith("\"\"\"", pos)
        pos += if (multiline) 3 else 1
        if (multiline) skipNewline()
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos]
            when {
                multiline && text.startsWith("\"\"\"", pos) -> {
                    pos += 3
                    return out.toString()
                }
                !multiline && c == '"' -> {
                    pos++
                    return out.toString()
                }
                !multiline && c == '\n' -> fail("newline in string")
                c == '\\' -> {
                    pos++
                    out.append(parseEscape())
                }
                else -> {
                    out.append(c)
                    pos++
                }
            }
        }
    }

    private fun parseEscape(): String {
        val c = peek()
        pos++
        return when (c) {
            'n' -> "\n"
            't' -> "\t"
            'r' -> "\r"
            'b' -> "\b"
            'f' -> "\u000c"
            '"' -> "\""
            '\\' -> "\\"
            'u', 'U' -> {
                val length = if (c == 'u') 4 else 8
                val code = text.substring(pos, minOf(pos + length, text.length)).toIntOrNull(16)
                    ?: fail("invalid unicode escape")
                pos += length
                String(Character.toChars(code))
            }
            else -> fail("invalid escape '\\$c'")
        }
    }

    private fun parseLiteralString(): String {
        val delimiter = if (text.startsWith("'''", pos)) "'''" else "'"
        pos += delimiter.length
        if (delimiter.length == 3) skipNewline()
        val end = text.indexOf(delimiter, pos)
        if (end < 0) fail("unterminated string")
        return text.substring(pos, end).also { pos = end + delimiter.length }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val items = mutableListOf<Any?>()
        while (true) {
            skipBlank(acrossLines = true)
            if (peek() == ']') {
                pos++
                return items
            }
            items += parseValue()
            skipBlank(acrossLines = true)
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return items
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun parseInlineTable(): Map<String, Any?> {
        expect('{')
        val table = LinkedHashMap<String, Any?>()
        skipBlank()
        if (peek() == '}') {
            pos++
            return table
        }
        while (true) {
            val path = parseKeyPath()
            expect('=')
            skipBlank()
            assign(table, path, parseValue())
            skipBlank()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return table
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun parseBoolean(): Boolean = when {
        text.startsWith("true", pos) -> true.also { pos += 4 }
        text.startsWith("false", pos) -> false.also { pos += 5 }
        else -> fail("expected a value")
    }

    private fun parseNumber(): Any {
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] in "+-._")) pos++
        val token = text.substring(start, pos).replace("_", "")
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("invalid number '$token'")
    }

    @Suppress("UNCHECKED_CAST")
    private fun tableAt(root: MutableMap<String, Any?>, path: List<String>): MutableMap<String, Any?> {
        var table = root
        for (key in path) {
            table = when (val existing = table[key]) {
                null -> LinkedHashMap<String, Any?>().also { table[key] = it }
                is MutableMap<*, *> -> existing as MutableMap<String, Any?>
                is MutableList<*> -> existing.lastOrNull() as? MutableMap<String, Any?> ?: fail("'$key' is not a table")
                else -> fail("'$key' is already defined as a value")
            }
        }
        return table
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendTable(root: MutableMap<String, Any?>, path: List<String>): MutableMap<String, Any?> {
        val parent = tableAt(root, path.dropLast(1))
        val list = when (val existing = parent[path.last()]) {
            null -> mutableListOf<Any?>().also { parent[path.last()] = it }
            is MutableList<*> -> existing as MutableList<Any?>
            else -> fail("'${path.last()}' is not an array of tables")
        }
        return LinkedHashMap<String, Any?>().also { list += it }
    }

    private fun assign(table: MutableMap<String, Any?>, path: List<String>, value: Any?) {
        val target = tableAt(table, path.dropLast(1))
        if (path.last() in target) fail("duplicate key '${path.joinToString(".")}'")
        target[path.last()] = value
    }

    private fun skipBlank(acrossLines: Boolean = false) {
        while (pos < text.length) {
            when (text[pos]) {
                ' ', '\t' -> pos++
                '\r', '\n' -> if (acrossLines) pos++ else return
                '#' -> while (pos < text.length && text[pos] != '\n') pos++
                else -> return
            }
        }
    }

    private fun skipNewline() {
        if (peek() == '\r') pos++
        if (peek() == '\n') pos++
    }

    private fun endLine() {
        skipBlank()
        if (pos >= text.length) return
        if (peek() == '\r') pos++
        expect('\n')
    }

    private fun expect(c: Char) {
        if (peek() != c) fail("expected '$c'")
        pos++
    }

    private fun peek(offset: Int = 0): Char = if (pos + offset < text.length) text[pos + offset] else '\u0000'

    private fun fail(message: String): Nothing {
        val line = text.substring(0, minOf(pos, text.length)).count { it == '\n' } + 1
        throw SkiffException("$fileName:$line: $message")
    }
}
