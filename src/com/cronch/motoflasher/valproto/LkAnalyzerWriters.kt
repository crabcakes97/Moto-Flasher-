// Analysis-dir writers for lk_static_analyzer.py — Kotlin port.
// Every file the Python analyzer writes, byte-for-byte: lk.bin,
// summary.txt, strings.txt, xrefs.txt, candidate_*.asm, flow reports,
// checker/validator reports + per-candidate detail dirs, partition erase
// reports, serialno reports, mermaid diagrams, flow_diagram.html, and the
// full disassembly.
//
// Upstream: Val Protocol (lk_static_analyzer.py). No AI branding anywhere
// in this code; real credits only.
//
// Input contract (see LkStaticAnalyzer): in = LibLk.LkImage (the parsed
// LK/LKS container — liblk is the container layer of the Val flow);
// out = analysis dir holding the extracted lk.bin plus the JSON artifacts
// the Phase 1 patch engine reads (flow_candidates.json,
// partition_erase_operations.json, partition_erases/<name>/strings.json,
// frp_checkers.json + frp_checkers/*_details.json, key_validators.json +
// key_validators/*_details.json, erase_permission_checks.json +
// erase_permission_checks/*_details.json, serialno_runtime.json) and the
// human-readable .txt/.asm/.mmd/.html reports. A raw lk.bin payload may be
// analyzed directly with an explicit base (the Python --base path).
package com.cronch.motoflasher.valproto

import com.cronch.motoflasher.LibLk
import java.io.File

private fun File.writeUtf8(text: String) = writeText(text, Charsets.UTF_8)

private fun StringBuilder.line(s: String = "") {
    append(s)
    append('\n')
}

fun writeDisassembly(path: File, instructions: List<LkInstruction>) {
    val sb = StringBuilder()
    for (insn in instructions) {
        val raw = ValBytes.hex(insn.raw)
        sb.append("0x").append(uHex(insn.address, 16)).append(":  ")
        sb.append(raw.padEnd(10)).append("  ")
        sb.append(insn.mnemonic.padEnd(8)).append(' ')
        sb.append(insn.operands).append('\n')
    }
    path.writeUtf8(sb.toString())
}

fun writeStrings(path: File, data: ByteArray, hits: List<LkStringHit>) {
    val sb = StringBuilder()
    for (hit in hits) {
        sb.append("0x").append(uHex(hit.address, 16))
        sb.append("  off=0x").append(uHex(hit.offset.toLong()))
        sb.append("  target=").append(pyRepr(hit.target)).append('\n')
        sb.append("  string: ").append(pyRepr(hit.text)).append('\n')
        sb.append("  context: ").append(printableContext(data, hit.offset)).append("\n\n")
    }
    path.writeUtf8(sb.toString())
}

fun writeXrefs(
    path: File,
    instructions: List<LkInstruction>,
    xrefs: List<LkXrefHit>,
    context: Int,
) {
    val sb = StringBuilder()
    if (xrefs.isEmpty()) {
        sb.append("No xrefs found by ADRP pattern.\n")
        path.writeUtf8(sb.toString())
        return
    }
    for (xref in xrefs) {
        sb.append("XREF 0x").append(uHex(xref.address, 16)).append(": ")
        sb.append(xref.mnemonic).append(' ').append(xref.operands)
        sb.append(" -> 0x").append(uHex(xref.effectiveAddress, 16))
        sb.append(" (").append(xref.target.target).append(") via ").append(xref.via).append('\n')
        sb.append("  string: ").append(pyRepr(xref.target.text)).append('\n')
        val start = maxOf(0, xref.insnIndex - context)
        val end = minOf(instructions.size, xref.insnIndex + context + 1)
        for (index in start until end) {
            val insn = instructions[index]
            val marker = if (index == xref.insnIndex) ">>" else "  "
            sb.append(marker).append(" 0x").append(uHex(insn.address, 16)).append(": ")
            sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
        }
        sb.append('\n')
    }
    path.writeUtf8(sb.toString())
}

fun writeCandidateFunctions(
    outputDir: File,
    instructions: List<LkInstruction>,
    xrefs: List<LkXrefHit>,
) {
    val seenRanges = mutableSetOf<Pair<Int, Int>>()
    var writtenCount = 0
    for (xref in xrefs) {
        val (start, end) = estimateFunctionRange(instructions, xref.insnIndex)
        val key = start to end
        if (key in seenRanges) continue
        seenRanges.add(key)
        writtenCount++
        val startAddr = instructions[start].address
        val safeTarget = Regex("[^A-Za-z0-9._-]+").replace(xref.target.target, "_").trim('_')
        val path = File(
            outputDir,
            "candidate_%02d_%016x_%s.asm".format(writtenCount, startAddr, safeTarget.take(40))
        )
        val sb = StringBuilder()
        sb.append("; candidate function around xref 0x").append(uHex(xref.address, 16))
        sb.append(" to ").append(pyRepr(xref.target.target)).append('\n')
        sb.append("; estimated range 0x").append(uHex(instructions[start].address, 16))
        sb.append("-0x").append(uHex(instructions[end].address, 16)).append("\n\n")
        for (index in start..end) {
            val insn = instructions[index]
            val marker = if (index == xref.insnIndex) ">>" else "  "
            sb.append(marker).append(" 0x").append(uHex(insn.address, 16)).append(": ")
            sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
        }
        path.writeUtf8(sb.toString())
    }
}

fun shortLabel(value: String, limit: Int = 72): String {
    val cleaned = value.replace("\n", "\\n").replace('"', '\'')
    if (cleaned.length <= limit) return cleaned
    return cleaned.substring(0, limit - 3) + "..."
}

