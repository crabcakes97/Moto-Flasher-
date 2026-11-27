// Candidate detectors for lk_static_analyzer.py — Kotlin port.
// FRP/OEM checkers, key validators, erase-permission checks, serialno
// runtime source, and partition erase operations.
//
// Upstream: Val Protocol (lk_static_analyzer.py). No AI branding anywhere
// in this code; real credits only.
package com.cronch.motoflasher.valproto

fun identifyFrpCheckers(
    core: LkAnalyzerCore,
    flows: List<LkFunctionFlow>,
): List<LkFrpChecker> {
    val instructions = core.instructions
    val checkers = mutableListOf<LkFrpChecker>()
    for (flow in flows) {
        val insns = flowInstructionSlice(instructions, flow)
        val targets = flow.matchedTargets.toSet()
        val evidence = mutableListOf<String>()
        var confidence = 0
        for ((target, weight) in LK_FRP_FLOW_TARGETS) {
            if (target in targets) {
                evidence.add("string target: $target")
                confidence += weight
            }
        }
        if (insns.any { "#0x100" in it.operands && it.mnemonic in setOf("sub", "subs.w", "mov", "mov.w") }) {
            evidence.add("uses 0x100 byte window")
            confidence += 5
        }
        if (insns.any { it.mnemonic in setOf("sub", "subs.w") && ", #0x100" in it.operands }) {
            evidence.add("subtracts 0x100 from partition/end pointer")
            confidence += 4
        }
        if (insns.any {
                (it.mnemonic == "mov" && it.operands == "w3, #0x100") ||
                    (it.mnemonic == "mov.w" && it.operands in setOf("r1, #0x100", "r2, #0x100"))
            }
        ) {
            evidence.add("passes read length 0x100 in w3")
            confidence += 4
        }
        if (insns.any { it.mnemonic == "ldrb" && "[sp, #0xff]" in it.operands }) {
            evidence.add("loads last byte from stack buffer [sp,#0xff]")
            confidence += 8
        }
        if (insns.any { it.mnemonic == "ldrb.w" && "[r6, #0xff]" in it.operands }) {
            evidence.add("loads last byte from stack buffer [r6,#0xff]")
            confidence += 8
        }
        outer@ for ((index, insn) in insns.withIndex()) {
            if (insn.mnemonic == "cmp" && insn.operands.endsWith(", #0")) {
                val nearby = insns.pySlice(index + 1, index + 4)
                if (nearby.any { it.mnemonic == "cset" && it.operands.endsWith(", eq") }) {
                    evidence.add("returns equality result with cmp #0 / cset eq")
                    confidence += 8
                    break@outer
                }
            }
            if (insn.mnemonic == "clz" && insn.operands == "r0, r0") {
                val previous = insns.pySlice(maxOf(0, index - 4), index)
                val nearby = insns.pySlice(index + 1, index + 4)
                if (previous.any { it.mnemonic == "ldrb.w" && "[r6, #0xff]" in it.operands } &&
                    nearby.any { it.mnemonic == "lsrs" && it.operands == "r0, r0, #5" }
                ) {
                    evidence.add("returns equality result with ldrb/clz/lsrs")
                    confidence += 8
                    break@outer
                }
            }
        }
        val callers = mutableListOf<Pair<Long, Long>>()
        for (caller in flows) {
            val callSites = flowInstructionSlice(instructions, caller)
                .filter { core.callTargetMatchesFunction(parseCallTarget(it), flow.startAddress) }
                .map { it.address }
            for (callSite in callSites) callers.add(caller.startAddress to callSite)
        }
        if (callers.isNotEmpty()) {
            evidence.add("called by another flow candidate")
            confidence += 5
        }
        if (callers.map { it.second }.toSet().size >= 2) {
            evidence.add("called multiple times from unlock-related flow")
            confidence += 5
        }
        val callerStarts = callers.map { it.first }.toSet()
        if (flows.any { it.startAddress in callerStarts && flowHasXrefTarget(it, "Check 'Allow OEM Unlock'") }) {
            evidence.add("caller contains Check 'Allow OEM Unlock' gate")
            confidence += 6
        }
        val hasFrpTarget = targets.intersect(LK_FRP_FLOW_TARGETS.keys).isNotEmpty()
        val hasWindowEvidence = evidence.any { "0x100" in it }
        val hasBooleanEvidence = evidence.any { "returns equality result" in it }
        if (!hasFrpTarget && !(hasWindowEvidence && hasBooleanEvidence)) continue
        if (confidence < 18) continue
        checkers.add(
            LkFrpChecker(
                flow = flow,
                confidence = confidence,
                evidence = evidence.distinct(),
                callers = callers.distinct(),
            )
        )
    }
    return checkers.sortedWith(
        compareByDescending<LkFrpChecker> { it.confidence }.thenBy { it.flow.startAddress }
    )
}

fun frpStructuralInstructions(
    instructions: List<LkInstruction>,
    checker: LkFrpChecker,
    base: Long,
): List<PyObj> {
    val flow = checker.flow
    val structural = mutableListOf<PyObj>()
    val seen = mutableSetOf<Pair<String, Long>>()
    fun add(role: String, insn: LkInstruction) {
        val key = role to insn.address
        if (key in seen) return
        seen.add(key)
        structural.add(instructionToDict(insn, base, role))
    }
    for (index in flow.startIndex..flow.endIndex) {
        val insn = instructions[index]
        if (insn.mnemonic == "sub" && ", #0x100" in insn.operands) {
            add("frp_window_start_sub_0x100", insn)
        }
        if (insn.mnemonic == "mov" && insn.operands == "w3, #0x100") {
            add("frp_read_length_0x100", insn)
        }
        if (insn.mnemonic == "ldrb" && "[sp, #0xff]" in insn.operands) {
            add("last_byte_load_sp_0xff", insn)
        }
        if (insn.mnemonic == "ldrb.w" && "[r6, #0xff]" in insn.operands) {
            add("last_byte_load_sp_0xff", insn)
        }
        if (insn.mnemonic == "cmp" && insn.operands.endsWith(", #0")) {
            val nearby = instructions.pySlice(index + 1, minOf(flow.endIndex + 1, index + 4))
            if (nearby.any { it.mnemonic == "cset" && it.operands.endsWith(", eq") }) {
                add("last_byte_compare_zero", insn)
            }
        }
        if (insn.mnemonic == "clz" &&
            insn.operands == "r0, r0" &&
            index > flow.startIndex &&
            instructions.pySlice(maxOf(flow.startIndex, index - 4), index)
                .any { it.mnemonic == "ldrb.w" && "[r6, #0xff]" in it.operands } &&
            instructions.pySlice(index + 1, minOf(flow.endIndex + 1, index + 4))
                .any { it.mnemonic == "lsrs" && it.operands == "r0, r0, #5" }
        ) {
            add("last_byte_compare_zero", insn)
        }
        if (insn.mnemonic == "cset" && insn.operands.endsWith(", eq")) {
            val previous = instructions.pySlice(maxOf(flow.startIndex, index - 3), index)
            if (previous.any { it.mnemonic == "cmp" && it.operands.endsWith(", #0") }) {
                add("return_allow_oem_unlock_state", insn)
            }
        }
    }
    return structural
}

