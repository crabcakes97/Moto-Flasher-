// Static analyzer for LK payloads — Kotlin port of Val Protocol's
// lk_static_analyzer.py (3,615 lines). Core: payload handling, string
// search, architecture detection, per-slot Capstone disassembly, string
// xref discovery, function-flow building, and the JSON dict converters.
//
// Upstream: Val Protocol (lk_static_analyzer.py). Capstone by the Capstone
// Engine authors; LK container parsing reuses LibLk (Kotlin port of
// R0rt1z2/liblk by Roger Ortiz). No AI branding anywhere in this code;
// real credits only.
//
// Disassembly goes through CapstoneNative (Phase 0 JNI, Capstone 5.0.7):
// the analyzer uses ONLY disassembleOne(word, addr, count=1) per aligned
// slot, arch ARM64 or THUMB, and per instruction address/mnemonic/op_str/
// size/bytes — everything downstream is regex over mnemonic/op_str, exactly
// like the Python analyzer (md.detail = False). The caller must have
// loaded CapstoneNative first (ensureLoaded on device).
package com.cronch.motoflasher.valproto

import com.cronch.motoflasher.CapstoneNative
import com.cronch.motoflasher.LibLk

/** One disassembled instruction. Mirrors the Python Instruction dataclass. */
data class LkInstruction(
    val address: Long,
    val mnemonic: String,
    val operands: String,
    val raw: ByteArray,
    val mode: String = "aarch64",
)

data class LkStringHit(
    val target: String,
    val offset: Int,
    val address: Long,
    val text: String,
)

data class LkXrefHit(
    val insnIndex: Int,
    val target: LkStringHit,
    val address: Long,
    val mnemonic: String,
    val operands: String,
    val effectiveAddress: Long,
    val via: String,
)

data class LkFunctionFlow(
    val startIndex: Int,
    val endIndex: Int,
    val startAddress: Long,
    val endAddress: Long,
    val xrefs: List<LkXrefHit>,
    val calls: List<Long>,
    val branches: List<Triple<Long, Long, String>>,
    val score: Int,
    val matchedTargets: List<String>,
)

data class LkFrpChecker(
    val flow: LkFunctionFlow,
    val confidence: Int,
    val evidence: List<String>,
    val callers: List<Pair<Long, Long>>,
)

data class LkKeyValidator(
    val flow: LkFunctionFlow,
    val confidence: Int,
    val evidence: List<String>,
    val callers: List<Pair<Long, Long>>,
    val failureEdges: List<Triple<Long, Long, String>>,
)

/** (xref_address, name, compare_call_target, branch_target). */
data class LkNameCheck(
    val xrefAddress: Long,
    val name: String,
    val callTarget: Long?,
    val branchTarget: Long?,
)

data class LkErasePermissionCheck(
    val flow: LkFunctionFlow,
    val confidence: Int,
    val evidence: List<String>,
    val callers: List<Pair<Long, Long>>,
    val nameChecks: List<LkNameCheck>,
    val permissionCalls: List<Pair<Long, Long>>,
    val deniedEdges: List<Triple<Long, Long, String>>,
)

data class LkPartitionEraseOperation(
    val flowStart: Long,
    val partition: String,
    val operation: String,
    val xrefAddress: Long,
    val stringAddress: Long,
    val stringText: String,
    val referenceMode: String,
    val xrefMnemonic: String,
    val xrefOperands: String,
    val xrefVia: String,
    val callSite: Long?,
    val callTarget: Long?,
    val resultCheckAddress: Long?,
    val failureTarget: Long?,
    val failureMessageAddress: Long?,
    val failureMessage: String?,
)

