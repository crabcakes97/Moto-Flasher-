// Ramdump research scan + detect report (report-only paths).
// Port of lk_patch_partition.py ramdump_research_scan,
// print_ramdump_research_report and detect_report.
package com.cronch.motoflasher.valproto

import java.io.File

object ValReports {
    private fun exactCStringOffsets(data: ByteArray, token: ByteArray): List<Int> {
        val needle = token + byteArrayOf(0)
        val out = mutableListOf<Int>()
        var start = 0
        while (true) {
            val idx = ValBytes.find(data, needle, start)
            if (idx < 0) return out
            if (idx == 0 || data[idx - 1] == 0.toByte()) out.add(idx)
            start = idx + 1
        }
    }

    private fun handlerRangeRefs(data: ByteArray, base: Long, strOff: Int, handlerOff: Int): List<Int> {
        if (base == 0L || handlerOff < 0) return emptyList()
        val refs = ValPatchCore.findStringRefs(data, base, base + strOff)
        val lo = handlerOff
        val hi = handlerOff + ValPatchCore.RAMDUMP_HANDLER_RANGE
        return refs.filter { it in lo..hi }
    }

    private fun directTableRow(data: ByteArray, base: Long, cmdOff: Int): Pair<Int, Int>? {
        val want = ValBytes.packU64(base + cmdOff)
        var best: Pair<Int, Int>? = null
        var pos = 0
        while (true) {
            val row = ValBytes.find(data, want, pos)
            if (row < 0) return best
            pos = row + 1
            if (row % 8 != 0 || row + 16 > data.size) continue
            val nameVa = ValBytes.u64At(data, row)
            val codeVa = ValBytes.u64At(data, row + 8)
            if (nameVa != base + cmdOff) continue
            if (!(base <= codeVa && codeVa < base + data.size)) continue
            val handlerOff = (codeVa - base).toInt()
            if (handlerOff % 4 != 0) continue
            val word = ValBytes.u32At(data, handlerOff)
            val prologue = ((word and 0xFFC00000L) == 0xD1000000L) ||
                ((word and 0xFFE003E0L) == 0xA98003E0L) ||
                word == 0xD503233FL || word == 0xD503237FL
            if (prologue) return row to handlerOff
            if (best == null) best = row to handlerOff
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun ramdumpResearchScan(data: ByteArray, base: Long): Map<String, Any?> {
        val raw = data.copyOf()
        val cmdOffs = exactCStringOffsets(raw, ValPatchCore.RAMDUMP_CMD_NAME)
        var tableOff: Int? = null
        var handlerOff: Int? = null
        if (base != 0L && cmdOffs.isNotEmpty()) {
            val found = try {
                ValPatchCore.findFastbootCommandHandlerOffset(raw, base, "ramdump")
            } catch (e: Exception) {
                null
            }
            if (found != null) {
                handlerOff = found
                val nameVa = ValBytes.packU64(base + cmdOffs[0])
                var pos = 0
                while (true) {
                    val row = ValBytes.find(raw, nameVa, pos)
                    if (row < 0) break
                    pos = row + 1
                    if (row % 8 != 0 || row + 16 > raw.size) continue
                    val nameVa2 = ValBytes.u64At(raw, row)
                    val codeVa = ValBytes.u64At(raw, row + 8)
                    if (nameVa2 == base + cmdOffs[0] && codeVa == base + found) {
                        tableOff = row
                        break
                    }
                }
            } else {
                val direct = directTableRow(raw, base, cmdOffs[0])
                if (direct != null) {
                    tableOff = direct.first
                    handlerOff = direct.second
                }
            }
        }
        val subcommands = mutableListOf<Map<String, Any?>>()
        for (token in ValPatchCore.RAMDUMP_SUBCOMMANDS) {
            val offs = exactCStringOffsets(raw, token)
            val rangeRefs = mutableListOf<Int>()
            if (handlerOff != null) {
                for (off in offs.take(4)) rangeRefs.addAll(handlerRangeRefs(raw, base, off, handlerOff))
            }
            subcommands.add(
                mapOf(
                    "token" to String(token, Charsets.US_ASCII),
                    "offsets" to offs.take(4),
                    "count" to offs.size,
                    "handler_refs" to rangeRefs.sorted().toSet().toList(),
                )
            )
        }
        val probes = mutableListOf<Map<String, Any?>>()
        for (token in ValPatchCore.RAMDUMP_PROBE_TOKENS) {
            val offs = exactCStringOffsets(raw, token)
            val rangeRefs = mutableListOf<Int>()
            if (handlerOff != null) {
                for (off in offs.take(4)) rangeRefs.addAll(handlerRangeRefs(raw, base, off, handlerOff))
            }
            probes.add(
                mapOf(
                    "token" to String(token, Charsets.US_ASCII),
                    "offsets" to offs.take(4),
                    "count" to offs.size,
                    "handler_refs" to rangeRefs.sorted().toSet().toList(),
                )
            )
        }
        val usb = mutableListOf<Map<String, Any?>>()
        for ((label, needle) in ValPatchCore.RAMDUMP_USB_MARKERS) {
            val offs = ValBytes.findAll(raw, needle)
            var refs = 0
            if (base != 0L) {
                for (off in offs.take(4)) refs += ValPatchCore.findStringRefs(raw, base, base + off).size
            }
            usb.add(mapOf("label" to label, "count" to offs.size, "offsets" to offs.take(4), "adpr_refs" to refs))
        }
        val dead = mutableListOf<Map<String, Any?>>()
        for ((label, needle) in ValPatchCore.RAMDUMP_DEAD_MARKERS) {
            val offs = ValBytes.findAll(raw, needle)
            var refs = 0
            if (base != 0L) {
                for (off in offs.take(4)) refs += ValPatchCore.findStringRefs(raw, base, base + off).size
            }
            val secondRows = mutableListOf<Int>()
            if (base != 0L) {
                val full = "oem ".toByteArray() + needle + byteArrayOf(0)
                val fo = ValBytes.find(raw, full)
                if (fo >= 0) {
                    val want = ValBytes.packU64(base + fo)
                    var pos = 0
                    while (true) {
                        val row = ValBytes.find(raw, want, pos)
                        if (row < 0) break
                        pos = row + 1
                        if (row % 8 == 0 && row + 16 <= raw.size) {
                            val codeVa = ValBytes.u64At(raw, row + 8)
                            if (base <= codeVa && codeVa < base + raw.size) secondRows.add(row)
                        }
                    }
                }
            }
            dead.add(
                mapOf(
                    "label" to label, "count" to offs.size, "offsets" to offs.take(4),
                    "adpr_refs" to refs, "second_rows" to secondRows,
                )
            )
        }
        val absent = ValPatchCore.RAMDUMP_ABSENT_MARKERS.filter { (_, needle) -> ValBytes.find(raw, needle) < 0 }.map { it.first }
        return mapOf(
            "cmd_offsets" to cmdOffs.take(4),
            "cmd_count" to cmdOffs.size,
            "table_offset" to tableOff,
            "handler_offset" to handlerOff,
            "subcommands" to subcommands,
            "probes" to probes,
            "usb" to usb,
            "dead" to dead,
            "absent" to absent,
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun printRamdumpResearchReport(data: ByteArray, analysisDir: File) {
        val lkBase = ValAnalysis.tryLoadBase(analysisDir)
        val arch = ValAnalysis.loadArchitecture(analysisDir)
        println("Report  : ramdump/MRDUMP research (REPORT-ONLY, no LK bytes changed)")
        println("LK size : ${data.size} bytes  arch=$arch")
        if (lkBase != 0L) println("LK base : 0x${lkBase.toString(16)}") else println("LK base : unknown (offsets only, no VA resolution)")
        val scan = ramdumpResearchScan(data, lkBase)
        fun va(off: Int): String = if (lkBase != 0L) " VA=0x${(lkBase + off).toString(16)}" else ""
        val cmdOffs = scan["cmd_offsets"] as List<Int>
        if (cmdOffs.isNotEmpty()) {
            val shown = cmdOffs.joinToString(", ") { "0x${it.toString(16)} / HxD ${"%08X".format(it)}${va(it)}" }
            println("CmdStr  : 'ramdump' x${scan["cmd_count"]} @ $shown")
        } else {
            println("CmdStr  : 'ramdump' ABSENT (no fastboot ramdump surface)")
        }
        val tableOff = scan["table_offset"] as Int?
        val handlerOff = scan["handler_offset"] as Int?
        if (tableOff != null && handlerOff != null) {
            println("CmdTbl  : [name-ptr, handler-ptr] row @ 0x${tableOff.toString(16)} / HxD ${"%08X".format(tableOff)}")
            println("Handler : entry @ 0x${handlerOff.toString(16)} / HxD ${"%08X".format(handlerOff)}${va(handlerOff)} (strcmp chain within +0x${ValPatchCore.RAMDUMP_HANDLER_RANGE.toString(16)})")
        } else {
            println("CmdTbl  : NO ROW (command table pair unresolved; dispatcher shape unknown on this build)")
        }
        for (sub in scan["subcommands"] as List<Map<String, Any?>>) {
            val refs = sub["handler_refs"] as List<Int>
            if (refs.isNotEmpty()) {
                val shown = (sub["offsets"] as List<Int>).take(2).joinToString(", ") { "0x${it.toString(16)}${va(it)}" }
                val rshown = refs.take(6).joinToString(", ") { "0x${it.toString(16)}" }
                println("SubCmd  : ${"%-8s".format(sub["token"])} x${sub["count"]} @ $shown handler-refs=[$rshown]")
            } else {
                println("SubCmd  : ${"%-8s".format(sub["token"])} x${sub["count"]} NO handler-range ref (not parsed by this handler)")
            }
        }
        for (probe in scan["probes"] as List<Map<String, Any?>>) {
            val refs = probe["handler_refs"] as List<Int>
            if (refs.isNotEmpty()) {
                val rshown = refs.take(6).joinToString(", ") { "0x${it.toString(16)}" }
                println("Probe   : ${"%-8s".format(probe["token"])} HANDLER-REF [$rshown] (freeze surface: audit this path first)")
            } else {
                println("Probe   : ${"%-8s".format(probe["token"])} no subcommand slot (x${probe["count"]} generic hits, none referenced from the handler range)")
            }
        }
        for (marker in scan["usb"] as List<Map<String, Any?>>) {
            val count = marker["count"] as Int
            if (count == 0) {
                println("USB     : ${marker["label"]} ABSENT")
                continue
            }
            val shown = (marker["offsets"] as List<Int>).take(3).joinToString(", ") { "0x${it.toString(16)}${va(it)}" }
            val extra = if (count > 3) " (+${count - 3} more)" else ""
            println("USB     : ${marker["label"]} x$count @ $shown$extra adrp-refs=${marker["adpr_refs"]}")
        }
        for (marker in scan["dead"] as List<Map<String, Any?>>) {
            val count = marker["count"] as Int
            val adprRefs = marker["adpr_refs"] as Int
            val secondRows = marker["second_rows"] as List<Int>
            if (count == 0) {
                println("Dead    : ${marker["label"]} ABSENT")
            } else if (adprRefs != 0) {
                println("Dead    : ${marker["label"]} x$count REFERENCED (adpr-refs=$adprRefs: live backend, map it)")
            } else if (secondRows.isNotEmpty()) {
                val rshown = secondRows.take(2).joinToString(", ") { "0x${it.toString(16)}" }
                println("Dead    : ${marker["label"]} x$count in full-name table [$rshown] (help/gate list only: live dispatcher rejects it, cannot freeze, cannot pull)")
            } else {
                println("Dead    : ${marker["label"]} x$count present but UNREFERENCED (no code path: cannot freeze, cannot pull)")
            }
        }
        for (label in scan["absent"] as List<String>) {
            println("Absent  : $label NOT FOUND (do not assume this log/range exists)")
        }
        println("Freeze  : subcommand parser holds no DRAM-range check on the -111-3")
        println("Freeze  : family (strcmp chain + flag store only). A pull/now Data")
        println("Freeze  : Abort would live in the dump backend behind the enable")
        println("Freeze  : path: trace BL targets from the handler entry with")
        println("Freeze  : capstone, then audit CBZ/CBNZ/CMP vs 0x40000000 there.")
        println("Status  : REPORT ONLY - payload left unmodified by design.")
    }

    /** Detect-only report. The payload comes from the LkImage
     *  (`lkImage.partitions[imageName].data`) — the analysis dir's lk.bin is
     *  the analyzer's extraction of that same payload and is never read here. */
    fun detectReport(payload: ByteArray, analysisDir: File, experimental: Boolean): Int {
        val data: ByteArray = payload
        val arch = try {
            ValAnalysis.loadArchitecture(analysisDir)
        } catch (e: Exception) {
            "aarch64"
        }
        val base = ValAnalysis.tryLoadBase(analysisDir)
        val (build, describe) = ValPatchCore.fingerprintLk(data, arch, base)
        println("Detect  : $describe")
        println("Known   : ${build?.id ?: "NO (unknown build)"}")
        if (arch != "aarch64") {
            println("Modem   : REFUSE (aarch64 discovery only)")
            println("Factory : REFUSE (aarch64 discovery only)")
            println("Force   : REFUSE (aarch64 discovery only)")
            println("Bootmode: REFUSE (aarch64 discovery only)")
            return 1
        }
        if (build == null && !experimental) {
            println("Modem   : REFUSE unknown build without --experimental")
            println("Factory : REFUSE unknown build without --experimental")
            println("Force   : REFUSE unknown build without --experimental")
            println("Bootmode: REFUSE unknown build without --experimental")
            return 1
        }
        var rc = 0
        val families: List<Triple<String, (ByteArray, Long) -> List<GateDef>, (KnownBuild) -> List<Int>>> = listOf(
            Triple("modem", { d, b -> ValPatchCore.discoverModemGates(d, b) }, { it.modemOffsets }),
            Triple("factory", { d, b -> ValPatchCore.discoverFactoryGates(d, b) }, { it.factoryOffsets }),
            Triple("force", { d, b -> ValPatchCore.discoverFactoryForceGates(d, b) }, { it.forceOffsets }),
            Triple("mrdump", { d, b -> ValPatchCore.discoverMrdumpGates(d, b) }, { it.mrdumpOffsets }),
            Triple("ssm-force", { d, b -> ValPatchCore.discoverSsmForceGates(d, b) }, { it.ssmForceOffsets }),
        )
        for ((family, discover, key) in families) {
            val label = "%-8s".format(family.lowercase().replaceFirstChar { it.uppercase() })
            val gates = try {
                discover(data, base)
            } catch (e: IllegalArgumentException) {
                println("$label: REFUSE ${e.message}")
                rc = 1
                continue
            }
            if (build != null) {
                val found = gates.map { it.offset }
                if (found.sorted() != key(build).sorted()) {
                    println("$label: REFUSE layout drift ${found.map { "0x${it.toString(16)}" }} vs ${key(build).map { "0x${it.toString(16)}" }}")
                    rc = 1
                    continue
                }
            }
            val offsets = gates.joinToString(", ") { "0x${it.offset.toString(16)}" }
            println("$label: OK gates=[$offsets]" + (if (build == null) " (experimental, unknown build)" else ""))
        }
        try {
            val (hookOff, _) = ValPatchCore.discoverBootmodeHook(data, base)
            if (build != null && hookOff != build.bootmodeOffsets[0]) {
                println("Bootmode: REFUSE layout drift 0x${hookOff.toString(16)} vs 0x${build.bootmodeOffsets[0].toString(16)}")
                rc = 1
            } else {
                println("Bootmode: OK hook=[0x${hookOff.toString(16)}]" + (if (build == null) " (experimental, unknown build)" else ""))
            }
        } catch (e: IllegalArgumentException) {
            println("Bootmode: REFUSE ${e.message}")
            rc = 1
        }
        try {
            val (lhook, lcave, _) = ValPatchCore.discoverLockspoof(data, base)
            println("Lockspoof: OK hook=[0x${lhook.toString(16)}] cave=[0x${lcave.toString(16)}]" + (if (build == null) " (experimental, unknown build)" else ""))
        } catch (e: IllegalArgumentException) {
            println("Lockspoof: REFUSE ${e.message}")
            rc = 1
        }
        try {
            val ssm = ValPatchCore.discoverSsmSurface(data)
            println("Ssm     : OK verbs=[${ssm.joinToString(", ") { "0x${it.toString(16)}" }}]" + (if (build == null) " (experimental, unknown build)" else ""))
        } catch (e: IllegalArgumentException) {
            println("Ssm     : REFUSE ${e.message}")
            rc = 1
        }
        val rd = try {
            ramdumpResearchScan(data, base)
        } catch (e: Exception) {
            null
        }
        val handlerOff = rd?.get("handler_offset") as Int?
        if (rd != null && handlerOff != null) {
            val subs = (rd["subcommands"] as List<Map<String, Any?>>)
                .filter { (it["handler_refs"] as List<Int>).isNotEmpty() }
                .joinToString(",") { it["token"] as String }
                .ifEmpty { "none-parsed" }
            println("Ramdump : OK handler=0x${handlerOff.toString(16)} table=0x${(rd["table_offset"] as Int?)?.toString(16)} sub=[$subs]")
        } else {
            println("Ramdump : NO ROW (no ramdump command-table pair on this build)")
        }
        return rc
    }
}
