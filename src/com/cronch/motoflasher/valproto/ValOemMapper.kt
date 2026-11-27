// lk_oem_cmd_mapper.py — map every fastboot oem command gate in an LK image.
// Read-only static analysis: command-name strings, ADRP+ADD / ADR xrefs,
// global restriction gates (shared dispatcher), known-gate comparison.
// NOTE: analyze() reuses ValPatchCore gate discovery; no Capstone is used
// here (the Python file imports Capstone but never calls it).
package com.cronch.motoflasher.valproto

import com.cronch.motoflasher.LibLk
import java.io.File

object ValOemMapper {
    val COMMANDS: List<Pair<String, String>> = listOf(
        "unlock" to "top", "lock" to "top", "get_unlock_data" to "top",
        "device-info" to "top", "get_config" to "top", "rm_config" to "top",
        "read_sv" to "top", "config" to "top", "ramdump" to "top",
        "hw" to "top", "dmesg" to "top", "dump-chipid" to "top",
        "ddrtest" to "top", "enable-hw-factory" to "top", "backlight" to "top",
        "display" to "top", "off-mode-charge" to "top",
        "select-display-panel" to "top", "setbrightness" to "top",
        "enter-shipmode" to "top", "continue-factory" to "top",
        "bp-tools-on" to "top", "bp-tools-off" to "top", "qcom-on" to "top",
        "qcom-off" to "top", "partition" to "top", "erase" to "top",
        "fb_mode_set" to "top", "fb_mode_clear" to "top",
        "force-fastboot" to "top", "enable-verity" to "top",
        "disable-verity" to "top", "enable-verification" to "top",
        "disable-verification" to "top", "clear_dm_verity_error" to "top",
        "disable-thinkshield" to "top", "enable-thinkshield" to "top",
        "fuse-lock" to "top", "fuse-version" to "top", "help" to "top",
        "uart" to "top", "p2u" to "top", "passwd" to "top",
        "writeimei" to "top", "getimei1" to "top", "getimei2" to "top",
        "ultraflash_en" to "top", "ultraflash:system_a" to "top",
        "barcode" to "top", "halt" to "top", "reboot-rom" to "top",
        "watchdog" to "top", "hwid" to "top", "cdms" to "top",
        "get_socid" to "top", "show_screen" to "top", "p2u" to "top",
        "dump_pllk_log" to "top", "printk-ratelimit" to "top",
        "mrdump_chkimg" to "top", "mrdump_fallocate" to "top",
        "mrdump_out_set" to "top", "cid_prov_req" to "top",
        "fsg-id" to "config", "carrier" to "config", "console" to "config",
        "protect" to "config", "unprotect" to "config", "facmode" to "config",
        "fac" to "config", "unlockable" to "config", "ro.build" to "config",
        "info" to "hw", "test" to "hw", "factory" to "hw",
        "enable" to "ramdump", "pull" to "ramdump", "now" to "ramdump",
        "clear" to "ramdump",
    )

    val DONT_PROBE: Set<String> = setOf(
        "lock", "unlock", "erase", "partition", "writeimei", "fuse-lock",
        "fuse-version", "enter-shipmode", "ultraflash_en", "ultraflash:system_a",
        "fb_mode_set", "cid_prov_req", "passwd", "protect", "unprotect",
        "off-mode-charge", "halt", "reboot-rom", "watchdog", "setbrightness",
    )

    val KNOWN_GATES: Map<Int, String> = mapOf(0xF3F4 to "restricted-tbz", 0xAD88 to "config-unprotect-tbz")
    const val AARCH64_NOP_HEX = "1f2003d5"

    val LIVE_PROVEN: Map<String, String> = mapOf(
        "ramdump" to "works", "config" to "works", "hw" to "works",
        "cdms" to "works", "read_sv" to "works", "get_unlock_data" to "works",
        "cid_prov_req" to "works", "show_screen" to "works", "hwid" to "works",
        "fb_mode_set" to "works", "fb_mode_clear" to "works",
        "off-mode-charge" to "works",
        "usb2jtag" to "absent", "p2u" to "absent", "get_socid" to "absent",
        "mrdump_chkimg" to "absent", "mrdump_fallocate" to "absent",
        "mrdump_out_set" to "absent", "dump_pllk_log" to "absent",
        "printk-ratelimit" to "absent",
    )

    fun stringHits(data: ByteArray, needle: ByteArray): List<Int> = ValBytes.findAll(data, needle)

