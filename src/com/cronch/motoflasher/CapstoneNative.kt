package com.cronch.motoflasher

import android.content.Context
import java.io.File

/**
 * Capstone disassembler, native build for Moto Flasher.
 *
 * Capstone by the Capstone Engine authors (BSD license) — C library
 * libcapstone.so plus the thin JNI bridge libcapstone_jni.so, both built
 * from Capstone 5.0.7 for arm64-v8a (API 28) with ARM + ARM64 backends only.
 * Provenance and build notes: val-port/phase0-notes.md, toolchain/capstone/CAPSTONE-NOTES.md.
 *
 * The APK's lib/ dir is NOT used by this hand-build pipeline, so the .so
 * files ship as assets under assets/capstone/ and are extracted to
 * filesDir/capstone/ on first use, then loaded by absolute path with
 * System.load (libcapstone.so first: libcapstone_jni.so needs it).
 *
 * This mirrors what lk_static_analyzer.py (Val Protocol) does with Python
 * Capstone 5.0.7, and only that:
 *   - ARCH_ARM64: Cs(CS_ARCH_ARM64, CS_MODE_ARM)
 *   - ARCH_THUMB: Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
 *   - per instruction: address, size, mnemonic, op_str, raw bytes
 * The analyzer sets md.detail = False and parses mnemonic/op_str strings;
 * detail fields (operands, regs read/written, groups) are exposed here
 * because the native build enables CS_OPT_DETAIL, for the later
 * patch-engine phase — the analyzer itself never reads them.
 */
object CapstoneNative {

    const val ARCH_ARM64 = 0
    const val ARCH_THUMB = 1

    /** One disassembled instruction. Field order must match the JNI constructor. */
    data class CapstoneInsn(
        val address: Long,
        val size: Int,
        val mnemonic: String,
        val opStr: String,
        val bytes: ByteArray,
        // Detail fields (CS_OPT_DETAIL on). The analyzer does not use these.
        val opCount: Int,
        val opTypes: IntArray,       // cs_arm64_op_type / cs_arm_op_type values
        val opRegNames: Array<String>, // "" when the operand is not a register
        val opImms: LongArray,       // 0 unless the operand is an immediate
        val opMemBase: Array<String>,  // "" unless the operand is memory
        val opMemIndex: Array<String>, // "" unless the operand is memory
        val opMemDisp: LongArray,    // 0 unless the operand is memory
        val regsRead: Array<String>,   // registers read (cs_reg_name strings)
        val regsWrite: Array<String>,  // registers written
        val groups: IntArray,        // cs_group_type values
    ) {
        // ByteArray/IntArray use reference equality in a data class; compare
        // by content when that matters.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CapstoneInsn) return false
            return address == other.address && size == other.size &&
                mnemonic == other.mnemonic && opStr == other.opStr &&
                bytes.contentEquals(other.bytes) && opCount == other.opCount &&
                opTypes.contentEquals(other.opTypes) &&
                opRegNames.contentEquals(other.opRegNames) &&
                opImms.contentEquals(other.opImms) &&
                opMemBase.contentEquals(other.opMemBase) &&
                opMemIndex.contentEquals(other.opMemIndex) &&
                opMemDisp.contentEquals(other.opMemDisp) &&
                regsRead.contentEquals(other.regsRead) &&
                regsWrite.contentEquals(other.regsWrite) &&
                groups.contentEquals(other.groups)
        }

        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + size
            result = 31 * result + mnemonic.hashCode()
            result = 31 * result + opStr.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + opCount
            result = 31 * result + opTypes.contentHashCode()
            result = 31 * result + opRegNames.contentHashCode()
            result = 31 * result + opImms.contentHashCode()
            result = 31 * result + opMemBase.contentHashCode()
            result = 31 * result + opMemIndex.contentHashCode()
            result = 31 * result + opMemDisp.contentHashCode()
            result = 31 * result + regsRead.contentHashCode()
            result = 31 * result + regsWrite.contentHashCode()
            result = 31 * result + groups.contentHashCode()
            return result
        }
    }

    private val LIBS = arrayOf("libcapstone.so", "libcapstone_jni.so")

    @Volatile
    private var loaded = false

    /**
     * Extract the .so files from assets/capstone/ to filesDir/capstone/ and
     * load them. Idempotent; safe to call on every use. Returns an error
     * string, or null on success.
     */
    @Synchronized
    fun ensureLoaded(ctx: Context): String? {
        if (loaded) return null
        return try {
            val dir = File(ctx.filesDir, "capstone").apply { mkdirs() }
            for (name in LIBS) {
                val out = File(dir, name)
                val assetLen = try {
                    ctx.assets.openFd("capstone/$name").use { it.length }
                } catch (_: Exception) {
                    -1L
                }
                if (!out.exists() || (assetLen >= 0 && out.length() != assetLen)) {
                    val tmp = File(dir, "$name.tmp")
                    ctx.assets.open("capstone/$name").use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp.setExecutable(true, false)
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                        out.setExecutable(true, false)
                    }
                } else {
                    out.setExecutable(true, false)
                }
            }
            // Order matters: the JNI lib has DT_NEEDED libcapstone.so.
            System.load(File(dir, "libcapstone.so").absolutePath)
            System.load(File(dir, "libcapstone_jni.so").absolutePath)
            loaded = true
            null
        } catch (e: Exception) {
            "capstone load failed: ${e.message}"
        }
    }

    /** True once ensureLoaded has succeeded in this process. */
    fun isLoaded(): Boolean = loaded

    /** Capstone version as "major.minor" (cs_version; e.g. "5.0"). */
    fun version(): String {
        check(loaded) { "CapstoneNative not loaded — call ensureLoaded(context) first" }
        return nativeVersion()
    }

    /**
     * Disassemble [code] at [baseAddress] with [arch] (ARCH_ARM64/ARCH_THUMB).
     * Bulk cs_disasm: returns instructions until the first undecodable byte,
     * like md.disasm(data, base) with no count. Empty array when nothing
     * decodes or the arch is invalid.
     */
    fun disassemble(code: ByteArray, baseAddress: Long, arch: Int): Array<CapstoneInsn> {
        check(loaded) { "CapstoneNative not loaded — call ensureLoaded(context) first" }
        return nativeDisassemble(code, baseAddress, arch)
    }

    /**
     * Disassemble exactly one instruction at the start of [code].
     * Mirrors the analyzer's per-slot call: md.disasm(word, addr, count=1).
     * Returns null when the bytes do not decode (literal pool / data word).
     */
    fun disassembleOne(code: ByteArray, baseAddress: Long, arch: Int): CapstoneInsn? {
        check(loaded) { "CapstoneNative not loaded — call ensureLoaded(context) first" }
        return nativeDisassembleOne(code, baseAddress, arch)
    }

    private external fun nativeVersion(): String
    private external fun nativeDisassemble(code: ByteArray, baseAddress: Long, arch: Int): Array<CapstoneInsn>
    private external fun nativeDisassembleOne(code: ByteArray, baseAddress: Long, arch: Int): CapstoneInsn?
}
