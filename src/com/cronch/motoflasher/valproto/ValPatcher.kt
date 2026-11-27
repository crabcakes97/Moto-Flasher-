// lk_patch_partition.py main() — option validation + patch dispatch.
// INPUT CONTRACT (liblk correction): the patch engine consumes the parsed
// LkImage (com.cronch.motoflasher.LibLk) plus the analysis-dir JSON — it
// works on `lkImage.partitions[imageName].data`, never on a re-parsed file
// or a raw byte array. Returns process-style exit codes (0 ok, 1
// patch/refusal error, 2 usage error) plus the patched payload bytes.
package com.cronch.motoflasher.valproto

import com.cronch.motoflasher.LibLk
import java.io.File

data class PatchOptions(
    val analysisDir: File,
    /** Subimage to patch inside the LkImage (default "lk"). */
    val imageName: String = "lk",
    val output: File? = null,
    val erasePartition: String? = null,
    val oldName: String? = null,
    val newName: String? = null,
    val frpCompareValue: Int? = null,
    val frpSkipCheck: Boolean = false,
    val keyForceSuccess: Boolean = false,
    val keyCustomSignature: String? = null,
    val keyCaveOffset: String? = null,
    val keyTokenSecret: String? = null,
    val keyTokenDevice: String = "",
    val keyTokenImei: String? = null,
    val keyTokenRuntimeSerial: Boolean = false,
    val keyTokenDebugLog: Boolean = false,
    val keyTokenDebugStop: String? = null,
    val keyTokenAlphabet: String = "alnum",
    val eraseTokenPartition: String? = null,
    val eraseTokenSecret: String? = null,
    val eraseTokenDevice: String = "",
    val eraseTokenImei: String? = null,
    val eraseTokenAlphabet: String = "alnum",
    val eraseTokenCaveOffset: String? = null,
    val unlockEraseOnly: Boolean = false,
    val modemResearchReportOnly: Boolean = false,
    val ramdumpResearchReportOnly: Boolean = false,
    val modemSizeBypass: Boolean = false,
    val modemAllowUnsafe: Boolean = false,
    val factoryAllow: Boolean = false,
    val factoryAllowUnsafe: Boolean = false,
    val factoryForce: Boolean = false,
    val factoryForceUnsafe: Boolean = false,
    val bootmodeCmdline: Boolean = false,
    val bootmodeCmdlineUnsafe: Boolean = false,
    val mrdumpForce: Boolean = false,
    val mrdumpForceUnsafe: Boolean = false,
    val lockspoof: Boolean = false,
    val lockspoofUnsafe: Boolean = false,
    val ssmBypass: Boolean = false,
    val ssmBypassUnsafe: Boolean = false,
    val experimental: Boolean = false,
    val apply: Boolean = true,
)

object ValPatcher {
    private fun err(msg: String) = System.err.println("Error: $msg")

    private fun resolvePartitionPatchNames(opts: PatchOptions): Pair<String, String>? {
        if (opts.erasePartition != null) {
            if (opts.oldName != null || opts.newName != null) throw IllegalArgumentException("use --erase-partition alone, or --from/--to; do not mix both modes")
            return "nvdata" to opts.erasePartition.trim()
        }
        if (opts.oldName != null || opts.newName != null) {
            if (opts.oldName == null || opts.newName == null) throw IllegalArgumentException("give both: --from <old> --to <new>")
            return opts.oldName.trim() to opts.newName.trim()
        }
        return null
    }

    private fun writeOutputIfRequested(outputPath: File, data: ByteArray, apply: Boolean): Int {
        if (!apply) {
            println("Dry-run : no file written. Use --apply to write the patched binary.")
            return 0
        }
        if (outputPath.exists()) {
            val name = outputPath.name
            val dot = name.lastIndexOf('.')
            val backupName = if (dot >= 0) name.substring(0, dot) + name.substring(dot) + ".bak" else name + ".bak"
            // Python: with_suffix(suffix + ".bak") replaces the last suffix.
            val backup = File(outputPath.parentFile, backupName)
            outputPath.copyTo(backup, overwrite = true)
            println("Backup  : $backup")
        }
        outputPath.writeBytes(data)
        println("Status  : patched file written")
        return 0
    }

    data class PatchRunResult(val exitCode: Int, val patchedPayload: ByteArray?)