val LK_DEFAULT_TARGETS = listOf(
    "fastboot oem unlock",
    "This command requires you to first unlock the bootloader",
    "Check 'Allow OEM Unlock'",
    "Code validation failure",
    "Invalid boot state for unlocking",
    "A unlock code may be required",
    "Now unlocked for flashing",
    "Bootloader is unlocked",
    "Already unlocked",
    "flashing_locked",
    "flashing_unlocked",
    "userdata",
    "metadata",
    "md_udc",
    "nvdata",
    "frp",
    "frp-state",
    "Invalid partition table!",
    "FRP partition is invalid, lock anyway!",
    "Couldn't read from %llx",
    "mot_sec: Invalid partition table!",
    "mot_sec: FRP partition is invalid, lock anyway!",
    "mot_sec: Couldn't read from %llx",
    "hash calculation failure!",
    "hash at offset i:",
    "validate_hash_password: Version 2 datablock",
    "validate_hash_password: Version 1 datablock",
    "validate_hash_password: Unsupported datablock",
    "mot_sec: hash calculation failure!",
    "mot_sec: hash at offset i:",
    "mot_sec: validate_hash_password: Version 2 datablock",
    "mot_sec: validate_hash_password: Version 1 datablock",
    "mot_sec: validate_hash_password: Unsupported datablock",
    "validate_hash_password",
    "fboot_cmd_erase_permission_validate",
    "erase permission denied",
    "Invalid partition name %s",
    "Disallow to erase partition %s on oem_locked device",
    "debug_token",
    "avb_custom_key",
    "cache",
    "all",
    "securestate",
    "iswarrantyvoid",
    "max-download-size",
    "is-userspace",
    "serialno",
    "barcode",
    "version-bootloader",
    "imei",
    "imei2",
    "meid",
    "esimid",
    "sku",
    "battid",
    "iccid",
)

val LK_CORE_FLOW_TARGETS = mapOf(
    "fastboot oem unlock" to 7,
    "Check 'Allow OEM Unlock'" to 10,
    "Bootloader is unlocked" to 9,
    "Code validation failure" to 9,
    "Now unlocked for flashing" to 8,
    "This command requires you to first unlock the bootloader" to 6,
    "Invalid boot state for unlocking" to 5,
    "A unlock code may be required" to 5,
    "Already unlocked" to 4,
    "flashing_locked" to 3,
    "flashing_unlocked" to 3,
)

val LK_CONTEXT_FLOW_TARGETS = mapOf(
    "userdata" to 2,
    "metadata" to 2,
    "md_udc" to 2,
    "nvdata" to 2,
)

val LK_TARGET_ERASE_PARTITIONS = setOf("userdata", "metadata", "md_udc", "nvdata")

val LK_FRP_FLOW_TARGETS = mapOf(
    "frp" to 3,
    "frp-state" to 2,
    "Invalid partition table!" to 4,
    "FRP partition is invalid, lock anyway!" to 6,
    "Couldn't read from %llx" to 5,
    "mot_sec: Invalid partition table!" to 5,
    "mot_sec: FRP partition is invalid, lock anyway!" to 7,
    "mot_sec: Couldn't read from %llx" to 6,
)

val LK_KEY_VALIDATOR_TARGETS = mapOf(
    "hash calculation failure!" to 6,
    "hash at offset i:" to 6,
    "validate_hash_password: Version 2 datablock" to 7,
    "validate_hash_password: Version 1 datablock" to 4,
    "validate_hash_password: Unsupported datablock" to 4,
    "mot_sec: hash calculation failure!" to 7,
    "mot_sec: hash at offset i:" to 7,
    "mot_sec: validate_hash_password: Version 2 datablock" to 8,
    "mot_sec: validate_hash_password: Version 1 datablock" to 5,
    "mot_sec: validate_hash_password: Unsupported datablock" to 4,
    "validate_hash_password" to 3,
)

val LK_ERASE_PERMISSION_TARGETS = mapOf(
    "fboot_cmd_erase_permission_validate" to 14,
    "erase permission denied" to 12,
    "Invalid partition name %s" to 8,
    "Disallow to erase partition %s on oem_locked device" to 10,
    "debug_token" to 5,
    "avb_custom_key" to 5,
    "cache" to 3,
    "all" to 3,
)

val LK_ERASE_PERMISSION_NAME_TARGETS = setOf("cache", "debug_token", "avb_custom_key", "all")
val LK_EXACT_C_STRING_TARGETS = LK_ERASE_PERMISSION_NAME_TARGETS

/** Unsigned 64-bit comparisons: addresses live in [0, 2^64) as Python
 *  ints but in signed Long here (Nevada base 0xffff000050f00000). */
