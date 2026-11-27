// Analysis-directory readers for lk_patch_partition.py.
// JSON contract (produced by lk_static_analyzer.py; the analyzer phase must
// match these exact files/fields):
//   summary.txt        — "Architecture: <aarch64|arm32_thumb>" line and
//                          "LK base: 0x..." line; lk.bin = extracted payload.
//   flow_candidates.json — list of {start_address: "0x...", end_address: "0x...",
//                          xrefs: [{target, xref_address: "0x...",
//                                   string_address: "0x...", instruction, via}]}
//   partition_erases/<old>/strings.json — list of {role, text,
//                          reference_mode, hxd_offset}
//   partition_erase_operations.json — list of {operation, partition,
//                          flow_start_file_offset, call_site_file_offset,
//                          result_check_file_offset | result_check_address,
//                          string_file_offset}
//   frp_checkers.json | frp_checkers/frp_checker_*_details.json — list of
//                          {callers: [{caller_start_file_offset,
//                          call_site_file_offset}], structural_instructions:
//                          [{role, hxd_offset, instruction, file_offset}]}
//   key_validators.json | key_validators/key_validator_*_details.json —
//                          [{function_start_file_offset,
//                          function_end_file_offset, function_start,
//                          function_end, callers: [...],
//                          structural_instructions: [...], failure_edges:
//                          [{branch}] }]
//   serialno_runtime.json — {confidence, architecture,
//                          hw_get_serialno_string_file_offset, xrefs: [{string}]}
//   erase_permission_checks.json |
//   erase_permission_checks/erase_permission_*_details.json —
//                          [{function_start_file_offset,
//                          function_end_file_offset, structural_instructions,
//                          denied_edges: [{branch_file_offset,
//                          target_file_offset, mnemonic, branch_hxd_offset,
//                          target_hxd_offset}]}]
package com.cronch.motoflasher.valproto

import java.io.File

class ValAnalysisException(message: String) : Exception(message)