fun writeFlowAsm(
    path: File,
    instructions: List<LkInstruction>,
    flow: LkFunctionFlow,
) {
    val xrefsByIndex = mutableMapOf<Int, MutableList<LkXrefHit>>()
    val branchTargets = flow.branches.map { it.second }.toSet()
    val callTargets = flow.calls.toSet()
    for (xref in flow.xrefs) {
        xrefsByIndex.getOrPut(xref.insnIndex) { mutableListOf() }.add(xref)
    }
    val sb = StringBuilder()
    sb.append("; flow candidate 0x").append(uHex(flow.startAddress, 16))
    sb.append("-0x").append(uHex(flow.endAddress, 16)).append('\n')
    sb.append("; score: ").append(flow.score).append('\n')
    sb.append("; matched targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
    sb.append("; calls: ").append(flow.calls.joinToString(", ") { "0x" + uHex(it, 16) }).append("\n\n")
    for (index in flow.startIndex..flow.endIndex) {
        val insn = instructions[index]
        if (insn.address in branchTargets) {
            sb.append("\nloc_").append(uHex(insn.address, 16)).append(":\n")
        }
        for (xref in xrefsByIndex[index] ?: emptyList()) {
            sb.append("; XREF string 0x").append(uHex(xref.effectiveAddress, 16)).append(": ")
            sb.append(pyRepr(xref.target.target)).append(" -> ").append(pyRepr(xref.target.text)).append('\n')
        }
        val callTarget = parseCallTarget(insn)
        var suffix = ""
        if (callTarget != null) {
            suffix = "  ; call"
            if (callTarget in callTargets) suffix += " 0x" + uHex(callTarget, 16)
        }
        val marker = if (index in xrefsByIndex) ">>" else "  "
        sb.append(marker).append(" 0x").append(uHex(insn.address, 16)).append(": ")
        sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append(suffix).append('\n')
    }
    path.writeUtf8(sb.toString())
}

fun writeFlowReports(
    outputDir: File,
    instructions: List<LkInstruction>,
    flows: List<LkFunctionFlow>,
) {
    val flowDir = File(outputDir, "flow_functions")
    flowDir.mkdirs()
    File(outputDir, "flow_candidates.json").writeUtf8(
        pyJsonDump(pyArrOf(flows.map { flowToDict(it) }))
    )
    val txt = StringBuilder()
    if (flows.isEmpty()) {
        txt.append("No flow candidates found.\n")
        File(outputDir, "flow_candidates.txt").writeUtf8(txt.toString())
        return
    }
    for ((number, flow) in flows.withIndex()) {
        val n = number + 1
        txt.append("[%02d] score=%d range=0x%s-0x%s xrefs=%d calls=%d branches=%d\n".format(
            n, flow.score, uHex(flow.startAddress, 16), uHex(flow.endAddress, 16),
            flow.xrefs.size, flow.calls.size, flow.branches.size
        ))
        txt.append("  targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
        for (xref in flow.xrefs) {
            txt.append("  - 0x").append(uHex(xref.address, 16))
            txt.append(" -> 0x").append(uHex(xref.effectiveAddress, 16))
            txt.append(" ").append(pyRepr(xref.target.target))
            txt.append(" [").append(xref.via).append("]\n")
        }
        txt.append('\n')
    }
    File(outputDir, "flow_candidates.txt").writeUtf8(txt.toString())
    for ((number, flow) in flows.withIndex()) {
        val n = number + 1
        val path = File(
            flowDir,
            "flow_%02d_score_%d_0x%s.asm".format(n, flow.score, uHex(flow.startAddress, 16))
        )
        writeFlowAsm(path, instructions, flow)
    }
    writeFlowAsm(File(outputDir, "best_flow.asm"), instructions, flows[0])
}

fun writeCheckAllowReport(
    path: File,
    instructions: List<LkInstruction>,
    flows: List<LkFunctionFlow>,
) {
    val sb = StringBuilder()
    var found = false
    for ((flowIndex, flow) in flows.withIndex()) {
        val matches = flow.xrefs.filter { it.target.target == "Check 'Allow OEM Unlock'" }
        if (matches.isEmpty()) continue
        found = true
        sb.append("Flow #%02d score=%d range=0x%s-0x%s\n".format(
            flowIndex + 1, flow.score, uHex(flow.startAddress, 16), uHex(flow.endAddress, 16)
        ))
        sb.append("  matched targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
        for (xref in matches) {
            sb.append("  XREF 0x").append(uHex(xref.address, 16)).append(": ")
            sb.append(xref.mnemonic).append(' ').append(xref.operands)
            sb.append(" -> 0x").append(uHex(xref.effectiveAddress, 16)).append('\n')
            val contextStart = maxOf(flow.startIndex, xref.insnIndex - 16)
            val contextEnd = minOf(flow.endIndex + 1, xref.insnIndex + 17)
            for (index in contextStart until contextEnd) {
                val insn = instructions[index]
                val marker = if (index == xref.insnIndex) ">>" else "  "
                sb.append(marker).append(" 0x").append(uHex(insn.address, 16)).append(": ")
                sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
            }
            sb.append('\n')
        }
    }
    if (!found) sb.append("No xrefs to Check 'Allow OEM Unlock' found.\n")
    path.writeUtf8(sb.toString())
}

fun writeFrpCheckerReports(
    outputDir: File,
    instructions: List<LkInstruction>,
    checkers: List<LkFrpChecker>,
    base: Long,
) {
    File(outputDir, "frp_checkers.json").writeUtf8(
        pyJsonDump(pyArrOf(checkers.map { frpCheckerToDict(it, base, instructions) }))
    )
    val sb = StringBuilder()
    if (checkers.isEmpty()) {
        sb.append("No FRP checker candidates found.\n")
        File(outputDir, "frp_checker_report.txt").writeUtf8(sb.toString())
        return
    }
    for ((number, checker) in checkers.withIndex()) {
        val n = number + 1
        val flow = checker.flow
        sb.append("[%02d] confidence=%d range=0x%s-0x%s HxD=0x%s-0x%s\n".format(
            n, checker.confidence, uHex(flow.startAddress, 16), uHex(flow.endAddress, 16),
            hxdOffsetForVa(flow.startAddress, base), hxdOffsetForVa(flow.endAddress, base)
        ))
        sb.append("  targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
        sb.append("  evidence:\n")
        for (item in checker.evidence) sb.append("    - ").append(item).append('\n')
        if (checker.callers.isNotEmpty()) {
            sb.append("  callers:\n")
            for ((callerStart, callSite) in checker.callers) {
                sb.append("    - caller 0x").append(uHex(callerStart, 16))
                sb.append(", HxD=").append(hxdOffsetForVa(callerStart, base))
                sb.append(", call site 0x").append(uHex(callSite, 16))
                sb.append(", call_HxD=").append(hxdOffsetForVa(callSite, base)).append('\n')
            }
        } else {
            sb.append("  callers: none found in current flow candidates\n")
        }
        val structural = frpStructuralInstructions(instructions, checker, base)
        if (structural.isNotEmpty()) {
            sb.append("  structural instructions:\n")
            for (item in structural) {
                val m = item.map
                sb.append("    - ").append((m["role"] as PyStr).s).append(": ")
                sb.append((m["address"] as PyStr).s)
                sb.append(" off=").append((m["file_offset"] as PyStr).s)
                sb.append(" HxD=").append((m["hxd_offset"] as PyStr).s)
                sb.append(" raw=").append((m["raw"] as PyStr).s)
                sb.append("  ").append((m["instruction"] as PyStr).s).append('\n')
            }
        }
        sb.append("  key instructions:\n")
        for (index in flow.startIndex..flow.endIndex) {
            val insn = instructions[index]
            val interesting = "#0x100" in insn.operands ||
                "[sp, #0xff]" in insn.operands ||
                insn.mnemonic in setOf("cmp", "cset") ||
                parseCallTarget(insn) != null ||
                flow.xrefs.any { it.insnIndex == index }
            if (interesting) {
                sb.append("    0x").append(uHex(insn.address, 16))
                sb.append(" off=0x").append(uHex(fileOffsetForVa(insn.address, base)))
                sb.append(" HxD=").append(hxdOffsetForVa(insn.address, base)).append(": ")
                sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
            }
        }
        sb.append('\n')
    }
    File(outputDir, "frp_checker_report.txt").writeUtf8(sb.toString())
    val frpDir = File(outputDir, "frp_checkers")
    frpDir.mkdirs()
    for ((number, checker) in checkers.withIndex()) {
        val n = number + 1
        File(frpDir, "frp_checker_%02d_details.json".format(n)).writeUtf8(
            pyJsonDump(frpCheckerToDict(checker, base, instructions))
        )
        val path = File(
            frpDir,
            "frp_checker_%02d_conf_%d_0x%s.asm".format(n, checker.confidence, uHex(checker.flow.startAddress, 16))
        )
        writeFlowAsm(path, instructions, checker.flow)
    }
}

fun writeKeyValidatorReports(
    outputDir: File,
    instructions: List<LkInstruction>,
    validators: List<LkKeyValidator>,
    base: Long,
) {
    File(outputDir, "key_validators.json").writeUtf8(
        pyJsonDump(pyArrOf(validators.map { keyValidatorToDict(it, base, instructions) }))
    )
    val sb = StringBuilder()
    if (validators.isEmpty()) {
        sb.append("No KEY validator candidates found.\n")
        File(outputDir, "key_validator_report.txt").writeUtf8(sb.toString())
        return
    }
    for ((number, validator) in validators.withIndex()) {
        val n = number + 1
        val flow = validator.flow
        sb.append("[%02d] confidence=%d range=0x%s-0x%s HxD=0x%s-0x%s\n".format(
            n, validator.confidence, uHex(flow.startAddress, 16), uHex(flow.endAddress, 16),
            hxdOffsetForVa(flow.startAddress, base), hxdOffsetForVa(flow.endAddress, base)
        ))
        sb.append("  targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
        sb.append("  evidence:\n")
        for (item in validator.evidence) sb.append("    - ").append(item).append('\n')
        if (validator.callers.isNotEmpty()) {
            sb.append("  callers:\n")
            for ((callerStart, callSite) in validator.callers) {
                sb.append("    - caller 0x").append(uHex(callerStart, 16))
                sb.append(", HxD=").append(hxdOffsetForVa(callerStart, base))
                sb.append(", call site 0x").append(uHex(callSite, 16))
                sb.append(", call_HxD=").append(hxdOffsetForVa(callSite, base)).append('\n')
            }
        } else {
            sb.append("  callers: none found in current flow candidates\n")
        }
        if (validator.failureEdges.isNotEmpty()) {
            sb.append("  failure edges:\n")
            for ((callSite, failureTarget, branch) in validator.failureEdges) {
                sb.append("    - call 0x").append(uHex(callSite, 16))
                sb.append(", ").append(branch).append(" -> 0x").append(uHex(failureTarget, 16))
                sb.append(" call_HxD=").append(hxdOffsetForVa(callSite, base))
                sb.append(" failure_HxD=").append(hxdOffsetForVa(failureTarget, base))
                sb.append(" (Code validation failure path)\n")
            }
        }
        val structural = keyValidatorStructuralInstructions(instructions, validator, base)
        if (structural.isNotEmpty()) {
            sb.append("  structural instructions:\n")
            for (item in structural) {
                val m = item.map
                sb.append("    - ").append((m["role"] as PyStr).s).append(": ")
                sb.append((m["address"] as PyStr).s)
                sb.append(" off=").append((m["file_offset"] as PyStr).s)
                sb.append(" HxD=").append((m["hxd_offset"] as PyStr).s)
                sb.append(" raw=").append((m["raw"] as PyStr).s)
                sb.append("  ").append((m["instruction"] as PyStr).s).append('\n')
            }
        }
        sb.append("  key instructions:\n")
        for (index in flow.startIndex..flow.endIndex) {
            val insn = instructions[index]
            val interesting = insn.mnemonic in setOf("cmp", "cset", "ldrb", "ldur", "mov", "ret") ||
                parseCallTarget(insn) != null ||
                flow.xrefs.any { it.insnIndex == index } ||
                "#0x20" in insn.operands
            if (interesting) {
                sb.append("    0x").append(uHex(insn.address, 16))
                sb.append(" off=0x").append(uHex(fileOffsetForVa(insn.address, base)))
                sb.append(" HxD=").append(hxdOffsetForVa(insn.address, base)).append(": ")
                sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
            }
        }
        sb.append('\n')
    }
    File(outputDir, "key_validator_report.txt").writeUtf8(sb.toString())
    val keyDir = File(outputDir, "key_validators")
    keyDir.mkdirs()
    for ((number, validator) in validators.withIndex()) {
        val n = number + 1
        File(keyDir, "key_validator_%02d_details.json".format(n)).writeUtf8(
            pyJsonDump(keyValidatorToDict(validator, base, instructions))
        )
        val path = File(
            keyDir,
            "key_validator_%02d_conf_%d_0x%s.asm".format(n, validator.confidence, uHex(validator.flow.startAddress, 16))
        )
        writeFlowAsm(path, instructions, validator.flow)
    }
}

fun writeErasePermissionReports(
    outputDir: File,
    instructions: List<LkInstruction>,
    checks: List<LkErasePermissionCheck>,
    base: Long,
) {
    File(outputDir, "erase_permission_checks.json").writeUtf8(
        pyJsonDump(pyArrOf(checks.map { erasePermissionToDict(it, base, instructions) }))
    )
    val sb = StringBuilder()
    if (checks.isEmpty()) {
        sb.append("No erase permission check candidates found.\n")
        File(outputDir, "erase_permission_report.txt").writeUtf8(sb.toString())
        return
    }
    for ((number, check) in checks.withIndex()) {
        val n = number + 1
        val flow = check.flow
        sb.append("[%02d] confidence=%d range=0x%s-0x%s HxD=0x%s-0x%s\n".format(
            n, check.confidence, uHex(flow.startAddress, 16), uHex(flow.endAddress, 16),
            hxdOffsetForVa(flow.startAddress, base), hxdOffsetForVa(flow.endAddress, base)
        ))
        sb.append("  targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
        sb.append("  evidence:\n")
        for (item in check.evidence) sb.append("    - ").append(item).append('\n')
        if (check.callers.isNotEmpty()) {
            sb.append("  callers:\n")
            for ((callerStart, callSite) in check.callers) {
                sb.append("    - caller 0x").append(uHex(callerStart, 16))
                sb.append(", HxD=").append(hxdOffsetForVa(callerStart, base))
                sb.append(", call site 0x").append(uHex(callSite, 16))
                sb.append(", call_HxD=").append(hxdOffsetForVa(callSite, base)).append('\n')
            }
        } else {
            sb.append("  callers: none found in current flow candidates\n")
        }
        if (check.nameChecks.isNotEmpty()) {
            sb.append("  fixed-name checks:\n")
            for (nc in check.nameChecks) {
                val callText = if (nc.callTarget != null) {
                    "0x" + uHex(nc.callTarget, 16) + " HxD=" + hxdOffsetForVa(nc.callTarget, base)
                } else "none"
                val branchText = if (nc.branchTarget != null) {
                    "0x" + uHex(nc.branchTarget, 16) + " HxD=" + hxdOffsetForVa(nc.branchTarget, base)
                } else "none"
                sb.append("    - ").append(nc.name).append(": xref=0x").append(uHex(nc.xrefAddress, 16))
                sb.append(" HxD=").append(hxdOffsetForVa(nc.xrefAddress, base))
                sb.append(" compare_call=").append(callText)
                sb.append(" branch_target=").append(branchText).append('\n')
            }
        }
        if (check.deniedEdges.isNotEmpty()) {
            sb.append("  denied edges:\n")
            for ((branchAddress, target, mnemonic) in check.deniedEdges) {
                sb.append("    - ").append(mnemonic).append(" 0x").append(uHex(branchAddress, 16))
                sb.append(" HxD=").append(hxdOffsetForVa(branchAddress, base))
                sb.append(" -> 0x").append(uHex(target, 16))
                sb.append(" HxD=").append(hxdOffsetForVa(target, base))
                sb.append(" (erase permission denied path)\n")
            }
        }
        val structural = erasePermissionStructuralInstructions(instructions, check, base)
        if (structural.isNotEmpty()) {
            sb.append("  structural instructions:\n")
            for (item in structural) {
                val m = item.map
                sb.append("    - ").append((m["role"] as PyStr).s).append(": ")
                sb.append((m["address"] as PyStr).s)
                sb.append(" off=").append((m["file_offset"] as PyStr).s)
                sb.append(" HxD=").append((m["hxd_offset"] as PyStr).s)
                sb.append(" raw=").append((m["raw"] as PyStr).s)
                sb.append("  ").append((m["instruction"] as PyStr).s).append('\n')
            }
        }
        sb.append("  key instructions:\n")
        for (index in flow.startIndex..flow.endIndex) {
            val insn = instructions[index]
            val interesting = parseCallTarget(insn) != null ||
                isConditionalBranch(insn) ||
                insn.mnemonic in setOf("ldr", "add", "mov", "ret", "cmn", "cmp") ||
                flow.xrefs.any { it.insnIndex == index }
            if (interesting) {
                sb.append("    0x").append(uHex(insn.address, 16))
                sb.append(" off=0x").append(uHex(fileOffsetForVa(insn.address, base)))
                sb.append(" HxD=").append(hxdOffsetForVa(insn.address, base)).append(": ")
                sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
            }
        }
        sb.append('\n')
    }
    File(outputDir, "erase_permission_report.txt").writeUtf8(sb.toString())
    val checkDir = File(outputDir, "erase_permission_checks")
    checkDir.mkdirs()
    for ((number, check) in checks.withIndex()) {
        val n = number + 1
        File(checkDir, "erase_permission_%02d_details.json".format(n)).writeUtf8(
            pyJsonDump(erasePermissionToDict(check, base, instructions))
        )
        val path = File(
            checkDir,
            "erase_permission_%02d_conf_%d_0x%s.asm".format(n, check.confidence, uHex(check.flow.startAddress, 16))
        )
        writeFlowAsm(path, instructions, check.flow)
    }
}

fun writePartitionOperationAsm(
    path: File,
    core: LkAnalyzerCore,
    operation: LkPartitionEraseOperation,
    base: Long,
    context: Int = 24,
) {
    val instructions = core.instructions
    val callIndex = core.findInstructionIndexByAddress(operation.callSite)
    val xrefIndex = core.findInstructionIndexByAddress(operation.xrefAddress)
    val centerIndex = callIndex ?: xrefIndex
    val sb = StringBuilder()
    sb.append("; partition: ").append(operation.partition).append('\n')
    sb.append("; operation: ").append(operation.operation).append('\n')
    sb.append("; flow start: 0x").append(uHex(operation.flowStart, 16))
    sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.flowStart, base)))
    sb.append(" HxD=").append(hxdOffsetForVa(operation.flowStart, base)).append('\n')
    sb.append("; string VA: 0x").append(uHex(operation.stringAddress, 16))
    sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.stringAddress, base)))
    sb.append(" HxD=").append(hxdOffsetForVa(operation.stringAddress, base)).append('\n')
    sb.append("; string text: ").append(pyRepr(operation.stringText)).append('\n')
    sb.append("; reference mode: ").append(operation.referenceMode).append('\n')
    sb.append("; xref: 0x").append(uHex(operation.xrefAddress, 16))
    sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.xrefAddress, base)))
    sb.append(" HxD=").append(hxdOffsetForVa(operation.xrefAddress, base)).append('\n')
    sb.append("; xref instruction: ").append(operation.xrefMnemonic).append(' ').append(operation.xrefOperands).append('\n')
    sb.append("; xref via: ").append(operation.xrefVia).append('\n')
    if (operation.callSite != null) {
        sb.append("; call site: 0x").append(uHex(operation.callSite, 16))
        sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.callSite, base)))
        sb.append(" HxD=").append(hxdOffsetForVa(operation.callSite, base)).append('\n')
    }
    if (operation.callTarget != null) {
        sb.append("; call target: 0x").append(uHex(operation.callTarget, 16))
        sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.callTarget, base)))
        sb.append(" HxD=").append(hxdOffsetForVa(operation.callTarget, base)).append('\n')
    }
    if (operation.resultCheckAddress != null) {
        sb.append("; result check: 0x").append(uHex(operation.resultCheckAddress, 16))
        sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.resultCheckAddress, base)))
        sb.append(" HxD=").append(hxdOffsetForVa(operation.resultCheckAddress, base)).append('\n')
    }
    if (operation.failureTarget != null) {
        sb.append("; failure target: 0x").append(uHex(operation.failureTarget, 16))
        sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.failureTarget, base)))
        sb.append(" HxD=").append(hxdOffsetForVa(operation.failureTarget, base)).append('\n')
    }
    if (operation.failureMessage != null) {
        sb.append("; failure message: 0x").append(uHex(operation.failureMessageAddress!!, 16))
        sb.append(" ").append(pyRepr(operation.failureMessage)).append('\n')
    }
    sb.append('\n')
    if (centerIndex == null) {
        sb.append("; No instruction context found for this operation.\n")
        path.writeUtf8(sb.toString())
        return
    }
    val start = maxOf(0, centerIndex - context)
    val end = minOf(instructions.size, centerIndex + context + 1)
    for (index in start until end) {
        val insn = instructions[index]
        val markers = mutableListOf<String>()
        if (insn.address == operation.xrefAddress) markers.add("XREF")
        if (insn.address == operation.callSite) markers.add("CALL")
        if (insn.address == operation.resultCheckAddress) markers.add("CHECK")
        if (insn.address == operation.failureTarget) markers.add("FAIL")
        val prefix = if (markers.isNotEmpty()) ">>" else "  "
        val markerText = if (markers.isNotEmpty()) " ; " + markers.joinToString(",") else ""
        sb.append(prefix).append(" 0x").append(uHex(insn.address, 16)).append(": ")
        sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append(markerText).append('\n')
    }
    path.writeUtf8(sb.toString())
}