fun uCmp(a: Long, b: Long): Int = java.lang.Long.compareUnsigned(a, b)
fun uLess(a: Long, b: Long): Boolean = uCmp(a, b) < 0
fun uLessEq(a: Long, b: Long): Boolean = uCmp(a, b) <= 0

/** Python int(value, 0): 0x hex, 0o octal, 0b binary, else decimal. */
fun parseInt0(s: String): Long {
    val t = s.trim()
    return when {
        t.startsWith("0x") || t.startsWith("0X") ->
            java.lang.Long.parseUnsignedLong(t.substring(2), 16)
        t.startsWith("0o") || t.startsWith("0O") -> t.substring(2).toLong(8)
        t.startsWith("0b") || t.startsWith("0B") -> t.substring(2).toLong(2)
        else -> t.toLong()
    }
}

/** Python bytes.lower(): ASCII A-Z only. */
fun ByteArray.lowerAscii(): ByteArray {
    val out = copyOf()
    for (i in out.indices) {
        val b = out[i].toInt()
        if (b in 0x41..0x5A) out[i] = (b + 0x20).toByte()
    }
    return out
}

/** Python bytes.rfind(needle, start, end): last occurrence starting in
 *  [start, end). Only used for a single NUL byte. */
fun rfindByte(data: ByteArray, start: Int, end: Int): Int {
    var i = minOf(end, data.size) - 1
    while (i >= start) {
        if (data[i] == 0.toByte()) return i
        i--
    }
    return -1
}

/** Python read_c_string(data, offset, limit=512), latin-1 decoded. */
fun readCString(data: ByteArray, offset: Int, limit: Int = 512): String {
    val bound = minOf(data.size, offset + limit)
    var end = offset
    while (end < bound && data[end] != 0.toByte()) end++
    return String(data, offset, end - offset, Charsets.ISO_8859_1)
}

/** Python printable_context(data, center, radius=96). */
fun printableContext(data: ByteArray, center: Int, radius: Int = 96): String {
    val start = maxOf(0, center - radius)
    val end = minOf(data.size, center + radius)
    val sb = StringBuilder(end - start)
    for (i in start until end) {
        val b = data[i].toInt() and 0xFF
        sb.append(if (b in 32..126) b.toChar() else '.')
    }
    return sb.toString()
}

fun fileOffsetForVa(address: Long, base: Long): Long = address - base
fun hxdOffsetForVa(address: Long, base: Long): String = hxd(fileOffsetForVa(address, base))

fun classifyTargetText(target: String, text: String): String {
    val cleanText = text.trim('\u0000').trim()
    if (cleanText.lowercase() == target.lowercase()) return "direct_string"
    if (target.lowercase() in cleanText.lowercase()) return "embedded_string"
    return "address_only"
}

fun partitionReferenceMode(xref: LkXrefHit): String =
    classifyTargetText(xref.target.target, xref.target.text)

/** Mirror of Python load_lk_payload: container -> lk partition payload +
 *  base, or a raw payload with an explicit base. */
fun loadLkPayload(imageFile: java.io.File, rawBase: Long?): Triple<ByteArray, Long, String> {
    if (rawBase != null) return Triple(imageFile.readBytes(), rawBase, "raw lk payload")
    val image = LibLk.parse(imageFile.readBytes())
    return loadLkPayload(image)
}

fun loadLkPayload(image: LibLk.LkImage): Triple<ByteArray, Long, String> {
    val partition = image.partitions.firstOrNull { it.name == "lk" }
        ?: throw IllegalArgumentException(
            "image does not contain a subpartition named 'lk'"
        )
    val base = partition.lkAddress ?: run {
        if ((partition.memoryAddress and 0xFFFFFFFFL) == 0xFFFFFFFFL) {
            throw IllegalArgumentException(
                "could not detect Memory Address for 'lk'; use --base 0x..."
            )
        }
        partition.memoryAddress
    }
    return Triple(partition.data, base, "lk partition from container")
}

