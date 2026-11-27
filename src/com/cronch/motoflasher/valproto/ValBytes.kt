// Val Protocol — portable engine, Kotlin port.
// Upstream: Val Protocol (lk_patch_partition.py, lk_repack_signed.py,
// lk_keygen.py, lk_oem_cmd_mapper.py, lk_auto_patch.py,
// tools/sign_mtk_cert.py, tools/build-part-img.py, tools/parse_mtk_certs.py).
// Container parsing reuses LibLk (Kotlin port of R0rt1z2/liblk by Roger Ortiz).
// No AI branding anywhere in this code; real credits only.
package com.cronch.motoflasher.valproto

/** Little-endian / byte helpers mirroring Python struct + bytes behaviour. */
object ValBytes {
    fun u32At(data: ByteArray, off: Int): Long {
        require(off >= 0 && off + 4 <= data.size) { "u32 read out of range at 0x${off.toString(16)}" }
        return (data[off].toLong() and 0xFF) or
            ((data[off + 1].toLong() and 0xFF) shl 8) or
            ((data[off + 2].toLong() and 0xFF) shl 16) or
            ((data[off + 3].toLong() and 0xFF) shl 24)
    }

    fun u32At(data: ByteArray, off: Int, default: Long): Long =
        if (off < 0 || off + 4 > data.size) default else u32At(data, off)

    fun u16At(data: ByteArray, off: Int): Int {
        require(off >= 0 && off + 2 <= data.size) { "u16 read out of range at 0x${off.toString(16)}" }
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }

    fun u64At(data: ByteArray, off: Int): Long {
        return u32At(data, off) or (u32At(data, off + 4) shl 32)
    }

