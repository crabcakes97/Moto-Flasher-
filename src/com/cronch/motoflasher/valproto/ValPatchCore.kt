// Gate discovery + application for lk_patch_partition.py.
// Cross-device discovery (anchor string -> code xref -> backscan feeder)
// with known-build cross-checks and old-byte gates. Raises on any gap.
package com.cronch.motoflasher.valproto

import java.io.File

data class GatePatch(val label: String, val offset: Int, val old: ByteArray, val new: ByteArray)
data class GateDef(val label: String, val offset: Int, val old: ByteArray, val new: ByteArray, val why: String)
data class KnownBuild(
    val id: String, val arch: String, val base: Long, val versionNeedle: ByteArray,
    val modemOffsets: List<Int>, val factoryOffsets: List<Int>, val forceOffsets: List<Int>,
    val mrdumpOffsets: List<Int>, val ssmForceOffsets: List<Int>, val bootmodeOffsets: List<Int>,
)

object ValPatchCore {
    val CMP_W8_ZERO = ValBytes.fromHex("1f010071")
    val CMP_W8_ONE = ValBytes.fromHex("1f050071")
    val MOV_W0_W20 = ValBytes.fromHex("e003142a")
    val MOV_W0_ZERO = ValBytes.fromHex("00008052")
    val MOV_W0_ONE = ValBytes.fromHex("20008052")
    val RET = ValBytes.fromHex("c0035fd6")
    val THUMB_MOVS_R0_ONE_BX_LR = ValBytes.fromHex("01207047")
    val THUMB_MOVS_R0_ZERO_NOP = ValBytes.fromHex("002000bf")
    val THUMB_FRP_ZERO_BOOL = ValBytes.fromHex("b0fa80f04009")
    val THUMB_FRP_ONE_BOOL = ValBytes.fromHex("002000bf00bf")
    val PRINTABLE_TOKEN_BYTES = (0x21 until 0x7F).toList()
    val ALNUM_TOKEN_BYTES = ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").map { it.code }
    val RUNTIME_SERIAL_TOKEN_POSITIONS = (0 until 10).toList()
    val KEY_TOKEN_DEBUG_STOPS = listOf("caller_before_key", "entry", "before_serial", "after_serial", "token_ok", "token_fail")
    val THUMB_NOP = ValBytes.fromHex("00bf")
    val THUMB_POP_R4_PC = ValBytes.fromHex("10bd")
    val AARCH64_NOP = ValBytes.fromHex("1f2003d5")

    val MODEM_RESEARCH_MARKERS: List<Pair<String, ByteArray>> = listOf(
        "ccci-load-modem" to "platform_load_modem".toByteArray(),
        "ccci-resv-mem" to "ccci_resv_named_memory".toByteArray(),
        "ccci-apply-mpu" to "ccci_plat_apply_mpu_setting".toByteArray(),
        "ccci-sec-data" to "ccci_sec_data".toByteArray(),
        "ccci-md-sib-mem" to "md1_sib_mem".toByteArray(),
        "ccci-md-bank4" to "md1_bank4_cache_info".toByteArray(),
        "emi-mpu-set" to "emi_mpu_set_protection".toByteArray(),
        "mmu-arm64-unmap" to "arm64_mmu_unmap".toByteArray(),
        "mmu-arm64-src" to "arch/arm64/mmu.c".toByteArray(),
        "mblock-alloc" to "motorola_alloc_mblock".toByteArray(),
        "wdt-doe-setup" to "mtk_wdt_doe_setup".toByteArray(),
        "wdt-apwdt-doe" to "apwdt is disabled by doe".toByteArray(),
    )
    val MODEM_ABSENT_MARKERS: List<Pair<String, ByteArray>> = listOf(
        "modem_auth" to "modem_auth".toByteArray(),
        "load_modem_fw" to "load_modem_fw".toByteArray(),
        "mmu_table_init" to "mmu_table_init".toByteArray(),
        "armv7_mmu_init" to "armv7_mmu_init".toByteArray(),
    )
    val MODEM_SIZE_GATES: List<GateDef> = listOf(
        GateDef("region-id-gate", 0x49A9C, ValBytes.fromHex("21060054"), ValBytes.fromHex("1f2003d5"), "cmp w22,#0x11c / b.ne unknown-region-log -> fall through"),
        GateDef("size-bound-gate-1", 0x49AAC, ValBytes.fromHex("88040054"), ValBytes.fromHex("1f2003d5"), "cmp w22,w20 / b.hi size-fail-log -> fall through to continue"),
        GateDef("size-bound-gate-2", 0x49B24, ValBytes.fromHex("c8000054"), ValBytes.fromHex("1f2003d5"), "cmp w22,w20 / b.hi size-fail-log -> fall through to continue"),
    )
    val FACTORY_FORCE_GATES: List<GateDef> = listOf(
        GateDef("factory-boot-early", 0x159CC, ValBytes.fromHex("a0000054"), ValBytes.fromHex("05000014"), "cmp x8,x9 vs factory + b.eq early -> b always"),
        GateDef("factory-boot-main", 0x17700, ValBytes.fromHex("80010054"), ValBytes.fromHex("0c000014"), "cmp x8,x9 vs factory + b.eq main -> b always"),
        GateDef("factory-cable-ne", 0x17ECC, ValBytes.fromHex("c1010054"), ValBytes.fromHex("1f2003d5"), "cmp w0,#2 + b.ne skip -> nop falls into cable path"),
    )
    const val BOOTMODE_CAVE_OFF = 0xCE080
    const val BOOTMODE_CAVE_SIZE = 0x200
    val BOOTMODE_CMDLINE_STR = "androidboot.bootmode=factory\u0000".toByteArray(Charsets.US_ASCII)
    const val BOOTMODE_HOOK_OFF = 0x1ADF0
    const val BOOTMODE_SETTER_OFF = 0x28DF0

    val MRDUMP_FORCE_GATES: List<GateDef> = listOf(
        GateDef("mrdump-enable", 0x3A180, ValBytes.fromHex("f3031f2a"), ValBytes.fromHex("53008052"), "enable resolver fallback-0 -> armed-2"),
        GateDef("mrdump-output", 0x39C54, ValBytes.fromHex("f4031f2a"), ValBytes.fromHex("74008052"), "output mapper unknown-0 -> internal-storage-3"),
        GateDef("mrdump-fullmem", 0x9268, ValBytes.fromHex("a0040054"), ValBytes.fromHex("25000014"), "fallocate w8==2-only -> always fullmem"),
    )
    const val LOCKSPOOF_STR_OFF = 0xD90C9
    val LOCKSPOOF_STR = "flashing_locked".toByteArray(Charsets.US_ASCII)
    const val LOCKSPOOF_CAVE_SIZE = 0x40
    const val LOCKSPOOF_SEARCH_START = 0xCE280