fun findTargets(data: ByteArray, base: Long, targets: Iterable<String>): List<LkStringHit> {
    val hits = mutableListOf<LkStringHit>()
    val lowered = data.lowerAscii()
    for (target in targets) {
        val needle = target.toByteArray(Charsets.ISO_8859_1).lowerAscii()
        if (needle.isEmpty()) continue
        if (target in LK_EXACT_C_STRING_TARGETS) {
            var offset = 0
            while (true) {
                offset = ValBytes.find(lowered, needle, offset)
                if (offset == -1) break
                val beforeOk = offset == 0 || data[offset - 1] == 0.toByte()
                val after = offset + needle.size
                val afterOk = after < data.size && data[after] == 0.toByte()
                if (beforeOk && afterOk) {
                    hits.add(
                        LkStringHit(
                            target = target,
                            offset = offset,
                            address = base + offset,
                            text = readCString(data, offset),
                        )
                    )
                }
                offset += maxOf(1, needle.size)
            }
            continue
        }
        var offset = 0
        while (true) {
            offset = ValBytes.find(lowered, needle, offset)
            if (offset == -1) break
            val stringStart = rfindByte(data, 0, offset) + 1
            for (referenceOffset in sortedSetOf(stringStart, offset)) {
                hits.add(
                    LkStringHit(
                        target = target,
                        offset = referenceOffset,
                        address = base + referenceOffset,
                        text = readCString(data, referenceOffset),
                    )
                )
            }
            offset += maxOf(1, needle.size)
        }
    }
    val unique = linkedMapOf<Pair<String, Int>, LkStringHit>()
    for (hit in hits) unique[hit.target to hit.offset] = hit
    return unique.values.sortedWith(compareBy({ it.offset }, { it.target }))
}

fun detectArchitecture(data: ByteArray, base: Long): String {
    val padded = ByteArray(4)
    data.copyInto(padded, 0, 0, minOf(4, data.size))
    val firstWord = ValBytes.u32At(padded, 0)
    if ((firstWord and 0x0F000000L) == 0x0A000000L) return "arm32_thumb"
    if (uLess(base, 0x1_0000_0000L) && (firstWord ushr 24) in setOf(0xEAL, 0xEBL)) {
        return "arm32_thumb"
    }
    return "aarch64"
}

/** Python disassemble(): per aligned slot, md.disasm(word, addr, count=1),
 *  keep size==4 (aarch64) or size in {2,4} (thumb). */
fun disassemble(data: ByteArray, base: Long, architecture: String): List<LkInstruction> {
    val step = if (architecture == "arm32_thumb") 2 else 4
    val arch = if (architecture == "arm32_thumb") CapstoneNative.ARCH_THUMB
    else CapstoneNative.ARCH_ARM64
    val out = ArrayList<LkInstruction>(data.size / step + 1)
    var offset = 0
    while (offset < data.size - 1) {
        val end = minOf(offset + 4, data.size)
        val word = data.copyOfRange(offset, end)
        val insn = CapstoneNative.disassembleOne(word, base + offset, arch)
        if (insn != null) {
            val ok = if (architecture == "aarch64") insn.size == 4
            else insn.size == 2 || insn.size == 4
            if (ok) {
                out.add(
                    LkInstruction(
                        address = insn.address,
                        mnemonic = insn.mnemonic,
                        operands = insn.opStr,
                        raw = insn.bytes,
                        mode = architecture,
                    )
                )
            }
        }
        offset += step
    }
    return out
}

/** Kotlin Regex match anchored at index 0 — Python re.match semantics. */
fun pyMatch(pattern: Regex, input: String): MatchResult? = pattern.matchAt(input, 0)

val ADRP_RE = Regex("""\s*(x\d+),\s*#(0x[0-9a-fA-F]+)\s*$""")
val BRANCH_TARGET_RE = Regex("""#(0x[0-9a-fA-F]+)""")

fun parseAdrp(operands: String): Pair<String, Long>? {
    val m = pyMatch(ADRP_RE, operands) ?: return null
    return m.groupValues[1] to java.lang.Long.parseUnsignedLong(m.groupValues[2].substring(2), 16)
}

fun parseAddAddress(operands: String, reg: String): Long? {
    val re = Regex("""\s*""" + reg + """,\s*""" + reg + """,\s*#(0x[0-9a-fA-F]+|\d+)\s*$""")
    val m = pyMatch(re, operands) ?: return null
    return parseInt0(m.groupValues[1])
}