    /** Patch the [opts.imageName] payload from [lkImage] using the
     *  analysis-dir JSON. The payload comes from the parsed container
     *  (`lkImage.partitions[imageName].data`); the file is never re-parsed.
     *  Returns the patched payload bytes (same size as the input) on exit 0,
     *  else null. When opts.apply is true the patched bin is also written to
     *  opts.output (default `<analysisDir>/<imageName>.patched.bin`), with
     *  the .bak backup behaviour of the Python tool. */
    fun runPatch(lkImage: LibLk.LkImage, opts: PatchOptions): PatchRunResult {
        val part = lkImage.partitions.firstOrNull { it.name == opts.imageName }
        if (part == null) {
            err("no partition '${opts.imageName}' in the LkImage (have: ${lkImage.partitions.map { it.name }.sorted().pyStrListRepr()})")
            return PatchRunResult(2, null)
        }
        val outputPath = opts.output ?: File(opts.analysisDir, "${opts.imageName}.patched.bin")
        val data = part.data.copyOf()
        val rc = runPatchOnData(
            data, opts, outputPath,
            "LkImage partition '${opts.imageName}' (${data.size} bytes @ container offset 0x${part.offset.toString(16)})",
        )
        return PatchRunResult(rc, if (rc == 0) data else null)
    }