    val RAMDUMP_CMD_NAME = "ramdump".toByteArray(Charsets.US_ASCII)
    val RAMDUMP_SUBCOMMANDS: List<ByteArray> = listOf("help", "enable", "disable", "status").map { it.toByteArray() }
    const val RAMDUMP_HANDLER_RANGE = 0x400
    val RAMDUMP_PROBE_TOKENS: List<ByteArray> = listOf("pull", "now", "clear").map { it.toByteArray() }
    val RAMDUMP_USB_MARKERS: List<Pair<String, ByteArray>> = listOf(
        "usage" to "usage: fastboot oem ramdump".toByteArray(),
        "fulldump-flag" to "enable_fulldump".toByteArray(),
        "fastboot-okay" to "OKAY".toByteArray(),
        "fastboot-info" to "INFO".toByteArray(),
        "usb-write" to "usb_write".toByteArray(),
    )
    val RAMDUMP_DEAD_MARKERS: List<Pair<String, ByteArray>> = listOf(
        "mrdump-chkimg" to "mrdump_chkimg".toByteArray(),
        "mrdump-fallocate" to "mrdump_fallocate".toByteArray(),
        "mrdump-out-set" to "mrdump_out_set".toByteArray(),
    )
    val RAMDUMP_ABSENT_MARKERS: List<Pair<String, ByteArray>> = listOf(
        "total-ram-size" to "total ram size".toByteArray(),
        "dram-init" to "dram init".toByteArray(),
        "pl-log" to "[PL LOG]".toByteArray(),
    )

    val GATE_ANCHORS: Map<String, ByteArray> = mapOf(
        "size-log" to "MD image size(%u) < check header size(%u)".toByteArray(),
        "region-log" to "Header version detect fail[size:%d]".toByteArray(),
        "restricted-log" to "command restricted".toByteArray(),
        "notallowed-log" to "Not allowed command".toByteArray(),
        "factory-enter-log" to "Entering factory mode".toByteArray(),
        "factory-bootmode-log" to "bootmode UTAG is set to factory".toByteArray(),
        "factory-cable-log" to "mmi,factory-cable".toByteArray(),
    )

    val KNOWN_LK_BUILDS: List<KnownBuild> = listOf(
        KnownBuild(
            "nevada XT2615V bootloader -111-3 (Val-patched phone pull)", "aarch64",
            0xFFFF000050F00000uL.toLong(), "W1WNS36.18-111-3".toByteArray(),
            listOf(0x49A9C, 0x49AAC, 0x49B24), listOf(0xF3F4, 0xAD88),
            listOf(0x159CC, 0x17700, 0x17ECC), listOf(0x3A180, 0x39C54, 0x9268),
            listOf(0x9F83C, 0x9DAA4), listOf(0x1ADF0),
        ),
        KnownBuild(
            "nevada XT2615V bootloader -114-1 (RETUS stock)", "aarch64",
            0xFFFF000050F00000uL.toLong(), "W1WNS36.18-114-1".toByteArray(),
            listOf(0x49A9C, 0x49AAC, 0x49B24), listOf(0xF3F4, 0xAD88),
            listOf(0x159CC, 0x17700, 0x17ECC), listOf(0x3A180, 0x39C54, 0x9268),
            listOf(0x9F83C, 0x9DAA4), listOf(0x1ADF0),
        ),
    )

    val SSM_VERB_ANCHORS: List<ByteArray> = listOf(
        "disable-verity".toByteArray(), "disable-thinkshield".toByteArray(),
        "enable-verity".toByteArray(), "mot_sec: Entering ThinkShield protection check".toByteArray(),
    )
    // (label, offset, old, why)
    val SSM_FORCE_GATES: List<Quad<String, Int, ByteArray, String>> = listOf(
        Quad("ssm-thinkshield-disable", 0x9F83C, ValBytes.fromHex("48ff0737"), "tbnz-bit3 success-select -> B success (fail print dead)"),
        Quad("ssm-avb-red", 0x9DAA4, ValBytes.fromHex("c0010054"), "b.eq AVB-red deny -> nop never denies boot"),
    )
    val SSM_FORCE_FAIL_STR = "Failed to disable-thinkshield!".toByteArray()
    val SSM_FORCE_AVB_STR = "mot_sec: AVB state is red and disallow to boot".toByteArray()
    const val SSM_FORCE_SUCCESS_WORD = 0x2A1F03E0L

    fun patchBytesChecked(data: ByteArray, offset: Int, old: ByteArray, new: ByteArray, label: String) {
        require(old.size == new.size) { "$label: old/new size mismatch (${old.size} != ${new.size})" }
        require(offset >= 0 && offset + old.size <= data.size) { "$label: offset 0x${offset.toString(16)} out of range" }
        val actual = data.copyOfRange(offset, offset + old.size)
        require(actual.contentEquals(old)) {
            "$label: old-byte mismatch at 0x${offset.toString(16)} / HxD ${"%08X".format(offset)}: " +
                "expected ${old.hex()}, found ${actual.hex()} (wrong LK build: aborting)"
        }
        new.copyInto(data, offset)
    }

    fun applyModemSizeGates(data: ByteArray, analysisDir: File): List<GatePatch> =
        applyDiscoveredGates(data, analysisDir, "modem", false)

    fun applyFactoryGates(data: ByteArray, analysisDir: File): List<GatePatch> =
        applyDiscoveredGates(data, analysisDir, "factory", false)

    fun applyFactoryForceGates(data: ByteArray, analysisDir: File): List<GatePatch> =
        applyDiscoveredGates(data, analysisDir, "force", false)

    fun findStringRefs(data: ByteArray, base: Long, strVa: Long): List<Int> {
        val refs = mutableListOf<Int>()
        val n = data.size - 20
        var off = 0
        while (off < n) {
            val word = ValBytes.u32At(data, off)
            if ((word and 0x9F000000L) != 0x90000000L) { off += 4; continue }
            val va = base + off
            var imm = (((word shr 5) and 0x7FFFFL) shl 2) or ((word shr 29) and 0x3L)
            if (imm and 0x100000L != 0L) imm -= 0x200000L
            if ((va and -0x1000L) + imm * 0x1000L != (strVa and -0x1000L)) { off += 4; continue }
            val rd = (word and 0x1FL).toInt()
            for (jump in 1..4) {
                val word2 = ValBytes.u32At(data, off + jump * 4)
                if ((word2 and 0xFF800000L) != 0x91000000L || ((word2 shr 5) and 0x1FL).toInt() != rd) continue
                var imm12 = (word2 shr 10) and 0xFFFL
                if ((word2 shr 22) and 0x3L == 1L) imm12 = imm12 shl 12
                if (((va and -0x1000L) + imm * 0x1000L) + imm12 == strVa) refs.add(off)
                break
            }
            off += 4
        }
        return refs
    }