    fun packU32(value: Long): ByteArray {
        val v = value and 0xFFFFFFFFL
        return byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )
    }

    fun packU16(value: Int): ByteArray {
        val v = value and 0xFFFF
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }

    fun packU64(value: Long): ByteArray = packU32(value) + packU32(value ushr 32)

    /** Python bytes.find(needle, start) semantics; -1 when absent. */
    fun find(data: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty()) return start.coerceIn(0, data.size)
        if (data.size < needle.size) return -1
        outer@ for (i in start.coerceAtLeast(0)..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    fun findAll(data: ByteArray, needle: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var start = 0
        while (true) {
            val idx = find(data, needle, start)
            if (idx < 0) return out
            out.add(idx)
            start = idx + 1
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hex(bytes: ByteArray, off: Int, len: Int): String = hex(bytes.copyOfRange(off, off + len))

    fun fromHex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        require(clean.length % 2 == 0) { "odd hex string" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Python bytes.ljust(n, b'\x00'). */
    fun ljust(data: ByteArray, size: Int): ByteArray {
        if (data.size >= size) return data.copyOf()
        return data + ByteArray(size - data.size)
    }

    fun readCString(data: ByteArray, offset: Int, maxLen: Int = 96): ByteArray {
        if (offset < 0 || offset >= data.size) return ByteArray(0)
        var end = offset
        val limit = minOf(data.size, offset + maxLen)
        while (end < limit && data[end] != 0.toByte()) end++
        return data.copyOfRange(offset, end)
    }
}

fun ByteArray.hex(): String = ValBytes.hex(this)

/** Python `repr()` of a bytes object: `b'...'` (or `b"..."` when the bytes
 *  contain a single quote but no double quote), with `\t \n \r \\ \'`
 *  escapes and `\xhh` for other non-printables. Matches the `{bytes!r}`
 *  renderings in lk_patch_partition.py's refusal messages. */
fun ByteArray.pyBytesRepr(): String {
    val useDouble = any { it == '\''.code.toByte() } && none { it == '"'.code.toByte() }
    val q = if (useDouble) '"' else '\''
    val sb = StringBuilder("b").append(q)
    for (b in this) {
        when (b) {
            '\\'.code.toByte() -> sb.append("\\\\")
            '\t'.code.toByte() -> sb.append("\\t")
            '\n'.code.toByte() -> sb.append("\\n")
            '\r'.code.toByte() -> sb.append("\\r")
            '\''.code.toByte() -> if (useDouble) sb.append('\'') else sb.append("\\'")
            '"'.code.toByte() -> if (useDouble) sb.append("\\\"") else sb.append('"')
            else -> {
                val u = b.toInt() and 0xFF
                if (u in 0x20..0x7E) sb.append(u.toChar()) else sb.append("\\x%02x".format(u))
            }
        }
    }
    return sb.append(q).toString()
}

/** Python `repr()` of a list of plain-ASCII strings: `['a', 'b']`. */
fun List<String>.pyStrListRepr(): String = joinToString(", ", "[", "]") { "'$it'" }

/** Minimal JSON parser (stdlib-only; the app has no JSON library).
 *  Mirrors Python json.loads for the analysis-dir files: objects, arrays,
 *  strings (with escapes incl. \uXXXX), numbers, true/false/null.
 *  Numbers that are integral and fit in Long come back as Long, else Double.
 */
sealed interface JsonVal
data class JsonObj(val map: Map<String, JsonVal>) : JsonVal
data class JsonArr(val items: List<JsonVal>) : JsonVal
data class JsonStr(val s: String) : JsonVal
data class JsonNum(val n: Number) : JsonVal
data class JsonBool(val b: Boolean) : JsonVal
object JsonNull : JsonVal

object ValJson {
    fun parse(text: String): JsonVal {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos >= text.length) { "trailing data after JSON value" }
        return v
    }

    fun parseFile(path: java.io.File): JsonVal = parse(path.readText(Charsets.UTF_8))

    private class Parser(val s: String) {
        var pos = 0
        fun skipWs() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
        fun expect(c: Char) { skipWs(); require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }; pos++ }

        fun value(): JsonVal {
            skipWs()
            return when (peek()) {
                '{' -> { pos++; valObj() }
                '[' -> { pos++; valArr() }
                '"' -> JsonStr(string())
                't' -> { expectWord("true"); JsonBool(true) }
                'f' -> { expectWord("false"); JsonBool(false) }
                'n' -> { expectWord("null"); JsonNull }
                else -> number()
            }
        }

        fun expectWord(w: String) {
            require(s.startsWith(w, pos)) { "bad literal at $pos" }
            pos += w.length
        }

        fun valObj(): JsonObj {
            val m = linkedMapOf<String, JsonVal>()
            skipWs()
            if (peek() == '}') { pos++; return JsonObj(m) }
            while (true) {
                skipWs()
                val k = string()
                expect(':')
                m[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return JsonObj(m) }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        fun valArr(): JsonArr {
            val list = mutableListOf<JsonVal>()
            skipWs()
            if (peek() == ']') { pos++; return JsonArr(list) }
            while (true) {
                list.add(value())
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return JsonArr(list) }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        fun string(): String {
            require(peek() == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val cp = s.substring(pos, pos + 4).toInt(16)
                                pos += 4
                                sb.append(cp.toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        fun number(): JsonNum {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val txt = s.substring(start, pos)
            return try {
                JsonNum(txt.toLong())
            } catch (e: NumberFormatException) {
                JsonNum(txt.toDouble())
            }
        }
    }
}

/** Convenience accessors mirroring Python dict.get()/[...] on parsed JSON. */
fun JsonVal?.asObj(): JsonObj = (this as? JsonObj) ?: throw IllegalArgumentException("JSON value is not an object")
fun JsonVal?.asArr(): JsonArr = (this as? JsonArr) ?: throw IllegalArgumentException("JSON value is not an array")
fun JsonVal?.asStr(): String = (this as? JsonStr)?.s ?: throw IllegalArgumentException("JSON value is not a string")
fun JsonVal?.asLong(): Long = when (this) {
    is JsonNum -> n.toLong()
    is JsonStr -> s.toLong()
    else -> throw IllegalArgumentException("JSON value is not a number")
}
fun JsonVal?.asInt(): Int = asLong().toInt()
fun JsonVal?.asBool(): Boolean = (this as? JsonBool)?.b ?: false
fun JsonObj.get(key: String): JsonVal? = map[key]
fun JsonObj.str(key: String, default: String? = null): String? =
    (map[key] as? JsonStr)?.s ?: default
fun JsonObj.long(key: String, default: Long? = null): Long? =
    when (val v = map[key]) {
        is JsonNum -> v.n.toLong()
        is JsonStr -> v.s.toLongOrNull()
        else -> null
    } ?: default
fun JsonObj.arr(key: String): List<JsonVal> = (map[key] as? JsonArr)?.items ?: emptyList()
fun JsonObj.obj(key: String): JsonObj? = map[key] as? JsonObj
