// lk_auto_patch.py — preset driver: full flow is analyze -> patch the lk
// payload from the shared LkImage -> repack the container with CERT2
// re-sign. INPUT CONTRACT (liblk correction): LkImage (parsed container,
// com.cronch.motoflasher.LibLk — the same object the app flow holds) +
// prepared analysis-dir JSON. The patch engine works on
// `lkImage.partitions["lk"].data`; the output is a patched container
// (rebuilt LkImage bytes) the app flow can flash. The static analyzer
// itself is a separate phase (Capstone); this driver takes a prepared
// analysis dir (produced on a PC by lk_static_analyzer.py) and runs the
// patch + repack in-process.
// Presets (all 23): manual, unlock-imei, erase-imei, unlock-imei-nvdata,
// unlock-serial, erase-serial, unlock-serial-nvdata, modem-unlock,
// ramdump-map, readdump-read, scp-bridge, factory-allow, factory-force,
// bootmode-cmdline, mrdump-force, lockspoof, ssm-bypass, wide-open,
// full-allow, gz-canary, gz-range, frp-skip, frp-compare.
package com.cronch.motoflasher.valproto

import com.cronch.motoflasher.LibLk
import java.io.File

class ValUnimplemented(message: String) : Exception(message)

data class PresetArgs(
    val image: File,
    val output: File? = null,
    val analysisDir: File? = null,
    val preset: String = "manual",
    val imei: String? = null,
    val erasePartition: String = "cache",
    val frpCompareValue: Int = 1,
    val keyCustomSignature: String? = null,
    val keyTokenSecret: String? = null,
    val keyTokenRuntimeSerial: Boolean = false,
    val keyTokenDebugLog: Boolean = false,
    val keyTokenDebugStop: String? = null,
    val keyTokenDevice: String = "",
    val keyTokenImei: String? = null,
    val keyTokenAlphabet: String = "alnum",
    val keyCaveOffset: String? = null,
    val eraseTokenPartition: String? = null,
    val eraseTokenSecret: String? = null,
    val eraseTokenDevice: String = "",
    val eraseTokenImei: String? = null,
    val eraseTokenAlphabet: String = "alnum",
    val eraseTokenCaveOffset: String? = null,
    val noKeyForceSuccess: Boolean = false,
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
    val detectOnly: Boolean = false,
    val name: String = "lk",
    val keepWorkdir: Boolean = false,
)

object ValPresets {
    val PRESETS: List<String> = listOf(
        "manual", "unlock-imei", "erase-imei", "unlock-imei-nvdata",
        "unlock-serial", "erase-serial", "unlock-serial-nvdata",
        "modem-unlock", "ramdump-map", "readdump-read", "scp-bridge",
        "factory-allow", "factory-force", "bootmode-cmdline", "mrdump-force",
        "lockspoof", "ssm-bypass", "wide-open", "full-allow",
        "gz-canary", "gz-range", "frp-skip", "frp-compare",
    )

    val GZ_CANARY_OLD = "Hello from".toByteArray(Charsets.US_ASCII)
    val GZ_CANARY_NEW = "Hello fron".toByteArray(Charsets.US_ASCII)
    // (label, file_offset, expected_old_bytes)
    val GZ_RANGE_GATES: List<Triple<String, Int, ByteArray>> = listOf(
        Triple("range-hi-gate", 0x200E4, ValBytes.fromHex("c8010054")),
        Triple("range-lo-gate", 0x200EC, ValBytes.fromHex("83010054")),
    )
    val GZ_NOP = ValBytes.fromHex("1f2003d5")

    const val SCP_PART = "tinysys-scp-RV55_A"
    val SCP_CANARY_OLD = "L2TCM-MPU ENABLED!!\n".toByteArray(Charsets.US_ASCII)
    val SCP_CANARY_NEW = "L2TCM-MPU ENABLED!?\n".toByteArray(Charsets.US_ASCII)

