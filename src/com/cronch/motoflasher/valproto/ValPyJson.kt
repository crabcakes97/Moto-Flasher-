// Python-compatible JSON output + string formatting helpers for the
// lk_static_analyzer.py port.
//
// Upstream: Val Protocol (lk_static_analyzer.py) — the analyzer writes its
// analysis dir with Python's json.dump(..., indent=2) and Python repr() in
// its text reports. The Phase 1 patch engine (ValAnalysis) reads those
// files, so this writer reproduces Python's exact byte layout: 2-space
// indent, ": " after keys, ensure_ascii=True string escaping, no trailing
// newline, and Python repr() for single-quoted strings in .txt/.asm files.
// No AI branding anywhere in this code; real credits only.
package com.cronch.motoflasher.valproto

/** Unsigned 64-bit hex, Python f"{value:0{width}x}" semantics: the value is
 *  a 64-bit address, formatted as its unsigned hex digits, zero-padded. */
fun uHex(value: Long, width: Int = 0, upper: Boolean = false): String {
    val digits = java.lang.Long.toUnsignedString(value, 16)
        .let { if (upper) it.uppercase() else it }
    return digits.padStart(width, '0')
}

/** Signed hex, Python f"{value:0{width}x}" semantics for a value that may be
 *  negative (file offsets): "-0000005" for -5 with width 8. */
fun sHex(value: Long, width: Int = 0, upper: Boolean = false): String {
    if (value >= 0) {
        val digits = value.toString(16).let { if (upper) it.uppercase() else it }
        return digits.padStart(width, '0')
    }
    val mag = if (value == Long.MIN_VALUE) "8000000000000000"
    else (-value).toString(16).let { if (upper) it.uppercase() else it }
    return "-" + mag.padStart(maxOf(0, width - 1), '0')
}

/** "0x" + 16-digit lowercase hex, like Python f"0x{address:016x}". */
fun hex16(value: Long): String = "0x" + uHex(value, 16)

/** "0x" + unpadded lowercase hex, like Python f"0x{value:x}". */
fun hexVar(value: Long): String = "0x" + uHex(value)

/** "0x" + unpadded lowercase hex of a (possibly negative) file offset,
 *  like Python f"0x{file_offset:x}". */
fun hexOff(value: Long): String = "0x" + sHex(value)

/** 8-digit uppercase hex, like Python f"{file_offset:08X}" (HxD offset). */
fun hxd(value: Long): String = sHex(value, 8, upper = true)

/** Python str() of a JSON-ish value, as used inside f-strings in the text
 *  reports: None -> "None", True/False, numbers decimal, strings as-is. */
fun pyStr(value: Any?): String = when (value) {
    null -> "None"
    is Boolean -> if (value) "True" else "False"
    is String -> value
    is Long -> value.toString()
    is Int -> value.toString()
    else -> value.toString()
}

/** Python str.isprintable(): every character except Unicode categories
 *  Other (Cc, Cf, Cs, Co, Cn) and Separator (Zs, Zl, Zp), except ASCII
 *  space U+0020. Java's Character.getType maps onto the same categories. */
fun isPyPrintable(codePoint: Int): Boolean {
    if (codePoint == 0x20) return true
    // Character.getType codes: CONTROL=15, FORMAT=16, SURROGATE=19,
    // PRIVATE_USE=18, UNASSIGNED=0, SPACE_SEPARATOR=12, LINE_SEPARATOR=13,
    // PARAGRAPH_SEPARATOR=14.
    return when (Character.getType(codePoint)) {
        15, 16, 19, 18, 0, 12, 13, 14 -> false
        else -> true
    }
}

/** Python repr() of a str: single quotes unless the string contains a
 *  single quote and no double quote; \t \n \r \\ and \' escapes, other
 *  non-printables as \xhh / \uhhhh / \Uhhhhhhhh. */