fun frpCheckerToDict(
    checker: LkFrpChecker,
    base: Long,
    instructions: List<LkInstruction>,
): PyObj {
    // Key order mirrors Python dict.update: "callers" keeps its original
    // position; the file-offset keys are appended after it.
    val map = linkedMapOf<String, PyJson>()
    map["function_start"] = PyStr(hex16(checker.flow.startAddress))
    map["function_end"] = PyStr(hex16(checker.flow.endAddress))
    map["confidence"] = PyInt(checker.confidence.toLong())
    map["evidence"] = pyArrOf(checker.evidence.map { PyStr(it) })
    map["matched_targets"] = pyArrOf(checker.flow.matchedTargets.map { PyStr(it) })
    map["callers"] = pyArrOf(checker.callers.map { (caller, site) ->
        pyObjOf(
            "caller_start" to PyStr(hex16(caller)),
            "call_site" to PyStr(hex16(site)),
        )
    })
    map["function_start_file_offset"] = PyStr(hexOff(fileOffsetForVa(checker.flow.startAddress, base)))
    map["function_start_hxd_offset"] = PyStr(hxdOffsetForVa(checker.flow.startAddress, base))
    map["function_end_file_offset"] = PyStr(hexOff(fileOffsetForVa(checker.flow.endAddress, base)))
    map["function_end_hxd_offset"] = PyStr(hxdOffsetForVa(checker.flow.endAddress, base))
    map["callers"] = pyArrOf(checker.callers.map { (caller, site) ->
        pyObjOf(
            "caller_start" to PyStr(hex16(caller)),
            "caller_start_file_offset" to PyStr(hexOff(fileOffsetForVa(caller, base))),
            "caller_start_hxd_offset" to PyStr(hxdOffsetForVa(caller, base)),
            "call_site" to PyStr(hex16(site)),
            "call_site_file_offset" to PyStr(hexOff(fileOffsetForVa(site, base))),
            "call_site_hxd_offset" to PyStr(hxdOffsetForVa(site, base)),
        )
    })
    map["structural_instructions"] = pyArrOf(
        frpStructuralInstructions(instructions, checker, base)
    )
    map["xrefs"] = pyArrOf(checker.flow.xrefs.map { xrefToDict(it, base) })
    return PyObj(map)
}

fun findCodeValidationFailureEdges(
    core: LkAnalyzerCore,
    flows: List<LkFunctionFlow>,
    validatorStart: Long,
): List<Triple<Long, Long, String>> {
    val instructions = core.instructions
    val edges = mutableListOf<Triple<Long, Long, String>>()
    for (caller in flows) {
        if (!flowHasXrefTarget(caller, "Code validation failure")) continue
        val insns = flowInstructionSlice(instructions, caller)
        for ((localIndex, insn) in insns.withIndex()) {
            if (!core.callTargetMatchesFunction(parseCallTarget(insn), validatorStart)) continue
            for (nextInsn in insns.pySlice(localIndex + 1, localIndex + 7)) {
                if (nextInsn.mnemonic == "bl") break
                if (nextInsn.mnemonic !in setOf("tbz", "tbnz", "cbz", "cbnz", "b.eq", "b.ne")) continue
                val branchTarget = parseBranchTarget(nextInsn.operands) ?: continue
                val failureXrefs = caller.xrefs.filter { it.target.target == "Code validation failure" }
                if (failureXrefs.any { kotlin.math.abs(branchTarget - it.address) <= 0x40 }) {
                    edges.add(Triple(insn.address, branchTarget, nextInsn.mnemonic))
                }
            }
        }
    }
    return edges.distinct()
}

fun identifyKeyValidators(
    core: LkAnalyzerCore,
    flows: List<LkFunctionFlow>,
): List<LkKeyValidator> {
    val instructions = core.instructions
    val validators = mutableListOf<LkKeyValidator>()
    val cmpRegReg = Regex("""w\d+, w\d+""")
    for (flow in flows) {
        val insns = flowInstructionSlice(instructions, flow)
        val targets = flow.matchedTargets.toSet()
        val evidence = mutableListOf<String>()
        var confidence = 0
        for ((target, weight) in LK_KEY_VALIDATOR_TARGETS) {
            if (target in targets) {
                evidence.add("string target: $target")
                confidence += weight
            }
        }
        if (targets.intersect(LK_KEY_VALIDATOR_TARGETS.keys).isEmpty()) continue
        if (insns.any { it.mnemonic == "mov" && it.operands == "w0, w20" }) {
            evidence.add("returns accumulated result with mov w0, w20")
            confidence += 10
        }
        for (index in 0 until insns.size - 1) {
            val insn = insns[index]
            if (insn.mnemonic == "mov" && insn.operands == "w0, w20" && insns[index + 1].mnemonic == "ldp") {
                val nearby = insns.pySlice(index + 1, index + 8)
                if (nearby.any { it.mnemonic == "ret" }) {
                    evidence.add("function epilogue returns w20 before ret")
                    confidence += 8
                    break
                }
            }
        }
        if (insns.any { it.mnemonic == "mov" && it.operands == "w20, wzr" }) {
            evidence.add("initializes/falls back result to 0 in w20")
            confidence += 5
        }
        if (insns.any { it.mnemonic == "mov" && it.operands == "w20, #1" }) {
            evidence.add("sets result to 1 after successful comparisons")
            confidence += 6
        }
        if (insns.any { it.mnemonic == "cmp" && cmpRegReg.containsMatchIn(it.operands) }) {
            evidence.add("contains register-to-register comparison")
            confidence += 3
        }
        if (insns.any { it.mnemonic in setOf("ldrb", "ldur") && "w" in it.operands }) {
            evidence.add("loads byte/word data for comparison")
            confidence += 3
        }
        if (insns.any { it.mnemonic == "cmp" && "#0xf" in it.operands }) {
            evidence.add("checks Motorola helper success value 0xf")
            confidence += 4
        }
        if (insns.any { it.mnemonic == "bl" } && insns.any { "#0x20" in it.operands }) {
            evidence.add("uses hash-sized 0x20 byte buffer")
            confidence += 4
        }
        if (insns.count { parseCallTarget(it) != null } >= 5) {
            evidence.add("contains multi-stage decode/hash call chain")
            confidence += 4
        }
        val callers = core.callSitesForTarget(flows, flow.startAddress)
        if (callers.isNotEmpty()) {
            evidence.add("called by another flow candidate")
            confidence += 5
        }
        val failureEdges = findCodeValidationFailureEdges(core, flows, flow.startAddress)
        if (failureEdges.isNotEmpty()) {
            evidence.add("caller branches validator failure toward Code validation failure")
            confidence += 12
        }
        if (confidence < 22) continue
        validators.add(
            LkKeyValidator(
                flow = flow,
                confidence = confidence,
                evidence = evidence.distinct(),
                callers = callers,
                failureEdges = failureEdges,
            )
        )
    }
    return validators.sortedWith(
        compareByDescending<LkKeyValidator> { it.confidence }.thenBy { it.flow.startAddress }
    )
}