fun writePartitionEraseReports(
    outputDir: File,
    core: LkAnalyzerCore,
    operations: List<LkPartitionEraseOperation>,
    base: Long,
) {
    val instructions = core.instructions
    File(outputDir, "partition_erase_operations.json").writeUtf8(
        pyJsonDump(pyArrOf(operations.map { partitionEraseToDict(it, base) }))
    )
    val inventory = partitionStringInventory(operations, base)
    val inventoryJson = pyObjOf(
        inventory.entries.associate { (k, v) -> k to pyArrOf(v) }
    )
    File(outputDir, "partition_string_locations.json").writeUtf8(pyJsonDump(inventoryJson))
    val eraseDir = File(outputDir, "partition_erases")
    eraseDir.mkdirs()
    val indexJson = pyObjOf(
        "strings" to inventoryJson,
        "operations" to pyArrOf(operations.map { partitionEraseToDict(it, base) }),
    )
    File(eraseDir, "index.json").writeUtf8(pyJsonDump(indexJson))
    val sb = StringBuilder()
    sb.append("Unlock-flow partition string locations\n")
    sb.append("=".repeat(38)).append('\n')
    for (partition in LK_TARGET_ERASE_PARTITIONS.sorted()) {
        val entries = inventory[partition] ?: emptyList()
        val directEntries = entries.filter { (it.map["reference_mode"] as PyStr).s == "direct_string" }
        val embeddedEntries = entries.filter { (it.map["reference_mode"] as PyStr).s == "embedded_string" }
        sb.append("\nPartition: ").append(partition).append('\n')
        if (entries.isEmpty()) {
            sb.append("  found: no\n")
        } else {
            sb.append("  found: yes (").append(directEntries.size)
            sb.append(" direct, ").append(embeddedEntries.size).append(" embedded)\n")
            for (entry in entries) {
                val m = entry.map
                sb.append("  - ").append((m["reference_mode"] as PyStr).s)
                sb.append(" VA=").append((m["address"] as PyStr).s)
                sb.append(" off=").append((m["file_offset"] as PyStr).s)
                sb.append(" HxD=").append((m["hxd_offset"] as PyStr).s)
                sb.append(" text=").append(pyRepr((m["text"] as PyStr).s)).append('\n')
            }
        }
    }
    sb.append("\nPartition erase operations\n")
    sb.append("=".repeat(26)).append('\n')
    if (operations.isEmpty()) {
        sb.append("No partition erase operations found in unlock flow.\n")
    } else {
        val byFlow = linkedMapOf<Long, MutableList<LkPartitionEraseOperation>>()
        for (operation in operations) {
            byFlow.getOrPut(operation.flowStart) { mutableListOf() }.add(operation)
        }
        for ((flowStart, flowOperations) in byFlow) {
            sb.append("Flow 0x").append(uHex(flowStart, 16)).append('\n')
            sb.append("=".repeat(34)).append('\n')
            for (partition in LK_TARGET_ERASE_PARTITIONS.sorted()) {
                val partOps = flowOperations.filter { it.partition == partition }
                if (partOps.isEmpty()) continue
                sb.append("\nPartition: ").append(partition).append('\n')
                for (operation in partOps) {
                    sb.append("  - ").append(operation.operation.uppercase())
                    sb.append(" xref=0x").append(uHex(operation.xrefAddress, 16))
                    sb.append(" off=0x").append(uHex(fileOffsetForVa(operation.xrefAddress, base)))
                    sb.append(" HxD=").append(hxdOffsetForVa(operation.xrefAddress, base))
                    sb.append(" string=0x").append(uHex(operation.stringAddress, 16))
                    sb.append(" string_off=0x").append(uHex(fileOffsetForVa(operation.stringAddress, base)))
                    sb.append(" string_HxD=").append(hxdOffsetForVa(operation.stringAddress, base))
                    sb.append(" mode=").append(operation.referenceMode).append('\n')
                    sb.append("    string text: ").append(pyRepr(operation.stringText)).append('\n')
                    if (operation.callSite != null) {
                        sb.append("    call: 0x").append(uHex(operation.callSite, 16))
                        sb.append(" -> 0x").append(uHex(operation.callTarget!!, 16)).append('\n')
                    }
                    if (operation.resultCheckAddress != null) {
                        sb.append("    result check: 0x").append(uHex(operation.resultCheckAddress, 16)).append('\n')
                    }
                    if (operation.failureTarget != null) {
                        sb.append("    failure branch: 0x").append(uHex(operation.failureTarget, 16)).append('\n')
                    }
                    if (operation.failureMessage != null) {
                        sb.append("    failure message: 0x").append(uHex(operation.failureMessageAddress!!, 16))
                        sb.append(" ").append(pyRepr(operation.failureMessage)).append('\n')
                    }
                    if (operation.callSite != null) {
                        val callIndex = core.findInstructionIndexByAddress(operation.callSite)
                        if (callIndex != null) {
                            sb.append("    context:\n")
                            for (index in maxOf(0, callIndex - 4) until minOf(instructions.size, callIndex + 5)) {
                                val insn = instructions[index]
                                val marker = if (insn.address == operation.callSite) ">>" else "  "
                                sb.append("    ").append(marker).append(" 0x").append(uHex(insn.address, 16)).append(": ")
                                sb.append(insn.mnemonic.padEnd(8)).append(' ').append(insn.operands).append('\n')
                            }
                        }
                    }
                }
            }
        }
    }
    sb.append("\nReference modes\n")
    sb.append("-".repeat(15)).append('\n')
    sb.append("direct_string: the partition name is referenced as a direct C-string via ADRP/ADD.\n")
    sb.append("embedded_string: the target appears inside a longer diagnostic string.\n")
    sb.append("address_only: a target was matched by address but not as direct text.\n")
    File(outputDir, "partition_erase_report.txt").writeUtf8(sb.toString())
    val stringsTxt = StringBuilder()
    for (partition in LK_TARGET_ERASE_PARTITIONS.sorted()) {
        stringsTxt.append(partition).append('\n')
        stringsTxt.append("-".repeat(partition.length)).append('\n')
        val entries = inventory[partition] ?: emptyList()
        if (entries.isEmpty()) {
            stringsTxt.append("not found\n\n")
            continue
        }
        for (entry in entries) {
            val m = entry.map
            stringsTxt.append((m["reference_mode"] as PyStr).s)
            stringsTxt.append("  VA=").append((m["address"] as PyStr).s)
            stringsTxt.append("  off=").append((m["file_offset"] as PyStr).s)
            stringsTxt.append("  HxD=").append((m["hxd_offset"] as PyStr).s)
            stringsTxt.append("  text=").append(pyRepr((m["text"] as PyStr).s)).append('\n')
        }
        stringsTxt.append('\n')
    }
    File(eraseDir, "strings.txt").writeUtf8(stringsTxt.toString())
    for (partition in LK_TARGET_ERASE_PARTITIONS.sorted()) {
        val partitionDir = File(eraseDir, partition)
        partitionDir.mkdirs()
        val partitionOperations = operations.filter { it.partition == partition }
        File(partitionDir, "strings.json").writeUtf8(
            pyJsonDump(pyArrOf(inventory[partition] ?: emptyList()))
        )
        File(partitionDir, "operations.json").writeUtf8(
            pyJsonDump(pyArrOf(partitionOperations.map { partitionEraseToDict(it, base) }))
        )
        val readme = StringBuilder()
        val entries = inventory[partition] ?: emptyList()
        val directEntries = entries.filter { (it.map["reference_mode"] as PyStr).s == "direct_string" }
        val embeddedEntries = entries.filter { (it.map["reference_mode"] as PyStr).s == "embedded_string" }
        readme.append("Partition: ").append(partition).append('\n')
        readme.append("String found: ").append(if (entries.isNotEmpty()) "yes" else "no").append('\n')
        readme.append("Direct strings: ").append(directEntries.size).append('\n')
        readme.append("Embedded strings: ").append(embeddedEntries.size).append('\n')
        readme.append("Operations in analyzed flows: ").append(partitionOperations.size).append("\n\n")
        for (entry in entries) {
            val m = entry.map
            readme.append("- ").append((m["reference_mode"] as PyStr).s)
            readme.append(" VA=").append((m["address"] as PyStr).s)
            readme.append(" off=").append((m["file_offset"] as PyStr).s)
            readme.append(" HxD=").append((m["hxd_offset"] as PyStr).s)
            readme.append(" text=").append(pyRepr((m["text"] as PyStr).s)).append('\n')
        }
        if (partitionOperations.isNotEmpty()) {
            readme.append("\nOperations:\n")
            for (operation in partitionOperations) {
                val callSite = if (operation.callSite != null) "0x" + uHex(operation.callSite, 16) else "None"
                val callTarget = if (operation.callTarget != null) "0x" + uHex(operation.callTarget, 16) else "None"
                readme.append("- ").append(operation.operation)
                readme.append(" xref=0x").append(uHex(operation.xrefAddress, 16))
                readme.append(" call=").append(callSite)
                readme.append(" target=").append(callTarget)
                readme.append(" mode=").append(operation.referenceMode).append('\n')
            }
        }
        File(partitionDir, "README.txt").writeUtf8(readme.toString())
        for ((number, operation) in partitionOperations.withIndex()) {
            val n = number + 1
            val callSite = if (operation.callSite != null) "0x" + uHex(operation.callSite, 16) else "no_call"
            val path = File(
                partitionDir,
                "%02d_%s_0x%s_call_%s.asm".format(n, operation.operation, uHex(operation.xrefAddress, 16), callSite)
            )
            writePartitionOperationAsm(path, core, operation, base)
        }
    }
}