    fun stringStart(data: ByteArray, idx: Int): Int = ValPatchCore.stringStart(data, idx)

    /** File offsets of ADRP+ADD or ADR resolving exactly to va. */
    fun codeRefs(data: ByteArray, base: Long, va: Long): List<Int> {
        val out = mutableListOf<Int>()
        val n = data.size - 20
        var off = 0
        while (off < n) {
            val word = ValBytes.u32At(data, off)
            if ((word and 0x9F000000L) == 0x90000000L) {
                val cur = base + off
                var imm = (((word shr 5) and 0x7FFFFL) shl 2) or ((word shr 29) and 0x3L)
                if (imm and 0x100000L != 0L) imm -= 0x200000L
                if ((cur and -0x1000L) + imm * 0x1000L != (va and -0x1000L)) {
                    off += 4
                    continue
                }
                val rd = (word and 0x1FL).toInt()
                for (jump in 1..4) {
                    val word2 = ValBytes.u32At(data, off + jump * 4)
                    if ((word2 and 0xFF800000L) != 0x91000000L || ((word2 shr 5) and 0x1FL).toInt() != rd) continue
                    var imm12 = (word2 shr 10) and 0xFFFL
                    if ((word2 shr 22) and 0x3L == 1L) imm12 = imm12 shl 12
                    if (((cur and -0x1000L) + imm * 0x1000L) + imm12 == va) out.add(off)
                    break
                }
            } else if ((word and 0x9F000000L) == 0x10000000L) {
                val cur = base + off
                var imm = (((word shr 5) and 0x7FFFFL) shl 2) or ((word shr 29) and 0x3L)
                if (imm and 0x100000L != 0L) imm -= 0x200000L
                if (cur + imm * 4 == va) out.add(off)
            }
            off += 4
        }
        return out
    }

    data class Payload(val bytes: ByteArray, val base: Long, val source: String)

    fun loadPayload(path: File): Payload {
        val data = path.readBytes()
        if (data.size >= 4 && data[0] == 0x88.toByte() && data[1] == 0x16.toByte() && data[2] == 0x88.toByte() && data[3] == 0x58.toByte()) {
            val img = LibLk.parse(data)
            val lk = img.partitions.firstOrNull { it.name == "lk" }
                ?: throw IllegalStateException("container has no 'lk' subimage")
            return Payload(lk.data, 0L, "container:lk")
        }
        return Payload(data, 0L, "raw")
    }

    @Suppress("UNCHECKED_CAST")
    fun analyze(payload: ByteArray, base: Long): Map<String, Any?> {
        val globalGates = mutableListOf<Map<String, Any?>>()
        for ((anchorKey, label) in listOf("restricted-log" to "restricted-tbz", "notallowed-log" to "config-unprotect-tbz")) {
            val deny = try {
                ValPatchCore.denyBlockForAnchor(payload, base, anchorKey)
            } catch (e: Exception) {
                continue
            }
            for (off in ValPatchCore.backscanBranch(payload, base, deny, setOf("tbz0"), nearestOnly = true)) {
                val raw = payload.copyOfRange(off, off + 4)
                globalGates.add(
                    mapOf(
                        "offset" to "0x${off.toString(16)}", "label" to label,
                        "anchor" to anchorKey, "bytes" to raw.hex(),
                    )
                )
            }
        }
        val known = globalGates.map { (it["offset"] as String).removePrefix("0x").toLong(16).toInt() }.toSet() intersect KNOWN_GATES.keys
        val entries = linkedMapOf<String, Map<String, Any?>>()
        for ((name, kind) in COMMANDS) {
            if (name in entries) continue
            val needle = name.toByteArray(Charsets.US_ASCII)
            val hits = stringHits(payload, needle)
            val locs = mutableListOf<Int>()
            for (hit in hits) {
                val end = hit + needle.size
                val nxt = if (end < payload.size) payload[end] else 0
                if (nxt == 0.toByte() || nxt == ' '.code.toByte() || nxt == '\n'.code.toByte() ||
                    nxt == ','.code.toByte() || nxt == ':'.code.toByte() || nxt == '_'.code.toByte() || nxt == '-'.code.toByte()
                ) locs.add(stringStart(payload, hit))
            }
            val sortedLocs = locs.sorted().toSet().toList()
            if (sortedLocs.isEmpty()) {
                entries[name] = mapOf("present" to false, "status" to "ABSENT", "kind" to kind, "live_probe" to (name !in DONT_PROBE))
                continue
            }
            val refs = mutableListOf<Int>()
            for (loc in sortedLocs.take(6)) refs.addAll(codeRefs(payload, base, base + loc))
            val sortedRefs = refs.sorted().toSet().toList()
            val live = LIVE_PROVEN[name]
            val status = when {
                live == "works" -> "PROVEN_LIVE"
                live == "absent" -> "PROVEN_ABSENT_LIVE"
                sortedRefs.isEmpty() -> "NO_HANDLER"
                else -> "ROUTED"
            }
            entries[name] = mapOf(
                "present" to true, "status" to status, "kind" to kind,
                "str_offsets" to sortedLocs.take(4).map { "0x${it.toString(16)}" },
                "refs" to sortedRefs.take(6).map { "0x${it.toString(16)}" },
                "live_probe" to (name !in DONT_PROBE),
            )
        }
        val gatesMatchKnown = known.sorted() == (KNOWN_GATES.keys intersect globalGates.map { (it["offset"] as String).removePrefix("0x").toLong(16).toInt() }.toSet()).sorted()
        return mapOf(
            "global_gates" to globalGates,
            "gates_match_known" to gatesMatchKnown,
            "commands" to entries,
        )
    }