    private fun requireArg(condition: Boolean, message: String) {
        if (!condition) throw IllegalArgumentException(message)
    }

    /** Port of lk_auto_patch.build_patch_command: preset -> PatchOptions. */
    fun buildPatchOptions(args: PresetArgs, analysisDir: File, patchedBin: File): PatchOptions {
        val base = PatchOptions(
            analysisDir = analysisDir,
            imageName = args.name,
            output = patchedBin,
            // NOTE: Python forwards --experimental only in the modem(size-bypass),
            // factory-allow/force, bootmode-cmdline, mrdump-force, lockspoof,
            // ssm-bypass, wide-open and full-allow branches — NOT for
            // ramdump-map, unlock/erase/nvdata or manual. Mirrored below.
            experimental = false,
        )
        fun runtimeSerialOpts(secret: String, alphabet: String, caveOffset: String?, debugLog: Boolean): PatchOptions =
            base.copy(
                keyTokenSecret = secret, keyTokenRuntimeSerial = true,
                keyTokenAlphabet = alphabet, keyCaveOffset = caveOffset,
                keyTokenDebugLog = debugLog,
            )
        when (args.preset) {
            "modem-unlock" -> {
                if (args.modemSizeBypass) {
                    requireArg(args.modemAllowUnsafe, "--preset modem-unlock with --modem-size-bypass needs --modem-allow-unsafe.")
                    return base.copy(modemSizeBypass = true, modemAllowUnsafe = true, experimental = args.experimental)
                }
                return base.copy(modemResearchReportOnly = true)
            }
            "ramdump-map" -> return base.copy(ramdumpResearchReportOnly = true)
            "factory-allow" -> {
                requireArg(args.factoryAllow, "--preset factory-allow needs --factory-allow.")
                requireArg(args.factoryAllowUnsafe, "--preset factory-allow needs --factory-allow-unsafe.")
                return base.copy(factoryAllow = true, factoryAllowUnsafe = true, experimental = args.experimental)
            }
            "factory-force" -> {
                requireArg(args.factoryAllow, "--preset factory-force needs --factory-allow.")
                requireArg(args.factoryAllowUnsafe, "--preset factory-force needs --factory-allow-unsafe.")
                requireArg(args.factoryForce, "--preset factory-force needs --factory-force.")
                requireArg(args.factoryForceUnsafe, "--preset factory-force needs --factory-force-unsafe.")
                return base.copy(factoryAllow = true, factoryAllowUnsafe = true, factoryForce = true, factoryForceUnsafe = true, experimental = args.experimental)
            }
            "bootmode-cmdline" -> {
                requireArg(args.factoryAllow, "--preset bootmode-cmdline needs --factory-allow.")
                requireArg(args.factoryAllowUnsafe, "--preset bootmode-cmdline needs --factory-allow-unsafe.")
                requireArg(args.factoryForce, "--preset bootmode-cmdline needs --factory-force.")
                requireArg(args.factoryForceUnsafe, "--preset bootmode-cmdline needs --factory-force-unsafe.")
                requireArg(args.bootmodeCmdline, "--preset bootmode-cmdline needs --bootmode-cmdline.")
                requireArg(args.bootmodeCmdlineUnsafe, "--preset bootmode-cmdline needs --bootmode-cmdline-unsafe.")
                return base.copy(
                    factoryAllow = true, factoryAllowUnsafe = true,
                    factoryForce = true, factoryForceUnsafe = true,
                    bootmodeCmdline = true, bootmodeCmdlineUnsafe = true,
                    experimental = args.experimental,
                )
            }
            "mrdump-force" -> {
                requireArg(args.mrdumpForce, "--preset mrdump-force needs --mrdump-force.")
                requireArg(args.mrdumpForceUnsafe, "--preset mrdump-force needs --mrdump-force-unsafe.")
                return base.copy(mrdumpForce = true, mrdumpForceUnsafe = true, experimental = args.experimental)
            }
            "lockspoof" -> {
                requireArg(args.lockspoof, "--preset lockspoof needs --lockspoof.")
                requireArg(args.lockspoofUnsafe, "--preset lockspoof needs --lockspoof-unsafe.")
                return base.copy(lockspoof = true, lockspoofUnsafe = true, experimental = args.experimental)
            }
            "ssm-bypass" -> {
                requireArg(args.ssmBypass, "--preset ssm-bypass needs --ssm-bypass.")
                requireArg(args.ssmBypassUnsafe, "--preset ssm-bypass needs --ssm-bypass-unsafe.")
                return base.copy(ssmBypass = true, ssmBypassUnsafe = true, experimental = args.experimental)
            }
            "wide-open" -> {
                data class FlagPair(val flag: String, val unsafe: String, val value: Boolean, val uvalue: Boolean)
                for ((flag, unsafe, value, uvalue) in listOf(
                    FlagPair("factory_allow", "factory_allow_unsafe", args.factoryAllow, args.factoryAllowUnsafe),
                    FlagPair("factory_force", "factory_force_unsafe", args.factoryForce, args.factoryForceUnsafe),
                    FlagPair("bootmode_cmdline", "bootmode_cmdline_unsafe", args.bootmodeCmdline, args.bootmodeCmdlineUnsafe),
                    FlagPair("mrdump_force", "mrdump_force_unsafe", args.mrdumpForce, args.mrdumpForceUnsafe),
                    FlagPair("lockspoof", "lockspoof_unsafe", args.lockspoof, args.lockspoofUnsafe),
                )) {
                    requireArg(value, "--preset wide-open needs --${flag.replace('_', '-')}.")
                    requireArg(uvalue, "--preset wide-open needs --${unsafe.replace('_', '-')}.")
                }
                return base.copy(
                    factoryAllow = true, factoryAllowUnsafe = true,
                    factoryForce = true, factoryForceUnsafe = true,
                    bootmodeCmdline = true, bootmodeCmdlineUnsafe = true,
                    mrdumpForce = true, mrdumpForceUnsafe = true,
                    lockspoof = true, lockspoofUnsafe = true,
                    experimental = args.experimental,
                )
            }
            "full-allow" -> {
                requireArg(args.modemSizeBypass, "--preset full-allow needs --modem-size-bypass.")
                requireArg(args.modemAllowUnsafe, "--preset full-allow needs --modem-allow-unsafe.")
                requireArg(args.factoryAllow, "--preset full-allow needs --factory-allow.")
                requireArg(args.factoryAllowUnsafe, "--preset full-allow needs --factory-allow-unsafe.")
                return base.copy(modemSizeBypass = true, modemAllowUnsafe = true, factoryAllow = true, factoryAllowUnsafe = true, experimental = args.experimental)
            }
            "unlock-imei", "unlock-serial" -> {
                requireArg(args.keyTokenSecret != null, "--preset ${args.preset} needs --key-token-secret.")
                if (args.keyTokenDebugLog) throw IllegalArgumentException("direct debug-log is off; use --key-token-debug-stop")
                val opts = runtimeSerialOpts(args.keyTokenSecret!!, args.keyTokenAlphabet, args.keyCaveOffset, args.keyTokenDebugLog)
                    .copy(frpSkipCheck = true, keyTokenDebugStop = args.keyTokenDebugStop)
                return opts
            }
            "erase-imei", "erase-serial" -> {
                requireArg(args.eraseTokenPartition != null, "--preset ${args.preset} needs --erase-token-partition.")
                requireArg(args.eraseTokenSecret != null, "--preset ${args.preset} needs --erase-token-secret.")
                if (args.keyTokenDebugLog) throw IllegalArgumentException("debug-log directo deshabilitado; usa --key-token-debug-stop")
                return runtimeSerialOpts(args.eraseTokenSecret!!, args.eraseTokenAlphabet, args.keyCaveOffset, args.keyTokenDebugLog)
                    .copy(
                        erasePartition = args.eraseTokenPartition, frpSkipCheck = true,
                        unlockEraseOnly = true, keyTokenDebugStop = args.keyTokenDebugStop,
                    )
            }
            "unlock-imei-nvdata", "unlock-serial-nvdata" -> {
                requireArg(args.keyTokenSecret != null, "--preset ${args.preset} requires --key-token-secret.")
                requireArg(args.erasePartition.isNotEmpty(), "--preset ${args.preset} needs --erase-partition.")
                if (args.keyTokenDebugLog) throw IllegalArgumentException("debug-log directo deshabilitado; usa --key-token-debug-stop")
                return runtimeSerialOpts(args.keyTokenSecret!!, args.keyTokenAlphabet, args.keyCaveOffset, args.keyTokenDebugLog)
                    .copy(erasePartition = args.erasePartition, frpSkipCheck = true, keyTokenDebugStop = args.keyTokenDebugStop)
            }
            // 2.0.10: FRP presets. The engine already had both halves
            // (frpSkipCheck / frpCompareValue in ValPatcher); these give
            // them names Kyle can pick in the Val tab. frp-skip = patch
            // the FRP check out. frp-compare = set what the FRP check
            // compares against (args.frpCompareValue, default 1).
            "frp-skip" -> return base.copy(frpSkipCheck = true)
            "frp-compare" -> return base.copy(frpCompareValue = args.frpCompareValue)
        }
        // manual (fall-through)
        var opts = base.copy(erasePartition = args.erasePartition, frpCompareValue = args.frpCompareValue)
        val keyModes = listOf(args.keyCustomSignature != null, args.keyTokenSecret != null).count { it }
        if (keyModes > 1) throw IllegalArgumentException("use only one: --key-custom-signature or --key-token-secret")
        if (args.keyCustomSignature != null) {
            opts = opts.copy(keyCustomSignature = args.keyCustomSignature, keyCaveOffset = args.keyCaveOffset)
        } else if (args.keyTokenSecret != null) {
            opts = opts.copy(
                keyTokenSecret = args.keyTokenSecret,
                keyTokenRuntimeSerial = args.keyTokenRuntimeSerial,
                keyTokenDebugLog = args.keyTokenDebugLog,
                keyTokenDebugStop = args.keyTokenDebugStop,
                keyTokenDevice = args.keyTokenDevice,
                keyTokenImei = args.keyTokenImei ?: args.imei,
                keyTokenAlphabet = args.keyTokenAlphabet,
                keyCaveOffset = args.keyCaveOffset,
            )
        } else if (!args.noKeyForceSuccess) {
            opts = opts.copy(keyForceSuccess = true)
        }
        if (args.eraseTokenPartition != null || args.eraseTokenSecret != null) {
            if (args.eraseTokenPartition == null || args.eraseTokenSecret == null) {
                throw IllegalArgumentException("use both: --erase-token-partition and --erase-token-secret")
            }
            opts = opts.copy(
                eraseTokenPartition = args.eraseTokenPartition,
                eraseTokenSecret = args.eraseTokenSecret,
                eraseTokenAlphabet = args.eraseTokenAlphabet,
                eraseTokenDevice = args.eraseTokenDevice,
                eraseTokenImei = args.eraseTokenImei ?: args.imei,
                eraseTokenCaveOffset = args.eraseTokenCaveOffset,
            )
        }
        return opts
    }