fun keyValidatorStructuralInstructions(
    instructions: List<LkInstruction>,
    validator: LkKeyValidator,
    base: Long,
): List<PyObj> {
    val flow = validator.flow
    val structural = mutableListOf<PyObj>()
    val seen = mutableSetOf<Pair<String, Long>>()
    fun add(role: String, insn: LkInstruction) {
        val key = role to insn.address
        if (key in seen) return
        seen.add(key)
        structural.add(instructionToDict(insn, base, role))
    }
    val cmpRegReg = Regex("""w\d+, w\d+""")
    for (index in flow.startIndex..flow.endIndex) {
        val insn = instructions[index]
        if (insn.mnemonic == "mov" && insn.operands == "w20, wzr") add("result_init_zero", insn)
        if (insn.mnemonic == "mov" && insn.operands == "w20, #1") add("result_set_success_one", insn)
        if (insn.mnemonic == "mov" && insn.operands == "w0, w20") add("return_result_w20", insn)
        if (insn.mnemonic == "ret") add("function_return", insn)
        if (insn.mnemonic == "cmp") {
            if (cmpRegReg.containsMatchIn(insn.operands)) add("register_compare", insn)
            else if ("#0" in insn.operands) add("zero_compare", insn)
        }
        if (insn.mnemonic in setOf("ldrb", "ldur") && "w" in insn.operands) {
            add("data_load_for_compare", insn)
        }
        if ("#0x20" in insn.operands) add("hash_0x20_buffer_or_length", insn)
        if (parseCallTarget(insn) != null) add("call", insn)
        if (flow.xrefs.any { it.insnIndex == index }) add("string_xref", insn)
    }
    return structural
}

fun keyValidatorToDict(
    validator: LkKeyValidator,
    base: Long,
    instructions: List<LkInstruction>,
): PyObj {
    val map = linkedMapOf<String, PyJson>()
    map["function_start"] = PyStr(hex16(validator.flow.startAddress))
    map["function_end"] = PyStr(hex16(validator.flow.endAddress))
    map["confidence"] = PyInt(validator.confidence.toLong())
    map["evidence"] = pyArrOf(validator.evidence.map { PyStr(it) })
    map["matched_targets"] = pyArrOf(validator.flow.matchedTargets.map { PyStr(it) })
    map["callers"] = pyArrOf(validator.callers.map { (caller, site) ->
        pyObjOf(
            "caller_start" to PyStr(hex16(caller)),
            "call_site" to PyStr(hex16(site)),
        )
    })
    map["failure_edges"] = pyArrOf(validator.failureEdges.map { (callSite, failureTarget, branch) ->
        pyObjOf(
            "call_site" to PyStr(hex16(callSite)),
            "failure_target" to PyStr(hex16(failureTarget)),
            "branch" to PyStr(branch),
        )
    })
    map["function_start_file_offset"] = PyStr(hexOff(fileOffsetForVa(validator.flow.startAddress, base)))
    map["function_start_hxd_offset"] = PyStr(hxdOffsetForVa(validator.flow.startAddress, base))
    map["function_end_file_offset"] = PyStr(hexOff(fileOffsetForVa(validator.flow.endAddress, base)))
    map["function_end_hxd_offset"] = PyStr(hxdOffsetForVa(validator.flow.endAddress, base))
    map["callers"] = pyArrOf(validator.callers.map { (caller, site) ->
        pyObjOf(
            "caller_start" to PyStr(hex16(caller)),
            "caller_start_file_offset" to PyStr(hexOff(fileOffsetForVa(caller, base))),
            "caller_start_hxd_offset" to PyStr(hxdOffsetForVa(caller, base)),
            "call_site" to PyStr(hex16(site)),
            "call_site_file_offset" to PyStr(hexOff(fileOffsetForVa(site, base))),
            "call_site_hxd_offset" to PyStr(hxdOffsetForVa(site, base)),
        )
    })
    map["failure_edges"] = pyArrOf(validator.failureEdges.map { (callSite, failureTarget, branch) ->
        pyObjOf(
            "call_site" to PyStr(hex16(callSite)),
            "call_site_file_offset" to PyStr(hexOff(fileOffsetForVa(callSite, base))),
            "call_site_hxd_offset" to PyStr(hxdOffsetForVa(callSite, base)),
            "failure_target" to PyStr(hex16(failureTarget)),
            "failure_target_file_offset" to PyStr(hexOff(fileOffsetForVa(failureTarget, base))),
            "failure_target_hxd_offset" to PyStr(hxdOffsetForVa(failureTarget, base)),
            "branch" to PyStr(branch),
        )
    })
    map["structural_instructions"] = pyArrOf(
        keyValidatorStructuralInstructions(instructions, validator, base)
    )
    map["xrefs"] = pyArrOf(validator.flow.xrefs.map { xrefToDict(it, base) })
    return PyObj(map)
}

fun findNextCall(
    instructions: List<LkInstruction>,
    startIndex: Int,
    endIndex: Int,
    limit: Int = 5,
): Pair<Int, LkInstruction>? {
    for (index in startIndex + 1..minOf(endIndex, startIndex + limit)) {
        val insn = instructions[index]
        if (insn.mnemonic == "bl") return index to insn
    }
    return null
}

fun findNearbyBranchAfterCall(
    instructions: List<LkInstruction>,
    callIndex: Int,
    flowEnd: Int,
    limit: Int = 4,
): LkInstruction? {
    for (index in callIndex + 1..minOf(flowEnd, callIndex + limit)) {
        val insn = instructions[index]
        if (isConditionalBranch(insn)) return insn
        if (insn.mnemonic == "bl") break
    }
    return null
}

