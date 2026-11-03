package com.rustedwarfare.reforge.core.content

import java.util.IdentityHashMap
import org.hjson.JsonValue

/** Aligns an already validated syntax tree with its source; never admits or repairs syntax. */
object StructuredNumberLexemes {
    private val number = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

    fun read(text: String, root: JsonValue): Map<JsonValue, String> = Cursor(text).read(root)

    private class Cursor(val text: String) {
        var offset = 0
        // JsonNumber equality compares rounded doubles, so equal values must remain distinct nodes.
        val numbers = IdentityHashMap<JsonValue, String>()

        fun read(root: JsonValue): Map<JsonValue, String> {
            value(root, rootObject = true)
            trivia()
            if (offset != text.length) mismatch()
            return numbers
        }

        fun mismatch(): Nothing = throw IllegalArgumentException(
            "Cannot retain a validated numeric source token at offset $offset")

        fun expect(character: Char) {
            if (text.getOrNull(offset) != character) mismatch()
            offset++
        }

        fun trivia() {
            while (offset < text.length) {
                when {
                    text[offset].isWhitespace() -> offset++
                    text[offset] == '#' || text.startsWith("//", offset) -> {
                        while (offset < text.length && text[offset] !in "\r\n") offset++
                    }
                    text.startsWith("/*", offset) -> {
                        val end = text.indexOf("*/", offset + 2)
                        if (end < 0) mismatch()
                        offset = end + 2
                    }
                    else -> return
                }
            }
        }

        fun quoted() {
            if (text.startsWith("'''", offset)) {
                val end = text.indexOf("'''", offset + 3)
                if (end < 0) mismatch()
                offset = end + 3
                return
            }
            val quote = text.getOrNull(offset) ?: mismatch()
            offset++
            while (offset < text.length) {
                val character = text[offset++]
                if (character == '\\') offset++
                else if (character == quote) return
            }
            mismatch()
        }

        fun value(node: JsonValue, rootObject: Boolean = false) {
            trivia()
            when {
                node.isObject -> {
                    val braces = text.getOrNull(offset) == '{'
                    if (braces) offset++ else if (!rootObject) mismatch()
                    for (member in node.asObject()) {
                        trivia()
                        if (text.getOrNull(offset) in listOf('"', '\'')) quoted()
                        else while (offset < text.length && text[offset] != ':') offset++
                        trivia()
                        expect(':')
                        value(member.value)
                        trivia()
                        if (text.getOrNull(offset) == ',') offset++
                    }
                    trivia()
                    if (braces) expect('}')
                }
                node.isArray -> {
                    expect('[')
                    for (entry in node.asArray()) {
                        value(entry)
                        trivia()
                        if (text.getOrNull(offset) == ',') offset++
                    }
                    trivia()
                    expect(']')
                }
                node.isNumber -> {
                    val token = number.matchAt(text, offset) ?: mismatch()
                    numbers[node] = token.value
                    offset = token.range.last + 1
                }
                node.isString -> {
                    if (text.getOrNull(offset) in listOf('"', '\'')) quoted()
                    else while (offset < text.length && text[offset] !in "\r\n") offset++
                }
                node.isBoolean -> offset += if (node.asBoolean()) 4 else 5
                node.isNull -> offset += 4
                else -> mismatch()
            }
        }
    }
}