    private fun defaultAnalysisDir(image: File): File =
        File(image.parentFile, image.nameWithoutExtension + "_analysis")

    private fun defaultOutputImage(image: File): File =
        File(image.parentFile, image.nameWithoutExtension + ".patched" + image.extension.let { if (it.isEmpty()) "" else ".$it" })

    /** CERT2 re-sign + exact-size trim for GZ images (port of _gz_sign_and_finish).
     *  The Python flow then runs verify_mtk_image.py and requires Result:
     *  VALID; that verifier is NOT ported, so this returns the trimmed bytes
     *  with verified=false and the caller must verify externally. */
    data class GzResult(val output: ByteArray, val verified: Boolean = false, val certLog: List<String>)

    fun gzSignAndFinish(patched: ByteArray, inputLen: Int): GzResult {
        val signResult = ValCert.sign(patched, legacy = false)
        val signed = signResult.signed
        if (signed.size < patched.size) throw IllegalStateException("signed image shorter than input")
        val trimmed = signed.copyOfRange(0, inputLen)
        if (signed.copyOfRange(inputLen, signed.size).any { it != 0.toByte() }) {
            throw IllegalStateException("cert growth overwrote non-padding bytes; refusing")
        }
        return GzResult(trimmed, verified = false, certLog = signResult.info.log)
    }