    private fun runPatchOnData(data: ByteArray, opts: PatchOptions, outputPath: File, inputLabel: String): Int {
        var partitionPatch: Pair<String, String>?
        var keyTokenDevice: String
        var keyTokenDeviceKind: String
        var eraseTokenDevice: String
        var eraseTokenDeviceKind: String
        var eraseTokenPartitionParsed: String?
        try {
            partitionPatch = resolvePartitionPatchNames(opts)
            val (kd, kdk) = ValAnalysis.resolveDeviceOrImei(opts.keyTokenDevice, opts.keyTokenImei, "key-token")
            keyTokenDevice = kd; keyTokenDeviceKind = kdk
            val (ed, edk) = ValAnalysis.resolveDeviceOrImei(opts.eraseTokenDevice, opts.eraseTokenImei, "erase-token")
            eraseTokenDevice = ed; eraseTokenDeviceKind = edk
            eraseTokenPartitionParsed = if (opts.eraseTokenPartition != null) ValAnalysis.parsePartitionName(opts.eraseTokenPartition) else null
        } catch (e: Exception) {
            err("${e.message}")
            return 2
        }
        if (eraseTokenPartitionParsed != null && opts.eraseTokenSecret == null) {
            err("use --erase-token-secret together with --erase-token-partition.")
            return 2
        }
        if (opts.eraseTokenSecret != null && eraseTokenPartitionParsed == null) {
            err("use --erase-token-partition together with --erase-token-secret.")
            return 2
        }
        if (opts.keyTokenRuntimeSerial && opts.keyTokenSecret == null) {
            err("use --key-token-runtime-serial together with --key-token-secret.")
            return 2
        }
        if (opts.keyTokenDebugLog && !opts.keyTokenRuntimeSerial) {
            err("--key-token-debug-log only applies with --key-token-runtime-serial.")
            return 2
        }
        if (opts.keyTokenDebugStop != null && opts.keyTokenSecret == null) {
            err("--key-token-debug-stop needs --key-token-secret.")
            return 2
        }
        if (opts.keyTokenDebugStop != null && opts.keyTokenDebugStop != "caller_before_key" && !opts.keyTokenRuntimeSerial) {
            err("--key-token-debug-stop other than caller_before_key needs --key-token-runtime-serial.")
            return 2
        }
        val keyModes = listOf(opts.keyForceSuccess, opts.keyCustomSignature != null, opts.keyTokenSecret != null).count { it }
        if (keyModes > 1) {
            err("--key-force-success, --key-custom-signature and --key-token-secret change the same KEY entry; use only one.")
            return 2
        }
        if (opts.frpSkipCheck && opts.frpCompareValue != null) {
            err("usa solo uno: --frp-skip-check o --frp-compare-value.")
            return 2
        }
        if (partitionPatch == null && !opts.frpSkipCheck && opts.frpCompareValue == null &&
            !opts.keyForceSuccess && opts.keyCustomSignature == null && opts.keyTokenSecret == null &&
            eraseTokenPartitionParsed == null && !opts.unlockEraseOnly &&
            !opts.modemResearchReportOnly && !opts.ramdumpResearchReportOnly &&
            !opts.modemSizeBypass && !opts.factoryAllow && !opts.factoryForce &&
            !opts.bootmodeCmdline && !opts.mrdumpForce && !opts.lockspoof && !opts.ssmBypass
        ) {
            err("use --erase-partition <name>, --from/--to, --frp-skip-check, --frp-compare-value, --key-force-success, --key-custom-signature, --key-token-secret, --erase-token-partition, --unlock-erase-only, --modem-research-report-only, --ramdump-research-report-only, --modem-size-bypass, --factory-allow, --factory-force, --ssm-bypass, or a combination.")
            return 2
        }
        if (opts.modemSizeBypass && !opts.modemAllowUnsafe) {
            err("--modem-size-bypass requires --modem-allow-unsafe (read the modem-unlock preset risk notes).")
            return 2
        }
        if (opts.factoryAllow && !opts.factoryAllowUnsafe) { err("--factory-allow requires --factory-allow-unsafe."); return 2 }
        if (opts.factoryForce && !opts.factoryForceUnsafe) { err("--factory-force requires --factory-force-unsafe."); return 2 }
        if (opts.bootmodeCmdline && !opts.bootmodeCmdlineUnsafe) { err("--bootmode-cmdline requires --bootmode-cmdline-unsafe."); return 2 }
        if (opts.mrdumpForce && !opts.mrdumpForceUnsafe) { err("--mrdump-force requires --mrdump-force-unsafe."); return 2 }
        if (opts.lockspoof && !opts.lockspoofUnsafe) { err("--lockspoof requires --lockspoof-unsafe."); return 2 }
        if (opts.ssmBypass && !opts.ssmBypassUnsafe) { err("--ssm-bypass requires --ssm-bypass-unsafe."); return 2 }

        val architecture = ValAnalysis.loadArchitecture(opts.analysisDir)
        if (opts.keyTokenRuntimeSerial && architecture !in setOf("aarch64", "arm32_thumb")) {
            err("--key-token-runtime-serial is experimental and only implemented for AArch64/ARM32 Thumb; analysis detected '$architecture'.")
            return 1
        }
        if (opts.keyTokenDebugStop != null && architecture != "aarch64") {
            err("--key-token-debug-stop is only implemented for AArch64; analysis detected '$architecture'.")
            return 1
        }
        if (architecture != "aarch64") {
            val unsupported = mutableListOf<String>()
            if (opts.keyCustomSignature != null) unsupported.add("--key-custom-signature")
            if (unsupported.isNotEmpty()) {
                err("el analisis detecto arquitectura '$architecture'; estos parches aun usan stubs AArch64: " + unsupported.joinToString(", "))
                return 1
            }
        }
        if (architecture == "arm32_thumb" && opts.erasePartition != null && partitionPatch != null && partitionPatch.first == "nvdata") {
            partitionPatch = "md_udc" to partitionPatch.second
        }
        println("Input   : $inputLabel")
        println("Output  : $outputPath")

        if (opts.modemResearchReportOnly) {
            val conflicting = listOf(
                partitionPatch != null, opts.frpSkipCheck, opts.frpCompareValue != null,
                opts.keyForceSuccess, opts.keyCustomSignature != null, opts.keyTokenSecret != null,
                eraseTokenPartitionParsed != null, opts.unlockEraseOnly, opts.modemSizeBypass,
                opts.factoryAllow, opts.factoryForce, opts.bootmodeCmdline, opts.mrdumpForce,
                opts.lockspoof, opts.ssmBypass, opts.ramdumpResearchReportOnly,
            )
            if (conflicting.any { it }) {
                err("--modem-research-report-only does not combine with other patches; report only.")
                return 2
            }
            ValPatchCore.printModemResearchReport(data, opts.analysisDir)
            return writeOutputIfRequested(outputPath, data, opts.apply)
        }
        if (opts.ramdumpResearchReportOnly) {
            val conflicting = listOf(
                partitionPatch != null, opts.frpSkipCheck, opts.frpCompareValue != null,
                opts.keyForceSuccess, opts.keyCustomSignature != null, opts.keyTokenSecret != null,
                eraseTokenPartitionParsed != null, opts.unlockEraseOnly, opts.modemResearchReportOnly,
                opts.modemSizeBypass, opts.factoryAllow, opts.factoryForce, opts.bootmodeCmdline,
                opts.mrdumpForce, opts.lockspoof, opts.ssmBypass,
            )
            if (conflicting.any { it }) {
                err("--ramdump-research-report-only does not combine with other patches; report only.")
                return 2
            }
            ValReports.printRamdumpResearchReport(data, opts.analysisDir)
            return writeOutputIfRequested(outputPath, data, opts.apply)
        }

        if (opts.modemSizeBypass) {
            try {
                val applied = ValPatchCore.applyDiscoveredGates(data, opts.analysisDir, "modem", opts.experimental)
                println("ModemGates: ${applied.size} LK-side MD validation gates -> NOP")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
            println("Warn    : this does NOT bypass modem signature checks (verified outside LK),")
            println("Warn    : does NOT remap MMU/EMI-MPU (kernel rebuilds it), does NOT touch watchdog,")
            println("Warn    : does NOT give EL3. An MD image with out-of-range regions can")
            println("Warn    : corrupt LK memory -> bootloop with dead USB. Have recovery ready.")
        }
        if (opts.factoryAllow) {
            try {
                val applied = ValPatchCore.applyDiscoveredGates(data, opts.analysisDir, "factory", opts.experimental)
                println("FactoryGates: ${applied.size} restriction gates -> NOP")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
            println("Warn    : each gate was resolved per-image with verification; other")
            println("Warn    : nearby checks may still deny. The on-device test")
            println("Warn    : decides: 'oem ramdump' without 'restricted' = win.")
        }
        if (opts.factoryForce) {
            try {
                val applied = ValPatchCore.applyDiscoveredGates(data, opts.analysisDir, "force", opts.experimental)
                println("ForceGates: ${applied.size} factory-force gates -> B/NOP")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
            println("Warn    : auto-factory on every boot of this LK; keep USB")
            println("Warn    : connected (factory_kill_timeout powers off without USB).")
            println("Warn    : slot B only; go back with --set-active=a.")
        }
        if (opts.bootmodeCmdline) {
            try {
                val applied = ValPatchCore.applyBootmodeCmdline(data, opts.analysisDir, opts.experimental)
                println("BootmodeGates: ${applied.size} cmdline hook + cave + str")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
            println("Warn    : init sets ro.bootmode=factory from cmdline; verify")
            println("Warn    : with cat /proc/cmdline in Android.")
        }
        if (opts.mrdumpForce) {
            try {
                val applied = ValPatchCore.applyDiscoveredGates(data, opts.analysisDir, "mrdump", opts.experimental)
                println("MrdumpGates: ${applied.size} mrdump force gates")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
            println("Warn    : crash dumps go to internal-storage fullmem; a real")
            println("Warn    : crash can take a while and fill flash. Trigger = watchdog.")
        }
        if (opts.lockspoof) {
            try {
                val applied = ValPatchCore.applyLockspoof(data, opts.analysisDir, opts.experimental)
                println("LockspoofGates: ${applied.size} hook + cave (report-only)")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }
        if (opts.ssmBypass) {
            try {
                val applied = ValPatchCore.applySsmBypass(data, opts.analysisDir, opts.experimental)
                println("SsmGates: ${applied.size} patches (dispatcher ungate + 2 enforcement gates)")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
            println("Warn    : UNTESTED: live-run 'oem disable-verity' + 'oem disable-thinkshield'")
            println("Warn    : to learn each handler's own checks. Slot B first.")
        }

        if (partitionPatch != null) {
            val (oldName, newName) = partitionPatch
            if (oldName.isEmpty() || newName.isEmpty()) {
                err("partition names must not be empty.")
                return 2
            }
            if (oldName.any { it.code > 127 } || newName.any { it.code > 127 }) {
                err("use ASCII names for partitions.")
                return 2
            }
            var offsets: List<Int> = emptyList()
            try {
                val entries = ValAnalysis.loadPartitionArgOffsets(opts.analysisDir, oldName)
                offsets = entries.map { ValAnalysis.parseHxdOffset(it.str("hxd_offset") ?: "0") }.toSortedSet().toList()
                for (offset in offsets) ValPatchOps.patchBytes(data, offset, oldName, newName)
            } catch (e: Exception) {
                if (architecture == "arm32_thumb") {
                    println("Skip    : '$oldName' -> '$newName' (${e.message})")
                    offsets = emptyList()
                } else {
                    err("${e.message}")
                    return 1
                }
            }
            if (offsets.isNotEmpty()) {
                println("Patch   : '$oldName' -> '$newName'")
                println("Offsets : " + offsets.joinToString(", ") { "0x${it.toString(16)} / HxD ${"%08X".format(it)}" })
            }
        }

        if (opts.frpSkipCheck) {
            try {
                val offsets = ValAnalysis.frpSkipCallOffsets(opts.analysisDir)
                val patches = if (architecture == "arm32_thumb") {
                    offsets.map { ValPatchOps.patchFrpSkipCallThumb(data, it) }
                } else {
                    offsets.map { ValPatchOps.patchFrpSkipCall(data, it) }
                }
                println("Patch   : FRP/OEM checker call skip")
                for ((offset, patch) in offsets.zip(patches)) {
                    println("Call    : 0x${offset.toString(16)} / HxD ${"%08X".format(offset)}  ${patch.first.hex()} -> ${patch.second.hex()}")
                }
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }

        if (opts.frpCompareValue != null) {
            try {
                val entries = ValAnalysis.loadFrpCompareOffsets(opts.analysisDir)
                val offsets = entries.map { ValAnalysis.parseHxdOffset(it.str("hxd_offset") ?: "0") }.toSortedSet().toList()
                val patches = if (architecture == "arm32_thumb") {
                    offsets.map { ValPatchOps.patchFrpCompareThumb(data, it, opts.frpCompareValue) }
                } else {
                    offsets.map { ValPatchOps.patchFrpCompare(data, it, opts.frpCompareValue) }
                }
                if (architecture == "arm32_thumb") println("Patch   : FRP Thumb last-byte boolean -> value ${opts.frpCompareValue}")
                else println("Patch   : FRP cmp w8,#? -> cmp w8,#${opts.frpCompareValue}")
                for ((offset, patch) in offsets.zip(patches)) {
                    println("Offset  : 0x${offset.toString(16)} / HxD ${"%08X".format(offset)}  ${patch.first.hex()} -> ${patch.second.hex()}")
                }
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }

        if (opts.keyForceSuccess) {
            if (architecture == "arm32_thumb") {
                try {
                    val patch = ValPatchOps.patchKeyThumbForceSuccess(data, opts.analysisDir)
                    val entryOffset = patch["entry_offset"] as Int
                    println("Patch   : KEY Thumb entry force-success")
                    println("Entry   : 0x${entryOffset.toString(16)} / HxD ${"%08X".format(entryOffset)}  ${(patch["original_instr"] as ByteArray).hex()} -> ${(patch["replacement"] as ByteArray).hex()}")
                } catch (e: Exception) {
                    err("${e.message}")
                    return 1
                }
            } else {
                try {
                    val entries = ValAnalysis.loadKeyPrimaryReturnOffsets(opts.analysisDir)
                    val offsets = entries.map { ValAnalysis.parseHxdOffset(it.str("hxd_offset") ?: "0") }.toSortedSet().toList()
                    val patches = offsets.map { ValPatchOps.patchKeyReturnSuccess(data, it) }
                    println("Patch   : KEY primary return mov w0,w20 -> mov w0,#1")
                    for ((offset, patch) in offsets.zip(patches)) {
                        println("Offset  : 0x${offset.toString(16)} / HxD ${"%08X".format(offset)}  ${patch.first.hex()} -> ${patch.second.hex()}")
                    }
                } catch (primaryExc: Exception) {
                    try {
                        val patch = ValPatchOps.patchKeyEntryForceSuccess(data, opts.analysisDir)
                        val entryOffset = patch["entry_offset"] as Int
                        val usedCave = patch["cave_offset"] as Int
                        val stubSize = patch["stub_size"] as Int
                        println("Patch   : KEY entry force-success stub")
                        println("Reason  : primary return patch unavailable (${primaryExc.message})")
                        println("Entry   : 0x${entryOffset.toString(16)} / HxD ${"%08X".format(entryOffset)}  ${(patch["original_instr"] as ByteArray).hex()} -> ${(patch["entry_branch"] as ByteArray).hex()}")
                        println("Cave    : 0x${usedCave.toString(16)} / HxD ${"%08X".format(usedCave)}  size=$stubSize bytes")
                    } catch (fallbackExc: Exception) {
                        err("${primaryExc.message}; fallback entry stub also failed: ${fallbackExc.message}")
                        return 1
                    }
                }
            }
        }

        if (opts.keyCustomSignature != null) {
            try {
                val signature = ValAnalysis.parseCustomSignature(opts.keyCustomSignature)
                val caveOffset = if (opts.keyCaveOffset != null) ValAnalysis.parseHxdOffset(opts.keyCaveOffset) else null
                val patch = ValPatchOps.patchKeyCustomSignature(data, opts.analysisDir, signature, caveOffset)
                val entryOffset = (patch["entry_offset"] as? Int) ?: (patch["call_offset"] as Int)
                val usedCave = patch["cave_offset"] as Int
                val stubSize = patch["stub_size"] as Int
                println("Patch   : KEY custom signature gate")
                println("Signature: '${String(signature, Charsets.US_ASCII)}'")
                println("Entry   : 0x${entryOffset.toString(16)} / HxD ${"%08X".format(entryOffset)}  ${(patch["original_instr"] as ByteArray).hex()} -> ${(patch["entry_branch"] as ByteArray).hex()}")
                println("Cave    : 0x${usedCave.toString(16)} / HxD ${"%08X".format(usedCave)}  size=$stubSize bytes")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }

        if (opts.keyTokenSecret != null) {
            try {
                val caveOffset = if (opts.keyCaveOffset != null) ValAnalysis.parseHxdOffset(opts.keyCaveOffset) else null
                var constants: ByteArray? = null
                val patch: Map<String, Any?>
                if (opts.keyTokenDebugStop == "caller_before_key") {
                    patch = ValPatchOps.patchKeyCallerBeforeKeyCheckpoint(data, opts.analysisDir, "LKDBG before key\n")
                } else if (opts.keyTokenDebugStop == "entry") {
                    patch = ValPatchOps.patchKeyEntryZeroCheckpoint(data, opts.analysisDir, "LKDBG entry\n")
                } else if (opts.keyTokenRuntimeSerial) {
                    if (opts.keyTokenDebugStop != null) {
                        ValAnalysis.patchCodeValidationMessage(data, opts.analysisDir, "LKDBG ${opts.keyTokenDebugStop}\n")
                    }
                    patch = ValPatchOps.patchKeyRuntimeSerialTokenGate(data, opts.analysisDir, opts.keyTokenSecret, opts.keyTokenAlphabet, caveOffset, opts.keyTokenDebugLog, opts.keyTokenDebugStop)
                } else {
                    constants = ValTokens.deriveTokenConstants(opts.keyTokenSecret, keyTokenDevice, opts.keyTokenAlphabet)
                    patch = if (architecture == "arm32_thumb") {
                        ValPatchOps.patchKeyTokenGateThumb(data, opts.analysisDir, constants, caveOffset)
                    } else {
                        ValPatchOps.patchKeyTokenGate(data, opts.analysisDir, constants, caveOffset)
                    }
                }
                val entryOffset = (patch["entry_offset"] as? Int) ?: (patch["call_offset"] as? Int) ?: 0
                val caveValue = patch["cave_offset"] as? Int
                val usedCave = caveValue ?: 0
                val stubSize = (patch["stub_size"] as? Int) ?: 0
                val oldRaw = patch["original_instr"] as ByteArray
                val newRaw = (patch["entry_branch"] as? ByteArray) ?: (patch["replacement"] as? ByteArray) ?: ByteArray(0)
                val deviceLabel = if (keyTokenDevice.isEmpty()) "<global>" else keyTokenDevice
                println("Patch   : KEY derived token gate")
                var continueKeyPrint = true
                if (opts.keyTokenDebugStop in setOf("caller_before_key", "entry")) {
                    println("Mode    : DIAGNOSTIC checkpoint ${opts.keyTokenDebugStop}")
                    val patchOffset: Int
                    val label: String
                    if (opts.keyTokenDebugStop == "caller_before_key") {
                        patchOffset = patch["call_offset"] as Int
                        label = "Call"
                    } else {
                        patchOffset = patch["entry_offset"] as Int
                        label = "Entry"
                    }
                    println("${"%-7s".format(label)}: 0x${patchOffset.toString(16)} / HxD ${"%08X".format(patchOffset)}  ${oldRaw.hex()} -> ${newRaw.hex()}")
                    val msg = patch["message_patch"] as? Map<*, *>
                    if (msg != null) {
                        println("DbgMsg  : HxD ${"%08X".format(msg["message_offset"] as Int)} '${msg["new_message"]}'")
                    }
                    continueKeyPrint = false
                }
                if (continueKeyPrint && opts.keyTokenRuntimeSerial) {
                    println("Mode    : EXPERIMENTAL runtime-serial-compact-inplace")
                    if (patch["architecture"] != null) println("Arch    : ${patch["architecture"]}")
                    if (opts.keyTokenDebugStop != null) println("Stop    : ${opts.keyTokenDebugStop}")
                    println("Device  : runtime serialno from LK")
                    println("SerialFn: 0x${(patch["serial_func_offset"] as Int).toString(16)} / HxD ${"%08X".format(patch["serial_func_offset"] as Int)} VA=0x${uHex(patch["serial_func_va"] as Long)}")
                    if (patch["masks"] != null) println("Masks   : ${(patch["masks"] as ByteArray).hex()}")
                    if (patch["success_value"] != null) println("Returns : success=${patch["success_value"]} fail=${patch["fail_value"]}")
                    if (patch["debug_log"] as? Boolean == true) {
                        println("Debug   : LKDBG enabled via 0x${(patch["debug_print_func_offset"] as Int?).toString()} / HxD ${"%08X".format(patch["debug_print_func_offset"] as Int? ?: 0)}")
                        val debugMessages = patch["debug_message_vas"] as? Map<*, *>
                        if (debugMessages != null) {
                            for ((name, va) in debugMessages) println("DbgMsg  : $name=0x${uHex(va as Long)}")
                        }
                    }
                } else if (continueKeyPrint) {
                    println("Mode    : static-derived-no-runtime")
                    println("Device  : '$deviceLabel' ($keyTokenDeviceKind)")
                }
                if (continueKeyPrint) {
                    println("Alphabet: ${opts.keyTokenAlphabet}")
                    if (constants != null) println("Const   : ${constants.hex()}")
                    println("Entry   : 0x${entryOffset.toString(16)} / HxD ${"%08X".format(entryOffset)}  ${oldRaw.copyOfRange(0, minOf(8, oldRaw.size)).hex()} -> ${newRaw.copyOfRange(0, minOf(8, newRaw.size)).hex()}")
                    if (caveValue == null) println("InPlace : size=$stubSize bytes")
                    else println("Cave    : 0x${usedCave.toString(16)} / HxD ${"%08X".format(usedCave)}  size=$stubSize bytes")
                }
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }

        if (eraseTokenPartitionParsed != null) {
            try {
                val eraseConstants = ValTokens.deriveEraseTokenConstants(opts.eraseTokenSecret ?: "", eraseTokenDevice, eraseTokenPartitionParsed, opts.eraseTokenAlphabet)
                val eraseCaveOffset = if (opts.eraseTokenCaveOffset != null) ValAnalysis.parseHxdOffset(opts.eraseTokenCaveOffset) else null
                val patch = if (architecture == "arm32_thumb") {
                    ValPatchOps.patchEraseTokenGateThumb(data, opts.analysisDir, eraseTokenPartitionParsed, eraseConstants, eraseCaveOffset)
                } else {
                    ValPatchOps.patchEraseTokenGate(data, opts.analysisDir, eraseTokenPartitionParsed, eraseConstants, eraseCaveOffset)
                }
                val deviceLabel = if (eraseTokenDevice.isEmpty()) "<global>" else eraseTokenDevice
                val preHook = patch["pre_hook_offset"] as Int
                val gateHook = patch["gate_hook_offset"] as Int
                val cave = patch["cave_offset"] as Int
                val gateCave = patch["gate_cave_offset"] as Int
                val lateHook = patch["late_hook_offset"] as? Int
                val lateCave = patch["late_cave_offset"] as? Int
                println("Patch   : ERASE protected token gate")
                if (patch["erase_token_mode"] != null) println("Mode    : ${patch["erase_token_mode"]}")
                println("Partition: '$eraseTokenPartitionParsed'")
                println("Device  : '$deviceLabel' ($eraseTokenDeviceKind)")
                println("Alphabet: ${opts.eraseTokenAlphabet}")
                println("Const   : ${eraseConstants.hex()}")
                if (patch["pre_pointer_register"] != null || patch["gate_pointer_expression"] != null) {
                    println("ArgPtr  : pre=${patch["pre_pointer_register"]} gate=${patch["gate_pointer_expression"]}")
                }
                if (patch["token_allowed_note"] == "direct_return") {
                    val tokenTarget = patch["token_allowed_target"] as Int
                    println("TokAllow: 0x${tokenTarget.toString(16)} / HxD ${"%08X".format(tokenTarget)}  direct_return")
                }
                if (patch["token_denied_note"] == "direct_return") {
                    val tokenTarget = patch["token_denied_target"] as Int
                    println("TokDeny : 0x${tokenTarget.toString(16)} / HxD ${"%08X".format(tokenTarget)}  direct_return")
                }
                val flowAudits = patch["flow_audits"] as? Map<*, *>
                if (flowAudits != null) {
                    for ((auditName, auditRaw) in flowAudits) {
                        val audit = auditRaw as? Map<*, *> ?: continue
                        val status = if (audit["ok"] as? Boolean == true) "OK" else "WARN"
                        println("Audit   : $auditName $status terminal=${audit["terminal"]} steps=${audit["steps"]} calls=${(audit["calls"] as? List<*>)?.size ?: 0} conds=${(audit["conditional_branches"] as? List<*>)?.size ?: 0}")
                    }
                }
                println("PreHook : 0x${preHook.toString(16)} / HxD ${"%08X".format(preHook)}  ${(patch["pre_original"] as ByteArray).hex()} -> ${(patch["pre_branch"] as ByteArray).hex()}")
                println("GateHook: 0x${gateHook.toString(16)} / HxD ${"%08X".format(gateHook)}  ${(patch["gate_original"] as ByteArray).hex()} -> ${(patch["gate_branch"] as ByteArray).hex()}")
                if (lateHook != null && lateCave != null) {
                    println("LateHook: 0x${lateHook.toString(16)} / HxD ${"%08X".format(lateHook)}  ${(patch["late_original"] as ByteArray).hex()} -> ${(patch["late_branch"] as ByteArray).hex()}")
                }
                println("Cave    : 0x${cave.toString(16)} / HxD ${"%08X".format(cave)}  pre=${patch["pre_stub_size"]} gate=${patch["gate_stub_size"]} late=${patch["late_stub_size"] ?: 0} total=${patch["stub_size"]} bytes")
                println("GateCave: 0x${gateCave.toString(16)} / HxD ${"%08X".format(gateCave)}")
                if (lateCave != null) println("LateCave: 0x${lateCave.toString(16)} / HxD ${"%08X".format(lateCave)}")
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }

        if (opts.unlockEraseOnly) {
            try {
                val patch = if (architecture == "arm32_thumb") {
                    ValPatchOps.patchUnlockFlowEraseOnlyThumb(data, opts.analysisDir)
                } else {
                    ValPatchOps.patchUnlockFlowEraseOnly(data, opts.analysisDir, opts.keyTokenDebugLog)
                }
                val uiCall = patch["ui_call_offset"] as Int
                val returnHook = patch["return_hook_offset"] as Int
                println("Patch   : unlock-flow erase-only")
                println("EraseBlk: " + (patch["erase_partitions"] as List<*>).joinToString(", "))
                println("UIHook  : 0x${uiCall.toString(16)} / HxD ${"%08X".format(uiCall)}  ${(patch["original_ui_call"] as ByteArray).hex()} -> ${(patch["new_ui_call"] as ByteArray).hex()}")
                println("RetHook : 0x${returnHook.toString(16)} / HxD ${"%08X".format(returnHook)}  ${(patch["original_return_hook"] as ByteArray).hex()} -> ${(patch["new_return_hook"] as ByteArray).hex()}")
                if (patch.containsKey("silent_return_target")) {
                    val silentReturn = patch["silent_return_target"] as Int
                    println("RetTo   : 0x${silentReturn.toString(16)} / HxD ${"%08X".format(silentReturn)}")
                }
                if ((patch["md_udc_xrefs"] as? List<*>)?.isNotEmpty() == true) {
                    println("MdUdcX  : ${(patch["md_udc_xrefs"] as List<*>).size} xrefs in unlock flow")
                }
                if (patch["debug_log"] as? Boolean == true) {
                    val debugCave = patch["debug_cave_offset"] as Int
                    println("DbgFlow : 0x${debugCave.toString(16)} / HxD ${"%08X".format(debugCave)} size=${patch["debug_stub_size"] as Int} bytes")
                    val debugOps = patch["debug_erase_ops"] as? List<*>
                    if (debugOps != null) {
                        for (operation in debugOps) {
                            val op = operation as? Map<*, *> ?: continue
                            println("DbgErase: ${op["partition"]} call=0x${(op["call_site_offset"] as Int).toString(16)}")
                        }
                    }
                }
            } catch (e: Exception) {
                err("${e.message}")
                return 1
            }
        }

        return writeOutputIfRequested(outputPath, data, opts.apply)
    }
}