    /** Port of main(): analyze + write JSON + print the summary table. */
    fun run(image: File, baseArg: String = "auto", out: File? = null): Int {
        val payload = loadPayload(image)
        val base = if (baseArg == "auto") payload.base else java.lang.Long.parseUnsignedLong(baseArg, 16)
        val result = analyze(payload.bytes, base)
        val entries = result["commands"] as Map<String, Map<String, Any?>>
        val outMap = linkedMapOf<String, Any?>(
            "image" to image.toString(),
            "base" to "0x${uHex(base)}",
            "known_gates" to KNOWN_GATES.mapKeys { "0x${it.key.toString(16)}" },
            "global_gates" to result["global_gates"],
            "gates_match_known" to result["gates_match_known"],
            "commands" to entries,
        )
        val outPath = out ?: File(image.parentFile, image.nameWithoutExtension + ".oemmap.json")
        outPath.writeText(jsonDumps(outMap, indent = 1))
        println("GLOBAL RESTRICTION GATES (shared dispatcher):")
        for (gate in result["global_gates"] as List<Map<String, Any?>>) {
            val known = if ((gate["offset"] as String).removePrefix("0x").toLong(16).toInt() in KNOWN_GATES) "KNOWN" else "NEW"
            println("  ${gate["offset"]} ${gate["label"]} (${gate["anchor"]}) [$known]")
        }
        println("gates_match_known=${result["gates_match_known"]}")
        println()
        val counts = mutableMapOf<String, Int>()
        println("${"%-26s".format("COMMAND")} | ${"%-4s".format("REFS")} | STATUS")
        for (name in entries.keys.sorted()) {
            val entry = entries[name]!!
            val status = entry["status"] as String
            counts[status] = (counts[status] ?: 0) + 1
            val nrefs = (entry["refs"] as? List<*>)?.size ?: 0
            println("${"%-26s".format(name)} | ${"%4d".format(nrefs)} | $status")
        }
        println()
        println(counts)
        println("JSON: $outPath")
        return 0
    }

    /** Minimal JSON writer matching Python json.dumps(..., indent=1). */
    fun jsonDumps(value: Any?, indent: Int = 1): String {
        val sb = StringBuilder()
        writeJson(value, indent, 0, sb)
        return sb.toString()
    }

    private fun writeJson(value: Any?, indent: Int, level: Int, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is Number -> sb.append(value.toString())
            is String -> sb.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")).append('"')
            is ByteArray -> sb.append('"').append(value.hex()).append('"')
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val pad = " ".repeat(indent * (level + 1))
                val entries = value.entries.toList()
                for ((i, e) in entries.withIndex()) {
                    sb.append(pad)
                    writeJson(e.key.toString(), indent, level + 1, sb)
                    sb.append(": ")
                    writeJson(e.value, indent, level + 1, sb)
                    if (i < entries.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(" ".repeat(indent * level)).append("}")
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                val pad = " ".repeat(indent * (level + 1))
                for ((i, item) in value.withIndex()) {
                    sb.append(pad)
                    writeJson(item, indent, level + 1, sb)
                    if (i < value.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(" ".repeat(indent * level)).append("]")
            }
            else -> sb.append('"').append(value.toString()).append('"')
        }
    }
}