fun writeFlowOverviewMermaid(path: File, flows: List<LkFunctionFlow>, limit: Int = 8) {
    val sb = StringBuilder()
    sb.append("flowchart LR\n")
    if (flows.isEmpty()) {
        sb.append("  empty[\"No flow candidates\"]\n")
        path.writeUtf8(sb.toString())
        return
    }
    val targetIds = linkedMapOf<String, String>()
    for ((flowIndex, flow) in flows.take(limit).withIndex()) {
        val flowId = "F${flowIndex + 1}"
        sb.append("  ").append(flowId).append("[\"#").append(flowIndex + 1)
        sb.append(" score ").append(flow.score).append("<br/>")
        sb.append("0x").append(uHex(flow.startAddress, 16)).append("<br/>")
        sb.append("xrefs ").append(flow.xrefs.size).append(" calls ").append(flow.calls.size).append("\"]\n")
        for (target in flow.matchedTargets) {
            if (target !in targetIds) {
                val targetId = "T${targetIds.size + 1}"
                targetIds[target] = targetId
                sb.append("  ").append(targetId).append("[\"").append(shortLabel(target, 48)).append("\"]\n")
            }
            sb.append("  ").append(flowId).append(" --> ").append(targetIds[target]).append('\n')
        }
    }
    path.writeUtf8(sb.toString())
}