    fun runGzCanary(args: PresetArgs): Int {
        val image = args.image
        val output = args.output ?: File(image.parentFile, image.nameWithoutExtension + ".canary.img")
        val data = image.readBytes().copyOf()
        val hits = ValBytes.findAll(data, GZ_CANARY_OLD)
        if (hits.size != 1) {
            System.err.println("Error: canary anchor found ${hits.size}x (need exactly 1): refusing")
            return 1
        }
        val off = hits[0]
        println("Canary  : 'Hello from' -> 'Hello fron' @ 0x${off.toString(16)}")
        GZ_CANARY_NEW.copyInto(data, off)
        val result = gzSignAndFinish(data, data.size)
        output.writeBytes(result.output)
        println()
        println("Done (gz-canary, 1 byte + re-sign, exact size kept)")
        println("Patched image: $output")
        println("Note    : verify_mtk_image.py not ported; Result: VALID not checked in-process.")
        return 0
    }

    fun runGzRange(args: PresetArgs): Int {
        val image = args.image
        val output = args.output ?: File(image.parentFile, image.nameWithoutExtension + ".gzrange.img")
        val data = image.readBytes().copyOf()
        for ((label, offset, old) in GZ_RANGE_GATES) {
            if (offset < 0 || offset + old.size > data.size) {
                System.err.println("Error: $label offset 0x${offset.toString(16)} out of range: refusing")
                return 1
            }
            val actual = data.copyOfRange(offset, offset + old.size)
            if (!actual.contentEquals(old)) {
                System.err.println("Error: $label old-byte mismatch at 0x${offset.toString(16)}: expected ${old.hex()}, found ${actual.hex()}: refusing")
                return 1
            }
            GZ_NOP.copyInto(data, offset)
            println("Patch   : gz $label @ 0x${offset.toString(16)}  ${old.hex()} -> ${GZ_NOP.hex()}")
        }
        val result = gzSignAndFinish(data, data.size)
        output.writeBytes(result.output)
        println()
        println("Done (gz-range, 8 bytes + re-sign, exact size kept)")
        println("Patched image: $output")
        println("Note    : verify_mtk_image.py not ported; Result: VALID not checked in-process.")
        return 0
    }

