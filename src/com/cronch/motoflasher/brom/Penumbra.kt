// Penumbra.kt — Antumbra CLI driver (route (a) from the Penumbra
// evaluation: bundle the static aarch64 release binary, drive its CLI).
//
// The binary is Penumbra/Antumbra v2.0.0 (commit f721520), AGPL-3.0,
// shipped UNMODIFIED as assets/antumbra. It is statically linked
// (no INTERP, no dynamic section), so it needs no Bionic/glibc — it
// just needs root (usbfs via /dev/bus/usb, same as the fastboot /
// rootusb helpers) and a fixed working directory.
//
// Rules, from the evaluation (~/workspace/penumbra-eval/EVAL.md §2.3):
//  - run as root via `su -c`, fixed cwd = filesDir/penumbra (the
//    .antumbra_state file lives in the cwd; the binary caches
//    soc_id/meid/hw_code/hw_subcode/target_config there across
//    invocations, which is how multi-step flows stay connected).
//  - set HOME explicitly for the su invocation (dirs::config_dir
//    resolves against HOME; without it the config lands in root's home).
//  - backend -b usb (the nusb/usbfs backend the release was built with).
//  - pass --vid 0x0E8D. PID is deliberately NOT pinned: the phone
//    re-enumerates between preloader (0x2000) and BROM (0x0003) every
//    ~2.5 s, so a pinned PID misses the other mode; VID-only grabs
//    whichever MTK device is present (there is normally one on the
//    OTG bus). The connect loop retries, same as 1.1.x.
//  - every invocation does a full connect (handshake → send_auth →
//    DA upload) inside the binary; the app treats each CLI run as one
//    operation and parses stdout/stderr + exit code for the result.
//  - Antumbra has NO flash timeout, so the app kills a hung child
//    itself (1.1.21-style): no output for `inactivityTimeoutMs`, or
//    total runtime past `timeoutMs`, destroys the process forcibly
//    and reports it as a failure, never a silent hang.
//  - DA hw_subcode must equal 0x8A00 at runtime (the DA entry match
//    in Penumbra's get_da_entry). The driver logs the hw_subcode
//    whenever the binary reports it; it does NOT block on it —
//    Antumbra itself errors NoCompatibleDa if it mismatches, and
//    that error is surfaced verbatim.
//  - The driver takes ANY DA/auth file, no checks of any kind.
package com.cronch.motoflasher.brom

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest

object Penumbra {
    const val VERSION = "2.0.0"
    const val COMMIT = "f721520"
    const val EXPECTED_SHA256 = "c0257dd7104f9df66bb66371bcf1b44055165f20b4ea1504ee9a3c4bd164fb20"
    const val EXPECTED_SIZE: Long = 6213928L
    const val VID_MTK: Int = 0x0E8D
    const val PRELOADER_PID: Int = 0x2000
    const val BROM_PID: Int = 0x0003
    const val DA_HW_SUBCODE_EXPECTED: Int = 0x8A00
    const val MT6835_HW_CODE: Int = 0x1209

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    data class StorageInfo(
        val storageType: String,
        val totalSize: Long,
        val blockSize: Long,
        val userSize: Long,
        val boot1Size: Long,
        val partitionCount: Int,
    )

    private fun workDir(ctx: Context): File = File(ctx.filesDir, "penumbra").apply { mkdirs() }
    fun binFile(ctx: Context): File = File(workDir(ctx), "antumbra")
    fun stateFile(ctx: Context): File = File(workDir(ctx), ".antumbra_state")

