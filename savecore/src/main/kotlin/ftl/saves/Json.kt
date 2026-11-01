package ftl.saves

/**
 * A small JSON model, parser and writer. The save core has no dependencies so the same sources compile for Android
 * (JVM) and for the website (TeaVM). Objects keep their key order; numbers are doubles (revisions and sizes stay below
 * 2^53).
 */
sealed class Json {
    object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json()
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json() {
        operator fun get(key: String): Json? = fields[key]
    }

    companion object {
        fun parse(text: String): Json = Parser(text).document()

        fun write(value: Json): String = StringBuilder().also { writeTo(it, value) }.toString()

        private fun writeTo(out: StringBuilder, value: Json) {
            when (value) {
                Null -> out.append("null")
                is Bool -> out.append(if (value.value) "true" else "false")
                is Num -> out.append(number(value.value))
                is Str -> quote(out, value.value)
                is Arr -> {
                    out.append('[')
                    value.items.forEachIndexed { i, item -> if (i > 0) out.append(','); writeTo(out, item) }
                    out.append(']')
                }
                is Obj -> {
                    out.append('{')
                    var first = true
                    for ((k, v) in value.fields) {
                        if (!first) out.append(',')
                        first = false
                        quote(out, k)
                        out.append(':')
                        writeTo(out, v)
                    }
                    out.append('}')
                }
            }
        }

        /** JavaScript's number formatting for the values the save core uses: integers without a fraction. */
        private fun number(v: Double): String {
            require(!v.isNaN() && !v.isInfinite()) { "JSON has no NaN or infinity" }
            if (v == Math.floor(v) && Math.abs(v) < 9.007199254740992E15) return v.toLong().toString()
            return v.toString()
        }

        /** The escaping of JSON.stringify, so hashes over JSON text match the browser's. */
        fun quote(out: StringBuilder, s: String) {
            out.append('"')
            for (c in s) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\b' -> out.append("\\b")
                    '\u000c' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') {
                        out.append("\\u")
                        val hex = Integer.toHexString(c.code)
                        for (i in hex.length until 4) out.append('0')
                        out.append(hex)
                    } else out.append(c)
                }
            }
            out.append('"')
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun document(): Json {
            val v = value()
            space()
            if (i != s.length) fail("trailing text")
            return v
        }

        fun fail(what: String): Nothing = throw IllegalArgumentException("Invalid JSON at $i: $what")

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Json {
            space()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> num()
            }
        }

        fun literal(word: String, v: Json): Json {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        fun obj(): Json {
            i++
            val map = LinkedHashMap<String, Json>()
            space()
            if (i < s.length && s[i] == '}') { i++; return Obj(map) }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') fail("expected a key")
                val k = string()
                space()
                if (i >= s.length || s[i] != ':') fail("expected :")
                i++
                map[k] = value()
                space()
                if (i >= s.length) fail("unexpected end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return Obj(map) }
                fail("expected , or }")
            }
        }

        fun arr(): Json {
            i++
            val list = ArrayList<Json>()
            space()
            if (i < s.length && s[i] == ']') { i++; return Arr(list) }
            while (true) {
                list.add(value())
                space()
                if (i >= s.length) fail("unexpected end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return Arr(list) }
                fail("expected , or ]")
            }
        }

        fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape $e")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        fun num(): Json {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            val v = text.toDoubleOrNull() ?: fail("bad number")
            return Num(v)
        }
    }
}

// ---- building and reading helpers ----

fun obj(vararg pairs: Pair<String, Any?>): Json.Obj {
    val map = LinkedHashMap<String, Json>()
    for ((k, v) in pairs) map[k] = toJson(v)
    return Json.Obj(map)
}

fun toJson(v: Any?): Json = when (v) {
    null -> Json.Null
    is Json -> v
    is Boolean -> Json.Bool(v)
    is Int -> Json.Num(v.toDouble())
    is Long -> Json.Num(v.toDouble())
    is Double -> Json.Num(v)
    is Float -> Json.Num(v.toDouble())
    is String -> Json.Str(v)
    is List<*> -> Json.Arr(v.map { toJson(it) })
    else -> throw IllegalArgumentException("Not a JSON value: $v")
}

fun Json?.str(): String? = (this as? Json.Str)?.value
fun Json?.long(): Long? = (this as? Json.Num)?.value?.toLong()
fun Json?.int(): Int? = (this as? Json.Num)?.value?.toInt()
fun Json?.bool(): Boolean? = (this as? Json.Bool)?.value
fun Json?.arr(): List<Json> = (this as? Json.Arr)?.items ?: emptyList()
fun Json?.objOrNull(): Json.Obj? = this as? Json.Obj