object ValAnalysis {
    fun parseHxdOffset(value: String): Int {
        val v = value.trim()
        return if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2).toLong(16).toInt()
        else v.toLong(16).toInt()
    }

    /** Parse a hex VA/address string, full 64-bit range. VAs like
     *  0xffff000050f00000 exceed Long.MAX_VALUE, so parse unsigned and keep
     *  the bit pattern (same convention as 0x...uL.toLong() elsewhere). */
    fun parseHexLong(value: String): Long {
        val v = value.trim()
        return if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2).toULong(16).toLong()
        else v.toULong(16).toLong()
    }

    fun loadArchitecture(analysisDir: File): String {
        val summary = File(analysisDir, "summary.txt")
        if (!summary.isFile) return "aarch64"
        for (line in summary.readText(Charsets.UTF_8).split("\n")) {
            if (line.startsWith("Architecture")) {
                return line.substringAfter(":").trim().ifEmpty { "aarch64" }
            }
        }
        return "aarch64"
    }

    fun loadBase(analysisDir: File): Long {
        val summary = File(analysisDir, "summary.txt")
        if (!summary.isFile) throw java.io.FileNotFoundException("$summary does not exist")
        for (line in summary.readText(Charsets.UTF_8).split("\n")) {
            if (line.startsWith("LK base")) {
                return parseHexLong(line.substringAfter(":").trim())
            }
        }
        throw ValAnalysisException("summary.txt has no LK base")
    }

    fun tryLoadBase(analysisDir: File): Long = try {
        loadBase(analysisDir)
    } catch (e: Exception) {
        0L
    }

    fun loadFlowCandidates(analysisDir: File): List<JsonObj> {
        val path = File(analysisDir, "flow_candidates.json")
        if (!path.isFile) throw java.io.FileNotFoundException("$path does not exist")
        val flows = ValJson.parseFile(path)
        if (flows !is JsonArr) throw ValAnalysisException("flow_candidates.json is invalid")
        return flows.items.filterIsInstance<JsonObj>()
    }

    fun loadFlowForStart(analysisDir: File, startAddress: Long): JsonObj {
        for (flow in loadFlowCandidates(analysisDir)) {
            val raw = flow.str("start_address", "0") ?: "0"
            if (parseHexLong(raw) == startAddress) return flow
        }
        throw ValAnalysisException("no flow candidate found for 0x${startAddress.toString(16)}")
    }

    fun loadPartitionArgOffsets(analysisDir: File, oldName: String): List<JsonObj> {
        val stringsPath = File(File(File(analysisDir, "partition_erases"), oldName), "strings.json")
        if (!stringsPath.isFile) throw java.io.FileNotFoundException("$stringsPath does not exist")
        val entries = ValJson.parseFile(stringsPath)
        if (entries !is JsonArr) throw ValAnalysisException("strings.json is invalid")
        val matches = entries.items.filterIsInstance<JsonObj>().filter {
            it.str("role") == "partition_arg" && it.str("text") == oldName &&
                it.str("reference_mode") == "direct_string"
        }
        if (matches.isEmpty()) throw ValAnalysisException(
            "no direct role=partition_arg strings found for '$oldName'"
        )
        return matches
    }

    fun loadPartitionEraseOperations(analysisDir: File): List<JsonObj> {
        val path = File(analysisDir, "partition_erase_operations.json")
        if (!path.isFile) throw java.io.FileNotFoundException("$path does not exist")
        val ops = ValJson.parseFile(path)
        if (ops !is JsonArr) throw ValAnalysisException("partition_erase_operations.json is invalid")
        return ops.items.filterIsInstance<JsonObj>()
    }

    private fun loadFrpCheckers(analysisDir: File): List<JsonObj> {
        val dir = File(analysisDir, "frp_checkers")
        val details = if (dir.isDirectory) {
            dir.listFiles { f -> f.name.startsWith("frp_checker_") && f.name.endsWith("_details.json") }
                ?.sortedBy { it.name } ?: emptyList()
        } else emptyList()
        if (details.isNotEmpty()) return details.map { ValJson.parseFile(it).asObj() }
        val path = File(analysisDir, "frp_checkers.json")
        if (!path.isFile) throw java.io.FileNotFoundException("$path does not exist")
        val v = ValJson.parseFile(path)
        if (v !is JsonArr) throw ValAnalysisException("frp_checkers.json is invalid")
        return v.items.filterIsInstance<JsonObj>()
    }

    fun loadFrpCompareOffsets(analysisDir: File): List<JsonObj> {
        val checkers = loadFrpCheckers(analysisDir)
        val matches = mutableListOf<JsonObj>()
        for (checker in checkers.take(1)) {
            var fallbackThumbLoad: JsonObj? = null
            val structural = checker.arr("structural_instructions").filterIsInstance<JsonObj>()
            for (item in structural) {
                if (item.str("role") == "last_byte_compare_zero") matches.add(item)
                else if (item.str("role") == "last_byte_load_sp_0xff" &&
                    (item.str("instruction", "") ?: "").startsWith("ldrb.w ")
                ) fallbackThumbLoad = item
            }
            if (fallbackThumbLoad != null && structural.none { it.str("role") == "last_byte_compare_zero" }) {
                val loadOffset = parseHxdOffset(fallbackThumbLoad.str("hxd_offset") ?: throw ValAnalysisException("missing hxd_offset"))
                val cloned = JsonObj(
                    fallbackThumbLoad.map.toMutableMap().apply {
                        put("role", JsonStr("last_byte_compare_zero"))
                        put("hxd_offset", JsonStr("%08X".format(loadOffset + 4)))
                        put("file_offset", JsonStr("0x${(loadOffset + 4).toString(16)}"))
                    }
                )
                matches.add(cloned)
            }
        }
        if (matches.isEmpty()) throw ValAnalysisException(
            "FRP last_byte_compare_zero instruction not found; " +
                "regenerate the analysis with an updated lk_static_analyzer.py"
        )
        return matches
    }

    fun loadFrpCheckerEntry(analysisDir: File): JsonObj {
        val checkers = loadFrpCheckers(analysisDir)
        if (checkers.isEmpty()) throw ValAnalysisException("no FRP/OEM checker candidates found")
        val checker = checkers[0]
        if (!checker.map.containsKey("callers")) throw ValAnalysisException("FRP/OEM checker has no valid callers")
        return checker
    }

    fun frpSkipCallOffsets(analysisDir: File): List<Int> {
        val checker = loadFrpCheckerEntry(analysisDir)
        val callers = checker.arr("callers").filterIsInstance<JsonObj>()
        if (callers.isEmpty()) throw ValAnalysisException("FRP/OEM checker has no callers")
        var keyCallerOffset: Int? = null
        try {
            val validator = loadKeyValidatorEntry(analysisDir)
            val vCallers = validator.arr("callers").filterIsInstance<JsonObj>()
            if (vCallers.isNotEmpty()) {
                val caller = vCallers[0]
                if (caller.map.containsKey("caller_start_file_offset")) {
                    keyCallerOffset = parseHxdOffset(caller.str("caller_start_file_offset") ?: "-1")
                }
            }
        } catch (e: Exception) {
            keyCallerOffset = null
        }
        val offsets = mutableListOf<Int>()
        for (caller in callers) {
            if (keyCallerOffset != null) {
                val callerStart = parseHxdOffset(caller.str("caller_start_file_offset", "-1") ?: "-1")
                if (callerStart != keyCallerOffset) continue
            }
            if (!caller.map.containsKey("call_site_file_offset")) continue
            offsets.add(parseHxdOffset(caller.str("call_site_file_offset") ?: continue))
        }
        val sorted = offsets.toSortedSet().toList()
        if (sorted.isEmpty()) throw ValAnalysisException("no FRP/OEM calls found inside the unlock caller")
        return sorted
    }

    private fun loadKeyValidators(analysisDir: File): List<JsonObj> {
        val dir = File(analysisDir, "key_validators")
        val details = if (dir.isDirectory) {
            dir.listFiles { f -> f.name.startsWith("key_validator_") && f.name.endsWith("_details.json") }
                ?.sortedBy { it.name } ?: emptyList()
        } else emptyList()
        if (details.isNotEmpty()) return details.map { ValJson.parseFile(it).asObj() }
        val path = File(analysisDir, "key_validators.json")
        if (!path.isFile) throw java.io.FileNotFoundException("$path does not exist")
        val v = ValJson.parseFile(path)
        if (v !is JsonArr) throw ValAnalysisException("key_validators.json is invalid")
        return v.items.filterIsInstance<JsonObj>()
    }

    fun loadKeyPrimaryReturnOffsets(analysisDir: File): List<JsonObj> {
        val validators = loadKeyValidators(analysisDir)
        val matches = mutableListOf<JsonObj>()
        for (validator in validators.take(1)) {
            val structural = validator.arr("structural_instructions").filterIsInstance<JsonObj>()
            var firstRetOffset: Int? = null
            for (item in structural) {
                if (item.str("role") != "function_return") continue
                firstRetOffset = parseHxdOffset(item.str("hxd_offset") ?: continue)
                break
            }
            for (item in structural) {
                if (item.str("role") != "return_result_w20") continue
                val itemOffset = parseHxdOffset(item.str("hxd_offset") ?: continue)
                if (firstRetOffset != null && itemOffset > firstRetOffset) continue
                matches.add(item)
                break
            }
        }
        if (matches.isEmpty()) throw ValAnalysisException(
            "main KEY return_result_w20 return not found; " +
                "regenerate the analysis with an updated lk_static_analyzer.py"
        )
        return matches
    }

    fun loadKeyValidatorEntry(analysisDir: File): JsonObj {
        val validators = loadKeyValidators(analysisDir)
        if (validators.isEmpty()) throw ValAnalysisException("no KEY validator candidates found")
        val validator = validators[0]
        if (!validator.map.containsKey("function_start_file_offset") ||
            !validator.map.containsKey("function_end_file_offset")
        ) throw ValAnalysisException(
            "KEY candidate has no function offsets; " +
                "regenerate the analysis with an updated lk_static_analyzer.py"
        )
        return validator
    }

    fun loadSerialnoRuntimeEntry(analysisDir: File): JsonObj {
        val path = File(analysisDir, "serialno_runtime.json")
        if (!path.isFile) throw java.io.FileNotFoundException(
            "does not exist: $path; regenerate the analysis with an updated lk_static_analyzer.py"
        )
        val entry = ValJson.parseFile(path)
        if (entry !is JsonObj || entry.map.isEmpty()) throw ValAnalysisException("serialno_runtime.json has no valid candidate")
        val confidence = (entry.long("confidence") ?: 0L).toInt()
        if (confidence < 90) throw ValAnalysisException(
            "serialno runtime candidate confidence too low ($confidence); experimental stub will not be applied"
        )
        val arch = entry.str("architecture")
        if (arch != null && arch !in setOf("aarch64", "arm32_thumb")) throw ValAnalysisException("runtime serial stub does not support this architecture")
        if (entry.map["hw_get_serialno_string_file_offset"] == null) throw ValAnalysisException("serialno_runtime.json has no hw_get_serialno_string_file_offset")
        val xrefTexts = entry.arr("xrefs").filterIsInstance<JsonObj>()
            .mapNotNull { it.str("string")?.trim('\u0000') }.toSet()
        if ("serialno" !in xrefTexts) throw ValAnalysisException("serialno candidate does not reference the 'serialno' string")
        if ("barcode" !in xrefTexts) throw ValAnalysisException("serialno candidate shows no fallback to 'barcode'")
        return entry
    }

    fun loadErasePermissionEntry(analysisDir: File, data: ByteArray? = null): JsonObj {
        val dir = File(analysisDir, "erase_permission_checks")
        val details = if (dir.isDirectory) {
            dir.listFiles { f -> f.name.startsWith("erase_permission_") && f.name.endsWith("_details.json") }
                ?.sortedBy { it.name } ?: emptyList()
        } else emptyList()
        val checks: List<JsonObj>
        if (details.isNotEmpty()) {
            checks = details.map { ValJson.parseFile(it).asObj() }
        } else {
            val path = File(analysisDir, "erase_permission_checks.json")
            if (!path.isFile) throw java.io.FileNotFoundException(
                "does not exist: $path; regenerate the analysis with an updated lk_static_analyzer.py"
            )
            val v = ValJson.parseFile(path)
            if (v !is JsonArr) throw ValAnalysisException("erase_permission_checks.json is invalid")
            checks = v.items.filterIsInstance<JsonObj>()
        }
        if (checks.isEmpty()) throw ValAnalysisException("no erase_permission_check candidates found")
        if (data != null) {
            try {
                val candidateOffsets = checks.mapNotNull {
                    if (it.map.containsKey("function_start_file_offset")) parseHxdOffset(it.str("function_start_file_offset") ?: return@mapNotNull null) else null
                }.toSet()
                val base = loadBase(analysisDir)
                val handler = ValPatchCore.findFastbootCommandHandlerOffset(data, base, "erase", candidateOffsets)
                if (handler != null) {
                    for (check in checks) {
                        if (parseHxdOffset(check.str("function_start_file_offset", "-1") ?: "-1") == handler) return check
                    }
                }
            } catch (e: Exception) {
                // fall through to checks[0], exactly like Python
            }
        }
        val check = checks[0]
        val required = setOf("function_start_file_offset", "function_end_file_offset", "structural_instructions", "denied_edges")
        if (!required.all { check.map.containsKey(it) }) throw ValAnalysisException(
            "erase_permission_check candidate has no offsets/structure; " +
                "regenerate the analysis with an updated lk_static_analyzer.py"
        )
        return check
    }

    fun loadFastbootInfoTargets(analysisDir: File, data: ByteArray): Map<String, Long> {
        val base = loadBase(analysisDir)
        val infoOffset = ValBytes.find(data, "INFO\u0000".toByteArray(Charsets.US_ASCII))
        if (infoOffset < 0) throw ValAnalysisException("no INFO string found for fastboot debug messages")
        val flows = loadFlowCandidates(analysisDir)
        var xrefVa: Long? = null
        for (flow in flows.take(3)) {
            for (xref in flow.arr("xrefs").filterIsInstance<JsonObj>()) {
                if (xref.str("target") == "Code validation failure") {
                    xrefVa = parseHexLong(xref.str("xref_address") ?: continue)
                    break
                }
            }
            if (xrefVa != null) break
        }
        val xrefVaNn = xrefVa ?: throw ValAnalysisException("no xref to Code validation failure found to infer fastboot_info")
        val xrefOffset = (xrefVaNn - base).toInt()
        if (xrefOffset < 0 || xrefOffset + 8 > data.size) throw ValAnalysisException("Code validation failure xref is outside the file")
        val firstBranchWord = ValBytes.u32At(data, xrefOffset + 4)
        val epilogueVa = ValEncoders.decodeAarch64BranchTarget(firstBranchWord, xrefVaNn + 4)
            ?: throw ValAnalysisException("could not follow the branch from Code validation failure")
        val epilogueOffset = (epilogueVa - base).toInt()
        if (epilogueOffset < 0 || epilogueOffset + 4 > data.size) throw ValAnalysisException("fastboot_info epilogue is outside the file")
        var printFuncVa: Long? = null
        val scanEnd = minOf(data.size, epilogueOffset + 0x40)
        var offset = epilogueOffset
        while (offset < scanEnd) {
            val word = ValBytes.u32At(data, offset)
            if ((word and 0xFC000000L) == 0x14000000L) {
                val target = ValEncoders.decodeAarch64BranchTarget(word, base + offset)
                if (target != null) {
                    val targetOffset = target - base
                    if (targetOffset in 0 until data.size.toLong()) {
                        printFuncVa = target
                        break
                    }
                }
            }
            offset += 4
        }
        val printVa = printFuncVa ?: throw ValAnalysisException("no final branch to fastboot_info found")
        return mapOf(
            "info_string_offset" to infoOffset.toLong(),
            "info_string_va" to (base + infoOffset),
            "print_func_offset" to (printVa - base),
            "print_func_va" to printVa,
        )
    }

    fun patchCodeValidationMessage(data: ByteArray, analysisDir: File, message: String): Map<String, Any?> {
        val base = loadBase(analysisDir)
        val validator = loadKeyValidatorEntry(analysisDir)
        var callerStart: Int? = null
        val callers = validator.arr("callers").filterIsInstance<JsonObj>()
        if (callers.isNotEmpty()) {
            val caller = callers[0]
            if (caller.map.containsKey("caller_start_file_offset")) {
                callerStart = parseHxdOffset(caller.str("caller_start_file_offset") ?: "-1")
            }
        }
        val flows = loadFlowCandidates(analysisDir)
        var targetXref: JsonObj? = null
        outer@ for (flow in flows) {
            if (callerStart != null) {
                val flowStart = try {
                    parseHexLong(flow.str("start_address", "0") ?: "0") - base
                } catch (e: Exception) {
                    continue
                }
                if (flowStart != callerStart.toLong()) continue
            }
            for (xref in flow.arr("xrefs").filterIsInstance<JsonObj>()) {
                if (xref.str("target") == "Code validation failure") {
                    targetXref = xref
                    break@outer
                }
            }
        }
        val xref = targetXref ?: throw ValAnalysisException("no Code validation failure string found for checkpoint")
        val stringOffset = (parseHexLong(xref.str("string_address") ?: "0") - base).toInt()
        val original = ValBytes.readCString(data, stringOffset, 96)
        val replacement = message.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        if (replacement.size > original.size + 1) throw ValAnalysisException(
            "checkpoint message too long (${replacement.size} > ${original.size + 1})"
        )
        val padded = ValBytes.ljust(replacement, original.size + 1)
        padded.copyInto(data, stringOffset)
        return mapOf(
            "message_offset" to stringOffset,
            "original_message" to String(original, Charsets.US_ASCII),
            "new_message" to message,
        )
    }

    fun readCString(data: ByteArray, offset: Int, maxLen: Int = 96): ByteArray =
        ValBytes.readCString(data, offset, maxLen)

    fun normalizeImei(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) throw ValAnalysisException("IMEI cannot be empty")
        val candidates = Regex("\\b\\d{15}\\b").findAll(text).map { it.value }.toList()
        if (candidates.isEmpty()) throw ValAnalysisException("bad IMEI: use 15 digits, or paste a line that contains the IMEI")
        val unique = candidates.distinct()
        if (unique.size > 1) throw ValAnalysisException("more than one IMEI in the text; pass just one")
        return unique[0]
    }

    fun resolveDeviceOrImei(device: String, imei: String?, label: String = "token"): Pair<String, String> {
        if (imei == null) return device to if (device.isEmpty()) "global" else "device"
        val normalized = normalizeImei(imei)
        if (device.isNotEmpty() && device != normalized) throw ValAnalysisException(
            "--$label-device and --$label-imei do not match; use only one or the same value"
        )
        return normalized to "imei"
    }

    fun parseCustomSignature(value: String): ByteArray {
        val raw = try {
            value.toByteArray(Charsets.US_ASCII)
        } catch (e: Exception) {
            throw ValAnalysisException("custom signature must be ASCII only")
        }
        if (value.any { it.code > 127 }) throw ValAnalysisException("custom signature must be ASCII only")
        if (raw.size != 20) throw ValAnalysisException("custom signature must be exactly 20 ASCII bytes; it is ${raw.size}")
        return raw
    }

    fun parsePartitionName(value: String): String {
        val name = value.trim()
        if (name.isEmpty()) throw ValAnalysisException("partition name cannot be empty")
        if (name.any { it.code > 127 }) throw ValAnalysisException("use ASCII partition names")
        if ("__" in name) throw ValAnalysisException("partition name cannot contain '__'")
        if (name.length > 48) throw ValAnalysisException("partition name too long for this stub")
        return name
    }
}