fun erasePermissionNameChecks(
    instructions: List<LkInstruction>,
    flow: LkFunctionFlow,
): List<LkNameCheck> {
    val checks = mutableListOf<LkNameCheck>()
    val xReg = Regex("""x[12],""")
    for (xref in flow.xrefs) {
        if (xref.target.target !in LK_ERASE_PERMISSION_NAME_TARGETS) continue
        if (pyMatch(xReg, xref.operands) == null) continue
        val nextCall = findNextCall(instructions, xref.insnIndex, flow.endIndex, limit = 5) ?: continue
        val (callIndex, callInsn) = nextCall
        val branch = findNearbyBranchAfterCall(instructions, callIndex, flow.endIndex)
        checks.add(
            LkNameCheck(
                xrefAddress = xref.address,
                name = xref.target.target,
                callTarget = parseCallTarget(callInsn),
                branchTarget = branch?.let { parseBranchTarget(it.operands) },
            )
        )
    }
    return checks.distinct()
}

fun findDeniedEdges(
    instructions: List<LkInstruction>,
    flow: LkFunctionFlow,
): List<Triple<Long, Long, String>> {
    val deniedXrefs = flow.xrefs.filter { it.target.target == "erase permission denied" }
    if (deniedXrefs.isEmpty()) return emptyList()
    val edges = mutableListOf<Triple<Long, Long, String>>()
    for ((source, target, mnemonic) in flow.branches) {
        if (deniedXrefs.any { kotlin.math.abs(target - it.address) <= 0x30 }) {
            edges.add(Triple(source, target, mnemonic))
        }
    }
    return edges.distinct()
}

fun identifyErasePermissionChecks(
    core: LkAnalyzerCore,
    flows: List<LkFunctionFlow>,
): List<LkErasePermissionCheck> {
    val instructions = core.instructions
    val checks = mutableListOf<LkErasePermissionCheck>()
    val structOff10 = Regex("""x\d+, \[x\d+, #0x10\]""")
    val addPlus1 = Regex("""x\d+, x\d+, #1$""")
    for (flow in flows) {
        val insns = flowInstructionSlice(instructions, flow)
        val targets = flow.matchedTargets.toSet()
        val evidence = mutableListOf<String>()
        var confidence = 0
        for ((target, weight) in LK_ERASE_PERMISSION_TARGETS) {
            if (target in targets) {
                evidence.add("string target: $target")
                confidence += weight
            }
        }
        if ("fboot_cmd_erase_permission_validate" in targets) {
            evidence.add("debug tag names erase permission validator")
            confidence += 10
        }
        if (setOf("erase permission denied", "Invalid partition name %s").all { it in targets }) {
            evidence.add("contains user-visible deny and invalid partition errors")
            confidence += 10
        }
        val nameChecks = erasePermissionNameChecks(instructions, flow)
        if (nameChecks.size >= 3) {
            evidence.add("repeated partition-name comparisons (${nameChecks.size})")
            confidence += 10
        } else if (nameChecks.isNotEmpty()) {
            evidence.add("partition-name comparisons (${nameChecks.size})")
            confidence += 4
        }
        if (insns.any { it.mnemonic == "ldr" && structOff10.containsMatchIn(it.operands) }) {
            evidence.add("reads fastboot command argument pointer at struct offset +0x10")
            confidence += 6
        }
        if (insns.any { it.mnemonic == "add" && addPlus1.containsMatchIn(it.operands) }) {
            evidence.add("skips leading ':' with add argument pointer +1")
            confidence += 5
        }
        val permissionCallItems = mutableListOf<Pair<Long, Long>>()
        for ((localIndex, insn) in insns.withIndex()) {
            val target = parseCallTarget(insn) ?: continue
            if (insns.pySlice(localIndex + 1, minOf(insns.size, localIndex + 4))
                    .any { it.mnemonic in setOf("tbz", "tbnz", "cbz", "cbnz") }
            ) {
                permissionCallItems.add(insn.address to target)
            }
        }
        val permissionCalls = permissionCallItems
        if (permissionCalls.size >= 2) {
            evidence.add("contains calls whose boolean result gates erase flow")
            confidence += 5
        }
        val deniedEdges = findDeniedEdges(instructions, flow)
        if (deniedEdges.isNotEmpty()) {
            evidence.add("branch edge reaches erase permission denied message")
            confidence += 7
        }
        val callers = core.callSitesForTarget(flows, flow.startAddress)
        if (callers.isNotEmpty()) {
            evidence.add("called by another flow candidate")
            confidence += 3
        }
        if (confidence < 35) continue
        checks.add(
            LkErasePermissionCheck(
                flow = flow,
                confidence = confidence,
                evidence = evidence.distinct(),
                callers = callers,
                nameChecks = nameChecks,
                permissionCalls = permissionCalls,
                deniedEdges = deniedEdges,
            )
        )
    }
    return checks.sortedWith(
        compareByDescending<LkErasePermissionCheck> { it.confidence }.thenBy { it.flow.startAddress }
    )
}

fun erasePermissionStructuralInstructions(
    instructions: List<LkInstruction>,
    check: LkErasePermissionCheck,
    base: Long,
): List<PyObj> {
    val flow = check.flow
    val nameCheckAddresses = check.nameChecks.map { it.xrefAddress }.toSet()
    val permissionCallAddresses = check.permissionCalls.map { it.first }.toSet()
    val deniedBranchAddresses = check.deniedEdges.map { it.first }.toSet()
    val structural = mutableListOf<PyObj>()
    val seen = mutableSetOf<Pair<String, Long>>()
    fun add(role: String, insn: LkInstruction) {
        val key = role to insn.address
        if (key in seen) return
        seen.add(key)
        structural.add(instructionToDict(insn, base, role))
    }
    val structOff10 = Regex("""x\d+, \[x\d+, #0x10\]""")
    val addPlus1 = Regex("""x\d+, x\d+, #1$""")
    for (index in flow.startIndex..flow.endIndex) {
        val insn = instructions[index]
        if (insn.mnemonic == "ldr" && structOff10.containsMatchIn(insn.operands)) {
            add("fastboot_argument_load_struct_plus_0x10", insn)
        }
        if (insn.mnemonic == "add" && addPlus1.containsMatchIn(insn.operands)) {
            add("partition_name_skip_colon", insn)
        }
        if (insn.address in nameCheckAddresses) add("partition_name_string_xref", insn)
        if (parseCallTarget(insn) != null) {
            val role = if (insn.address in permissionCallAddresses) "permission_or_helper_call" else "call"
            add(role, insn)
        }
        if (isConditionalBranch(insn)) {
            val role = if (insn.address in deniedBranchAddresses) "branch_to_erase_permission_denied" else "conditional_branch"
            add(role, insn)
        }
        if (insn.mnemonic == "mov" && insn.operands in setOf("w20, wzr", "w20, #1", "w20, #3", "w0, w20")) {
            add("return_status_value", insn)
        }
        if (flow.xrefs.any { it.insnIndex == index }) add("string_xref", insn)
    }
    return structural
}

