// Patch operations: KEY/erase-token gates, FRP, unlock-flow.
// Port of lk_patch_partition.py patch_* / find_* / build_unlock_* functions.
package com.cronch.motoflasher.valproto

import java.io.File

object ValPatchOps {
    const val PACIASP_WORD = 0xD503233FL
    const val PACIBSP_WORD = 0xD503237FL

    fun adjustAarch64PacPrologueOffset(data: ByteArray, entryOffset: Int): Int {
        if (entryOffset < 4 || entryOffset + 4 > data.size) return entryOffset
        val prevWord = ValBytes.u32At(data, entryOffset - 4)
        val currentWord = ValBytes.u32At(data, entryOffset)
        if (prevWord != PACIASP_WORD && prevWord != PACIBSP_WORD) return entryOffset
        if ((currentWord and 0xFFC003FFL) != 0xA98003FDL) return entryOffset
        return entryOffset - 4
    }

    fun adjustAarch64PacFunctionEntry(data: ByteArray, validator: JsonObj, entryOffset: Int, base: Long): Int {
        if (entryOffset < 4) return entryOffset
        val prevWord = ValBytes.u32At(data, entryOffset - 4)
        if (prevWord != PACIASP_WORD && prevWord != PACIBSP_WORD) return entryOffset
        val expectedVa = base + entryOffset - 4
        for (caller in validator.arr("callers").filterIsInstance<JsonObj>()) {
            if (!caller.map.containsKey("call_site_file_offset")) continue
            val callOffset = ValAnalysis.parseHxdOffset(caller.str("call_site_file_offset") ?: continue)
            if (callOffset < 0 || callOffset + 4 > data.size) continue
            val callWord = ValBytes.u32At(data, callOffset)
            val target = ValEncoders.decodeAarch64CallTarget(callWord, base + callOffset)
            if (target == expectedVa) return entryOffset - 4
        }
        return entryOffset
    }

    fun patchBytes(data: ByteArray, offset: Int, oldName: String, newName: String) {
        val oldRaw = oldName.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val newRaw = newName.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        require(newRaw.size <= oldRaw.size) {
            "replacement too long: '$newName' needs ${newRaw.size} bytes, but '$oldName' only holds ${oldRaw.size} bytes with NUL"
        }
        val current = data.copyOfRange(offset, offset + oldRaw.size)
        require(current.contentEquals(oldRaw)) { "offset 0x${offset.toString(16)} does not contain ${oldRaw.contentToString()}; contains ${current.contentToString()}" }
        val padded = ValBytes.ljust(newRaw, oldRaw.size)
        padded.copyInto(data, offset)
    }

    fun patchKeyCustomSignature(data: ByteArray, analysisDir: File, signature: ByteArray, caveOffset: Int? = null): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEndOffset = ValAnalysis.parseHxdOffset(validator.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        require(entryOffset >= 0 && entryOffset + 4 <= data.size) { "KEY offset out of file: 0x${entryOffset.toString(16)}" }
        val originalInstr = data.copyOfRange(entryOffset, entryOffset + 4)
        val dummyStub = ValStubs.buildKeySignatureStub(entryOffset, entryOffset + 0x100, originalInstr, signature)
        val stubSize = dummyStub.size
        val cave = caveOffset ?: ValTokens.findZeroCave(data, stubSize, functionEndOffset)
        ValTokens.requireZeroCave(data, cave, stubSize)
        val entryBranch = ValEncoders.branchImm26(entryOffset.toLong(), cave.toLong())
        val stub = ValStubs.buildKeySignatureStub(entryOffset, cave, originalInstr, signature)
        stub.copyInto(data, cave)
        entryBranch.copyInto(data, entryOffset)
        return mapOf(
            "entry_offset" to entryOffset, "cave_offset" to cave, "stub_size" to stub.size,
            "original_instr" to originalInstr, "entry_branch" to entryBranch,
            "function_start" to validator.str("function_start"),
            "function_end" to validator.str("function_end"),
        )
    }

    fun patchKeyTokenGate(data: ByteArray, analysisDir: File, constants: ByteArray, caveOffset: Int? = null): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEndOffset = ValAnalysis.parseHxdOffset(validator.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        require(entryOffset >= 0 && entryOffset + 4 <= data.size) { "KEY offset out of file: 0x${entryOffset.toString(16)}" }
        val originalInstr = data.copyOfRange(entryOffset, entryOffset + 4)
        val dummyStub = ValStubs.buildKeyTokenStub(entryOffset, entryOffset + 0x100, originalInstr, constants)
        val stubSize = dummyStub.size
        val cave = caveOffset ?: ValTokens.findZeroCave(data, stubSize, functionEndOffset)
        ValTokens.requireZeroCave(data, cave, stubSize)
        val entryBranch = ValEncoders.branchImm26(entryOffset.toLong(), cave.toLong())
        val stub = ValStubs.buildKeyTokenStub(entryOffset, cave, originalInstr, constants)
        stub.copyInto(data, cave)
        entryBranch.copyInto(data, entryOffset)
        return mapOf(
            "entry_offset" to entryOffset, "cave_offset" to cave, "stub_size" to stub.size,
            "original_instr" to originalInstr, "entry_branch" to entryBranch,
            "function_start" to validator.str("function_start"),
            "function_end" to validator.str("function_end"),
        )
    }