fun parseMemOffset(operands: String, reg: String): Long? {
    val re = Regex("""\[""" + reg + """(?:,\s*#(0x[0-9a-fA-F]+|\d+))?\]""")
    val m = re.find(operands) ?: return null
    val g = m.groups[1]?.value ?: return 0L
    return parseInt0(g)
}

fun parseThumbLiteralLdr(operands: String): Pair<String, Long>? {
    val re = Regex("""\s*(r\d+),\s*\[pc(?:,\s*#(0x[0-9a-fA-F]+|\d+))?\]\s*$""")
    val m = pyMatch(re, operands) ?: return null
    val imm = m.groups[2]?.value?.let { parseInt0(it) } ?: 0L
    return m.groupValues[1] to imm
}

fun isThumbAddPc(operands: String, reg: String): Boolean {
    val re1 = Regex("""\s*""" + reg + """,\s*pc\s*$""")
    val re2 = Regex("""\s*""" + reg + """,\s*""" + reg + """,\s*pc\s*$""")
    return pyMatch(re1, operands) != null || pyMatch(re2, operands) != null
}

fun signedU32At(data: ByteArray, offset: Long): Int? {
    if (offset < 0 || offset + 4 > data.size) return null
    val o = offset.toInt()
    val value = ValBytes.u32At(data, o).toInt()
    return value
}

fun matchStringTarget(
    effective: Long,
    byAddress: Map<Long, LkStringHit>,
    hits: List<LkStringHit>,
): LkStringHit? {
    val target = byAddress[effective]
    if (target != null) return target
    for (hit in hits) {
        val end = hit.address + maxOf(1L, hit.text.length.toLong() + 1)
        if (uLessEq(hit.address - 4, effective) && uLess(effective, end)) return hit
    }
    return null
}

fun findStringXrefs(
    instructions: List<LkInstruction>,
    stringHits: List<LkStringHit>,
    lookahead: Int = 12,
    data: ByteArray,
    base: Long,
): List<LkXrefHit> {
    val byAddress = stringHits.associateBy { it.address }
    val byPage = mutableMapOf<Long, MutableList<LkStringHit>>()
    for (hit in stringHits) {
        byPage.getOrPut(hit.address and (-0x1000L)) { mutableListOf() }.add(hit)
    }
    val xrefs = mutableListOf<LkXrefHit>()
    for ((index, insn) in instructions.withIndex()) {
        if (insn.mnemonic == "adrp") {
            val parsed = parseAdrp(insn.operands)
            if (parsed != null) {
                val (reg, page) = parsed
                if (page in byPage) {
                    for (probeIndex in index + 1 until minOf(instructions.size, index + lookahead + 1)) {
                        val probe = instructions[probeIndex]
                        if (probe.mnemonic == "add") {
                            val imm = parseAddAddress(probe.operands, reg) ?: continue
                            val effective = page + imm
                            val target = byAddress[effective] ?: continue
                            xrefs.add(
                                LkXrefHit(
                                    insnIndex = probeIndex,
                                    target = target,
                                    address = probe.address,
                                    mnemonic = probe.mnemonic,
                                    operands = probe.operands,
                                    effectiveAddress = effective,
                                    via = "adrp/add $reg",
                                )
                            )
                        } else if (probe.mnemonic in setOf("ldr", "ldrb", "ldrh", "ldrsw", "str", "strb", "strh")) {
                            val imm = parseMemOffset(probe.operands, reg) ?: continue
                            val effective = page + imm
                            val target = byAddress[effective] ?: continue
                            xrefs.add(
                                LkXrefHit(
                                    insnIndex = probeIndex,
                                    target = target,
                                    address = probe.address,
                                    mnemonic = probe.mnemonic,
                                    operands = probe.operands,
                                    effectiveAddress = effective,
                                    via = "adrp/mem $reg",
                                )
                            )
                        }
                    }
                }
            }
        }
        if (insn.mode == "arm32_thumb") {
            val parsedThumb = parseThumbLiteralLdr(insn.operands) ?: continue
            val (reg, literalOffset) = parsedThumb
            val literalAddress = ((insn.address + 4) and 3L.inv()) + literalOffset
            val literalValue = signedU32At(data, literalAddress - base) ?: continue
            for (probeIndex in index + 1 until minOf(instructions.size, index + lookahead + 1)) {
                val probe = instructions[probeIndex]
                if (probe.mode != "arm32_thumb") continue
                if (probe.mnemonic != "add" || !isThumbAddPc(probe.operands, reg)) continue
                val effective = probe.address + 4 + literalValue
                val target = matchStringTarget(effective, byAddress, stringHits) ?: continue
                xrefs.add(
                    LkXrefHit(
                        insnIndex = probeIndex,
                        target = target,
                        address = probe.address,
                        mnemonic = probe.mnemonic,
                        operands = probe.operands,
                        effectiveAddress = target.address,
                        via = "thumb ldr/add-pc $reg",
                    )
                )
            }
        }
    }
    val unique = linkedMapOf<Triple<Long, Long, String>, LkXrefHit>()
    for (xref in xrefs) {
        unique[Triple(xref.address, xref.effectiveAddress, xref.via)] = xref
    }
    return unique.values.sortedWith(
        compareBy({ it.address }, { it.effectiveAddress })
    )
}