    fun stringStart(data: ByteArray, idx: Int): Int {
        var i = idx
        while (i > 0 && data[i - 1].toInt() in 32 until 127) i--
        return i
    }

    fun denyBlockForAnchor(data: ByteArray, base: Long, anchorKey: String): Int {
        val needle = GATE_ANCHORS[anchorKey] ?: throw IllegalArgumentException("unknown anchor $anchorKey")
        val hit = ValBytes.find(data, needle)
        if (hit < 0) throw IllegalArgumentException(
            "anchor '$anchorKey' (${needle.copyOfRange(0, minOf(40, needle.size)).pyBytesRepr()}…) not in this LK: layout unknown, refusing"
        )
        val strOff = stringStart(data, hit)
        val refs = findStringRefs(data, base, base + strOff)
        if (refs.isEmpty()) throw IllegalArgumentException(
            "anchor '$anchorKey' present but unreferenced by code: cannot locate deny block, refusing"
        )
        return refs[0]
    }

    fun backscanBranch(
        data: ByteArray, base: Long, denyOff: Int, kinds: Set<String>,
        window: Int = 1024, nearestOnly: Boolean = false,
    ): List<Int> {
        val denyVa = base + denyOff
        val found = mutableListOf<Int>()
        val start = maxOf(0, denyOff - window)
        var off = start
        while (off < denyOff) {
            val word = ValBytes.u32At(data, off)
            val va = base + off
            val targets = mutableListOf<Long>()
            if ("bhi" in kinds) {
                val dec = ValEncoders.decodeBCond(word, va)
                if (dec != null && dec.first == 0x8) targets.add(dec.second)
            }
            if ("bne" in kinds) {
                val dec = ValEncoders.decodeBCond(word, va)
                if (dec != null && dec.first == 0x1) targets.add(dec.second)
            }
            if ("tbz0" in kinds) {
                val dec = ValEncoders.decodeTbz(word, va)
                if (dec != null && dec.first == 0) targets.add(dec.second)
            }
            if (targets.any { denyVa - 64 <= it && it <= denyVa }) found.add(off)
            off += 4
        }
        if (nearestOnly && found.isNotEmpty()) return listOf(found.max())
        return found
    }

    fun fingerprintLk(data: ByteArray, arch: String, base: Long): Pair<KnownBuild?, String> {
        var describe = "arch=$arch base=0x${java.lang.Long.toUnsignedString(base, 16)} size=${data.size}"
        for (build in KNOWN_LK_BUILDS) {
            if (build.arch == arch && build.base == base && ValBytes.find(data, build.versionNeedle) >= 0) {
                return build to (describe + " known=${build.id}")
            }
        }
        return null to (describe + " known=none(unknown build)")
    }