    fun patchKeyRuntimeSerialTokenGate(
        data: ByteArray, analysisDir: File, secret: String, alphabet: String,
        caveOffset: Int? = null, debugLog: Boolean = false, debugStop: String? = null,
    ): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val serialEntry = ValAnalysis.loadSerialnoRuntimeEntry(analysisDir)
        val base = ValAnalysis.loadBase(analysisDir)
        val architecture = ValAnalysis.loadArchitecture(analysisDir)
        var entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEndOffset = ValAnalysis.parseHxdOffset(validator.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        var serialFuncOffset = ValAnalysis.parseHxdOffset(serialEntry.str("hw_get_serialno_string_file_offset") ?: throw IllegalArgumentException("missing hw_get_serialno_string_file_offset"))
        require(entryOffset >= 0 && entryOffset + 4 <= data.size) { "KEY offset out of file: 0x${entryOffset.toString(16)}" }
        require(serialFuncOffset >= 0 && serialFuncOffset + 4 <= data.size) { "serialno offset out of file: 0x${serialFuncOffset.toString(16)}" }
        if (caveOffset != null) throw IllegalArgumentException("--key-cave-offset does not apply to compact runtime-serial mode")
        if (debugLog) throw IllegalArgumentException("--key-token-debug-log no longer applies to compact mode; use --key-token-debug-stop")
        if (alphabet != "alnum") throw IllegalArgumentException("compact runtime-serial only supports --key-token-alphabet alnum")
        val masks = ValTokens.deriveRuntimeSerialCompactMasks(secret)
        var successValue: Int? = null
        var failValue: Int? = null
        val stub: ByteArray
        var functionSize: Int
        if (architecture == "arm32_thumb") {
            if (debugStop != null) throw IllegalArgumentException("--key-token-debug-stop is not implemented for ARM32 runtime-serial")
            serialFuncOffset = ValTokens.adjustThumbSerialFuncOffset(data, serialFuncOffset)
            if (serialFuncOffset % 2 != 0) throw IllegalArgumentException("serialno Thumb not aligned: 0x${serialFuncOffset.toString(16)}")
            val (s, f) = ValTokens.keyValidatorThumbReturnValues(validator)
            successValue = s
            failValue = f
            stub = ValStubs.buildThumbKeyRuntimeSerialCompactStub(entryOffset, serialFuncOffset, masks, successValue, failValue)
            functionSize = functionEndOffset - entryOffset
        } else {
            entryOffset = adjustAarch64PacFunctionEntry(data, validator, entryOffset, base)
            serialFuncOffset = adjustAarch64PacPrologueOffset(data, serialFuncOffset)
            functionSize = functionEndOffset - entryOffset
            stub = ValStubs.buildKeyRuntimeSerialCompactStub(entryOffset, serialFuncOffset, masks, debugStop)
        }
        if (stub.size > functionSize) throw IllegalArgumentException("compact runtime serial stub too big: ${stub.size} > $functionSize")
        val originalInstr = data.copyOfRange(entryOffset, entryOffset + stub.size)
        stub.copyInto(data, entryOffset)
        return mapOf(
            "entry_offset" to entryOffset, "cave_offset" to null, "stub_size" to stub.size,
            "architecture" to architecture, "masks" to masks,
            "success_value" to successValue, "fail_value" to failValue,
            "serial_func_offset" to serialFuncOffset, "serial_func_va" to (base + serialFuncOffset),
            "serial_confidence" to serialEntry.long("confidence"),
            "debug_log" to debugLog, "debug_stop" to debugStop,
            "debug_print_func_offset" to null, "debug_print_func_va" to null,
            "debug_info_va" to null, "debug_message_vas" to emptyMap<String, Int>(),
            "original_instr" to originalInstr, "entry_branch" to stub,
            "function_start" to validator.str("function_start"),
            "function_end" to validator.str("function_end"),
        )
    }

    fun patchKeyTokenGateThumb(data: ByteArray, analysisDir: File, constants: ByteArray, caveOffset: Int? = null): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEndOffset = ValAnalysis.parseHxdOffset(validator.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        require(entryOffset >= 0 && entryOffset + 4 <= data.size) { "KEY offset out of file: 0x${entryOffset.toString(16)}" }
        val originalInstr = data.copyOfRange(entryOffset, entryOffset + 4)
        val dummyStub = ValStubs.buildThumbKeyTokenStub(entryOffset, entryOffset + 0x100, originalInstr, constants)
        val stubSize = dummyStub.size
        val cave = caveOffset ?: ValTokens.findZeroCave(data, stubSize, functionEndOffset)
        ValTokens.requireZeroCave(data, cave, stubSize)
        val entryBranch = ValEncoders.thumbBW(entryOffset, cave)
        val stub = ValStubs.buildThumbKeyTokenStub(entryOffset, cave, originalInstr, constants)
        stub.copyInto(data, cave)
        entryBranch.copyInto(data, entryOffset)
        return mapOf(
            "entry_offset" to entryOffset, "cave_offset" to cave, "stub_size" to stub.size,
            "original_instr" to originalInstr, "entry_branch" to entryBranch,
            "function_start" to validator.str("function_start"),
            "function_end" to validator.str("function_end"),
        )
    }