fun erasePermissionToDict(
    check: LkErasePermissionCheck,
    base: Long,
    instructions: List<LkInstruction>,
): PyObj {
    val map = linkedMapOf<String, PyJson>()
    map["function_start"] = PyStr(hex16(check.flow.startAddress))
    map["function_end"] = PyStr(hex16(check.flow.endAddress))
    map["confidence"] = PyInt(check.confidence.toLong())
    map["evidence"] = pyArrOf(check.evidence.map { PyStr(it) })
    map["matched_targets"] = pyArrOf(check.flow.matchedTargets.map { PyStr(it) })
    map["calls"] = pyArrOf(check.flow.calls.map { PyStr(hex16(it)) })
    map["callers"] = pyArrOf(check.callers.map { (caller, site) ->
        pyObjOf(
            "caller_start" to PyStr(hex16(caller)),
            "call_site" to PyStr(hex16(site)),
        )
    })
    map["name_checks"] = pyArrOf(check.nameChecks.map { nc ->
        pyObjOf(
            "xref_address" to PyStr(hex16(nc.xrefAddress)),
            "name" to PyStr(nc.name),
            "compare_call_target" to pyStrOrNull(nc.callTarget?.let { hex16(it) }),
            "branch_target" to pyStrOrNull(nc.branchTarget?.let { hex16(it) }),
        )
    })
    map["denied_edges"] = pyArrOf(check.deniedEdges.map { (branchAddress, target, mnemonic) ->
        pyObjOf(
            "branch_address" to PyStr(hex16(branchAddress)),
            "target" to PyStr(hex16(target)),
            "mnemonic" to PyStr(mnemonic),
        )
    })
    map["function_start_file_offset"] = PyStr(hexOff(fileOffsetForVa(check.flow.startAddress, base)))
    map["function_start_hxd_offset"] = PyStr(hxdOffsetForVa(check.flow.startAddress, base))
    map["function_end_file_offset"] = PyStr(hexOff(fileOffsetForVa(check.flow.endAddress, base)))
    map["function_end_hxd_offset"] = PyStr(hxdOffsetForVa(check.flow.endAddress, base))
    map["callers"] = pyArrOf(check.callers.map { (caller, site) ->
        pyObjOf(
            "caller_start" to PyStr(hex16(caller)),
            "caller_start_file_offset" to PyStr(hexOff(fileOffsetForVa(caller, base))),
            "caller_start_hxd_offset" to PyStr(hxdOffsetForVa(caller, base)),
            "call_site" to PyStr(hex16(site)),
            "call_site_file_offset" to PyStr(hexOff(fileOffsetForVa(site, base))),
            "call_site_hxd_offset" to PyStr(hxdOffsetForVa(site, base)),
        )
    })
    map["name_checks"] = pyArrOf(check.nameChecks.map { nc ->
        pyObjOf(
            "xref_address" to PyStr(hex16(nc.xrefAddress)),
            "xref_file_offset" to PyStr(hexOff(fileOffsetForVa(nc.xrefAddress, base))),
            "xref_hxd_offset" to PyStr(hxdOffsetForVa(nc.xrefAddress, base)),
            "name" to PyStr(nc.name),
            "compare_call_target" to pyStrOrNull(nc.callTarget?.let { hex16(it) }),
            "compare_call_target_file_offset" to pyStrOrNull(
                nc.callTarget?.let { hexOff(fileOffsetForVa(it, base)) }
            ),
            "compare_call_target_hxd_offset" to pyStrOrNull(
                nc.callTarget?.let { hxdOffsetForVa(it, base) }
            ),
            "branch_target" to pyStrOrNull(nc.branchTarget?.let { hex16(it) }),
            "branch_target_file_offset" to pyStrOrNull(
                nc.branchTarget?.let { hexOff(fileOffsetForVa(it, base)) }
            ),
            "branch_target_hxd_offset" to pyStrOrNull(
                nc.branchTarget?.let { hxdOffsetForVa(it, base) }
            ),
        )
    })
    map["denied_edges"] = pyArrOf(check.deniedEdges.map { (branchAddress, target, mnemonic) ->
        pyObjOf(
            "branch_address" to PyStr(hex16(branchAddress)),
            "branch_file_offset" to PyStr(hexOff(fileOffsetForVa(branchAddress, base))),
            "branch_hxd_offset" to PyStr(hxdOffsetForVa(branchAddress, base)),
            "target" to PyStr(hex16(target)),
            "target_file_offset" to PyStr(hexOff(fileOffsetForVa(target, base))),
            "target_hxd_offset" to PyStr(hxdOffsetForVa(target, base)),
            "mnemonic" to PyStr(mnemonic),
        )
    })
    map["structural_instructions"] = pyArrOf(
        erasePermissionStructuralInstructions(instructions, check, base)
    )
    map["xrefs"] = pyArrOf(check.flow.xrefs.map { xrefToDict(it, base) })
    return PyObj(map)
}

fun registerWrittenByAdrp(insn: LkInstruction): Pair<String, Long>? {
    if (insn.mnemonic != "adrp") return null
    return parseAdrp(insn.operands)
}

fun memoryAccessRegisters(insn: LkInstruction): List<Pair<String, Long>> {
    val refs = mutableListOf<Pair<String, Long>>()
    val re = Regex("""\[(x\d+)""")
    for (m in re.findAll(insn.operands)) {
        val reg = m.groupValues[1]
        val offset = parseMemOffset(insn.operands, reg) ?: continue
        refs.add(reg to offset)
    }
    return refs
}