    fun runScpBridge(args: PresetArgs): Int {
        val image = args.image
        val output = args.output ?: File(image.parentFile, image.nameWithoutExtension + ".scpbridge.img")
        val containerBytes = image.readBytes()
        val img = LibLk.parse(containerBytes)
        val part = img.partitions.firstOrNull { it.name == SCP_PART }
        if (part == null) {
            System.err.println("Error: subimage '$SCP_PART' not in $image (have: ${img.partitions.map { it.name }.sorted().pyStrListRepr()})")
            return 1
        }
        val payload = part.data.copyOf()
        val hits = ValBytes.findAll(payload, SCP_CANARY_OLD)
        if (hits.size != 1) {
            System.err.println("Error: canary anchor found ${hits.size}x (need exactly 1): refusing")
            return 1
        }
        val off = hits[0]
        println("Canary  : 'L2TCM-MPU ENABLED!!\\n' -> 'L2TCM-MPU ENABLED!?\\n' @ payload+0x${off.toString(16)}")
        SCP_CANARY_NEW.copyInto(payload, off)
        val repack = ValRepack.repack(img, containerBytes, payload, SCP_PART, legacyCert = true)
        output.writeBytes(repack.output)
        println()
        println("Done (scp-bridge PoC: 1 byte + re-sign)")
        println("Patched image: $output")
        println("Note    : verify_mtk_image.py not ported; Result: VALID not checked in-process.")
        println("Flash ONLY to the inactive scp slot (scp_b when on _a) with Send+Write OKAY.")
        return 0
    }