    /** Extract assets/antumbra and verify size + SHA-256. Returns error or null. */
    fun install(ctx: Context): String? {
        return try {
            val bin = binFile(ctx)
            var fresh = false
            if (!bin.exists() || bin.length() != EXPECTED_SIZE || sha256(bin) != EXPECTED_SHA256) {
                ctx.assets.open("antumbra").use { input ->
                    bin.outputStream().use { output -> input.copyTo(output) }
                }
                bin.setExecutable(true, false)
                fresh = true
            }
            if (bin.length() != EXPECTED_SIZE) {
                return "antumbra install failed: size ${bin.length()} (expected $EXPECTED_SIZE)"
            }
            val sha = sha256(bin)
            if (sha != EXPECTED_SHA256) {
                return "antumbra install failed: SHA-256 $sha (expected $EXPECTED_SHA256) — the bundled binary is NOT the pinned fixed build, refusing to run it"
            }
            if (fresh) {
                // Stale persisted state from an older binary would lie
                // about the device; a fresh install starts clean.
                try { stateFile(ctx).delete() } catch (_: Exception) {}
            }
            null
        } catch (e: Exception) {
            "antumbra install failed: ${e.message}"
        }
    }

    fun isInstalled(ctx: Context): Boolean {
        val bin = binFile(ctx)
        return bin.exists() && bin.canExecute() && bin.length() == EXPECTED_SIZE
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

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Run one Antumbra CLI invocation as root, from the fixed work dir,
     * with HOME set to it. [cmdArgs] are the CLI arguments AFTER the
     * connection flags (subcommand + its args). da/auth are the picked
     * files (null = no -d/-a flags, e.g. --version). Lines are streamed
     * to onLine as they arrive. A run with no output for
     * [inactivityTimeoutMs], or past [timeoutMs] total, is killed and
     * reported as exit -1 — Antumbra has no timeout of its own.
     */
    fun run(
        ctx: Context,
        cmdArgs: List<String>,
        da: File? = null,
        auth: File? = null,
        onLine: (String) -> Unit,
        timeoutMs: Long = 300_000L,
        inactivityTimeoutMs: Long = 120_000L,
    ): Result {
        val bin = binFile(ctx)
        val dir = workDir(ctx)
        val head = mutableListOf<String>()
        head += "-b"; head += "usb"
        head += "--vid"; head += "0x%04X".format(VID_MTK)
        if (da != null) { head += "-d"; head += da.absolutePath }
        if (auth != null) { head += "-a"; head += auth.absolutePath }
        head += "-v"
        val all = head + cmdArgs
        val quoted = all.joinToString(" ") { shellQuote(it) }
        val cmd = "cd ${shellQuote(dir.absolutePath)} && HOME=${shellQuote(dir.absolutePath)} ${shellQuote(bin.absolutePath)} $quoted"
        return runSuWithWatchdog(cmd, onLine, timeoutMs, inactivityTimeoutMs)
    }

    /** `antumbra --version` with no device flags: the first on-phone test. */
    fun version(ctx: Context, onLine: (String) -> Unit): Result {
        val bin = binFile(ctx)
        val dir = workDir(ctx)
        val cmd = "cd ${shellQuote(dir.absolutePath)} && HOME=${shellQuote(dir.absolutePath)} ${shellQuote(bin.absolutePath)} --version"
        return runSuWithWatchdog(cmd, onLine, 30_000L, 10_000L)
    }

    private fun runSuWithWatchdog(
        cmd: String,
        onLine: (String) -> Unit,
        timeoutMs: Long,
        inactivityTimeoutMs: Long,
    ): Result {
        // Verbose: show the exact command before it runs, so the log
        // tells you what was attempted, not just what it printed.
        onLine("\$ $cmd")
        val out = StringBuilder()
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val started = System.currentTimeMillis()
            val lastOutput = java.util.concurrent.atomic.AtomicLong(started)
            val killed = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread {
                while (true) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { return@Thread }
                    val now = System.currentTimeMillis()
                    if (now - started > timeoutMs || now - lastOutput.get() > inactivityTimeoutMs) {
                        killed.set(true)
                        try { proc.destroyForcibly() } catch (_: Exception) {}
                        return@Thread
                    }
                    try {
                        if (proc.waitFor(1, java.util.concurrent.TimeUnit.MILLISECONDS)) return@Thread
                    } catch (_: Exception) { return@Thread }
                }
            }.apply { isDaemon = true; start() }
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        lastOutput.set(System.currentTimeMillis())
                        out.append(line).append('\n')
                        onLine(line!!)
                        if (killed.get()) break
                    }
                }
            } catch (_: Exception) {
                // Stream closed by the watchdog kill; fall through.
            } finally {
                watchdog.interrupt()
            }
            val code = try { proc.waitFor() } catch (_: Exception) { -1 }
            val elapsed = System.currentTimeMillis() - started
            if (killed.get()) {
                val hint = "Antumbra stopped: no output for ${inactivityTimeoutMs / 1000}s, or total over ${timeoutMs / 1000}s. The binary has no flash timeout of its own, so the app stops hung runs. The phone may have re-enumerated mid-run. Tap the button again."
                out.append(hint).append('\n')
                onLine(hint)
                Result(-1, out.toString())
            } else {
                onLine("exit=$code (${elapsed}ms)")
                Result(code, out.toString())
            }
        } catch (e: Exception) {
            out.append("ERROR: ${e.message}\n")
            onLine("ERROR: ${e.message}")
            Result(-1, out.toString())
        }
    }

    /** List MTK USB devices (VID 0E8D) via root shell + sysfs. No DA needed. */
    fun detectMtk(ctx: Context, onLine: (String) -> Unit): List<Pair<Int, Int>> {
        val found = mutableListOf<Pair<Int, Int>>()
        val cmd = "for d in /sys/bus/usb/devices/*/idVendor; do v=\$(cat \"\$d\" 2>/dev/null); p=\$(cat \"\${d%/*}/idProduct\" 2>/dev/null); echo \"\$v \$p\"; done"
        val r = com.cronch.motoflasher.Fastboot.runSu(cmd, onLine)
        for (line in r.output.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                try {
                    val vid = parts[0].toInt(16)
                    val pid = parts[1].toInt(16)
                    if (vid == VID_MTK) found += vid to pid
                } catch (_: Exception) {
                }
            }
        }
        return found
    }

    /** Parse `antumbra pgpt` output (info! lines) into a partition list. */
    fun parsePgpt(output: String): List<GptPartition> {
        val re = Regex("""Name:\s*(\S+)\s+Addr:\s*0x([0-9A-Fa-f]+)\s+Size:\s*0x([0-9A-Fa-f]+)""")
        val out = mutableListOf<GptPartition>()
        for (m in re.findAll(output)) {
            val name = m.groupValues[1]
            val addr = m.groupValues[2].toLongOrNull(16) ?: continue
            val size = m.groupValues[3].toLongOrNull(16) ?: continue
            out += GptPartition(name = name, startByte = addr, sizeBytes = size, guid = "")
        }
        return out
    }

    /** Parse `antumbra storage-info` output. Null when it does not parse. */
    fun parseStorageInfo(output: String): StorageInfo? {
        fun hex(label: String): Long? {
            val m = Regex("""$label:\s*0x([0-9A-Fa-f]+)""").find(output) ?: return null
            return m.groupValues[1].toLongOrNull(16)
        }
        val type = Regex("""Storage Type:\s*(\S+)""").find(output)?.groupValues?.getOrNull(1) ?: return null
        val total = hex("Total Size") ?: return null
        val block = hex("Block Size") ?: 512L
        val user = hex("User size") ?: total
        val boot1 = hex("Boot 1 size") ?: 0L
        val count = Regex("""Number of Partitions:\s*(\d+)""").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return StorageInfo(
            storageType = type,
            totalSize = total,
            blockSize = block,
            userSize = user,
            boot1Size = boot1,
            partitionCount = count,
        )
    }

    /** Quick root check: `su -c id` should report uid=0 (fresh, never cached). */
    fun hasRootFresh(onLine: (String) -> Unit): Boolean {
        val r = com.cronch.motoflasher.Fastboot.runSu("id", onLine)
        val ok = r.exitCode == 0 && r.output.contains("uid=0")
        if (!ok) onLine("hasRoot: su exit=${r.exitCode} output='${r.output.trim()}'")
        return ok
    }
}