fun detectGlobalCachePattern(
    core: LkAnalyzerCore,
    functionAddress: Long,
    base: Long,
    dataSize: Int,
): PyObj? {
    val instructions = core.instructions
    val bounds = core.findFunctionBoundsByAddress(functionAddress) ?: return null
    var (start, end) = bounds
    for (index in start + 1 until minOf(instructions.size, start + 256)) {
        if (index <= end) continue
        if (looksLikeFunctionStart(instructions[index])) {
            if (instructions[index].address - instructions[start].address <= 0x500) {
                end = index - 1
            }
            break
        }
    }
    val pagesByReg = mutableMapOf<String, Long>()
    val refsByAddress = linkedMapOf<Long, MutableList<PyObj>>()
    var conditionalAfterRead = false
    for (index in start..end) {
        val insn = instructions[index]
        val parsedAdrp = registerWrittenByAdrp(insn)
        if (parsedAdrp != null) {
            pagesByReg[parsedAdrp.first] = parsedAdrp.second
            continue
        }
        if (insn.mnemonic !in setOf("ldr", "ldrb", "ldrh", "str", "strb", "strh")) continue
        for ((reg, offset) in memoryAccessRegisters(insn)) {
            val page = pagesByReg[reg] ?: continue
            val address = page + offset
            if (uLessEq(base, address) && uLess(address, base + 0x1000)) continue
            val kind = if (insn.mnemonic.startsWith("str")) "write" else "read"
            val item = pyObjOf(
                "kind" to PyStr(kind),
                "address" to PyStr(hex16(insn.address)),
                "file_offset" to PyStr(hexOff(fileOffsetForVa(insn.address, base))),
                "hxd_offset" to PyStr(hxdOffsetForVa(insn.address, base)),
                "instruction" to PyStr("${insn.mnemonic} ${insn.operands}".trim()),
            )
            refsByAddress.getOrPut(address) { mutableListOf() }.add(item)
            if (kind == "read") {
                for (probe in instructions.pySlice(index + 1, minOf(end + 1, index + 5))) {
                    if (probe.mnemonic in setOf("cbz", "cbnz", "tbz", "tbnz", "b.eq", "b.ne")) {
                        conditionalAfterRead = true
                        break
                    }
                }
            }
        }
    }
    val candidates = refsByAddress.entries.filter { (_, refs) ->
        refs.any { (it.map["kind"] as PyStr).s == "read" } &&
            refs.any { (it.map["kind"] as PyStr).s == "write" }
    }
    if (candidates.isEmpty()) return null
    // Python: sorted(candidates, key=(len(refs), address), reverse=True)[0]
    // — largest key, ties keep first-seen order.
    var best: Map.Entry<Long, MutableList<PyObj>>? = null
    for (entry in candidates) {
        val cur = best
        if (cur == null) {
            best = entry
            continue
        }
        if (entry.value.size > cur.value.size ||
            (entry.value.size == cur.value.size && uCmp(entry.key, cur.key) > 0)
        ) {
            best = entry
        }
    }
    val (cacheAddress, refs) = best!!
    val inRange = uLessEq(base, cacheAddress) && uLess(cacheAddress, base + dataSize)
    return pyObjOf(
        "function" to PyStr(hex16(functionAddress)),
        "function_file_offset" to PyStr(hexOff(fileOffsetForVa(functionAddress, base))),
        "function_hxd_offset" to PyStr(hxdOffsetForVa(functionAddress, base)),
        "function_end" to PyStr(hex16(instructions[end].address)),
        "cache_address" to PyStr(hex16(cacheAddress)),
        "cache_file_offset" to pyStrOrNull(
            if (inRange) hexOff(fileOffsetForVa(cacheAddress, base)) else null
        ),
        "cache_hxd_offset" to pyStrOrNull(
            if (inRange) hxdOffsetForVa(cacheAddress, base) else null
        ),
        "has_conditional_after_read" to PyBool(conditionalAfterRead),
        "references" to pyArrOf(refs),
    )
}

fun detectSerialnoRuntimeSource(
    core: LkAnalyzerCore,
    xrefs: List<LkXrefHit>,
    base: Long,
    dataSize: Int,
): PyObj? {
    val instructions = core.instructions
    fun extendPastEarlyReturns(start: Int, end: Int, maxBytes: Long = 0x500): Int {
        for (index in start + 1 until minOf(instructions.size, start + 256)) {
            if (index <= end) continue
            if (looksLikeFunctionStart(instructions[index])) {
                if (instructions[index].address - instructions[start].address <= maxBytes) {
                    return index - 1
                }
                return end
            }
        }
        return end
    }

    data class SerialCandidate(
        val score: Int,
        val start: Int,
        val end: Int,
        val functionXrefs: List<LkXrefHit>,
        val calls: List<PyObj>,
        val genericPropGetString: Long?,
        val cacheCandidates: List<PyObj>,
    )

    val candidates = mutableListOf<SerialCandidate>()
    for (xref in xrefs) {
        if (xref.target.target != "serialno" || xref.target.text.trim('\u0000') != "serialno") continue
        val (start0, end0) = estimateFunctionRange(instructions, xref.insnIndex)
        val end = extendPastEarlyReturns(start0, end0)
        val start = start0
        val functionXrefs = xrefs.filter {
            it.insnIndex in start..end && it.target.target in setOf("serialno", "barcode")
        }
        val insns = instructions.subList(start, end + 1)
        val functionSize = instructions[end].address - instructions[start].address
        val calls = mutableListOf<PyObj>()
        val callTargets = mutableListOf<Long>()
        var hasCallResultGate = false
        for ((localIndex, insn) in insns.withIndex()) {
            val target = parseCallTarget(insn) ?: continue
            callTargets.add(target)
            calls.add(
                pyObjOf(
                    "site" to PyStr(hex16(insn.address)),
                    "site_file_offset" to PyStr(hexOff(fileOffsetForVa(insn.address, base))),
                    "site_hxd_offset" to PyStr(hxdOffsetForVa(insn.address, base)),
                    "target" to PyStr(hex16(target)),
                    "target_file_offset" to PyStr(hexOff(fileOffsetForVa(target, base))),
                    "target_hxd_offset" to PyStr(hxdOffsetForVa(target, base)),
                )
            )
            for (probe in insns.pySlice(localIndex + 1, minOf(insns.size, localIndex + 4))) {
                if (probe.mnemonic in setOf("cbz", "cbnz", "tbz", "tbnz", "b.eq", "b.ne")) {
                    hasCallResultGate = true
                    break
                }
            }
        }
        val repeatedTargets = callTargets.filter { t -> callTargets.count { it == t } >= 2 }.toSortedSet()
        val genericPropGetString = repeatedTargets.firstOrNull()
        val cacheCandidates = mutableListOf<PyObj>()
        for (target in callTargets.distinct()) {
            if (genericPropGetString != null && target == genericPropGetString) continue
            val cache = detectGlobalCachePattern(core, target, base, dataSize) ?: continue
            cacheCandidates.add(cache)
        }
        val exactTargets = functionXrefs
            .filter { it.target.text.trim('\u0000') == it.target.target }
            .map { it.target.target }.toSet()
        var score = 0
        if ("serialno" in exactTargets) score += 25
        if ("barcode" in exactTargets) score += 25
        if (genericPropGetString != null) score += 20
        if (cacheCandidates.isNotEmpty()) score += 20
        if (hasCallResultGate) score += 10
        if (functionSize <= 0x200) score += 10
        if (calls.size > 12) score -= 20
        candidates.add(
            SerialCandidate(score, start, end, functionXrefs, calls, genericPropGetString, cacheCandidates)
        )
    }
    if (candidates.isEmpty()) return null
    val best = candidates.sortedWith(
        compareByDescending<SerialCandidate> { it.score }
            .thenBy { instructions[it.start].address }
    )[0]
    val (score, start, end, functionXrefs, calls, genericPropGetString, cacheCandidates) = best
    return pyObjOf(
        "architecture" to PyStr(if (instructions.isNotEmpty()) instructions[0].mode else "unknown"),
        "hw_get_serialno_string" to PyStr(hex16(instructions[start].address)),
        "hw_get_serialno_string_file_offset" to PyStr(hexOff(fileOffsetForVa(instructions[start].address, base))),
        "hw_get_serialno_string_hxd_offset" to PyStr(hxdOffsetForVa(instructions[start].address, base)),
        "function_end" to PyStr(hex16(instructions[end].address)),
        "function_end_file_offset" to PyStr(hexOff(fileOffsetForVa(instructions[end].address, base))),
        "function_end_hxd_offset" to PyStr(hxdOffsetForVa(instructions[end].address, base)),
        "generic_prop_get_string" to pyStrOrNull(genericPropGetString?.let { hex16(it) }),
        "generic_prop_get_string_file_offset" to pyStrOrNull(
            genericPropGetString?.let { hexOff(fileOffsetForVa(it, base)) }
        ),
        "generic_prop_get_string_hxd_offset" to pyStrOrNull(
            genericPropGetString?.let { hxdOffsetForVa(it, base) }
        ),
        "xrefs" to pyArrOf(
            functionXrefs.filter { it.target.target in setOf("serialno", "barcode") }
                .map { xrefToDict(it, base) }
        ),
        "calls" to pyArrOf(calls),
        "cache_candidates" to pyArrOf(cacheCandidates),
        "confidence" to PyInt(score.toLong()),
        "notes" to pyArrOf(
            listOf(
                PyStr("analysis-only; no patch runtime is generated"),
                PyStr("serialno/barcode fallback chain candidate"),
                PyStr("cache candidate requires read/write to the same global cell"),
            )
        ),
    )
}