    fun discoverModemGates(data: ByteArray, base: Long): List<GateDef> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val gates = mutableListOf<GateDef>()
        val sizeDeny = denyBlockForAnchor(data, base, "size-log")
        var num = 1
        for (off in backscanBranch(data, base, sizeDeny, setOf("bhi"))) {
            val old = data.copyOfRange(off, off + 4)
            gates.add(GateDef("size-bound-gate-$num", off, old, AARCH64_NOP, "B.HI -> MD-size fail log; nop falls through to continue"))
            num++
        }
        if (gates.isEmpty()) throw IllegalArgumentException("no B.HI feeder into the MD-size deny block: refusing")
        val regionDeny = denyBlockForAnchor(data, base, "region-log")
        val regionGates = backscanBranch(data, base, regionDeny, setOf("bne"))
        if (regionGates.isEmpty()) throw IllegalArgumentException("no B.NE feeder into the region-id deny block: refusing")
        num = 1
        for (off in regionGates) {
            val old = data.copyOfRange(off, off + 4)
            gates.add(GateDef("region-id-gate-$num", off, old, AARCH64_NOP, "B.NE -> unknown-region fail log; nop falls through"))
            num++
        }
        return gates
    }

    fun discoverFactoryGates(data: ByteArray, base: Long): List<GateDef> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val gates = mutableListOf<GateDef>()
        for ((label, anchor) in listOf("restricted-tbz" to "restricted-log", "config-unprotect-tbz" to "notallowed-log")) {
            val deny = denyBlockForAnchor(data, base, anchor)
            val feeders = backscanBranch(data, base, deny, setOf("tbz0"), nearestOnly = true)
            if (feeders.isEmpty()) throw IllegalArgumentException("no TBZ-bit0 feeder into the $anchor deny block: refusing")
            val off = feeders[0]
            gates.add(GateDef(label, off, data.copyOfRange(off, off + 4), AARCH64_NOP, "TBZ-bit0 deny -> nop falls through to handler path"))
        }
        return gates
    }

    fun discoverFactoryForceGates(data: ByteArray, base: Long): List<GateDef> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val gates = mutableListOf<GateDef>()
        val entryRefs = mutableListOf<Int>()
        for (anchorKey in listOf("factory-enter-log", "factory-bootmode-log")) {
            val needle = GATE_ANCHORS[anchorKey]!!
            val hit = ValBytes.find(data, needle)
            if (hit < 0) throw IllegalArgumentException("anchor '$anchorKey' not in this LK: layout unknown, refusing")
            val strOff = stringStart(data, hit)
            val refs = findStringRefs(data, base, base + strOff)
            if (refs.isEmpty()) throw IllegalArgumentException("anchor '$anchorKey' present but unreferenced: refusing")
            entryRefs.addAll(refs)
        }
        if (entryRefs.isEmpty()) throw IllegalArgumentException("no factory-entry xrefs: refusing")
        val entryLo = entryRefs.min() - 0x200
        val entryHi = entryRefs.max() + 0x200
        val bootFound = mutableListOf<Int>()
        var off = 0
        while (off < data.size - 8) {
            val word = ValBytes.u32At(data, off)
            val dec = ValEncoders.decodeBCond(word, base + off)
            if (dec == null || dec.first != 0) { off += 4; continue }
            val tgtOff = (dec.second - base).toInt()
            if (tgtOff !in entryLo..entryHi) { off += 4; continue }
            if (off < 4) { off += 4; continue }
            val prev = ValBytes.u32At(data, off - 4)
            if (prev != 0xEB09011FL) { off += 4; continue }
            val window = data.copyOfRange(maxOf(0, off - 0x20), off - 4)
            if (ValBytes.find(window, ValBytes.fromHex("c92c8cd2")) < 0) { off += 4; continue }
            bootFound.add(off)
            off += 4
        }
        if (bootFound.size != 2) throw IllegalArgumentException(
            "expected 2 factory boot gates (early+main), found ${bootFound.size} (${bootFound.map { "0x${it.toString(16)}" }}): refusing"
        )
        bootFound.sort()
        for ((o, label) in bootFound.zip(listOf("factory-boot-early", "factory-boot-main"))) {
            val old = data.copyOfRange(o, o + 4)
            val dec = ValEncoders.decodeBCond(ValBytes.u32At(data, o), base + o)!!
            val tgtVa = dec.second
            val delta = tgtVa - (base + o)
            val imm26 = delta / 4
            val new = ValBytes.packU32(0x14000000L or (imm26 and 0x3FFFFFFL))
            val ntgt = ValEncoders.decodeBUncond(ValBytes.u32At(new, 0), base + o)
            if (ntgt != tgtVa) throw IllegalArgumentException("$label: branch re-encode mismatch: refusing")
            gates.add(GateDef(label, o, old, new, "cmp x8,x9 vs factory + b.eq -> b always (same target)"))
        }
        val cableNeedle = GATE_ANCHORS["factory-cable-log"]!!
        val cableHit = ValBytes.find(data, cableNeedle)
        if (cableHit < 0) throw IllegalArgumentException("anchor factory-cable-log not in this LK: refusing")
        val cableStr = stringStart(data, cableHit)
        val cableRefs = findStringRefs(data, base, base + cableStr)
        if (cableRefs.isEmpty()) throw IllegalArgumentException("mmi,factory-cable present but unreferenced: refusing")
        val cableRef = cableRefs.min()
        val cableFound = mutableListOf<Int>()
        var o2 = maxOf(0, cableRef - 0x40)
        while (o2 < cableRef) {
            val word = ValBytes.u32At(data, o2)
            val dec = ValEncoders.decodeBCond(word, base + o2)
            if (dec == null || dec.first != 1) { o2 += 4; continue }
            if (o2 < 4) { o2 += 4; continue }
            val prev = ValBytes.u32At(data, o2 - 4)
            if (prev != 0x7100081FL) { o2 += 4; continue }
            cableFound.add(o2)
            o2 += 4
        }
        if (cableFound.size != 1) throw IllegalArgumentException("expected 1 factory-cable gate, found ${cableFound.size}: refusing")
        val co = cableFound[0]
        gates.add(GateDef("factory-cable-ne", co, data.copyOfRange(co, co + 4), AARCH64_NOP, "cmp w0,#2 + b.ne skip -> nop falls into cable path"))
        return gates
    }

    fun buildBootmodeCave(data: ByteArray, base: Long): Triple<ByteArray, Int, Int> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val caveVa = base + BOOTMODE_CAVE_OFF
        val strOff = BOOTMODE_CAVE_OFF + 0x40
        val strVa = base + strOff
        if (strOff + BOOTMODE_CMDLINE_STR.size > BOOTMODE_CAVE_OFF + BOOTMODE_CAVE_SIZE) throw IllegalArgumentException("bootmode string does not fit the gated cave: refusing")
        val hookOff = BOOTMODE_HOOK_OFF
        val hookVa = base + hookOff
        val hookWord = ValBytes.u32At(data, hookOff)
        val setterVa = ValEncoders.decBlTarget(hookWord, hookVa)
            ?: throw IllegalArgumentException("hook at 0x${hookOff.toString(16)} is not BL (found 0x${hookWord.toString(16)}): refusing")
        val out = ByteArray(0x40)
        val words = mutableListOf<ByteArray>()
        words.add(ValBytes.fromHex("fd7bbea9"))
        words.add(ValBytes.fromHex("fd030091"))
        words.add(ValBytes.fromHex("e00b00f9"))
        words.add(ValEncoders.encBl(caveVa + 0xC, setterVa))
        words.add(ValBytes.fromHex("e00f00f9"))
        val (adrp, add) = ValEncoders.encAdrpAdd(0, caveVa + 0x14, strVa)
        words.add(adrp)
        words.add(add)
        words.add(ValEncoders.encBl(caveVa + 0x1C, setterVa))
        words.add(ValBytes.fromHex("e00f40f9"))
        words.add(ValBytes.fromHex("fd7bc2a8"))
        words.add(ValEncoders.encB(caveVa + 0x28, hookVa + 4))
        var blobLen = 0
        for (w in words) { w.copyInto(out, blobLen); blobLen += w.size }
        val expect = listOf("stp", "mov", "str", "bl", "str", "adrp", "add", "bl", "ldr", "ldp", "b")
        for ((idx, w) in words.withIndex()) {
            val word = ValBytes.u32At(w, 0)
            val va = caveVa + idx * 4
            val kind = expect[idx]
            val ok = when (kind) {
                "stp" -> word == 0xA9BE7BFDL
                "mov" -> word == 0x910003FDL
                "str" -> (idx == 2 && word == 0xF9000BE0L) || (idx == 4 && word == 0xF9000FE0L)
                "bl" -> ValEncoders.decBlTarget(word, va) == setterVa
                "adrp" -> (word and 0x9F000000L) == 0x90000000L && (word and 0x1FL) == 0L
                "add" -> (word and 0xFF800000L) == 0x91000000L
                "ldr" -> word == 0xF9400FE0L
                "ldp" -> word == 0xA8C27BFDL
                "b" -> {
                    (word and 0xFC000000L) == 0x14000000L &&
                        (va + ((if ((word and 0x2000000L) == 0L) (word and 0x3FFFFFFL) else (word and 0x3FFFFFFL) - 0x4000000L)) * 4) == hookVa + 4
                }
                else -> false
            }
            if (!ok) throw IllegalArgumentException("bootmode cave word $idx failed $kind gate (0x${word.toString(16)} at VA 0x${va.toString(16)}): refusing")
        }
        val adrpW = ValBytes.u32At(words[5], 0)
        val addW = ValBytes.u32At(words[6], 0)
        var imm = ((((adrpW shr 5) and 0x7FFFFL) shl 2) or ((adrpW shr 29) and 0x3L))
        if (imm and 0x100000L != 0L) imm -= 0x200000L
        val page = (caveVa + 0x14) and -0x1000L
        val resolved = page + imm * 0x1000L + ((addW shr 10) and 0xFFFL)
        if (resolved != strVa) throw IllegalArgumentException("bootmode adrp/add resolves 0x${resolved.toString(16)} != string 0x${strVa.toString(16)}: refusing")
        return Triple(out.copyOf(), hookOff, strOff)
    }

    fun discoverBootmodeHook(data: ByteArray, base: Long): Pair<Int, Long> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val needle = "androidboot.serialno".toByteArray(Charsets.US_ASCII)
        val hit = ValBytes.find(data, needle)
        if (hit < 0) throw IllegalArgumentException("anchor androidboot.serialno absent: refusing")
        val refs = findStringRefs(data, base, base + stringStart(data, hit))
        if (refs.isEmpty()) throw IllegalArgumentException("serialno anchor unreferenced by code: refusing")
        val start = refs.min()
        var off = start
        val end = minOf(data.size - 4, start + 0x400)
        while (off < end) {
            val w1 = ValBytes.u32At(data, off)
            val t1 = ValEncoders.decBlTarget(w1, base + off)
            if (t1 == null) { off += 4; continue }
            var off2 = off + 8
            val end2 = minOf(data.size - 4, off + 72)
            while (off2 < end2) {
                val w2 = ValBytes.u32At(data, off2)
                val t2 = ValEncoders.decBlTarget(w2, base + off2)
                if (t2 == null || t2 == t1) { off2 += 4; continue }
                val t2Off = (t2 - base).toInt()
                if (t2Off < 0 || t2Off + 4 > data.size) { off2 += 4; continue }
                if (!data.copyOfRange(t2Off, t2Off + 4).contentEquals(ValBytes.fromHex("fd7bbea9"))) { off2 += 4; continue }
                return off2 to t2
            }
            off += 4
        }
        throw IllegalArgumentException("no snprintf->setter hook pair near serialno: refusing")
    }

    fun applyBootmodeCmdline(data: ByteArray, analysisDir: File, experimental: Boolean): List<GatePatch> {
        val arch = ValAnalysis.loadArchitecture(analysisDir)
        if (arch != "aarch64") throw IllegalArgumentException("bootmode-cmdline supports aarch64 only; this LK is '$arch': refusing")
        val lkBase = ValAnalysis.tryLoadBase(analysisDir)
        val (build, describe) = fingerprintLk(data, arch, lkBase)
        println("Fingerprint: $describe")
        val (hookOff, setterVa) = discoverBootmodeHook(data, lkBase)
        if (build != null) {
            if (hookOff != BOOTMODE_HOOK_OFF) throw IllegalArgumentException(
                "known build '${build.id}' but hook resolved 0x${hookOff.toString(16)} vs expected 0x${BOOTMODE_HOOK_OFF.toString(16)}: layout drift, refusing"
            )
            println("KnownBuild: ${build.id} (hook reproduced)")
        } else if (!experimental) {
            throw IllegalArgumentException("unknown LK build: re-run with --experimental to trust per-image discovery (hook 0x${hookOff.toString(16)} unverified)")
        } else {
            println("Mode    : EXPERIMENTAL cross-device (unknown build)")
        }
        if (setterVa != lkBase + BOOTMODE_SETTER_OFF && build != null) throw IllegalArgumentException(
            "setter resolved 0x${setterVa.toString(16)} vs expected 0x${(lkBase + BOOTMODE_SETTER_OFF).toString(16)}: refusing"
        )
        val cave = data.copyOfRange(BOOTMODE_CAVE_OFF, BOOTMODE_CAVE_OFF + BOOTMODE_CAVE_SIZE)
        if (cave.any { it != 0.toByte() }) throw IllegalArgumentException(
            "cave 0x${BOOTMODE_CAVE_OFF.toString(16)}+0x${BOOTMODE_CAVE_SIZE.toString(16)} not zeroed (occupied, refusing to clobber)"
        )
        val (caveBlob, _, strOff) = buildBootmodeCave(data, lkBase)
        val applied = mutableListOf<GatePatch>()
        val hookVa = lkBase + hookOff
        val caveVa = lkBase + BOOTMODE_CAVE_OFF
        val newHook = ValEncoders.encBl(hookVa, caveVa)
        val oldHook = data.copyOfRange(hookOff, hookOff + 4)
        if (ValEncoders.decBlTarget(ValBytes.u32At(oldHook, 0), hookVa) != setterVa) throw IllegalArgumentException("hook no longer points at the setter: refusing")
        patchBytesChecked(data, hookOff, oldHook, newHook, "bootmode-hook")
        applied.add(GatePatch("bootmode-hook", hookOff, oldHook, newHook))
        println("Patch   : bootmode bootmode-hook @ 0x${hookOff.toString(16)}  ${oldHook.hex()} -> ${newHook.hex()}")
        caveBlob.copyInto(data, BOOTMODE_CAVE_OFF)
        BOOTMODE_CMDLINE_STR.copyInto(data, strOff)
        applied.add(GatePatch("bootmode-cave", BOOTMODE_CAVE_OFF, ByteArray(0x40), caveBlob))
        applied.add(GatePatch("bootmode-str", strOff, ByteArray(BOOTMODE_CMDLINE_STR.size), BOOTMODE_CMDLINE_STR))
        println("Patch   : bootmode cave+str @ 0x${BOOTMODE_CAVE_OFF.toString(16)}/0x${strOff.toString(16)}  ${caveBlob.size + BOOTMODE_CMDLINE_STR.size}B new")
        return applied
    }

    fun tableEntryForName(data: ByteArray, base: Long, name: ByteArray, stride: Int = 24): Pair<Int, Int> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val hit = ValBytes.find(data, name + byteArrayOf(0))
        if (hit < 0) throw IllegalArgumentException("table name ${name.pyBytesRepr()} absent: refusing")
        val want = ValBytes.packU64(base + hit)
        val ptrs = mutableListOf<Int>()
        var pos = 0
        while (true) {
            val idx = ValBytes.find(data, want, pos)
            if (idx < 0) break
            ptrs.add(idx)
            pos = idx + 1
            if (ptrs.size > 4) break
        }
        if (ptrs.size != 1) throw IllegalArgumentException("name ${name.pyBytesRepr()} has ${ptrs.size} table refs (need exactly 1): refusing")
        val entry = ptrs[0]
        val handlerVa = ValBytes.u64At(data, entry + 8)
        val handlerOff = (handlerVa - base).toInt()
        if (handlerOff < 0 || handlerOff + 4 > data.size) throw IllegalArgumentException("handler for ${name.pyBytesRepr()} out of image: refusing")
        val flags = ValBytes.u64At(data, entry + 16)
        if (flags > 0xFFL) throw IllegalArgumentException("name ${name.pyBytesRepr()} entry flags 0x${flags.toString(16)} don't look table-shaped: refusing")
        return entry to handlerOff
    }

    fun discoverMrdumpGates(data: ByteArray, base: Long): List<GateDef> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        for (anchor in listOf("mrdump_allocate_size", "mrdump_output", "enable_fulldump", "fullmem").map { it.toByteArray() }) {
            if (ValBytes.find(data, anchor) < 0) throw IllegalArgumentException("anchor ${anchor.pyBytesRepr()} absent: refusing")
        }
        val gates = mutableListOf<GateDef>()
        for ((label, offset, old, new, why) in MRDUMP_FORCE_GATES) {
            val actual = data.copyOfRange(offset, offset + 4)
            if (!actual.contentEquals(old)) throw IllegalArgumentException("$label: old-byte mismatch at 0x${offset.toString(16)}: expected ${old.hex()}, found ${actual.hex()}: refusing")
            val word = ValBytes.u32At(old, 0)
            val nword = ValBytes.u32At(new, 0)
            if (label == "mrdump-fullmem") {
                val dec = ValEncoders.decodeBCond(word, base + offset)
                if (dec == null || dec.first != 0) throw IllegalArgumentException("$label: old is not B.EQ: refusing")
                val tgt = dec.second
                if (ValEncoders.encB(base + offset, tgt).contentEquals(new).not() || ValEncoders.decodeBUncond(nword, base + offset) != tgt) throw IllegalArgumentException("$label: B re-encode mismatch: refusing")
            } else {
                if ((word and 0xFFFFFFE0L) != 0x2A1F03E0L) throw IllegalArgumentException("$label: old is not MOV-Rd,wzr: refusing")
                val ndec = ValEncoders.decMovzW(nword)
                if (ndec == null || ndec.first != (word and 0x1FL).toInt()) throw IllegalArgumentException("$label: new is not MOVZ same-rd: refusing")
            }
            gates.add(GateDef(label, offset, old, new, why))
        }
        return gates
    }

    fun discoverLockspoof(data: ByteArray, base: Long): Triple<Int, Int, Long> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        if (ValBytes.find(data, LOCKSPOOF_STR + byteArrayOf(0)) < 0) throw IllegalArgumentException("locked string absent: refusing")
        val (_, getter) = tableEntryForName(data, base, "securestate".toByteArray())
        val head = data.copyOfRange(getter, getter + 20)
        if (!head.copyOfRange(0, 4).contentEquals(ValBytes.fromHex("fd7bbea9")) || !head.copyOfRange(8, 12).contentEquals(ValBytes.fromHex("fd030091"))) {
            throw IllegalArgumentException("securestate getter head mismatch at 0x${getter.toString(16)}: refusing")
        }
        val hookOff = getter + 0x10
        val hookWord = ValBytes.u32At(data, hookOff)
        if ((ValEncoders.decBlTarget(hookWord, base + hookOff) ?: 0L) == 0L) throw IllegalArgumentException("hook at 0x${hookOff.toString(16)} is not BL: refusing")
        val page = 0xCE000
        var caveOff = -1
        var run = 0
        var off = LOCKSPOOF_SEARCH_START
        while (off < page + 0x1000) {
            if (data[off] == 0.toByte()) {
                run++
                if (run >= LOCKSPOOF_CAVE_SIZE) {
                    caveOff = off - LOCKSPOOF_CAVE_SIZE + 1
                    break
                }
            } else run = 0
            off++
        }
        if (caveOff < 0) throw IllegalArgumentException("no zero cave in CE000 page: refusing")
        val strHit = ValBytes.find(data, LOCKSPOOF_STR + byteArrayOf(0))
        return Triple(hookOff, caveOff, base + strHit)
    }

    fun buildLockspoofCave(base: Long, hookOff: Int, caveOff: Int, strVa: Long): ByteArray {
        val hookVa = base + hookOff
        val caveVa = base + caveOff
        val (adrp, add) = ValEncoders.encAdrpAdd(0, caveVa, strVa)
        val back = ValEncoders.encB(caveVa + 8, hookVa + 4)
        val blob = adrp + add + back
        val aw = ValBytes.u32At(adrp, 0)
        val dw = ValBytes.u32At(add, 0)
        if ((aw and 0x9F000000L) != 0x90000000L || (aw and 0x1FL) != 0L) throw IllegalArgumentException("lockspoof adrp gate failed: refusing")
        if ((dw and 0xFF800000L) != 0x91000000L) throw IllegalArgumentException("lockspoof add gate failed: refusing")
        var imm = ((((aw shr 5) and 0x7FFFFL) shl 2) or ((aw shr 29) and 0x3L))
        if (imm and 0x100000L != 0L) imm -= 0x200000L
        if (((caveVa and -0x1000L) + imm * 0x1000L + ((dw shr 10) and 0xFFFL)) != strVa) throw IllegalArgumentException("lockspoof adrp/add target mismatch: refusing")
        val bw = ValBytes.u32At(back, 0)
        if (ValEncoders.decodeBUncond(bw, caveVa + 8) != hookVa + 4) throw IllegalArgumentException("lockspoof back-branch mismatch: refusing")
        return blob
    }

    fun applyMrdumpGates(data: ByteArray, analysisDir: File): List<GatePatch> =
        applyDiscoveredGates(data, analysisDir, "mrdump", false)

    fun applyLockspoof(data: ByteArray, analysisDir: File, experimental: Boolean): List<GatePatch> {
        val arch = ValAnalysis.loadArchitecture(analysisDir)
        if (arch != "aarch64") throw IllegalArgumentException("lockspoof supports aarch64 only, got '$arch': refusing")
        val lkBase = ValAnalysis.tryLoadBase(analysisDir)
        val (build, describe) = fingerprintLk(data, arch, lkBase)
        println("Fingerprint: $describe")
        val (hookOff, caveOff, strVa) = discoverLockspoof(data, lkBase)
        if (build != null) {
            println("KnownBuild: ${build.id} (table-walked, no fixed offsets)")
        } else if (!experimental) {
            throw IllegalArgumentException("unknown LK build: re-run with --experimental")
        } else {
            println("Mode    : EXPERIMENTAL cross-device (unknown build)")
        }
        val hookVa = lkBase + hookOff
        val newHook = ValEncoders.encBl(hookVa, lkBase + caveOff)
        val oldHook = data.copyOfRange(hookOff, hookOff + 4)
        if (ValEncoders.decBlTarget(ValBytes.u32At(oldHook, 0), hookVa) == null) throw IllegalArgumentException("hook slot is not BL: refusing")
        val caveBlob = buildLockspoofCave(lkBase, hookOff, caveOff, strVa)
        val applied = mutableListOf<GatePatch>()
        patchBytesChecked(data, hookOff, oldHook, newHook, "lockspoof-hook")
        applied.add(GatePatch("lockspoof-hook", hookOff, oldHook, newHook))
        println("Patch   : lockspoof hook @ 0x${hookOff.toString(16)}  ${oldHook.hex()} -> ${newHook.hex()}")
        caveBlob.copyInto(data, caveOff)
        applied.add(GatePatch("lockspoof-cave", caveOff, ByteArray(caveBlob.size), caveBlob))
        println("Patch   : lockspoof cave @ 0x${caveOff.toString(16)}  ${caveBlob.size}B new")
        println("Warn    : REPORT-ONLY spoof (getvar lies, enforcement untouched).")
        return applied
    }

    fun discoverSsmSurface(data: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        for (anchor in SSM_VERB_ANCHORS) {
            val off = ValBytes.find(data, anchor)
            if (off < 0) throw IllegalArgumentException("SSM anchor ${anchor.pyBytesRepr()} absent: refusing")
            found.add(off)
        }
        return found
    }

    fun discoverSsmForceGates(data: ByteArray, base: Long): List<GateDef> {
        if (base == 0L) throw IllegalArgumentException("LK base unknown: cannot resolve VAs, refusing")
        val failRefs = findStringRefs(data, base, base + ValBytes.find(data, SSM_FORCE_FAIL_STR))
        if (ValBytes.find(data, SSM_FORCE_FAIL_STR) < 0 || failRefs.isEmpty()) throw IllegalArgumentException("SSM fail-string absent/unreferenced: refusing")
        val avbHit = ValBytes.find(data, SSM_FORCE_AVB_STR)
        if (avbHit < 0) throw IllegalArgumentException("SSM AVB-red string absent: refusing")
        val avbRefs = findStringRefs(data, base, base + avbHit)
        if (avbRefs.isEmpty()) throw IllegalArgumentException("SSM AVB-red string unreferenced: refusing")
        val gates = mutableListOf<GateDef>()
        for ((label, offset, old, why) in SSM_FORCE_GATES) {
            val actual = data.copyOfRange(offset, offset + 4)
            if (!actual.contentEquals(old)) throw IllegalArgumentException("$label: old-byte mismatch at 0x${offset.toString(16)}: expected ${old.hex()}, found ${actual.hex()}: refusing")
            val word = ValBytes.u32At(old, 0)
            val va = base + offset
            val new: ByteArray
            if (label == "ssm-thinkshield-disable") {
                val dec = ValEncoders.decodeTbnz(word, va)
                if (dec == null || dec.first != 0) throw IllegalArgumentException("$label: old is not TBNZ-bit0: refusing")
                val tgt = dec.second
                if (tgt >= va) throw IllegalArgumentException("$label: target not backwards: refusing")
                val head = ValBytes.u32At(data, (tgt - base).toInt() - 4)
                if (head != SSM_FORCE_SUCCESS_WORD) throw IllegalArgumentException("$label: word before target 0x${(tgt - base).toInt().toString(16)} is not mov-w0-wzr: refusing")
                new = ValEncoders.encB(va, tgt)
                val nword = ValBytes.u32At(new, 0)
                if (ValEncoders.decodeBUncond(nword, va) != tgt) throw IllegalArgumentException("$label: B re-encode mismatch: refusing")
            } else {
                val dec = ValEncoders.decodeBCond(word, va)
                if (dec == null || dec.first != 0) throw IllegalArgumentException("$label: old is not B.EQ: refusing")
                if (dec.second != base + avbRefs[0]) throw IllegalArgumentException("$label: target 0x${(dec.second - base).toInt().toString(16)} is not the AVB-red block 0x${avbRefs[0].toString(16)}: refusing")
                new = AARCH64_NOP
            }
            gates.add(GateDef(label, offset, old, new, why))
        }
        return gates
    }

    fun applySsmBypass(data: ByteArray, analysisDir: File, experimental: Boolean): List<GatePatch> {
        val arch = ValAnalysis.loadArchitecture(analysisDir)
        if (arch != "aarch64") throw IllegalArgumentException("ssm-bypass supports aarch64 only, got '$arch': refusing")
        val surface = discoverSsmSurface(data)
        println("SSM     : verbs present at " + surface.joinToString(", ") { "0x${it.toString(16)}" })
        val applied = applyDiscoveredGates(data, analysisDir, "factory", experimental).toMutableList()
        val forced = applyDiscoveredGates(data, analysisDir, "ssm-force", experimental)
        applied.addAll(forced)
        println("Warn    : UNTESTED preset (never flown live): the verbs are now")
        println("Warn    : reachable and the two enforcement gates forced, but a")
        println("Warn    : genuinely failed op still reports success and handlers")
        println("Warn    : may check unlock / CID / SSM state. Live verdict = run")
        println("Warn    : 'fastboot oem disable-verity' + 'fastboot oem")
        println("Warn    : disable-thinkshield' + attempt boot with red state.")
        return applied
    }

    fun applyDiscoveredGates(data: ByteArray, analysisDir: File, family: String, experimental: Boolean): List<GatePatch> {
        val arch = ValAnalysis.loadArchitecture(analysisDir)
        if (arch != "aarch64") throw IllegalArgumentException(
            "family '$family': cross-device discovery supports aarch64 only; this LK is '$arch': refusing (no thumb gate map yet)"
        )
        val lkBase = ValAnalysis.tryLoadBase(analysisDir)
        val (build, describe) = fingerprintLk(data, arch, lkBase)
        println("Fingerprint: $describe")
        val gates: List<GateDef>
        val key: (KnownBuild) -> List<Int>
        when (family) {
            "modem" -> { gates = discoverModemGates(data, lkBase); key = { it.modemOffsets } }
            "mrdump" -> { gates = discoverMrdumpGates(data, lkBase); key = { it.mrdumpOffsets } }
            "factory" -> { gates = discoverFactoryGates(data, lkBase); key = { it.factoryOffsets } }
            "force" -> { gates = discoverFactoryForceGates(data, lkBase); key = { it.forceOffsets } }
            "ssm-force" -> { gates = discoverSsmForceGates(data, lkBase); key = { it.ssmForceOffsets } }
            else -> throw IllegalArgumentException("unknown gate family '$family'")
        }
        val found = gates.map { it.offset }
        if (build != null) {
            if (found.sorted() != key(build).sorted()) throw IllegalArgumentException(
                "known build '${build.id}' but discovery resolved ${found.map { "0x${it.toString(16)}" }} vs expected ${key(build).map { "0x${it.toString(16)}" }}: layout drift, refusing"
            )
            println("KnownBuild: ${build.id} (offsets reproduced)")
        } else {
            if (!experimental) throw IllegalArgumentException(
                "unknown LK build: re-run with --experimental to trust per-image discovery on this image (offsets ${found.map { "0x${it.toString(16)}" }} unverified against any table)"
            )
            println("Mode    : EXPERIMENTAL cross-device (unknown build, discovery-only trust)")
        }
        val applied = mutableListOf<GatePatch>()
        for ((label, offset, old, new, _) in gates) {
            patchBytesChecked(data, offset, old, new, label)
            applied.add(GatePatch(label, offset, old, new))
            val va = if (lkBase != 0L) " VA=0x${(lkBase + offset).toString(16)}" else ""
            println("Patch   : $family $label @ 0x${offset.toString(16)} / HxD ${"%08X".format(offset)}$va  ${old.hex()} -> ${new.hex()}")
        }
        return applied
    }

    // ---- research / report-only ----
    fun modemResearchScan(data: ByteArray): Map<String, List<Int>> {
        val found = linkedMapOf<String, List<Int>>()
        for ((label, needle) in MODEM_RESEARCH_MARKERS) {
            val offsets = ValBytes.findAll(data, needle)
            if (offsets.isNotEmpty()) found[label] = offsets
        }
        return found
    }

    fun modemAbsentScan(data: ByteArray): List<String> {
        val missing = mutableListOf<String>()
        val lowered = String(data, Charsets.ISO_8859_1).lowercase()
        for ((label, needle) in MODEM_ABSENT_MARKERS) {
            if (lowered.indexOf(String(needle, Charsets.ISO_8859_1).lowercase()) < 0) missing.add(label)
        }
        return missing
    }

    fun printModemResearchReport(data: ByteArray, analysisDir: File) {
        val lkBase = ValAnalysis.tryLoadBase(analysisDir)
        println("Report  : modem-unlock research (REPORT-ONLY, no LK bytes changed)")
        println("LK size : ${data.size} bytes")
        if (lkBase != 0L) println("LK base : 0x${lkBase.toString(16)}")
        val found = modemResearchScan(data)
        for ((label, _) in MODEM_RESEARCH_MARKERS) {
            val offsets = found[label] ?: emptyList()
            if (offsets.isEmpty()) {
                println("Marker  : $label ABSENT")
                continue
            }
            val shown = offsets.take(5).joinToString(", ") {
                "0x${it.toString(16)} / HxD ${"%08X".format(it)}" + (if (lkBase != 0L) " VA=0x${(lkBase + it).toString(16)}" else "")
            }
            val extra = if (offsets.size > 5) " (+${offsets.size - 5} more)" else ""
            println("Marker  : $label x${offsets.size} @ $shown$extra")
        }
        val missing = modemAbsentScan(data)
        for ((label, _) in MODEM_ABSENT_MARKERS) {
            if (label in missing) println("Absent  : $label NOT FOUND (do not assume this function exists)")
            else println("Present : $label string present (needs xref confirmation)")
        }
        println("Note    : LK != EL3 (LK issues SMC calls INTO ATF/EL3; patching LK")
        println("Note    : does not yield EL3). LK MMU/EMI-MPU tables are rebuilt by")
        println("Note    : the kernel at boot, so LK remaps do not persist to Linux.")
        println("Status  : REPORT ONLY - payload left unmodified by design.")
    }

    fun findFastbootCommandHandlerOffset(data: ByteArray, base: Long, command: String, candidateOffsets: Set<Int>? = null): Int? {
        val commandRaw = command.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val stringOffsets = mutableListOf<Int>()
        var start = 0
        while (true) {
            val offset = ValBytes.find(data, commandRaw, start)
            if (offset < 0) break
            if (offset == 0 || data[offset - 1] == 0.toByte()) stringOffsets.add(offset)
            start = offset + 1
        }
        for (stringOffset in stringOffsets) {
            val stringVa = base + stringOffset
            val pointerRaw = ValBytes.packU64(stringVa)
            var pointerStart = 0
            while (true) {
                val pointerOffset = ValBytes.find(data, pointerRaw, pointerStart)
                if (pointerOffset < 0) break
                pointerStart = pointerOffset + 1
                if (pointerOffset % 8 != 0) continue
                if (pointerOffset + 16 > data.size) continue
                val handlerVa = ValBytes.u64At(data, pointerOffset + 8)
                if (!(base <= handlerVa && handlerVa < base + data.size)) continue
                val handlerOffset = (handlerVa - base).toInt()
                if (handlerOffset % 4 != 0) continue
                if (candidateOffsets != null && handlerOffset !in candidateOffsets) continue
                var neighborOk = false
                for (neighbor in listOf(pointerOffset - 16, pointerOffset + 16)) {
                    if (neighbor < 0 || neighbor + 16 > data.size) continue
                    val nameVa = ValBytes.u64At(data, neighbor)
                    val codeVa = ValBytes.u64At(data, neighbor + 8)
                    if (!(base <= nameVa && nameVa < base + data.size)) continue
                    if (!(base <= codeVa && codeVa < base + data.size)) continue
                    val nameBytes = ValBytes.readCString(data, (nameVa - base).toInt())
                    if (nameBytes.isNotEmpty() && nameBytes.all { it.toInt() in 0x20 until 0x7F }) {
                        neighborOk = true
                        break
                    }
                }
                if (neighborOk) return handlerOffset
            }
        }
        return null
    }

    fun auditAarch64DirectReturnPath(data: ByteArray, startOffset: Int, functionEndOffset: Int, maxSteps: Int = 48): Map<String, Any?> {
        var pc = startOffset
        val path = mutableListOf<Int>()
        val calls = mutableListOf<Int>()
        val conditional = mutableListOf<Int>()
        var terminal = "unknown"
        var steps = 0
        var done = false
        while (steps < maxSteps && !done) {
            steps++
            if (pc < 0 || pc + 4 > data.size) { terminal = "out_of_file"; break }
            if (pc > functionEndOffset + 4) { terminal = "out_of_function"; break }
            if (pc in path) { terminal = "loop"; break }
            path.add(pc)
            val word = ValBytes.u32At(data, pc)
            if (word == 0xD65F03C0L) { terminal = "ret"; break }
            if ((word and 0xFC000000L) == 0x94000000L) { calls.add(pc); terminal = "call"; break }
            if ((word and 0xFF000010L) == 0x54000000L) { conditional.add(pc); terminal = "conditional_branch"; break }
            if ((word and 0x7E000000L) == 0x34000000L || (word and 0x7E000000L) == 0x36000000L) { conditional.add(pc); terminal = "conditional_branch"; break }
            val target = ValEncoders.decodeAarch64BranchTarget(word, pc.toLong())
            if (target != null) { pc = target.toInt(); continue }
            pc += 4
        }
        if (steps >= maxSteps && terminal == "unknown") terminal = "step_limit"
        return mapOf(
            "ok" to (terminal == "ret" && calls.isEmpty() && conditional.isEmpty()),
            "terminal" to terminal,
            "steps" to path.size,
            "path" to path,
            "calls" to calls,
            "conditional_branches" to conditional,
        )
    }
}

/** Minimal 4-tuple (Kotlin stdlib has Pair/Triple only). */
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