fun looksLikeFunctionStart(insn: LkInstruction): Boolean {
    if (insn.mnemonic == "stp" && "x29, x30, [sp" in insn.operands) return true
    if (insn.mnemonic == "sub" && insn.operands.startsWith("sp, sp, #")) return true
    if (insn.mnemonic in setOf("pacibsp", "paciasp")) return true
    if (insn.mnemonic in setOf("push", "push.w") && "lr" in insn.operands) return true
    return false
}

fun looksLikeFunctionEnd(insn: LkInstruction): Boolean {
    if (insn.mnemonic == "ret") return true
    if (insn.mnemonic in setOf("pop", "pop.w") && "pc" in insn.operands) return true
    if (insn.mnemonic == "bx" && insn.operands.trim() == "lr") return true
    return false
}

fun estimateFunctionRange(
    instructions: List<LkInstruction>,
    centerIndex: Int,
): Pair<Int, Int> {
    val scanStart = maxOf(0, centerIndex - 256)
    var start = scanStart
    for (index in centerIndex downTo scanStart) {
        if (looksLikeFunctionStart(instructions[index])) {
            start = when {
                instructions[index].mnemonic == "stp" &&
                    index > 0 &&
                    instructions[index - 1].mnemonic == "sub" &&
                    instructions[index - 1].operands.startsWith("sp, sp, #") -> {
                    if (index > 1 && instructions[index - 2].mnemonic in setOf("pacibsp", "paciasp")) index - 2
                    else index - 1
                }
                instructions[index].mnemonic == "stp" &&
                    index > 0 &&
                    instructions[index - 1].mnemonic in setOf("pacibsp", "paciasp") -> index - 1
                instructions[index].mnemonic == "sub" &&
                    instructions[index].operands.startsWith("sp, sp, #") &&
                    index > 0 &&
                    instructions[index - 1].mnemonic in setOf("pacibsp", "paciasp") -> index - 1
                else -> index
            }
            break
        }
    }
    val scanEnd = minOf(instructions.size - 1, centerIndex + 384)
    var end = scanEnd
    for (index in centerIndex..scanEnd) {
        if (looksLikeFunctionEnd(instructions[index])) {
            end = index
            break
        }
    }
    return start to end
}

fun parseBranchTarget(operands: String): Long? {
    val matches = BRANCH_TARGET_RE.findAll(operands).map { it.groupValues[1] }.toList()
    if (matches.isEmpty()) return null
    return java.lang.Long.parseUnsignedLong(matches.last().substring(2), 16)
}

val THUMB_CONDITIONAL = setOf(
    "beq", "bne", "bhs", "blo", "bmi", "bpl", "bvs", "bvc",
    "bhi", "bls", "bge", "blt", "bgt", "ble",
)

fun isBranchInstruction(insn: LkInstruction): Boolean {
    return insn.mnemonic == "b" ||
        insn.mnemonic.startsWith("b.") ||
        insn.mnemonic in THUMB_CONDITIONAL ||
        insn.mnemonic in setOf("cbz", "cbnz", "tbz", "tbnz")
}

fun isConditionalBranch(insn: LkInstruction): Boolean {
    return insn.mnemonic.startsWith("b.") ||
        insn.mnemonic in THUMB_CONDITIONAL ||
        insn.mnemonic in setOf("cbz", "cbnz", "tbz", "tbnz")
}