fun buildBasicBlocks(
    instructions: List<LkInstruction>,
    flow: LkFunctionFlow,
): Pair<List<Pair<Int, Int>>, Map<Int, List<Triple<Int, String, Long>>>> {
    val addressToIndex = mutableMapOf<Long, Int>()
    for (index in flow.startIndex..flow.endIndex) {
        addressToIndex[instructions[index].address] = index
    }
    val starts = mutableSetOf(flow.startIndex)
    for (index in flow.startIndex..flow.endIndex) {
        val insn = instructions[index]
        if (!isBranchInstruction(insn)) continue
        val target = parseBranchTarget(insn.operands)
        if (target != null && target in addressToIndex) {
            starts.add(addressToIndex[target]!!)
        }
        if (isConditionalBranch(insn) && index + 1 <= flow.endIndex) {
            starts.add(index + 1)
        } else if (insn.mnemonic == "b" && index + 1 <= flow.endIndex) {
            starts.add(index + 1)
        }
    }
    val sortedStarts = starts.sorted()
    val blocks = mutableListOf<Pair<Int, Int>>()
    for ((position, start) in sortedStarts.withIndex()) {
        val end = if (position + 1 < sortedStarts.size) {
            minOf(sortedStarts[position + 1] - 1, flow.endIndex)
        } else {
            flow.endIndex
        }
        if (start <= end) blocks.add(start to end)
    }
    val blockForIndex = mutableMapOf<Int, Int>()
    for ((blockId, block) in blocks.withIndex()) {
        for (index in block.first..block.second) blockForIndex[index] = blockId
    }
    val edges = mutableMapOf<Int, MutableList<Triple<Int, String, Long>>>()
    for (idx in blocks.indices) edges[idx] = mutableListOf()
    for ((blockId, block) in blocks.withIndex()) {
        val terminal = instructions[block.second]
        if (isBranchInstruction(terminal)) {
            val target = parseBranchTarget(terminal.operands)
            if (target != null && target in addressToIndex) {
                val targetBlock = blockForIndex[addressToIndex[target]!!]!!
                edges[blockId]!!.add(Triple(targetBlock, terminal.mnemonic, terminal.address))
            }
            if (isConditionalBranch(terminal) && blockId + 1 < blocks.size) {
                edges[blockId]!!.add(Triple(blockId + 1, "fallthrough", terminal.address))
            }
        } else if (terminal.mnemonic != "ret" && blockId + 1 < blocks.size) {
            edges[blockId]!!.add(Triple(blockId + 1, "next", terminal.address))
        }
    }
    return blocks to edges
}

fun writeBestFlowCfg(
    outputDir: File,
    instructions: List<LkInstruction>,
    flow: LkFunctionFlow?,
) {
    val mermaidPath = File(outputDir, "best_flow_cfg.mmd")
    val dotPath = File(outputDir, "best_flow_cfg.dot")
    if (flow == null) {
        mermaidPath.writeUtf8("flowchart TD\n  empty[\"No best flow\"]\n")
        dotPath.writeUtf8("digraph best_flow { empty [label=\"No best flow\"]; }\n")
        return
    }
    val (blocks, edges) = buildBasicBlocks(instructions, flow)
    val xrefsByBlock = mutableMapOf<Int, MutableList<String>>()
    for ((blockId, block) in blocks.withIndex()) {
        for (xref in flow.xrefs) {
            if (xref.insnIndex in block.first..block.second) {
                xrefsByBlock.getOrPut(blockId) { mutableListOf() }.add(xref.target.target)
            }
        }
    }
    val mmd = StringBuilder()
    mmd.append("flowchart TD\n")
    for ((blockId, block) in blocks.withIndex()) {
        val startAddr = instructions[block.first].address
        val endAddr = instructions[block.second].address
        val labels = xrefsByBlock[blockId]?.toSortedSet() ?: emptySet()
        val extra = if (labels.isNotEmpty()) {
            "<br/>" + labels.joinToString("<br/>") { shortLabel(it, 42) }
        } else ""
        mmd.append("  B").append(blockId).append("[\"0x").append(uHex(startAddr, 16))
        mmd.append("<br/>- 0x").append(uHex(endAddr, 16)).append(extra).append("\"]\n")
    }
    for ((source, blockEdges) in edges) {
        for ((target, label, _) in blockEdges) {
            mmd.append("  B").append(source).append(" -->|\"").append(label).append("\"| B").append(target).append('\n')
        }
    }
    mermaidPath.writeUtf8(mmd.toString())
    val dot = StringBuilder()
    dot.append("digraph best_flow {\n")
    dot.append("  rankdir=TB;\n")
    for ((blockId, block) in blocks.withIndex()) {
        val startAddr = instructions[block.first].address
        val endAddr = instructions[block.second].address
        val labels = xrefsByBlock[blockId]?.toSortedSet() ?: emptySet()
        val extra = if (labels.isNotEmpty()) {
            "\\n" + labels.joinToString("\\n") { shortLabel(it, 42) }
        } else ""
        dot.append("  B").append(blockId).append(" [label=\"0x").append(uHex(startAddr, 16))
        dot.append("\\n- 0x").append(uHex(endAddr, 16)).append(extra).append("\"];\n")
    }
    for ((source, blockEdges) in edges) {
        for ((target, label, address) in blockEdges) {
            dot.append("  B").append(source).append(" -> B").append(target)
            dot.append(" [label=\"").append(label).append(" @ 0x").append(uHex(address, 16)).append("\"];\n")
        }
    }
    dot.append("}\n")
    dotPath.writeUtf8(dot.toString())
}