fun pyRepr(s: String): String {
    val quote = if ('\'' in s && '"' !in s) '"' else '\''
    val sb = StringBuilder()
    sb.append(quote)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when (cp) {
            '\t'.code -> sb.append("\\t")
            '\n'.code -> sb.append("\\n")
            '\r'.code -> sb.append("\\r")
            '\\'.code -> sb.append("\\\\")
            quote.code -> sb.append('\\').append(quote)
            else -> if (isPyPrintable(cp)) {
                sb.appendCodePoint(cp)
            } else {
                when {
                    cp < 0x100 -> sb.append("\\x%02x".format(cp))
                    cp < 0x10000 -> sb.append("\\u%04x".format(cp))
                    else -> sb.append("\\U%08x".format(cp))
                }
            }
        }
        i += Character.charCount(cp)
    }
    sb.append(quote)
    return sb.toString()
}

/** Minimal JSON value model, insertion-ordered like Python dicts. */
sealed interface PyJson
data class PyObj(val map: LinkedHashMap<String, PyJson>) : PyJson
data class PyArr(val items: List<PyJson>) : PyJson
data class PyStr(val s: String) : PyJson
data class PyInt(val n: Long) : PyJson
data class PyBool(val b: Boolean) : PyJson
object PyNull : PyJson

fun pyObjOf(vararg pairs: Pair<String, PyJson>): PyObj =
    PyObj(linkedMapOf(*pairs))

fun pyObjOf(map: Map<String, PyJson>): PyObj =
    PyObj(LinkedHashMap(map))

fun pyArrOf(vararg items: PyJson): PyArr = PyArr(items.toList())
fun pyArrOf(items: List<PyJson>): PyArr = PyArr(items)
fun pyStrOrNull(s: String?): PyJson = if (s == null) PyNull else PyStr(s)
fun pyIntOrNull(n: Long?): PyJson = if (n == null) PyNull else PyInt(n)

/** Python json.dumps(value, indent=2) with ensure_ascii=True, byte-exact:
 *  2-space indent, ": " after keys, ",\n" between items, empty dict/list as
 *  {} / [], no trailing newline. Non-ASCII is \uXXXX-escaped (astral
 *  characters as surrogate pairs), like CPython's ensure_ascii. */
fun pyJsonDump(value: PyJson): String {
    val sb = StringBuilder()
    dumpValue(value, 0, sb)
    return sb.toString()
}

private fun dumpValue(value: PyJson, level: Int, sb: StringBuilder) {
    when (value) {
        is PyObj -> {
            if (value.map.isEmpty()) {
                sb.append("{}")
                return
            }
            sb.append("{\n")
            val entries = value.map.entries.toList()
            for ((index, entry) in entries.withIndex()) {
                sb.append("  ".repeat(level + 1))
                dumpJsonString(entry.key, sb)
                sb.append(": ")
                dumpValue(entry.value, level + 1, sb)
                if (index < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ".repeat(level))
            sb.append("}")
        }
        is PyArr -> {
            if (value.items.isEmpty()) {
                sb.append("[]")
                return
            }
            sb.append("[\n")
            for ((index, item) in value.items.withIndex()) {
                sb.append("  ".repeat(level + 1))
                dumpValue(item, level + 1, sb)
                if (index < value.items.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ".repeat(level))
            sb.append("]")
        }
        is PyStr -> dumpJsonString(value.s, sb)
        is PyInt -> sb.append(value.n.toString())
        is PyBool -> sb.append(if (value.b) "true" else "false")
        is PyNull -> sb.append("null")
    }
}

/** One JSON string literal, CPython ensure_ascii=True escaping. */
fun dumpJsonString(s: String, sb: StringBuilder) {
    sb.append('"')
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        when (cp) {
            '"'.code -> sb.append("\\\"")
            '\\'.code -> sb.append("\\\\")
            '\n'.code -> sb.append("\\n")
            '\r'.code -> sb.append("\\r")
            '\t'.code -> sb.append("\\t")
            0x08 -> sb.append("\\b")
            0x0C -> sb.append("\\f")
            else -> if (cp < 0x20) {
                sb.append("\\u%04x".format(cp))
            } else if (cp < 0x7F) {
                sb.appendCodePoint(cp)
            } else if (cp <= 0xFFFF) {
                sb.append("\\u%04x".format(cp))
            } else {
                val high = Character.highSurrogate(cp)
                val low = Character.lowSurrogate(cp)
                sb.append("\\u%04x".format(high.code))
                sb.append("\\u%04x".format(low.code))
            }
        }
        i += Character.charCount(cp)
    }
    sb.append('"')
}
