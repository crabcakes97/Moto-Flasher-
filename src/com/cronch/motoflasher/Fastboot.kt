package com.cronch.motoflasher

import android.content.Context
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.MessageDigest

/**
 * Runs the bundled fastboot binary as root.
 *
 * The binary + its .so deps live in the app's files dir (extracted from assets
 * on first run). Every call goes through `su -c` with LD_LIBRARY_PATH pointed
 * at the bundled libs, because the root shell's PATH does not include them.
 */
object Fastboot {

    data class Result(val exitCode: Int, val output: String, val rebootDropped: Boolean = false) {
        // 2.0.12: a reboot-family command whose OKAY never arrived
        // because the device rebooted first (USB dropped) counts as
        // success — see run(). rebootDropped carries that verdict.
        val ok: Boolean get() = (exitCode == 0 && !output.contains("FAILED")) || rebootDropped
    }

    private fun binDir(ctx: Context): File = File(ctx.filesDir, "fb")
    private fun libDir(ctx: Context): File = File(binDir(ctx), "lib")
    fun binFile(ctx: Context): File = File(binDir(ctx), "fastboot")

    /** True once the binary + libs are extracted and executable. */
    fun isInstalled(ctx: Context): Boolean {
        val bin = binFile(ctx)
        return bin.exists() && bin.canExecute()
    }

    /** Extract assets/fastboot + the .so files under assets/lib into files/fb/. Returns error or null. */
    fun install(ctx: Context): String? {
        return try {
            val dir = binDir(ctx)
            val libs = libDir(ctx)
            dir.mkdirs()
            libs.mkdirs()
            copyAsset(ctx, "fastboot", binFile(ctx))
            binFile(ctx).setExecutable(true, false)
            val assetLibs = ctx.assets.list("lib") ?: emptyArray()
            for (name in assetLibs) {
                copyAsset(ctx, "lib/$name", File(libs, name))
            }
            null
        } catch (e: Exception) {
            "fastboot install failed: ${e.message}"
        }
    }

    private fun copyAsset(ctx: Context, asset: String, out: File) {
        ctx.assets.open(asset).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** Run `fastboot <args...>` as root. Calls onLine for each output line. */
    fun run(ctx: Context, args: List<String>, onLine: (String) -> Unit): Result {
        val bin = binFile(ctx)
        val lib = libDir(ctx)
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val cmd = "LD_LIBRARY_PATH='${lib.absolutePath}' '${bin.absolutePath}' $quoted"
        // 1.1.21: fastboot prints "< waiting for any device >" and then
        // BLOCKS forever when it cannot talk to a device — and runSu
        // does waitFor() with no timeout, so the command hangs and the
        // user just sees "waiting". Abort as soon as that line appears
        // (the device is either not there or its fastboot session is
        // stale) and say plainly what to do, instead of hanging.
        val r = runSu(cmd, onLine, abortOn = { line ->
            line.contains("waiting for any device", ignoreCase = true) ||
                line.contains("waiting for device", ignoreCase = true)
        })
        // 2.0.12: reboot-family false failure (Kyle, on-device: the
        // phone DID reboot but the log said FAILED). The LK reboots
        // before/without the OKAY reaching the host, USB drops, and
        // the client reports "Rebooting FAILED (remote: )". If the
        // command was sent ("Rebooting"/"Continuing" printed) and the
        // bootloader did not refuse it (no FAILED (remote: 'msg')),
        // the dropped connection means the reboot happened — verdict
        // SUCCESS. The raw lines already logged verbatim via onLine;
        // only the verdict changes. flash/erase/getvar/oem keep real
        // failure reporting.
        val first = args.firstOrNull()?.lowercase().orEmpty()
        val isReboot = first.startsWith("reboot")
        val isContinue = first == "continue"
        if ((isReboot || isContinue) && !r.ok) {
            val sent = if (isContinue) r.output.contains("Continuing") else r.output.contains("Rebooting")
            val refused = r.output.contains("FAILED (remote: '")
            if (sent && !refused) {
                val note = "(device rebooted; the connection dropped before OKAY, which is normal)"
                onLine(note)
                return Result(0, r.output + note + "\n", rebootDropped = true)
            }
        }
        return r
    }

    /** Run an arbitrary shell command as root. */
    fun runSu(cmd: String, onLine: (String) -> Unit, abortOn: ((String) -> Boolean)? = null): Result {
        val out = StringBuilder()
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            var aborted = false
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    out.append(line).append('\n')
                    onLine(line!!)
                    if (abortOn != null && abortOn(line!!)) {
                        // The command is about to block forever waiting
                        // for a device. Kill it now and say what to do.
                        val hint = "No fastboot device is answering. If 'fastboot devices' lists the phone, its fastboot session is stale. Restart the phone into fastboot mode and try again. If nothing is listed, plug the phone in over OTG and put it in fastboot mode."
                        out.append(hint).append('\n')
                        onLine(hint)
                        proc.destroyForcibly()
                        aborted = true
                        break
                    }
                }
            }
            val code = proc.waitFor()
            if (aborted) Result(-1, out.toString()) else Result(code, out.toString())
        } catch (e: Exception) {
            out.append("ERROR: ${e.message}\n")
            onLine("ERROR: ${e.message}")
            Result(-1, out.toString())
        }
    }

    /** Quick root check: `su -c id` should report uid=0. */
    fun hasRoot(onLine: (String) -> Unit): Boolean {
        val r = runSu("id", onLine)
        val ok = r.exitCode == 0 && r.output.contains("uid=0")
        if (!ok) onLine("hasRoot: su exit=${r.exitCode} output='${r.output.trim()}'")
        return ok
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            var n: Int
            while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