fun parseCallTarget(insn: LkInstruction): Long? {
    if (insn.mnemonic != "bl") return null
    return parseBranchTarget(insn.operands)
}

fun scoreTargets(targets: Iterable<String>): Int {
    var score = 0
    for (target in targets.toSet()) {
        score += LK_CORE_FLOW_TARGETS.getOrDefault(target, 0)
        score += LK_CONTEXT_FLOW_TARGETS.getOrDefault(target, 0)
        score += LK_FRP_FLOW_TARGETS.getOrDefault(target, 0)
        score += LK_KEY_VALIDATOR_TARGETS.getOrDefault(target, 0)
        score += LK_ERASE_PERMISSION_TARGETS.getOrDefault(target, 0)
    }
    return score
}

fun buildFunctionFlows(
    instructions: List<LkInstruction>,
    xrefs: List<LkXrefHit>,
): List<LkFunctionFlow> {
    if (xrefs.isEmpty()) return emptyList()
    val ranges = xrefs.map { estimateFunctionRange(instructions, it.insnIndex) }
    val sortedRanges = ranges.sortedWith(compareBy({ it.first }, { it.second }))
    val merged = mutableListOf<Pair<Int, Int>>()
    for ((start, end) in sortedRanges) {
        if (merged.isEmpty() || start > merged.last().second) {
            merged.add(start to end)
        } else {
            merged[merged.lastIndex] = merged.last().first to maxOf(merged.last().second, end)
        }
    }
    val flows = mutableListOf<LkFunctionFlow>()
    for ((start, end) in merged) {
        val localXrefs = xrefs.filter { it.insnIndex in start..end }
        if (localXrefs.isEmpty()) continue
        val calls = mutableListOf<Long>()
        val branches = mutableListOf<Triple<Long, Long, String>>()
        for (index in start..end) {
            val insn = instructions[index]
            val callTarget = parseCallTarget(insn)
            if (callTarget != null) calls.add(callTarget)
            if (isBranchInstruction(insn)) {
                val branchTarget = parseBranchTarget(insn.operands)
                if (branchTarget != null) {
                    branches.add(Triple(insn.address, branchTarget, insn.mnemonic))
                }
            }
        }
        val matchedTargets = localXrefs.map { it.target.target }.toSortedSet().toList()
        var score = scoreTargets(matchedTargets) + localXrefs.size
        val coreMatches = matchedTargets.count { it in LK_CORE_FLOW_TARGETS }
        if (coreMatches >= 3) score += 10
        if ("Check 'Allow OEM Unlock'" in matchedTargets) score += 5
        val frpMatches = matchedTargets.count { it in LK_FRP_FLOW_TARGETS }
        if (frpMatches >= 3) score += 8
        val keyMatches = matchedTargets.count { it in LK_KEY_VALIDATOR_TARGETS }
        if (keyMatches >= 3) score += 8
        val erasePermMatches = matchedTargets.count { it in LK_ERASE_PERMISSION_TARGETS }
        if (erasePermMatches >= 3) score += 10
        flows.add(
            LkFunctionFlow(
                startIndex = start,
                endIndex = end,
                startAddress = instructions[start].address,
                endAddress = instructions[end].address,
                xrefs = localXrefs,
                calls = calls.distinct(),
                branches = branches,
                score = score,
                matchedTargets = matchedTargets,
            )
        )
    }
    return flows.sortedWith(compareByDescending<LkFunctionFlow> { it.score }.thenBy { it.startAddress })
}

fun flowInstructionSlice(
    instructions: List<LkInstruction>,
    flow: LkFunctionFlow,
): List<LkInstruction> = instructions.subList(flow.startIndex, flow.endIndex + 1)

fun flowHasXrefTarget(flow: LkFunctionFlow, target: String): Boolean =
    flow.xrefs.any { it.target.target == target }

/** Analyzer state: the instruction list plus an address -> index map.
 *  Python scans the whole list linearly in call_target_matches_function
 *  and friends; the map gives the identical answer (addresses are unique)
 *  without the O(n) scan. */