fun classifyPartitionCall(
    instructions: List<LkInstruction>,
    callIndex: Int,
    flowEnd: Int,
): Triple<String, Long?, Long?> {
    var checkAddress: Long? = null
    var failureTarget: Long? = null
    for (index in callIndex + 1..minOf(flowEnd, callIndex + 4)) {
        val insn = instructions[index]
        if (insn.mnemonic == "cmn" && insn.operands == "w0, #1") {
            checkAddress = insn.address
            return Triple("lookup", checkAddress, null)
        }
        if (insn.mnemonic == "tbnz" && insn.operands.startsWith("x0, #0x3f")) {
            checkAddress = insn.address
            failureTarget = parseBranchTarget(insn.operands)
            return Triple("erase", checkAddress, failureTarget)
        }
        if (insn.mnemonic == "cmp" && insn.operands == "r0, #0") {
            checkAddress = insn.address
            for (branchIndex in index + 1..minOf(flowEnd, index + 2)) {
                val branch = instructions[branchIndex]
                if (branch.mnemonic in setOf("beq", "bne", "cbz", "cbnz")) {
                    failureTarget = parseBranchTarget(branch.operands)
                    return Triple("erase", checkAddress, failureTarget)
                }
            }
        }
        if (insn.mnemonic == "bl") break
    }
    return Triple("call", checkAddress, failureTarget)
}

fun findFailureMessageForTarget(
    flow: LkFunctionFlow,
    partition: String,
    failureTarget: Long?,
): LkXrefHit? {
    if (failureTarget == null) return null
    val candidates = flow.xrefs.filter {
        uLessEq(failureTarget, it.address) &&
            uLessEq(it.address, failureTarget + 0x40) &&
            partition.lowercase() in it.target.text.lowercase() &&
            "erase" in it.target.text.lowercase()
    }
    if (candidates.isNotEmpty()) {
        return candidates.sortedWith(compareBy { it.address })[0]
    }
    return null
}

fun isUnlockFlow(flow: LkFunctionFlow): Boolean {
    val targets = flow.matchedTargets.map { it.lowercase() }.toSet()
    val unlockMarkers = setOf(
        "check 'allow oem unlock'",
        "code validation failure",
        "bootloader is unlocked",
        "now unlocked for flashing",
        "fastboot oem unlock",
    )
    val partitionCount = targets.intersect(LK_TARGET_ERASE_PARTITIONS).size
    val markerCount = targets.intersect(unlockMarkers).size
    return markerCount >= 1 && partitionCount >= 2
}

fun detectPartitionEraseOperations(
    core: LkAnalyzerCore,
    flows: List<LkFunctionFlow>,
): List<LkPartitionEraseOperation> {
    val instructions = core.instructions
    val operations = mutableListOf<LkPartitionEraseOperation>()
    var unlockFlows = flows.filter { isUnlockFlow(it) }
    if (unlockFlows.isEmpty() && flows.isNotEmpty()) unlockFlows = listOf(flows[0])
    for (flow in unlockFlows) {
        for (xref in flow.xrefs) {
            val partition = xref.target.target.lowercase()
            if (partition !in LK_TARGET_ERASE_PARTITIONS) continue
            if (!(xref.operands.startsWith("x0, ") ||
                    xref.operands.startsWith("r0, ") ||
                    (xref.operands.startsWith("r4, ") && xref.via.startsWith("thumb ")))
            ) continue
            val nextCall = findNextCall(instructions, xref.insnIndex, flow.endIndex) ?: continue
            val (callIndex, callInsn) = nextCall
            val callTarget = parseCallTarget(callInsn)
            val (operation, checkAddress, failureTarget) = classifyPartitionCall(
                instructions, callIndex, flow.endIndex
            )
            if (operation !in setOf("lookup", "erase")) continue
            val failureXref = findFailureMessageForTarget(flow, partition, failureTarget)
            operations.add(
                LkPartitionEraseOperation(
                    flowStart = flow.startAddress,
                    partition = partition,
                    operation = operation,
                    xrefAddress = xref.address,
                    stringAddress = xref.effectiveAddress,
                    stringText = xref.target.text,
                    referenceMode = partitionReferenceMode(xref),
                    xrefMnemonic = xref.mnemonic,
                    xrefOperands = xref.operands,
                    xrefVia = xref.via,
                    callSite = callInsn.address,
                    callTarget = callTarget,
                    resultCheckAddress = checkAddress,
                    failureTarget = failureTarget,
                    failureMessageAddress = failureXref?.effectiveAddress,
                    failureMessage = failureXref?.target?.text,
                )
            )
        }
    }
    data class OpKey(val flowStart: Long, val partition: String, val operation: String, val xrefAddress: Long)
    val unique = linkedMapOf<OpKey, LkPartitionEraseOperation>()
    for (operation in operations) {
        unique[OpKey(operation.flowStart, operation.partition, operation.operation, operation.xrefAddress)] = operation
    }
    return unique.values.sortedWith(
        compareBy({ it.flowStart }, { it.xrefAddress }, { it.operation }, { it.partition })
    )
}