fun writeFlowDiagramHtml(outputDir: File) {
    val overview = File(outputDir, "flow_overview.mmd").readText(Charsets.UTF_8)
    val cfg = File(outputDir, "best_flow_cfg.mmd").readText(Charsets.UTF_8)
    val frp = File(outputDir, "frp_unlock_relation.mmd").readText(Charsets.UTF_8)
    val key = File(outputDir, "key_validation_relation.mmd").readText(Charsets.UTF_8)
    val erasePermission = File(outputDir, "erase_permission_relation.mmd").readText(Charsets.UTF_8)
    val erase = File(outputDir, "partition_erase_relation.mmd").readText(Charsets.UTF_8)
    val html = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>LK flow analysis</title>
  <style>
    body { font-family: Arial, sans-serif; margin: 24px; color: #111; }
    h1, h2 { font-weight: 600; }
    .diagram { margin: 20px 0 40px; overflow-x: auto; border: 1px solid #ddd; padding: 16px; }
    pre { white-space: pre-wrap; }
  </style>
  <script type="module">
    import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
    mermaid.initialize({ startOnLoad: true, securityLevel: 'loose' });
  </script>
</head>
<body>
  <h1>LK flow analysis</h1>
  <h2>Flow overview</h2>
  <div class="diagram"><pre class="mermaid">${overview}</pre></div>
  <h2>Best flow CFG</h2>
  <div class="diagram"><pre class="mermaid">${cfg}</pre></div>
  <h2>FRP unlock relation</h2>
  <div class="diagram"><pre class="mermaid">${frp}</pre></div>
  <h2>KEY validation relation</h2>
  <div class="diagram"><pre class="mermaid">${key}</pre></div>
  <h2>Erase permission relation</h2>
  <div class="diagram"><pre class="mermaid">${erasePermission}</pre></div>
  <h2>Partition erase relation</h2>
  <div class="diagram"><pre class="mermaid">${erase}</pre></div>
</body>
</html>
"""
    File(outputDir, "flow_diagram.html").writeUtf8(html)
}

fun writeFrpUnlockRelationMermaid(
    path: File,
    flows: List<LkFunctionFlow>,
    frpCheckers: List<LkFrpChecker>,
) {
    val sb = StringBuilder()
    sb.append("flowchart TD\n")
    if (frpCheckers.isEmpty()) {
        sb.append("  empty[\"No FRP checker found\"]\n")
        path.writeUtf8(sb.toString())
        return
    }
    val checker = frpCheckers[0]
    val callerStart = if (checker.callers.isNotEmpty()) checker.callers[0].first
    else if (flows.isNotEmpty()) flows[0].startAddress else 0L
    val callSite = if (checker.callers.isNotEmpty()) checker.callers[0].second else 0L
    sb.append("  U[\"Unlock flow<br/>0x").append(uHex(callerStart, 16)).append("\"]\n")
    if (callSite != 0L) {
        sb.append("  C[\"BL call site<br/>0x").append(uHex(callSite, 16)).append("\"]\n")
        sb.append("  U --> C\n")
        sb.append("  C --> F\n")
    } else {
        sb.append("  U --> F\n")
    }
    sb.append("  F[\"FRP checker<br/>0x").append(uHex(checker.flow.startAddress, 16))
    sb.append("<br/>confidence ").append(checker.confidence).append("\"]\n")
    sb.append("  P[\"Locate frp partition\"]\n")
    sb.append("  R[\"Read last 0x100 bytes\"]\n")
    sb.append("  B[\"Load [sp,#0xff]\"]\n")
    sb.append("  Z[\"byte == 0\"]\n")
    sb.append("  L[\"Return lock/apply rule\"]\n")
    sb.append("  A[\"Return allowed/no message\"]\n")
    sb.append("  F --> P --> R --> B --> Z\n")
    sb.append("  Z -->|\"yes\"| L\n")
    sb.append("  Z -->|\"no\"| A\n")
    path.writeUtf8(sb.toString())
}

fun writeKeyValidationRelationMermaid(
    path: File,
    flows: List<LkFunctionFlow>,
    validators: List<LkKeyValidator>,
) {
    val sb = StringBuilder()
    sb.append("flowchart TD\n")
    if (validators.isEmpty()) {
        sb.append("  empty[\"No KEY validator found\"]\n")
        path.writeUtf8(sb.toString())
        return
    }
    val validator = validators[0]
    val callerStart = if (validator.callers.isNotEmpty()) validator.callers[0].first
    else if (flows.isNotEmpty()) flows[0].startAddress else 0L
    val callSite = if (validator.callers.isNotEmpty()) validator.callers[0].second else 0L
    val failureTarget = if (validator.failureEdges.isNotEmpty()) validator.failureEdges[0].second else 0L
    val branch = if (validator.failureEdges.isNotEmpty()) validator.failureEdges[0].third else "branch"
    sb.append("  U[\"Unlock flow<br/>0x").append(uHex(callerStart, 16)).append("\"]\n")
    if (callSite != 0L) {
        sb.append("  C[\"KEY validator call<br/>0x").append(uHex(callSite, 16)).append("\"]\n")
        sb.append("  U --> C --> K\n")
    } else {
        sb.append("  U --> K\n")
    }
    sb.append("  K[\"KEY validator<br/>0x").append(uHex(validator.flow.startAddress, 16))
    sb.append("<br/>confidence ").append(validator.confidence).append("\"]\n")
    sb.append("  H[\"Hash key data\"]\n")
    sb.append("  B[\"Compare hash bytes\"]\n")
    sb.append("  R[\"Return w20 as w0\"]\n")
    sb.append("  T[\"Caller tests w0 bit 0\"]\n")
    sb.append("  OK[\"Continue unlock flow\"]\n")
    sb.append("  FAIL[\"Code validation failure\"]\n")
    sb.append("  K --> H --> B --> R --> T\n")
    sb.append("  T -->|\"valid\"| OK\n")
    if (failureTarget != 0L) {
        sb.append("  T -->|\"").append(branch).append(" -> 0x").append(uHex(failureTarget, 16)).append("\"| FAIL\n")
    } else {
        sb.append("  T -->|\"invalid\"| FAIL\n")
    }
    path.writeUtf8(sb.toString())
}

fun writePartitionEraseRelationMermaid(
    path: File,
    operations: List<LkPartitionEraseOperation>,
) {
    val sb = StringBuilder()
    sb.append("flowchart TD\n")
    val eraseOps = operations.filter { it.operation == "erase" }
    if (eraseOps.isEmpty()) {
        sb.append("  empty[\"No partition erase operations found\"]\n")
        path.writeUtf8(sb.toString())
        return
    }
    val byFlow = linkedMapOf<Long, MutableList<LkPartitionEraseOperation>>()
    for (operation in eraseOps) {
        byFlow.getOrPut(operation.flowStart) { mutableListOf() }.add(operation)
    }
    fun groupScore(group: List<LkPartitionEraseOperation>): Triple<Int, Int, Long> {
        val partitions = group.map { it.partition }.toSet()
        val firstXref = group.minOf { it.xrefAddress }
        return Triple(partitions.intersect(LK_TARGET_ERASE_PARTITIONS).size, group.size, -firstXref)
    }
    // Python max() returns the first maximal item on ties.
    var selectedFlow = 0L
    var selectedOps: List<LkPartitionEraseOperation> = emptyList()
    var bestScore: Triple<Int, Int, Long>? = null
    for ((flowStart, group) in byFlow) {
        val score = groupScore(group)
        val cur = bestScore
        if (cur == null ||
            score.first > cur.first ||
            (score.first == cur.first && score.second > cur.second) ||
            (score.first == cur.first && score.second == cur.second && score.third > cur.third)
        ) {
            bestScore = score
            selectedFlow = flowStart
            selectedOps = group
        }
    }
    sb.append("  U[\"Unlock flow<br/>0x").append(uHex(selectedFlow, 16)).append("\"]\n")
    for ((index, operation) in selectedOps.withIndex()) {
        val node = "E${index + 1}"
        sb.append("  ").append(node).append("[\"").append(operation.partition)
        sb.append("<br/>xref 0x").append(uHex(operation.xrefAddress, 16))
        sb.append("<br/>call 0x").append(uHex(operation.callSite!!, 16)).append("\"]\n")
        sb.append("  U --> ").append(node).append('\n')
        if (operation.failureMessage != null) {
            val fail = "F${index + 1}"
            sb.append("  ").append(fail).append("[\"failure<br/>")
            sb.append(shortLabel(operation.failureMessage, 48)).append("\"]\n")
            sb.append("  ").append(node).append(" -->|error| ").append(fail).append('\n')
        }
    }
    path.writeUtf8(sb.toString())
}

fun writeErasePermissionRelationMermaid(
    path: File,
    checks: List<LkErasePermissionCheck>,
) {
    val sb = StringBuilder()
    sb.append("flowchart TD\n")
    if (checks.isEmpty()) {
        sb.append("  empty[\"No erase permission check found\"]\n")
        path.writeUtf8(sb.toString())
        return
    }
    val check = checks[0]
    sb.append("  C[\"cmd_erase_permission_check<br/>0x").append(uHex(check.flow.startAddress, 16))
    sb.append("<br/>confidence ").append(check.confidence).append("\"]\n")
    sb.append("  A[\"Read fastboot argument<br/>[cmd + 0x10] + 1\"]\n")
    sb.append("  N[\"Fixed name checks\"]\n")
    for ((index, nc) in check.nameChecks.withIndex()) {
        val node = "N${index + 1}"
        sb.append("  ").append(node).append("[\"").append(shortLabel(nc.name, 32)).append("\"]\n")
        sb.append("  N --> ").append(node).append('\n')
    }
    sb.append("  G[\"Permission gate / partition table attribute\"]\n")
    sb.append("  OK[\"Proceed erase\"]\n")
    sb.append("  DENY[\"erase permission denied\"]\n")
    sb.append("  INV[\"Invalid partition name\"]\n")
    sb.append("  C --> A --> N --> G\n")
    sb.append("  G -->|\"allowed\"| OK\n")
    sb.append("  G -->|\"denied\"| DENY\n")
    sb.append("  G -->|\"not found\"| INV\n")
    path.writeUtf8(sb.toString())
}

fun writeSerialnoRuntimeReports(outputDir: File, serialnoRuntime: PyObj?) {
    File(outputDir, "serialno_runtime.json").writeUtf8(
        pyJsonDump(serialnoRuntime ?: pyObjOf())
    )
    val sb = StringBuilder()
    sb.append("Serialno runtime source analysis\n")
    sb.append("=".repeat(32)).append("\n\n")
    sb.append("Mode: analysis-only; no runtime patch is generated.\n\n")
    if (serialnoRuntime == null) {
        sb.append("No compatible serialno/barcode fallback chain was detected.\n")
        File(outputDir, "serialno_runtime.txt").writeUtf8(sb.toString())
        return
    }
    val m = serialnoRuntime.map
    fun str(key: String): String {
        val v = m[key] ?: return "None"
        return when (v) {
            is PyStr -> v.s
            is PyInt -> v.n.toString()
            is PyBool -> if (v.b) "True" else "False"
            is PyNull -> "None"
            else -> v.toString()
        }
    }
    sb.append("Confidence : ").append(str("confidence")).append('\n')
    sb.append("Function   : ").append(str("hw_get_serialno_string")).append('\n')
    sb.append("HxD        : ").append(str("hw_get_serialno_string_hxd_offset")).append('\n')
    sb.append("Generic    : ").append(str("generic_prop_get_string")).append('\n')
    sb.append("Generic HxD: ").append(str("generic_prop_get_string_hxd_offset")).append("\n\n")
    sb.append("String xrefs\n")
    sb.append("-".repeat(12)).append('\n')
    val xrefArr = (m["xrefs"] as? PyArr)?.items?.filterIsInstance<PyObj>() ?: emptyList()
    for (xref in xrefArr) {
        val xm = xref.map
        sb.append("  ").append((xm["target"] as PyStr).s)
        sb.append(": xref=").append((xm["xref_address"] as PyStr).s)
        sb.append(" HxD=").append((xm["xref_hxd_offset"] as PyStr).s)
        sb.append(" string=").append(pyRepr((xm["string"] as PyStr).s)).append('\n')
    }
    sb.append("\nCalls\n")
    sb.append("-".repeat(5)).append('\n')
    val callArr = (m["calls"] as? PyArr)?.items?.filterIsInstance<PyObj>() ?: emptyList()
    for (call in callArr) {
        val cm = call.map
        sb.append("  ").append((cm["site"] as PyStr).s)
        sb.append(" HxD=").append((cm["site_hxd_offset"] as PyStr).s)
        sb.append(" -> ").append((cm["target"] as PyStr).s)
        sb.append(" HxD=").append((cm["target_hxd_offset"] as PyStr).s).append('\n')
    }
    sb.append("\nCache candidates\n")
    sb.append("-".repeat(16)).append('\n')
    val candArr = (m["cache_candidates"] as? PyArr)?.items?.filterIsInstance<PyObj>() ?: emptyList()
    if (candArr.isEmpty()) {
        sb.append("  No read/write global cache candidate detected.\n")
    }
    for (candidate in candArr) {
        val cm = candidate.map
        val cond = (cm["has_conditional_after_read"] as PyBool).b
        sb.append("  function=").append((cm["function"] as PyStr).s)
        sb.append(" HxD=").append((cm["function_hxd_offset"] as PyStr).s)
        sb.append(" cache=").append((cm["cache_address"] as PyStr).s)
        sb.append(" conditional_after_read=").append(if (cond) "True" else "False").append('\n')
        val refArr = (cm["references"] as? PyArr)?.items?.filterIsInstance<PyObj>() ?: emptyList()
        for (ref in refArr) {
            val rm = ref.map
            sb.append("    ").append((rm["kind"] as PyStr).s).append(": ")
            sb.append((rm["address"] as PyStr).s)
            sb.append(" HxD=").append((rm["hxd_offset"] as PyStr).s)
            sb.append(" ").append((rm["instruction"] as PyStr).s).append('\n')
        }
    }
    File(outputDir, "serialno_runtime.txt").writeUtf8(sb.toString())
}

fun writeSummary(
    path: File,
    source: String,
    sourceKind: String,
    data: ByteArray,
    base: Long,
    architecture: String,
    instructions: List<LkInstruction>,
    strings: List<LkStringHit>,
    xrefs: List<LkXrefHit>,
    flows: List<LkFunctionFlow>,
    frpCheckers: List<LkFrpChecker>,
    keyValidators: List<LkKeyValidator>,
    erasePermissionChecks: List<LkErasePermissionCheck>,
    eraseOperations: List<LkPartitionEraseOperation>,
) {
    val sb = StringBuilder()
    sb.append("LK static analysis summary\n")
    sb.append("=".repeat(26)).append("\n\n")
    sb.append("Source       : ").append(source).append('\n')
    sb.append("Input type   : ").append(sourceKind).append('\n')
    sb.append("LK base      : 0x").append(uHex(base, 16)).append('\n')
    sb.append("LK size      : ").append(data.size).append(" bytes\n")
    sb.append("LK VA range  : 0x").append(uHex(base, 16))
    sb.append("-0x").append(uHex(base + data.size - 1, 16)).append('\n')
    sb.append("Architecture : ").append(architecture).append('\n')
    sb.append("Instructions : ").append(instructions.size).append('\n')
    sb.append("String hits  : ").append(strings.size).append('\n')
    sb.append("String xrefs : ").append(xrefs.size).append("\n\n")
    sb.append("Flow candidates: ").append(flows.size).append('\n')
    if (flows.isNotEmpty()) {
        sb.append("Best flow    : 0x").append(uHex(flows[0].startAddress, 16))
        sb.append("-0x").append(uHex(flows[0].endAddress, 16))
        sb.append(" (score ").append(flows[0].score).append(")\n")
    }
    sb.append("FRP checkers : ").append(frpCheckers.size).append('\n')
    if (frpCheckers.isNotEmpty()) {
        sb.append("Best FRP     : 0x").append(uHex(frpCheckers[0].flow.startAddress, 16))
        sb.append("-0x").append(uHex(frpCheckers[0].flow.endAddress, 16))
        sb.append(" (confidence ").append(frpCheckers[0].confidence).append(")\n")
    }
    sb.append("KEY validators: ").append(keyValidators.size).append('\n')
    if (keyValidators.isNotEmpty()) {
        sb.append("Best KEY     : 0x").append(uHex(keyValidators[0].flow.startAddress, 16))
        sb.append("-0x").append(uHex(keyValidators[0].flow.endAddress, 16))
        sb.append(" (confidence ").append(keyValidators[0].confidence).append(")\n")
    }
    sb.append("Erase permission checks: ").append(erasePermissionChecks.size).append('\n')
    if (erasePermissionChecks.isNotEmpty()) {
        sb.append("Best erase permission: 0x").append(uHex(erasePermissionChecks[0].flow.startAddress, 16))
        sb.append("-0x").append(uHex(erasePermissionChecks[0].flow.endAddress, 16))
        sb.append(" (confidence ").append(erasePermissionChecks[0].confidence).append(")\n")
    }
    val eraseCount = eraseOperations.count { it.operation == "erase" }
    sb.append("Partition erase ops: ").append(eraseCount).append("\n\n")
    sb.append("Address mapping\n")
    sb.append("-".repeat(15)).append('\n')
    sb.append("  file_offset = virtual_address - LK base\n")
    sb.append("  virtual_address = LK base + file_offset\n\n")
    sb.append("  HxD/File offset values are relative to the extracted lk.bin written in the output folder.\n")
    sb.append("  If you open that lk.bin in HxD, use the HxD offset directly with Go to / Ctrl+G.\n\n")
    sb.append("Interesting xrefs\n")
    sb.append("-".repeat(17)).append('\n')
    if (xrefs.isEmpty()) {
        sb.append("  No xrefs found with the current target strings.\n")
    }
    for (xref in xrefs) {
        sb.append("  0x").append(uHex(xref.address, 16))
        sb.append(" -> 0x").append(uHex(xref.effectiveAddress, 16))
        sb.append(" ").append(pyRepr(xref.target.target))
        sb.append(" [").append(xref.via).append("]\n")
    }
    sb.append("\nTop flow candidates\n")
    sb.append("-".repeat(19)).append('\n')
    if (flows.isEmpty()) {
        sb.append("  No flow candidates found.\n")
    }
    for ((index, flow) in flows.take(10).withIndex()) {
        sb.append("  #%02d score=%d 0x%s-0x%s xrefs=%d calls=%d\n".format(
            index + 1, flow.score, uHex(flow.startAddress, 16), uHex(flow.endAddress, 16),
            flow.xrefs.size, flow.calls.size
        ))
        sb.append("      targets: ").append(flow.matchedTargets.joinToString(", ")).append('\n')
    }
    sb.append("\nFRP checker candidates\n")
    sb.append("-".repeat(22)).append('\n')
    if (frpCheckers.isEmpty()) {
        sb.append("  No FRP checker candidates found.\n")
    }
    for ((index, checker) in frpCheckers.take(5).withIndex()) {
        sb.append("  #%02d confidence=%d 0x%s-0x%s\n".format(
            index + 1, checker.confidence, uHex(checker.flow.startAddress, 16), uHex(checker.flow.endAddress, 16)
        ))
        sb.append("      evidence: ").append(checker.evidence.joinToString(", ")).append('\n')
        if (checker.callers.isNotEmpty()) {
            sb.append("      callers: ").append(
                checker.callers.joinToString(", ") { (caller, site) ->
                    "0x" + uHex(caller, 16) + "@0x" + uHex(site, 16)
                }
            ).append('\n')
        }
    }
    sb.append("\nKEY validator candidates\n")
    sb.append("-".repeat(24)).append('\n')
    if (keyValidators.isEmpty()) {
        sb.append("  No KEY validator candidates found.\n")
    }
    for ((index, validator) in keyValidators.take(5).withIndex()) {
        sb.append("  #%02d confidence=%d 0x%s-0x%s\n".format(
            index + 1, validator.confidence, uHex(validator.flow.startAddress, 16), uHex(validator.flow.endAddress, 16)
        ))
        sb.append("      evidence: ").append(validator.evidence.joinToString(", ")).append('\n')
        if (validator.callers.isNotEmpty()) {
            sb.append("      callers: ").append(
                validator.callers.joinToString(", ") { (caller, site) ->
                    "0x" + uHex(caller, 16) + "@0x" + uHex(site, 16)
                }
            ).append('\n')
        }
    }
    sb.append("\nErase permission candidates\n")
    sb.append("-".repeat(27)).append('\n')
    if (erasePermissionChecks.isEmpty()) {
        sb.append("  No erase permission check candidates found.\n")
    }
    for ((index, check) in erasePermissionChecks.take(5).withIndex()) {
        sb.append("  #%02d confidence=%d 0x%s-0x%s\n".format(
            index + 1, check.confidence, uHex(check.flow.startAddress, 16), uHex(check.flow.endAddress, 16)
        ))
        sb.append("      evidence: ").append(check.evidence.joinToString(", ")).append('\n')
        if (check.nameChecks.isNotEmpty()) {
            sb.append("      fixed names: ").append(
                check.nameChecks.joinToString(", ") { nc ->
                    nc.name + "@0x" + uHex(nc.xrefAddress, 16)
                }
            ).append('\n')
        }
    }
    sb.append("\nPartition erase operations\n")
    sb.append("-".repeat(26)).append('\n')
    val eraseOps = eraseOperations.filter { it.operation == "erase" }
    if (eraseOps.isEmpty()) {
        sb.append("  No partition erase operations found.\n")
    }
    for (operation in eraseOps.take(20)) {
        sb.append("  ").append(operation.partition).append(": xref=0x").append(uHex(operation.xrefAddress, 16))
        sb.append(" call=0x").append(uHex(operation.callSite!!, 16))
        sb.append("->0x").append(uHex(operation.callTarget!!, 16))
        sb.append(" mode=").append(operation.referenceMode).append('\n')
        if (operation.failureMessage != null) {
            sb.append("      failure: ").append(pyRepr(operation.failureMessage)).append('\n')
        }
        if (operation.failureTarget != null) {
            sb.append("      failure target: 0x").append(uHex(operation.failureTarget, 16)).append('\n')
        }
    }
    path.writeUtf8(sb.toString())
}

/** Result counts for one analysis run, mirroring the Python CLI output. */
data class LkAnalysisResult(
    val base: Long,
    val architecture: String,
    val instructionCount: Int,
    val stringHitCount: Int,
    val xrefCount: Int,
    val flowCount: Int,
    val bestFlowStart: Long?,
    val bestFlowEnd: Long?,
    val bestFlowScore: Int?,
    val frpCheckerCount: Int,
    val keyValidatorCount: Int,
    val erasePermissionCheckCount: Int,
    val eraseOperationCount: Int,
    val serialnoRuntime: PyObj?,
)

/**
 * Static analyzer entry point — Kotlin port of lk_static_analyzer.py main().
 *
 * Input contract: in = [LibLk.LkImage], the parsed LK/LKS container
 * (liblk is the container layer of the Val flow; lk_auto_patch.py opens
 * the image with LkImage and works on the payload from that parsed
 * container). Out = analysis dir holding the extracted lk.bin plus the
 * JSON artifacts the Phase 1 patch engine reads, in the exact layout and
 * schema the Python analyzer writes. A raw lk.bin payload may be analyzed
 * directly with an explicit base (the Python --base path) via
 * [analyzePayload]. [analyzeFile] mirrors Python load_lk_payload: a
 * container file, or a raw payload when [rawBase] is given.
 *
 * CapstoneNative must be loaded before calling (CapstoneNative.
 * ensureLoaded on device); the JVM harness loads the host .so instead.
 */
object LkStaticAnalyzer {

    fun analyze(
        image: LibLk.LkImage,
        outputDir: File,
        source: String,
        targets: List<String> = LK_DEFAULT_TARGETS,
        context: Int = 12,
        noFullDisasm: Boolean = false,
    ): LkAnalysisResult {
        val (data, base, sourceKind) = loadLkPayload(image)
        return analyzePayload(data, base, outputDir, source, sourceKind, targets, context, noFullDisasm)
    }

    fun analyzeFile(
        imageFile: File,
        outputDir: File,
        rawBase: Long? = null,
        targets: List<String> = LK_DEFAULT_TARGETS,
        context: Int = 12,
        noFullDisasm: Boolean = false,
    ): LkAnalysisResult {
        val (data, base, sourceKind) = loadLkPayload(imageFile, rawBase)
        return analyzePayload(data, base, outputDir, imageFile.path, sourceKind, targets, context, noFullDisasm)
    }

    fun analyzePayload(
        data: ByteArray,
        base: Long,
        outputDir: File,
        source: String,
        sourceKind: String,
        targets: List<String> = LK_DEFAULT_TARGETS,
        context: Int = 12,
        noFullDisasm: Boolean = false,
    ): LkAnalysisResult {
        outputDir.mkdirs()
        File(outputDir, "lk.bin").writeBytes(data)
        val strings = findTargets(data, base, targets)
        val architecture = detectArchitecture(data, base)
        val instructions = disassemble(data, base, architecture)
        val core = LkAnalyzerCore(data, base, instructions)
        val xrefs = findStringXrefs(instructions, strings, data = data, base = base)
        val flows = buildFunctionFlows(instructions, xrefs)
        val frpCheckers = identifyFrpCheckers(core, flows)
        val keyValidators = identifyKeyValidators(core, flows)
        val erasePermissionChecks = identifyErasePermissionChecks(core, flows)
        val eraseOperations = detectPartitionEraseOperations(core, flows)
        val serialnoRuntime = detectSerialnoRuntimeSource(core, xrefs, base, data.size)

        writeSummary(
            File(outputDir, "summary.txt"), source, sourceKind, data, base,
            architecture, instructions, strings, xrefs, flows, frpCheckers,
            keyValidators, erasePermissionChecks, eraseOperations,
        )
        writeStrings(File(outputDir, "strings.txt"), data, strings)
        writeXrefs(File(outputDir, "xrefs.txt"), instructions, xrefs, context)
        writeCandidateFunctions(outputDir, instructions, xrefs)
        writeFlowReports(outputDir, instructions, flows)
        writeCheckAllowReport(File(outputDir, "check_allow_xrefs.txt"), instructions, flows)
        writeFrpCheckerReports(outputDir, instructions, frpCheckers, base)
        writeKeyValidatorReports(outputDir, instructions, keyValidators, base)
        writeErasePermissionReports(outputDir, instructions, erasePermissionChecks, base)
        writePartitionEraseReports(outputDir, core, eraseOperations, base)
        writeSerialnoRuntimeReports(outputDir, serialnoRuntime)
        writeFlowOverviewMermaid(File(outputDir, "flow_overview.mmd"), flows)
        writeBestFlowCfg(outputDir, instructions, flows.firstOrNull())
        writeFrpUnlockRelationMermaid(File(outputDir, "frp_unlock_relation.mmd"), flows, frpCheckers)
        writeKeyValidationRelationMermaid(File(outputDir, "key_validation_relation.mmd"), flows, keyValidators)
        writeErasePermissionRelationMermaid(
            File(outputDir, "erase_permission_relation.mmd"), erasePermissionChecks
        )
        writePartitionEraseRelationMermaid(File(outputDir, "partition_erase_relation.mmd"), eraseOperations)
        writeFlowDiagramHtml(outputDir)
        if (!noFullDisasm) {
            writeDisassembly(File(outputDir, "lk_full_disasm.asm"), instructions)
        }
        return LkAnalysisResult(
            base = base,
            architecture = architecture,
            instructionCount = instructions.size,
            stringHitCount = strings.size,
            xrefCount = xrefs.size,
            flowCount = flows.size,
            bestFlowStart = flows.firstOrNull()?.startAddress,
            bestFlowEnd = flows.firstOrNull()?.endAddress,
            bestFlowScore = flows.firstOrNull()?.score,
            frpCheckerCount = frpCheckers.size,
            keyValidatorCount = keyValidators.size,
            erasePermissionCheckCount = erasePermissionChecks.size,
            eraseOperationCount = eraseOperations.count { it.operation == "erase" },
            serialnoRuntime = serialnoRuntime,
        )
    }
}