    fun patchKeyCallerBeforeKeyCheckpoint(data: ByteArray, analysisDir: File, message: String = "LKDBG before key\n"): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val callers = validator.arr("callers").filterIsInstance<JsonObj>()
        if (callers.isEmpty()) throw IllegalArgumentException("KEY validator has no caller for checkpoint")
        val caller = callers[0]
        if (!caller.map.containsKey("call_site_file_offset")) throw IllegalArgumentException("KEY caller is invalid for checkpoint")
        val callOffset = ValAnalysis.parseHxdOffset(caller.str("call_site_file_offset") ?: "-1")
        require(callOffset >= 0 && callOffset + 4 <= data.size) { "KEY call out of file: 0x${callOffset.toString(16)}" }
        val original = data.copyOfRange(callOffset, callOffset + 4)
        if ((ValBytes.u32At(original, 0) and 0xFC000000L) != 0x94000000L) throw IllegalArgumentException("checkpoint found no BL KEY at 0x${callOffset.toString(16)}")
        ValEncoders.movReg32(0, 31).copyInto(data, callOffset)
        val messagePatch = ValAnalysis.patchCodeValidationMessage(data, analysisDir, message)
        return mapOf(
            "call_offset" to callOffset, "original_instr" to original,
            "replacement" to data.copyOfRange(callOffset, callOffset + 4),
            "message_patch" to messagePatch,
        )
    }

    fun patchKeyEntryZeroCheckpoint(data: ByteArray, analysisDir: File, message: String = "LKDBG entry\n"): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        require(entryOffset >= 0 && entryOffset + 8 <= data.size) { "KEY entry out of file: 0x${entryOffset.toString(16)}" }
        val original = data.copyOfRange(entryOffset, entryOffset + 8)
        val replacement = ValEncoders.movReg32(0, 31) + ValPatchCore.RET
        replacement.copyInto(data, entryOffset)
        val messagePatch = ValAnalysis.patchCodeValidationMessage(data, analysisDir, message)
        return mapOf(
            "entry_offset" to entryOffset, "original_instr" to original,
            "replacement" to replacement, "message_patch" to messagePatch,
        )
    }

    fun findEraseTokenHooks(check: JsonObj): Triple<Int, Int, Int> {
        val structural = check.arr("structural_instructions").filterIsInstance<JsonObj>()
        var skipColonOffset: Int? = null
        for (item in structural) {
            if (item.str("role") == "partition_name_skip_colon") {
                skipColonOffset = ValAnalysis.parseHxdOffset(item.str("hxd_offset") ?: continue)
                break
            }
        }
        val skip = skipColonOffset ?: throw IllegalArgumentException("partition_name_skip_colon not found in erase_permission_check")
        val deniedEdges = check.arr("denied_edges").filterIsInstance<JsonObj>()
        if (deniedEdges.isEmpty()) throw IllegalArgumentException("no denied_edges found in erase_permission_check")
        val firstEdge = deniedEdges[0]
        val preHookOffset = skip + 4
        val gateHookOffset = ValAnalysis.parseHxdOffset(firstEdge.str("branch_file_offset") ?: throw IllegalArgumentException("missing branch_file_offset"))
        val deniedTarget = ValAnalysis.parseHxdOffset(firstEdge.str("target_file_offset") ?: throw IllegalArgumentException("missing target_file_offset"))
        return Triple(preHookOffset, gateHookOffset, deniedTarget)
    }

    fun findEraseCommandTokenHooks(data: ByteArray, check: JsonObj): Quad<Int, Int, Int, Int> {
        val structural = check.arr("structural_instructions").filterIsInstance<JsonObj>()
        var skipColonOffset: Int? = null
        for (item in structural) {
            if (item.str("role") == "partition_name_skip_colon") {
                skipColonOffset = ValAnalysis.parseHxdOffset(item.str("hxd_offset") ?: continue)
                break
            }
        }
        val skip = skipColonOffset ?: throw IllegalArgumentException("partition_name_skip_colon not found in erase command handler")
        val functionStart = ValAnalysis.parseHxdOffset(check.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEnd = ValAnalysis.parseHxdOffset(check.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        val preHookOffset = skip + 4
        var offset = maxOf(functionStart, preHookOffset)
        val end = minOf(functionEnd, data.size - 12)
        while (offset < end) {
            val prevWord = if (offset >= 4) ValBytes.u32At(data, offset - 4) else 0L
            val cmnWord = ValBytes.u32At(data, offset)
            val branchWord = ValBytes.u32At(data, offset + 4)
            if ((prevWord and 0xFC000000L) != 0x94000000L) { offset += 4; continue }
            if (cmnWord != 0x3100041FL) { offset += 4; continue }
            if ((branchWord and 0xFL) != 0L) { offset += 4; continue }
            val allowedTarget = ValEncoders.decodeCondBranchTarget(branchWord, (offset + 4).toLong()) ?: run { offset += 4; continue }
            val allowedOffset = allowedTarget.toInt()
            val deniedOffset = offset + 8
            if (allowedOffset in functionStart..functionEnd && deniedOffset <= functionEnd) {
                return Quad(preHookOffset, offset + 4, allowedOffset, deniedOffset)
            }
            offset += 4
        }
        throw IllegalArgumentException("erase command handler gate not found: bl; cmn w0,#1; b.eq")
    }

    private val ADD_SKIP_COLON_RE = Regex("""^add\s+x(\d+),\s*x(\d+),\s*#1$""")
    private val CBNZ_W8_RE = Regex("""\bcbn?z\s+w8\b""")
    private val MOV_W20_1_RE = Regex("""^mov\s+w20,\s*#1$""")
    private val MOV_W20_3_RE = Regex("""^mov\s+w20,\s*#3$""")

    fun findAarch64FirstSkipColonRegisters(check: JsonObj): Pair<Int, Int>? {
        for (item in check.arr("structural_instructions").filterIsInstance<JsonObj>()) {
            if (item.str("role") != "partition_name_skip_colon") continue
            val instruction = item.str("instruction", "") ?: ""
            val m = ADD_SKIP_COLON_RE.find(instruction) ?: continue
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return null
    }

    fun hasAarch64PostGateNameFilter(check: JsonObj, gateHookOffset: Int): Boolean {
        for (item in check.arr("structural_instructions").filterIsInstance<JsonObj>()) {
            val offset = try {
                ValAnalysis.parseHxdOffset(item.str("hxd_offset") ?: continue)
            } catch (e: Exception) {
                continue
            }
            if (offset <= gateHookOffset) continue
            if (CBNZ_W8_RE.containsMatchIn(item.str("instruction", "") ?: "")) return true
        }
        return false
    }

    fun findAarch64DirectAllowedReturn(check: JsonObj, gateHookOffset: Int): Int? {
        val candidates = mutableListOf<Int>()
        for (item in check.arr("structural_instructions").filterIsInstance<JsonObj>()) {
            if (item.str("role") != "return_status_value") continue
            if (!MOV_W20_1_RE.containsMatchIn(item.str("instruction", "") ?: "")) continue
            val offset = try {
                ValAnalysis.parseHxdOffset(item.str("hxd_offset") ?: continue)
            } catch (e: Exception) {
                continue
            }
            if (offset > gateHookOffset + 4) candidates.add(offset)
        }
        return candidates.lastOrNull()
    }

    fun findAarch64DirectDeniedReturn(check: JsonObj, gateHookOffset: Int): Int? {
        val candidates = mutableListOf<Int>()
        for (item in check.arr("structural_instructions").filterIsInstance<JsonObj>()) {
            if (item.str("role") != "return_status_value") continue
            if (!MOV_W20_3_RE.containsMatchIn(item.str("instruction", "") ?: "")) continue
            val offset = try {
                ValAnalysis.parseHxdOffset(item.str("hxd_offset") ?: continue)
            } catch (e: Exception) {
                continue
            }
            if (offset > gateHookOffset + 4) candidates.add(offset)
        }
        return candidates.lastOrNull()
    }

    fun patchEraseTokenGate(data: ByteArray, analysisDir: File, partition: String, constants: ByteArray, caveOffset: Int? = null): Map<String, Any?> {
        val check = ValAnalysis.loadErasePermissionEntry(analysisDir, data)
        val functionEndOffset = ValAnalysis.parseHxdOffset(check.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        var commandHandlerMode = false
        var preHookOffset: Int
        var gateHookOffset: Int
        var allowedTarget: Int
        var deniedTarget: Int
        try {
            val hooks = findEraseCommandTokenHooks(data, check)
            preHookOffset = hooks.first
            gateHookOffset = hooks.second
            allowedTarget = hooks.third
            deniedTarget = hooks.fourth
            commandHandlerMode = true
        } catch (e: IllegalArgumentException) {
            val hooks = findEraseTokenHooks(check)
            preHookOffset = hooks.first
            gateHookOffset = hooks.second
            deniedTarget = hooks.third
            allowedTarget = gateHookOffset + 4
        }
        require(preHookOffset >= 0 && preHookOffset + 4 <= data.size) { "pre-token hook out of file: 0x${preHookOffset.toString(16)}" }
        require(gateHookOffset >= 0 && gateHookOffset + 4 <= data.size) { "gate hook out of file: 0x${gateHookOffset.toString(16)}" }
        if (commandHandlerMode && (allowedTarget < 0 || allowedTarget + 4 > data.size)) throw IllegalArgumentException("late-token hook outside the file: 0x${allowedTarget.toString(16)}")
        val partitionRaw = partition.toByteArray(Charsets.US_ASCII)
        val preOriginal = data.copyOfRange(preHookOffset, preHookOffset + 4)
        val gateOriginal = data.copyOfRange(gateHookOffset, gateHookOffset + 4)
        val lateOriginal: ByteArray? = if (commandHandlerMode) data.copyOfRange(allowedTarget, allowedTarget + 4) else null
        val skipRegs = findAarch64FirstSkipColonRegisters(check)
        var prePointerReg = 19
        var gatePointerReg: Int? = 19
        var gateArgumentReg: Int? = null
        var tokenAllowedTarget = allowedTarget
        var tokenAllowedNote = if (commandHandlerMode) "command_erase_path" else "fallthrough"
        var tokenDeniedTarget = deniedTarget
        var tokenDeniedNote = if (commandHandlerMode) "command_deny_path" else "original_error"
        if (skipRegs != null) {
            val (skipDestReg, skipSourceReg) = skipRegs
            prePointerReg = skipDestReg
            val movedReg = ValEncoders.decodeMovReg64(preOriginal)
            if (movedReg != null && movedReg.first == 0 && movedReg.second == skipDestReg) {
                gatePointerReg = skipDestReg
                gateArgumentReg = null
            } else if (skipDestReg != 0 && skipDestReg in 19..28) {
                gatePointerReg = skipDestReg
                gateArgumentReg = null
            } else {
                gatePointerReg = null
                gateArgumentReg = skipSourceReg
            }
        }
        if (!commandHandlerMode && hasAarch64PostGateNameFilter(check, gateHookOffset)) {
            val directAllowed = findAarch64DirectAllowedReturn(check, gateHookOffset)
            if (directAllowed != null) {
                tokenAllowedTarget = directAllowed
                tokenAllowedNote = "direct_return"
            }
        }
        if (!commandHandlerMode) {
            val directDenied = findAarch64DirectDeniedReturn(check, gateHookOffset)
            if (directDenied != null) {
                tokenDeniedTarget = directDenied
                tokenDeniedNote = "direct_return"
            }
        }
        val gatePtrForDummy: Int = gateArgumentReg ?: (gatePointerReg ?: prePointerReg)
        val preDummy: ByteArray
        val gateDummy: ByteArray
        val lateDummy: ByteArray
        if (commandHandlerMode) {
            preDummy = ValStubs.buildEraseTokenPreStubFromPointer(preHookOffset, preHookOffset + 0x100, preOriginal, partitionRaw, constants, pointerReg = prePointerReg)
            gateDummy = ValStubs.buildEraseCommandGateStubFromPointer(gateHookOffset, gateHookOffset + 0x200, tokenAllowedTarget, tokenDeniedTarget, partitionRaw, pointerReg = gatePtrForDummy, pointerFromArgument = gateArgumentReg != null)
            lateDummy = ValStubs.buildEraseCommandLateNameStub(allowedTarget, gateHookOffset + 0x400, lateOriginal ?: ByteArray(0), partitionRaw, constants)
        } else if (gateArgumentReg != null) {
            preDummy = ValStubs.buildEraseTokenPreStubFromPointer(preHookOffset, preHookOffset + 0x100, preOriginal, partitionRaw, constants, pointerReg = prePointerReg)
            gateDummy = ValStubs.buildEraseTokenGateStubFromArgument(gateHookOffset, gateHookOffset + 0x200, allowedTarget, deniedTarget, partitionRaw, argumentReg = gateArgumentReg, tokenAllowedTarget = tokenAllowedTarget, tokenDeniedTarget = tokenDeniedTarget)
            lateDummy = ByteArray(0)
        } else {
            preDummy = ValStubs.buildEraseTokenPreStubFromPointer(preHookOffset, preHookOffset + 0x100, preOriginal, partitionRaw, constants, pointerReg = prePointerReg)
            gateDummy = ValStubs.buildEraseTokenGateStubFromPointer(gateHookOffset, gateHookOffset + 0x200, allowedTarget, deniedTarget, partitionRaw, pointerReg = gatePointerReg ?: 19, tokenAllowedTarget = tokenAllowedTarget, tokenDeniedTarget = tokenDeniedTarget)
            lateDummy = ByteArray(0)
        }
        val totalSize = preDummy.size + gateDummy.size + lateDummy.size
        val cave = caveOffset ?: ValTokens.findZeroCave(data, totalSize, functionEndOffset)
        ValTokens.requireZeroCave(data, cave, totalSize)
        val preStub = ValStubs.buildEraseTokenPreStubFromPointer(preHookOffset, cave, preOriginal, partitionRaw, constants, pointerReg = prePointerReg)
        val gateCaveOffset = cave + preStub.size
        val gateStub: ByteArray
        var lateCaveOffset: Int? = null
        var lateStub: ByteArray? = null
        if (commandHandlerMode) {
            gateStub = ValStubs.buildEraseCommandGateStubFromPointer(gateHookOffset, gateCaveOffset, tokenAllowedTarget, tokenDeniedTarget, partitionRaw, pointerReg = gatePtrForDummy, pointerFromArgument = gateArgumentReg != null)
            lateCaveOffset = gateCaveOffset + gateStub.size
            lateStub = ValStubs.buildEraseCommandLateNameStub(allowedTarget, lateCaveOffset, lateOriginal ?: ByteArray(0), partitionRaw, constants)
        } else if (gateArgumentReg != null) {
            gateStub = ValStubs.buildEraseTokenGateStubFromArgument(gateHookOffset, gateCaveOffset, allowedTarget, deniedTarget, partitionRaw, argumentReg = gateArgumentReg, tokenAllowedTarget = tokenAllowedTarget, tokenDeniedTarget = tokenDeniedTarget)
            // NOTE: upstream Python never binds late_stub on this path; its
            // return dict then raises NameError. Mirror that refusal exactly.
            throw IllegalStateException("patch_erase_token_gate: late_stub unbound on gate-argument path (mirrors upstream NameError)")
        } else {
            gateStub = ValStubs.buildEraseTokenGateStubFromPointer(gateHookOffset, gateCaveOffset, allowedTarget, deniedTarget, partitionRaw, pointerReg = gatePointerReg ?: 19, tokenAllowedTarget = tokenAllowedTarget, tokenDeniedTarget = tokenDeniedTarget)
            lateCaveOffset = null
            lateStub = ByteArray(0)
        }
        val preBranch = ValEncoders.branchImm26(preHookOffset.toLong(), cave.toLong())
        val gateBranch = ValEncoders.branchImm26(gateHookOffset.toLong(), gateCaveOffset.toLong())
        val lateBranch: ByteArray? = if (commandHandlerMode && lateCaveOffset != null) ValEncoders.branchImm26(allowedTarget.toLong(), lateCaveOffset.toLong()) else null
        preStub.copyInto(data, cave)
        gateStub.copyInto(data, gateCaveOffset)
        if (commandHandlerMode && lateCaveOffset != null && lateStub != null) lateStub.copyInto(data, lateCaveOffset)
        preBranch.copyInto(data, preHookOffset)
        gateBranch.copyInto(data, gateHookOffset)
        if (commandHandlerMode && lateBranch != null) lateBranch.copyInto(data, allowedTarget)
        val flowAudits = mutableMapOf<String, Any?>()
        if (tokenAllowedNote == "direct_return") flowAudits["token_allow"] = ValPatchCore.auditAarch64DirectReturnPath(data, tokenAllowedTarget, functionEndOffset)
        if (tokenDeniedNote == "direct_return") flowAudits["token_deny"] = ValPatchCore.auditAarch64DirectReturnPath(data, tokenDeniedTarget, functionEndOffset)
        return mapOf(
            "partition" to partition,
            "pre_hook_offset" to preHookOffset, "gate_hook_offset" to gateHookOffset,
            "denied_target" to deniedTarget, "allowed_target" to allowedTarget,
            "token_allowed_target" to tokenAllowedTarget, "token_allowed_note" to tokenAllowedNote,
            "token_denied_target" to tokenDeniedTarget, "token_denied_note" to tokenDeniedNote,
            "cave_offset" to cave, "gate_cave_offset" to gateCaveOffset,
            "late_hook_offset" to if (commandHandlerMode) allowedTarget else null,
            "late_cave_offset" to if (commandHandlerMode) lateCaveOffset else null,
            "pre_stub_size" to preStub.size, "gate_stub_size" to gateStub.size,
            "late_stub_size" to (lateStub?.size ?: 0),
            "stub_size" to (preStub.size + gateStub.size + (lateStub?.size ?: 0)),
            "pre_original" to preOriginal, "gate_original" to gateOriginal,
            "late_original" to lateOriginal,
            "pre_branch" to preBranch, "gate_branch" to gateBranch, "late_branch" to lateBranch,
            "flow_audits" to flowAudits,
            "erase_token_mode" to if (commandHandlerMode) "command_handler" else "permission_validator",
            "argument_register" to if (gateArgumentReg != null) "x$gateArgumentReg" else null,
            "pre_pointer_register" to "x$prePointerReg",
            "gate_pointer_expression" to if (gateArgumentReg != null) "x${gateArgumentReg}+1" else "x${gatePointerReg ?: 19}",
            "function_start" to check.str("function_start"),
            "function_end" to check.str("function_end"),
        )
    }

    fun findThumbEraseTokenHooks(data: ByteArray, check: JsonObj): Triple<Int, Int, Int> {
        val functionStart = ValAnalysis.parseHxdOffset(check.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEnd = ValAnalysis.parseHxdOffset(check.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        val functionBytes = data.copyOfRange(functionStart, functionEnd)
        val preRel = ValBytes.find(functionBytes, ValBytes.fromHex("e4684822"))
        if (preRel < 0) throw IllegalArgumentException("erase_permission argument Thumb hook not found (e4684822)")
        val preHookOffset = functionStart + preRel
        var gateHookOffset: Int? = null
        var deniedTarget: Int? = null
        for (edge in check.arr("denied_edges").filterIsInstance<JsonObj>()) {
            if (edge.str("mnemonic") == "cbz") {
                gateHookOffset = ValAnalysis.parseHxdOffset(edge.str("branch_hxd_offset") ?: continue)
                deniedTarget = ValAnalysis.parseHxdOffset(edge.str("target_hxd_offset") ?: continue)
                break
            }
        }
        if (gateHookOffset == null || deniedTarget == null) throw IllegalArgumentException("Thumb cbz gate to erase permission denied not found")
        return Triple(preHookOffset, gateHookOffset, deniedTarget)
    }

    fun patchEraseTokenGateThumb(data: ByteArray, analysisDir: File, partition: String, constants: ByteArray, caveOffset: Int? = null): Map<String, Any?> {
        val check = ValAnalysis.loadErasePermissionEntry(analysisDir)
        val functionEndOffset = ValAnalysis.parseHxdOffset(check.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        val (preHookOffset, gateHookOffset, deniedTarget) = findThumbEraseTokenHooks(data, check)
        val allowedTarget = gateHookOffset + 4
        require(preHookOffset >= 0 && preHookOffset + 4 <= data.size) { "pre-token Thumb hook out of file: 0x${preHookOffset.toString(16)}" }
        require(gateHookOffset >= 0 && gateHookOffset + 4 <= data.size) { "gate Thumb hook out of file: 0x${gateHookOffset.toString(16)}" }
        val partitionRaw = partition.toByteArray(Charsets.US_ASCII)
        val preOriginal = data.copyOfRange(preHookOffset, preHookOffset + 4)
        val gateOriginal = data.copyOfRange(gateHookOffset, gateHookOffset + 4)
        require(preOriginal.contentEquals(ValBytes.fromHex("e4684822"))) { "hook pre-token Thumb inesperado: 0x${preHookOffset.toString(16)} contiene ${preOriginal.hex()}" }
        require(gateOriginal.copyOfRange(0, 2).contentEquals(ValBytes.fromHex("40b1"))) { "hook gate Thumb inesperado: 0x${gateHookOffset.toString(16)} contiene ${gateOriginal.hex()}" }
        val preDummy = ValStubs.buildThumbEraseTokenPreStub(preHookOffset, preHookOffset + 0x100, partitionRaw, constants)
        val gateDummy = ValStubs.buildThumbEraseTokenGateStub(gateHookOffset, gateHookOffset + 0x200, allowedTarget, deniedTarget, partitionRaw)
        val totalSize = preDummy.size + gateDummy.size
        val cave = caveOffset ?: ValTokens.findZeroCave(data, totalSize, functionEndOffset)
        ValTokens.requireZeroCave(data, cave, totalSize)
        val preStub = ValStubs.buildThumbEraseTokenPreStub(preHookOffset, cave, partitionRaw, constants)
        val gateCaveOffset = cave + preStub.size
        val gateStub = ValStubs.buildThumbEraseTokenGateStub(gateHookOffset, gateCaveOffset, allowedTarget, deniedTarget, partitionRaw)
        val preBranch = ValEncoders.thumbBW(preHookOffset, cave)
        val gateBranch = ValEncoders.thumbBW(gateHookOffset, gateCaveOffset)
        preStub.copyInto(data, cave)
        gateStub.copyInto(data, gateCaveOffset)
        preBranch.copyInto(data, preHookOffset)
        gateBranch.copyInto(data, gateHookOffset)
        return mapOf(
            "partition" to partition,
            "pre_hook_offset" to preHookOffset, "gate_hook_offset" to gateHookOffset,
            "allowed_target" to allowedTarget, "denied_target" to deniedTarget,
            "cave_offset" to cave, "gate_cave_offset" to gateCaveOffset,
            "stub_size" to (preStub.size + gateStub.size),
            "pre_stub_size" to preStub.size, "gate_stub_size" to gateStub.size,
            "pre_original" to preOriginal, "gate_original" to gateOriginal,
            "pre_branch" to preBranch, "gate_branch" to gateBranch,
            "function_start" to check.str("function_start"),
            "function_end" to check.str("function_end"),
        )
    }

    fun patchFrpCompare(data: ByteArray, offset: Int, value: Int): Pair<ByteArray, ByteArray> {
        val current = data.copyOfRange(offset, offset + 4)
        require(current.contentEquals(ValPatchCore.CMP_W8_ZERO) || current.contentEquals(ValPatchCore.CMP_W8_ONE)) {
            "offset 0x${offset.toString(16)} does not contain cmp w8,#0 or cmp w8,#1; contains ${current.hex()}"
        }
        val replacement = if (value == 1) ValPatchCore.CMP_W8_ONE else ValPatchCore.CMP_W8_ZERO
        replacement.copyInto(data, offset)
        return current to replacement
    }

    fun patchFrpCompareThumb(data: ByteArray, offset: Int, value: Int): Pair<ByteArray, ByteArray> {
        val current = data.copyOfRange(offset, offset + 6)
        require(current.contentEquals(ValPatchCore.THUMB_FRP_ZERO_BOOL) || current.contentEquals(ValPatchCore.THUMB_FRP_ONE_BOOL)) {
            "offset 0x${offset.toString(16)} does not contain the expected FRP Thumb pattern; contains ${current.hex()}"
        }
        val replacement = if (value == 1) ValPatchCore.THUMB_FRP_ONE_BOOL else ValPatchCore.THUMB_FRP_ZERO_BOOL
        replacement.copyInto(data, offset)
        return current to replacement
    }

    fun patchFrpSkipCall(data: ByteArray, offset: Int): Pair<ByteArray, ByteArray> {
        val current = data.copyOfRange(offset, offset + 4)
        require(current.size == 4) { "FRP/OEM offset out of file: 0x${offset.toString(16)}" }
        val word = ValBytes.u32At(current, 0)
        require((word and 0xFC000000L) == 0x94000000L) {
            "offset 0x${offset.toString(16)} does not contain BL AArch64 to FRP/OEM checker; contains ${current.hex()}"
        }
        ValPatchCore.MOV_W0_ZERO.copyInto(data, offset)
        return current to ValPatchCore.MOV_W0_ZERO
    }

    fun patchFrpSkipCallThumb(data: ByteArray, offset: Int): Pair<ByteArray, ByteArray> {
        val current = data.copyOfRange(offset, offset + 4)
        require(ValEncoders.isThumbBl(data, offset)) {
            "offset 0x${offset.toString(16)} does not contain BL Thumb to FRP/OEM checker; contains ${current.hex()}"
        }
        ValPatchCore.THUMB_MOVS_R0_ZERO_NOP.copyInto(data, offset)
        return current to ValPatchCore.THUMB_MOVS_R0_ZERO_NOP
    }

    fun patchKeyReturnSuccess(data: ByteArray, offset: Int): Pair<ByteArray, ByteArray> {
        val current = data.copyOfRange(offset, offset + 4)
        require(current.contentEquals(ValPatchCore.MOV_W0_W20) || current.contentEquals(ValPatchCore.MOV_W0_ONE)) {
            "offset 0x${offset.toString(16)} does not contain mov w0,w20 or mov w0,#1; contains ${current.hex()}"
        }
        ValPatchCore.MOV_W0_ONE.copyInto(data, offset)
        return current to ValPatchCore.MOV_W0_ONE
    }

    fun patchKeyEntryForceSuccess(data: ByteArray, analysisDir: File, caveOffset: Int? = null): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        val functionEndOffset = ValAnalysis.parseHxdOffset(validator.str("function_end_file_offset") ?: throw IllegalArgumentException("missing function_end_file_offset"))
        require(entryOffset >= 0 && entryOffset + 4 <= data.size) { "KEY offset out of file: 0x${entryOffset.toString(16)}" }
        val originalInstr = data.copyOfRange(entryOffset, entryOffset + 4)
        val stub = ValPatchCore.MOV_W0_ONE + ValPatchCore.RET
        val cave = caveOffset ?: ValTokens.findZeroCave(data, stub.size, functionEndOffset)
        ValTokens.requireZeroCave(data, cave, stub.size)
        val entryBranch = ValEncoders.branchImm26(entryOffset.toLong(), cave.toLong())
        stub.copyInto(data, cave)
        entryBranch.copyInto(data, entryOffset)
        return mapOf(
            "entry_offset" to entryOffset, "cave_offset" to cave, "stub_size" to stub.size,
            "original_instr" to originalInstr, "entry_branch" to entryBranch,
            "function_start" to validator.str("function_start"),
            "function_end" to validator.str("function_end"),
        )
    }

    fun patchKeyThumbForceSuccess(data: ByteArray, analysisDir: File): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val entryOffset = ValAnalysis.parseHxdOffset(validator.str("function_start_file_offset") ?: throw IllegalArgumentException("missing function_start_file_offset"))
        require(entryOffset >= 0 && entryOffset + 4 <= data.size) { "KEY offset out of file: 0x${entryOffset.toString(16)}" }
        val originalInstr = data.copyOfRange(entryOffset, entryOffset + 4)
        ValPatchCore.THUMB_MOVS_R0_ONE_BX_LR.copyInto(data, entryOffset)
        return mapOf(
            "entry_offset" to entryOffset, "original_instr" to originalInstr,
            "replacement" to ValPatchCore.THUMB_MOVS_R0_ONE_BX_LR,
            "function_start" to validator.str("function_start"),
            "function_end" to validator.str("function_end"),
        )
    }

    fun findAarch64NextBl(data: ByteArray, startOffset: Int, endOffset: Int): Int? {
        var offset = (startOffset + 3) and -4
        val end = minOf(endOffset, data.size - 4)
        while (offset <= end) {
            val word = ValBytes.u32At(data, offset)
            if ((word and 0xFC000000L) == 0x94000000L) return offset
            offset += 4
        }
        return null
    }

    data class DebugStubs(val blob: ByteArray, val stubOffsets: Map<String, Int>, val messageOffsets: Map<String, Int>)

    fun buildUnlockEraseDebugStubs(caveOffset: Int, eraseDebugOps: List<Map<String, Any?>>, doneTarget: Int, infoVa: Long, printFuncOffset: Int, messageVas: Map<String, Long>): DebugStubs {
        val code = mutableListOf<Byte>()
        fun emit(raw: ByteArray) { raw.forEach { code.add(it) } }
        fun pc() = caveOffset + code.size
        val stubOffsets = mutableMapOf<String, Int>()
        val messageOffsets = mutableMapOf<String, Int>()
        val messages = mutableListOf<Pair<String, ByteArray>>()
        fun emitPrint(messageName: String) {
            emit(ValEncoders.movImm64(0, infoVa))
            emit(ValEncoders.movImm64(1, messageVas[messageName] ?: caveOffset.toLong()))
            emit(ValEncoders.branchImm26(pc().toLong(), printFuncOffset.toLong(), 0x94000000L))
        }
        for ((index, operation) in eraseDebugOps.withIndex()) {
            val partition = operation["partition"] as String
            val messageName = "erase_${index}_$partition"
            stubOffsets[messageName] = code.size
            messages.add(messageName to ("LKDBG: erase $partition\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)))
            val callSite = operation["call_site_offset"] as Int
            val originalTarget = operation["original_target_offset"] as Int
            emit(ValEncoders.subImm64(31, 31, 0x30))
            emit(ValEncoders.strUnsigned(30, 31, 0x28, 64))
            emit(ValEncoders.strUnsigned(19, 31, 0x20, 64))
            emit(ValEncoders.movReg64(19, 0))
            emitPrint(messageName)
            emit(ValEncoders.movReg64(0, 19))
            emit(ValEncoders.branchImm26(pc().toLong(), originalTarget.toLong(), 0x94000000L))
            emit(ValEncoders.ldrUnsigned(19, 31, 0x20, 64))
            emit(ValEncoders.ldrUnsigned(30, 31, 0x28, 64))
            emit(ValEncoders.addImm64(31, 31, 0x30))
            emit(ValPatchCore.RET)
            require(callSite % 4 == 0) { "call_site debug not aligned: 0x${callSite.toString(16)}" }
        }
        val doneName = "erase_done"
        stubOffsets[doneName] = code.size
        messages.add(doneName to ("LKDBG: erase done\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)))
        emitPrint(doneName)
        emit(ValEncoders.branchImm26(pc().toLong(), doneTarget.toLong()))
        while (code.size % 16 != 0) emit(ValBytes.packU32(0xD503201FL))
        for ((name, raw) in messages) {
            messageOffsets[name] = code.size
            raw.forEach { code.add(it) }
        }
        while (code.size % 16 != 0) emit(byteArrayOf(0))
        return DebugStubs(code.toByteArray(), stubOffsets, messageOffsets)
    }

    fun patchUnlockFlowEraseOnly(data: ByteArray, analysisDir: File, debugLog: Boolean = false): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val base = ValAnalysis.loadBase(analysisDir)
        val callers = validator.arr("callers").filterIsInstance<JsonObj>()
        if (callers.isEmpty()) throw IllegalArgumentException("KEY validator has no cmd_oem_unlock caller")
        val caller = callers[0]
        val keyCallOffset = ValAnalysis.parseHxdOffset(caller.str("call_site_file_offset") ?: throw IllegalArgumentException("missing call_site_file_offset"))
        val callerStartOffset = ValAnalysis.parseHxdOffset(caller.str("caller_start_file_offset") ?: throw IllegalArgumentException("missing caller_start_file_offset"))
        require(keyCallOffset >= 0 && keyCallOffset + 12 <= data.size) { "KEY call out of file: 0x${keyCallOffset.toString(16)}" }
        val failureBranchOffset = keyCallOffset + 4
        val uiCallOffset = findAarch64NextBl(data, keyCallOffset + 8, keyCallOffset + 0x60)
            ?: throw IllegalArgumentException("ui_confirm_unlock call not found after KEY validator")
        val uiGateOffset = uiCallOffset + 4
        val uiGateWord = ValBytes.u32At(data, uiGateOffset)
        val silentReturnTarget = ValEncoders.decodeAarch64CbzTbzTarget(uiGateWord, uiGateOffset.toLong())?.toInt()
            ?: throw IllegalArgumentException("silent return gate not found after ui_confirm_unlock")
        val operations = ValAnalysis.loadPartitionEraseOperations(analysisDir)
        val eraseOps = operations.filter {
            it.str("operation") == "erase" &&
                ValAnalysis.parseHxdOffset(it.str("flow_start_file_offset", "-1") ?: "-1") == callerStartOffset &&
                ValAnalysis.parseHxdOffset(it.str("call_site_file_offset", "0") ?: "0") > uiCallOffset
        }
        if (eraseOps.isEmpty()) throw IllegalArgumentException("no partition erases found after ui_confirm_unlock")
        val sortedOps = eraseOps.sortedBy { ValAnalysis.parseHxdOffset(it.str("call_site_file_offset") ?: "0") }
        val firstCluster = mutableListOf<JsonObj>()
        val clusterStart = ValAnalysis.parseHxdOffset(sortedOps[0].str("call_site_file_offset") ?: "0")
        for (operation in sortedOps) {
            val callSite = ValAnalysis.parseHxdOffset(operation.str("call_site_file_offset") ?: "0")
            if (callSite - clusterStart > 0x120) break
            firstCluster.add(operation)
        }
        if (firstCluster.isEmpty()) throw IllegalArgumentException("could not build the first erase block")
        fun operationResultCheckOffset(operation: JsonObj): Int {
            return if (operation.map.containsKey("result_check_file_offset") && operation.map["result_check_file_offset"] != JsonNull) {
                ValAnalysis.parseHxdOffset(operation.str("result_check_file_offset") ?: "0")
            } else {
                (ValAnalysis.parseHexLong(operation.str("result_check_address") ?: "0") - base).toInt()
            }
        }
        val lastOperation = firstCluster.maxByOrNull { operationResultCheckOffset(it) }!!
        val returnHookOffset = operationResultCheckOffset(lastOperation) + 4
        val originalUiCall = data.copyOfRange(uiCallOffset, uiCallOffset + 4)
        val originalReturnHook = data.copyOfRange(returnHookOffset, returnHookOffset + 4)
        if ((ValBytes.u32At(originalUiCall, 0) and 0xFC000000L) != 0x94000000L) throw IllegalArgumentException("ui_confirm hook has no BL: 0x${uiCallOffset.toString(16)}")
        ValPatchCore.MOV_W0_ONE.copyInto(data, uiCallOffset)
        val debugPatch = mutableMapOf<String, Any?>()
        if (debugLog) {
            val debugTargets = ValAnalysis.loadFastbootInfoTargets(analysisDir, data)
            val eraseDebugOps = mutableListOf<Map<String, Any?>>()
            for (operation in firstCluster) {
                val callSite = ValAnalysis.parseHxdOffset(operation.str("call_site_file_offset") ?: "0")
                val callWord = ValBytes.u32At(data, callSite)
                val originalTarget = ValEncoders.decodeAarch64CallTarget(callWord, base + callSite)
                    ?: throw IllegalArgumentException("erase call has no BL at 0x${callSite.toString(16)}")
                var partition = operation.str("partition") ?: ""
                try {
                    val stringOffset = ValAnalysis.parseHxdOffset(operation.str("string_file_offset") ?: throw IllegalArgumentException("missing string"))
                    val currentPartition = String(ValBytes.readCString(data, stringOffset, 48), Charsets.US_ASCII)
                    if (currentPartition.isNotEmpty()) partition = currentPartition
                } catch (e: Exception) {
                }
                eraseDebugOps.add(
                    mapOf(
                        "partition" to partition, "call_site_offset" to callSite,
                        "original_target_offset" to (originalTarget - base).toInt(),
                    )
                )
            }
            val flowEndOffset = try {
                val flow = ValAnalysis.loadFlowForStart(analysisDir, base + callerStartOffset)
                (ValAnalysis.parseHexLong(flow.str("end_address") ?: "0") - base).toInt()
            } catch (e: Exception) {
                callerStartOffset + 0x1000
            }
            val dummyResult = buildUnlockEraseDebugStubs(
                returnHookOffset + 0x100, eraseDebugOps, silentReturnTarget,
                debugTargets["info_string_va"] ?: 0L, (debugTargets["print_func_offset"] ?: 0L).toInt(), emptyMap(),
            )
            val debugCaveOffset = ValTokens.findZeroCave(data, dummyResult.blob.size, flowEndOffset)
            ValTokens.requireZeroCave(data, debugCaveOffset, dummyResult.blob.size)
            val messageVas = dummyResult.messageOffsets.mapValues { (_, off) -> base + debugCaveOffset + off }
            val debugResult = buildUnlockEraseDebugStubs(
                debugCaveOffset, eraseDebugOps, silentReturnTarget,
                debugTargets["info_string_va"] ?: 0L, (debugTargets["print_func_offset"] ?: 0L).toInt(), messageVas,
            )
            debugResult.blob.copyInto(data, debugCaveOffset)
            for (operation in eraseDebugOps) {
                val messageName = debugResult.stubOffsets.keys.first {
                    it.startsWith("erase_") && it.endsWith("_${operation["partition"]}")
                }
                val callSite = operation["call_site_offset"] as Int
                ValEncoders.branchImm26(callSite.toLong(), (debugCaveOffset + debugResult.stubOffsets[messageName]!!).toLong(), 0x94000000L).copyInto(data, callSite)
            }
            val doneOffset = debugCaveOffset + debugResult.stubOffsets["erase_done"]!!
            ValEncoders.branchImm26(returnHookOffset.toLong(), doneOffset.toLong()).copyInto(data, returnHookOffset)
            debugPatch["debug_log"] = true
            debugPatch["debug_cave_offset"] = debugCaveOffset
            debugPatch["debug_stub_size"] = debugResult.blob.size
            debugPatch["debug_print_func_offset"] = (debugTargets["print_func_offset"] ?: 0L).toInt()
            debugPatch["debug_message_vas"] = messageVas
            debugPatch["debug_erase_ops"] = eraseDebugOps
        } else {
            ValEncoders.branchImm26(returnHookOffset.toLong(), silentReturnTarget.toLong()).copyInto(data, returnHookOffset)
        }
        val result = mutableMapOf<String, Any?>(
            "key_call_offset" to keyCallOffset,
            "failure_branch_offset" to failureBranchOffset,
            "ui_call_offset" to uiCallOffset,
            "ui_gate_offset" to uiGateOffset,
            "silent_return_target" to silentReturnTarget,
            "return_hook_offset" to returnHookOffset,
            "erase_partitions" to firstCluster.map { it.str("partition") ?: "" },
            "original_ui_call" to originalUiCall,
            "new_ui_call" to ValPatchCore.MOV_W0_ONE,
            "original_return_hook" to originalReturnHook,
            "new_return_hook" to data.copyOfRange(returnHookOffset, returnHookOffset + 4),
        )
        result.putAll(debugPatch)
        return result
    }

    fun findThumbUiConfirmCall(data: ByteArray, callerStartOffset: Int, keyCallOffset: Int): Int? {
        var offset = callerStartOffset
        val end = maxOf(callerStartOffset, keyCallOffset - 4)
        while (offset < end) {
            if (!ValEncoders.isThumbBl(data, offset)) { offset += 2; continue }
            if (!ValEncoders.isThumbCbnzR0(data, offset + 4)) { offset += 2; continue }
            if (!data.copyOfRange(offset + 6, offset + 8).contentEquals(ValPatchCore.THUMB_POP_R4_PC)) { offset += 2; continue }
            return offset
        }
        return null
    }

    fun patchUnlockFlowEraseOnlyThumb(data: ByteArray, analysisDir: File): Map<String, Any?> {
        val validator = ValAnalysis.loadKeyValidatorEntry(analysisDir)
        val base = ValAnalysis.loadBase(analysisDir)
        val callers = validator.arr("callers").filterIsInstance<JsonObj>()
        if (callers.isEmpty()) throw IllegalArgumentException("KEY validator has no cmd_oem_unlock caller")
        val caller = callers[0]
        val callerStartVa = ValAnalysis.parseHexLong(caller.str("caller_start") ?: "0")
        val callerStartOffset = ValAnalysis.parseHxdOffset(caller.str("caller_start_file_offset") ?: throw IllegalArgumentException("missing caller_start_file_offset"))
        val keyCallOffset = ValAnalysis.parseHxdOffset(caller.str("call_site_file_offset") ?: throw IllegalArgumentException("missing call_site_file_offset"))
        require(keyCallOffset >= 0 && keyCallOffset + 8 <= data.size) { "KEY call out of file: 0x${keyCallOffset.toString(16)}" }
        val uiCallOffset = findThumbUiConfirmCall(data, callerStartOffset, keyCallOffset)
            ?: throw IllegalArgumentException("Thumb ui_confirm_unlock pattern not found before KEY validator")
        val flow = ValAnalysis.loadFlowForStart(analysisDir, callerStartVa)
        val bootXrefs = flow.arr("xrefs").filterIsInstance<JsonObj>().filter {
            (it.str("target", "") ?: "").lowercase() == "bootloader is unlocked"
        }
        if (bootXrefs.isEmpty()) throw IllegalArgumentException("no Thumb xref to 'Bootloader is unlocked' found")
        val returnHookVa = ValAnalysis.parseHexLong(bootXrefs[0].str("xref_address") ?: "0")
        val returnHookOffset = (returnHookVa - base).toInt()
        require(returnHookOffset >= 0 && returnHookOffset + 2 <= data.size) { "return hook out of file: 0x${returnHookOffset.toString(16)}" }
        val mdUdcXrefs = flow.arr("xrefs").filterIsInstance<JsonObj>().filter {
            (it.str("target", "") ?: "").lowercase() == "md_udc" &&
                (it.str("instruction", "") ?: "").startsWith("add ")
        }
        val originalUiCall = data.copyOfRange(uiCallOffset, uiCallOffset + 4)
        val originalReturnHook = data.copyOfRange(returnHookOffset, returnHookOffset + 2)
        require(ValEncoders.isThumbBl(data, uiCallOffset)) { "ui_confirm hook does not contain BL Thumb: 0x${uiCallOffset.toString(16)}" }
        (ValEncoders.thumbMovsImm(0, 1) + ValPatchCore.THUMB_NOP).copyInto(data, uiCallOffset)
        ValPatchCore.THUMB_POP_R4_PC.copyInto(data, returnHookOffset)
        return mapOf(
            "key_call_offset" to keyCallOffset,
            "ui_call_offset" to uiCallOffset,
            "return_hook_offset" to returnHookOffset,
            "erase_partitions" to listOf("metadata", "userdata", "md_udc"),
            "md_udc_xrefs" to mdUdcXrefs.map {
                mapOf(
                    "xref_address" to it.str("xref_address"),
                    "string_address" to it.str("string_address"),
                    "instruction" to it.str("instruction"),
                    "via" to it.str("via"),
                )
            },
            "original_ui_call" to originalUiCall,
            "new_ui_call" to data.copyOfRange(uiCallOffset, uiCallOffset + 4),
            "original_return_hook" to originalReturnHook,
            "new_return_hook" to data.copyOfRange(returnHookOffset, returnHookOffset + 2),
        )
    }
}