fun partitionEraseToDict(operation: LkPartitionEraseOperation, base: Long): PyObj {
    val map = linkedMapOf<String, PyJson>()
    map["flow_start"] = PyStr(hex16(operation.flowStart))
    map["partition"] = PyStr(operation.partition)
    map["operation"] = PyStr(operation.operation)
    map["xref_address"] = PyStr(hex16(operation.xrefAddress))
    map["string_address"] = PyStr(hex16(operation.stringAddress))
    map["string_text"] = PyStr(operation.stringText)
    map["reference_mode"] = PyStr(operation.referenceMode)
    map["xref_instruction"] = PyStr("${operation.xrefMnemonic} ${operation.xrefOperands}".trim())
    map["xref_via"] = PyStr(operation.xrefVia)
    map["call_site"] = pyStrOrNull(operation.callSite?.let { hex16(it) })
    map["call_target"] = pyStrOrNull(operation.callTarget?.let { hex16(it) })
    map["result_check_address"] = pyStrOrNull(operation.resultCheckAddress?.let { hex16(it) })
    map["failure_target"] = pyStrOrNull(operation.failureTarget?.let { hex16(it) })
    map["failure_message_address"] = pyStrOrNull(operation.failureMessageAddress?.let { hex16(it) })
    map["failure_message"] = pyStrOrNull(operation.failureMessage)
    map["flow_start_file_offset"] = PyStr(hexOff(fileOffsetForVa(operation.flowStart, base)))
    map["flow_start_hxd_offset"] = PyStr(hxdOffsetForVa(operation.flowStart, base))
    map["xref_file_offset"] = PyStr(hexOff(fileOffsetForVa(operation.xrefAddress, base)))
    map["xref_hxd_offset"] = PyStr(hxdOffsetForVa(operation.xrefAddress, base))
    map["string_file_offset"] = PyStr(hexOff(fileOffsetForVa(operation.stringAddress, base)))
    map["string_hxd_offset"] = PyStr(hxdOffsetForVa(operation.stringAddress, base))
    map["call_site_file_offset"] = pyStrOrNull(
        operation.callSite?.let { hexOff(fileOffsetForVa(it, base)) }
    )
    map["call_site_hxd_offset"] = pyStrOrNull(
        operation.callSite?.let { hxdOffsetForVa(it, base) }
    )
    map["call_target_file_offset"] = pyStrOrNull(
        operation.callTarget?.let { hexOff(fileOffsetForVa(it, base)) }
    )
    map["call_target_hxd_offset"] = pyStrOrNull(
        operation.callTarget?.let { hxdOffsetForVa(it, base) }
    )
    map["failure_target_file_offset"] = pyStrOrNull(
        operation.failureTarget?.let { hexOff(fileOffsetForVa(it, base)) }
    )
    map["failure_target_hxd_offset"] = pyStrOrNull(
        operation.failureTarget?.let { hxdOffsetForVa(it, base) }
    )
    return PyObj(map)
}

fun partitionStringInventory(
    operations: List<LkPartitionEraseOperation>,
    base: Long,
): Map<String, List<PyObj>> {
    val inventory = linkedMapOf<String, List<PyObj>>()
    for (partition in LK_TARGET_ERASE_PARTITIONS.sorted()) {
        val keyedEntries = linkedMapOf<Triple<Long, String, String>, PyObj>()
        for (operation in operations) {
            if (operation.partition != partition) continue
            val key = Triple(operation.stringAddress, operation.stringText, "partition_arg")
            keyedEntries[key] = pyObjOf(
                "partition" to PyStr(partition),
                "address" to PyStr(hex16(operation.stringAddress)),
                "file_offset" to PyStr(hexOff(fileOffsetForVa(operation.stringAddress, base))),
                "hxd_offset" to PyStr(hxdOffsetForVa(operation.stringAddress, base)),
                "text" to PyStr(operation.stringText),
                "reference_mode" to PyStr(operation.referenceMode),
                "role" to PyStr("partition_arg"),
                "operation" to PyStr(operation.operation),
                "xref_address" to PyStr(hex16(operation.xrefAddress)),
                "xref_instruction" to PyStr("${operation.xrefMnemonic} ${operation.xrefOperands}".trim()),
                "xref_via" to PyStr(operation.xrefVia),
            )
            if (operation.failureMessageAddress != null && operation.failureMessage != null) {
                val failureKey = Triple(
                    operation.failureMessageAddress,
                    operation.failureMessage,
                    "failure_message",
                )
                keyedEntries[failureKey] = pyObjOf(
                    "partition" to PyStr(partition),
                    "address" to PyStr(hex16(operation.failureMessageAddress)),
                    "file_offset" to PyStr(hexOff(fileOffsetForVa(operation.failureMessageAddress, base))),
                    "hxd_offset" to PyStr(hxdOffsetForVa(operation.failureMessageAddress, base)),
                    "text" to PyStr(operation.failureMessage),
                    "reference_mode" to PyStr(
                        classifyTargetText(partition, operation.failureMessage)
                    ),
                    "role" to PyStr("failure_message"),
                    "operation" to PyStr(operation.operation),
                    "xref_address" to PyStr(hex16(operation.xrefAddress)),
                    "xref_instruction" to PyNull,
                    "xref_via" to PyNull,
                )
            }
        }
        val entries = keyedEntries.values.sortedWith(
            compareBy(
                { ((it.map["address"] as PyStr).s) },
                { ((it.map["role"] as PyStr).s) },
                { ((it.map["operation"] as PyStr).s) },
            )
        )
        inventory[partition] = entries
    }
    return inventory
}