    /** Port of lk_auto_patch.main() for the LK presets.
     *  Requires a prepared analysis dir (lk_static_analyzer.py output).
     *  Throws ValUnimplemented for readdump-read (see below). */
    fun runPreset(args: PresetArgs): Int {
        val image = args.image
        if (!image.isFile) {
            System.err.println("Error: $image does not exist")
            return 2
        }
        when (args.preset) {
            "gz-canary" -> return try {
                runGzCanary(args)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                1
            }
            "gz-range" -> return try {
                runGzRange(args)
            } catch (e: Exception) {
                System.err.println("Error: ${e.message}")
                1
            }
            "readdump-read" -> throw ValUnimplemented(
                "readdump-read delegates to lk-tools/build_bulk28.py (INFO 256 B + DATA 16 KB " +
                    "readdump builder with its own Capstone operand gates), which is NOT part of " +
                    "this port (lk_patch_partition / repack / sign / keygen / oem-mapper only). " +
                    "No Kotlin equivalent exists yet; the preset is honestly unimplemented."
            )
            "scp-bridge" -> return runScpBridge(args)
        }
        val analysisDir = (args.analysisDir ?: defaultAnalysisDir(image)).absoluteFile
        if (!analysisDir.isDirectory) {
            System.err.println("Error: analysis dir does not exist: $analysisDir (run lk_static_analyzer.py first — analyzer phase, not ported yet)")
            return 1
        }
        // Container layer: parse once; the LkImage is the input contract
        // for the patch engine and the repack (no re-parsing, no lk.bin read:
        // the payload comes from lkImage.partitions[args.name].data).
        val containerBytes = image.readBytes()
        val lkImage = try {
            LibLk.parse(containerBytes)
        } catch (e: Exception) {
            System.err.println("Error: could not parse container $image as LkImage: ${e.message}")
            return 1
        }
        val output = (args.output ?: defaultOutputImage(image)).absoluteFile
        val patchedBin = File(analysisDir, "${args.name}.auto.patched.bin")
        if (args.detectOnly) {
            val lkPart = lkImage.partitions.firstOrNull { it.name == args.name }
            if (lkPart == null) {
                System.err.println("Error: no partition '${args.name}' in the LkImage")
                return 1
            }
            val rc = ValReports.detectReport(lkPart.data, analysisDir, args.experimental)
            println()
            println("Done (detect-only, no changes written)")
            println("Analysis dir : $analysisDir")
            return rc
        }
        val patchOptions = try {
            buildPatchOptions(args, analysisDir, patchedBin)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            return 1
        }
        val patchResult = ValPatcher.runPatch(lkImage, patchOptions)
        if (patchResult.exitCode != 0) return patchResult.exitCode
        val patchedPayload = patchResult.patchedPayload
        if (patchedPayload == null) {
            System.err.println("Error: patch finished with no patched payload")
            return 1
        }
        val repack = try {
            ValRepack.repack(lkImage, containerBytes, patchedPayload, args.name, legacyCert = true)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            return 1
        }
        output.writeBytes(repack.output)
        println()
        println("Done")
        println("Analysis dir : $analysisDir")
        println("Patched bin  : $patchedBin")
        println("Patched image: $output")
        println("Note    : repack CERT2 re-signed in-process; verify_mtk_image.py not ported, Result: VALID not checked in-process.")
        return 0
    }
}