class LkAnalyzerCore(
    val data: ByteArray,
    val base: Long,
    val instructions: List<LkInstruction>,
) {
    val addrIndex: Map<Long, Int> =
        instructions.withIndex().associate { it.value.address to it.index }

    fun callTargetMatchesFunction(callTarget: Long?, functionStart: Long): Boolean {
        if (callTarget == null) return false
        if (callTarget == functionStart) return true
        if (callTarget + 4 != functionStart) return false
        val idx = addrIndex[callTarget] ?: return false
        return instructions[idx].mnemonic == "paciasp"
    }

    fun findFunctionBoundsByAddress(address: Long): Pair<Int, Int>? {
        val index = addrIndex[address] ?: return null
        return estimateFunctionRange(instructions, index)
    }

    fun findInstructionIndexByAddress(address: Long?): Int? {
        if (address == null) return null
        return addrIndex[address]
    }

    fun callSitesForTarget(
        flows: List<LkFunctionFlow>,
        targetAddress: Long,
    ): List<Pair<Long, Long>> {
        val callers = mutableListOf<Pair<Long, Long>>()
        for (caller in flows) {
            for (insn in flowInstructionSlice(instructions, caller)) {
                if (callTargetMatchesFunction(parseCallTarget(insn), targetAddress)) {
                    callers.add(caller.startAddress to insn.address)
                }
            }
        }
        return callers.distinct()
    }
}

/** Python slice list[a:b] with clamping. */
fun <T> List<T>.pySlice(from: Int, to: Int): List<T> {
    if (from >= size) return emptyList()
    val lo = from.coerceIn(0, size)
    val hi = to.coerceIn(lo, size)
    return subList(lo, hi)
}

fun flowToDict(flow: LkFunctionFlow): PyObj {
    return pyObjOf(
        "start_address" to PyStr(hex16(flow.startAddress)),
        "end_address" to PyStr(hex16(flow.endAddress)),
        "score" to PyInt(flow.score.toLong()),
        "matched_targets" to pyArrOf(flow.matchedTargets.map { PyStr(it) }),
        "calls" to pyArrOf(flow.calls.map { PyStr(hex16(it)) }),
        "branches" to pyArrOf(flow.branches.map { (source, target, mnemonic) ->
            pyObjOf(
                "from" to PyStr(hex16(source)),
                "to" to PyStr(hex16(target)),
                "mnemonic" to PyStr(mnemonic),
            )
        }),
        "xrefs" to pyArrOf(flow.xrefs.map { xref ->
            pyObjOf(
                "xref_address" to PyStr(hex16(xref.address)),
                "string_address" to PyStr(hex16(xref.effectiveAddress)),
                "target" to PyStr(xref.target.target),
                "string" to PyStr(xref.target.text),
                "via" to PyStr(xref.via),
                "instruction" to PyStr("${xref.mnemonic} ${xref.operands}".trim()),
            )
        }),
    )
}

fun instructionToDict(insn: LkInstruction, base: Long, role: String): PyObj {
    return pyObjOf(
        "role" to PyStr(role),
        "address" to PyStr(hex16(insn.address)),
        "file_offset" to PyStr(hexOff(fileOffsetForVa(insn.address, base))),
        "hxd_offset" to PyStr(hxdOffsetForVa(insn.address, base)),
        "instruction" to PyStr("${insn.mnemonic} ${insn.operands}".trim()),
        "raw" to PyStr(ValBytes.hex(insn.raw)),
    )
}

fun xrefToDict(xref: LkXrefHit, base: Long): PyObj {
    return pyObjOf(
        "target" to PyStr(xref.target.target),
        "string" to PyStr(xref.target.text),
        "xref_address" to PyStr(hex16(xref.address)),
        "xref_file_offset" to PyStr(hexOff(fileOffsetForVa(xref.address, base))),
        "xref_hxd_offset" to PyStr(hxdOffsetForVa(xref.address, base)),
        "string_address" to PyStr(hex16(xref.effectiveAddress)),
        "string_file_offset" to PyStr(hexOff(fileOffsetForVa(xref.effectiveAddress, base))),
        "string_hxd_offset" to PyStr(hxdOffsetForVa(xref.effectiveAddress, base)),
        "instruction" to PyStr("${xref.mnemonic} ${xref.operands}".trim()),
        "via" to PyStr(xref.via),
    )
}
