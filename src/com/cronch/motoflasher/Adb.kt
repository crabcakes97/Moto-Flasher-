package com.cronch.motoflasher

import android.content.Context
import java.io.File

/**
 * Runs the bundled adb binary as root — the puller half of the app.
 *
 * Same pattern as Fastboot: binary + its .so deps live in the app's
 * files dir (extracted from assets on first run), every call goes through
 * `su -c` with LD_LIBRARY_PATH pointed at the bundled libs. The adb libs
 * share the same files/fb/lib dir as fastboot's libs.
 *
 * The target phone needs USB debugging ON, adb authorized, and TEMP ROOT
 * (the pull runs `adb shell su -c dd ...`). adb itself cannot read
 * partitions — that is what the root shell on the target is for.
 *
 * 2.0.8 — why `adb devices` showed an empty list on-device (2.0.7):
 * the binary ran, the daemon answered, exit 0 — but the daemon saw no
 * USB device, in TWRP and in booted Android, while fastboot over the
 * same OTG link worked. Both binaries use libusb against /dev/bus/usb
 * (statically linked, verified with readelf), so the difference is HOW
 * they run: fastboot is a fresh foreground process per command and
 * enumerates USB on the spot; adb is a persistent daemon. A daemon
 * started before the Moto was plugged in — or a stale one left over
 * from an earlier session, possibly started without root — never sees
 * the device: libusb's in-house hotplug thread does not reliably pick
 * up a later plug-in here, and a client just talks to whatever daemon
 * already owns port 5037. The fix: kill the server and start a fresh
 * daemon with the device already plugged in, so enumeration happens at
 * daemon start (no hotplug needed). Devices + Diagnose both do this.
 * An empty device list is NEVER reported as success.
 *
 * 2.0.10 — 2.0.8's fix did not prove out on-device, so now EVERY adb
 * call is logged in full: the exact command line with its env (HOME,
 * ANDROID_USER_HOME, LD_LIBRARY_PATH), every stdout+stderr line
 * verbatim (Fastboot.runSu merges the streams), and the exit code.
 * Devices logs the server lifecycle (kill-server result, start-server
 * result, daemon pid check, port 5037 owner when visible) and the USB
 * bus listing, then runs `devices -l` raw. If the list comes back
 * empty it retries ONCE (kill-server, start-server, devices -l) and
 * logs both attempts. Diagnose dumps all of it in one shot.
 */
object Adb {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0 && !output.contains("error", ignoreCase = true)
    }

    private fun binDir(ctx: Context): File = File(ctx.filesDir, "fb")
    private fun libDir(ctx: Context): File = File(binDir(ctx), "lib")
    fun binFile(ctx: Context): File = File(binDir(ctx), "adb")

    fun isInstalled(ctx: Context): Boolean {
        val bin = binFile(ctx)
        return bin.exists() && bin.canExecute()
    }

    /**
     * Extract assets/adb + the .so files under assets/lib into files/fb/.
     * 2.0.8: copies EVERY time, not only when a file is missing — a
     * truncated or corrupt lib from an earlier install used to be skipped
     * forever because it already existed. Returns error or null.
     */
    fun install(ctx: Context): String? {
        return try {
            val dir = binDir(ctx)
            val libs = libDir(ctx)
            dir.mkdirs()
            libs.mkdirs()
            ctx.assets.open("adb").use { input ->
                binFile(ctx).outputStream().use { output -> input.copyTo(output) }
            }
            binFile(ctx).setExecutable(true, false)
            val assetLibs = ctx.assets.list("lib") ?: emptyArray()
            for (name in assetLibs) {
                val out = File(libs, name)
                ctx.assets.open("lib/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            null
        } catch (e: Exception) {
            "adb install failed: ${e.message}"
        }
    }

    // adb needs a writable HOME for its key ($HOME/.android/adbkey).
    // Under `su` HOME is unset, so adb tries `//.android` on the
    // read-only root and aborts (FATAL: Cannot mkdir '//.android').
    // fastboot doesn't need a home dir, which is why it worked while
    // adb crashed. Point both at a dir inside the app. 2.0.8: the
    // app-owned dir is checked for writability AS ROOT once per process
    // (a root shell and the app user do not always agree on what is
    // writable); if root cannot write there, fall back to /data/local/tmp.
    private var resolvedHome: File? = null

    fun homeDir(ctx: Context): File {
        resolvedHome?.let { return it }
        val appHome = File(ctx.filesDir, "adb-home").apply { mkdirs() }
        val probe = Fastboot.runSu("touch '${appHome.absolutePath}/.wtest' && rm '${appHome.absolutePath}/.wtest'", {})
        val home = if (probe.exitCode == 0) appHome else File("/data/local/tmp/motoflasher-adb-home").apply { mkdirs() }
        resolvedHome = home
        return home
    }

    /** Run `adb <args...>` as root. Calls onLine for each output line.
     *  2.0.10: logs the full command line (env included) before the run
     *  and the exit code after. Output lines stream through onLine
     *  verbatim — stdout and stderr merged by Fastboot.runSu. */
    fun run(ctx: Context, args: List<String>, onLine: (String) -> Unit): Result {
        val bin = binFile(ctx)
        val lib = libDir(ctx)
        val home = homeDir(ctx)
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val cmd = "HOME='${home.absolutePath}' ANDROID_USER_HOME='${home.absolutePath}' " +
            "LD_LIBRARY_PATH='${lib.absolutePath}' '${bin.absolutePath}' $quoted"
        onLine("adb cmd: $cmd")
        val r = Fastboot.runSu(cmd, onLine)
        onLine("adb exit: ${r.exitCode} (adb ${args.joinToString(" ")})")
        return Result(r.exitCode, r.output)
    }

    /** Kill any adb server that is running (stale or started without root). */
    fun killServer(ctx: Context, onLine: (String) -> Unit): Result =
        run(ctx, listOf("kill-server"), onLine)

    /** Start the server explicitly and capture what it says. */
    fun startServer(ctx: Context, onLine: (String) -> Unit): Result =
        run(ctx, listOf("start-server"), onLine)

    /**
     * `adb devices` with a FRESH server: kill whatever daemon owns port
     * 5037, start a new one as root (same su path as every other call),
     * check the daemon is really up, log the USB bus, then list. A fresh
     * daemon enumerates USB at start, with the device already plugged
     * in — no hotplug needed. This is the 2.0.8 fix for the empty list
     * on 2.0.7.
     *
     * 2.0.10: every step is logged (kill-server result, start-server
     * result, daemon pid, port 5037 owner, USB bus listing, raw
     * `devices -l` output). If the list comes back empty, ONE retry
     * runs (kill-server, start-server, devices -l) and both attempts
     * stay in the log. An empty list is still reported as empty — no
     * fake success.
     */
    fun devicesFresh(ctx: Context, onLine: (String) -> Unit): Result {
        onLine("adb devices: fresh server, attempt 1")
        val first = devicesAttempt(ctx, onLine)
        if (hasDevice(first.output)) {
            onLine("adb devices: attempt 1 listed device(s)")
            return first
        }
        onLine("adb devices: attempt 1 came back EMPTY (exit ${first.exitCode}) — retrying once")
        onLine("adb devices: fresh server, attempt 2 (retry)")
        val second = devicesAttempt(ctx, onLine)
        if (hasDevice(second.output)) {
            onLine("adb devices: attempt 2 listed device(s)")
        } else {
            onLine("adb devices: attempt 2 also EMPTY (exit ${second.exitCode}) — server is up but sees nothing. Run Diagnose ADB for the full dump.")
        }
        return second
    }

    /** One fresh-server devices attempt, everything logged. */
    private fun devicesAttempt(ctx: Context, onLine: (String) -> Unit): Result {
        val killed = killServer(ctx, onLine)
        onLine("kill-server: exit ${killed.exitCode}")
        val started = startServer(ctx, onLine)
        onLine("start-server: exit ${started.exitCode}")
        daemonCheck(onLine)
        usbVisibility(ctx, onLine)
        val devices = run(ctx, listOf("devices", "-l"), onLine)
        onLine("devices -l: exit ${devices.exitCode}, raw output above (${devices.output.lines().count { it.isNotBlank() }} non-blank lines)")
        return devices
    }

    /**
     * Is an adb daemon actually running after start-server, and who owns
     * port 5037? All from the root shell, all logged. `ss`/`netstat` may
     * not exist or may hide the owner — the log says so plainly.
     */
    fun daemonCheck(onLine: (String) -> Unit) {
        onLine("--- adb daemon check (root shell) ---")
        Fastboot.runSu(
            "echo 'pidof adb:'; pidof adb 2>/dev/null || echo '(pidof found no adb)'; " +
                "echo 'ps adb:'; ps -A 2>/dev/null | grep '[a]db' || echo '(no adb process in ps)'; " +
                "echo 'port 5037 owner:'; ss -ltnp 2>/dev/null | grep ':5037' || netstat -ltnp 2>/dev/null | grep ':5037' || echo '(port 5037 owner not visible)'",
            { onLine(it) },
        )
        onLine("--- end adb daemon check ---")
    }

    /** True when `adb devices` output lists at least one device line. */
    fun hasDevice(output: String): Boolean =
        output.lines().any { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("List of devices") && !t.startsWith("* daemon")
        }

    /**
     * USB visibility, from the root shell's point of view: are the
     * /dev/bus/usb nodes even there, and is an adb daemon running as
     * root? If the nodes are listed here but `adb devices` is empty,
     * the fault is inside adb's USB enumeration; if the nodes are
     * missing here, the OTG device is not visible to the host at all.
     */
    fun usbVisibility(ctx: Context, onLine: (String) -> Unit) {
        onLine("--- USB visibility (root shell) ---")
        Fastboot.runSu(
            "echo 'ls -la /dev/bus/usb:'; ls -la /dev/bus/usb/ 2>&1; " +
                "echo 'ls -la /dev/bus/usb/*:'; ls -la /dev/bus/usb/* 2>&1; " +
                "echo 'device nodes:'; ls -l /dev/bus/usb/*/* 2>&1; " +
                "echo 'node count:'; ls /dev/bus/usb/*/* 2>/dev/null | wc -l; " +
                "echo 'adb daemons:'; ps -A 2>/dev/null | grep '[a]db' || echo '(no adb process in ps)'",
            { onLine(it) },
        )
        onLine("adb home: ${homeDir(ctx).absolutePath}")
        onLine("--- end USB visibility ---")
    }

    /**
     * Full ADB diagnosis in one shot, everything logged verbatim:
     * binary path + version, libs present, HOME path + writable?,
     * kill + fresh start-server, daemon pid + port 5037 owner, USB bus
     * listing, then a fresh-server devices run (with the one retry).
     * Call this when Devices shows an empty list. The whole dump is in
     * the More-tab log, copyable as usual.
     */
    fun diagnose(ctx: Context, onLine: (String) -> Unit): String {
        onLine("=== ADB diagnose (2.0.11 full dump) ===")
        val bin = binFile(ctx)
        onLine("adb binary: ${bin.absolutePath}")
        onLine("adb binary exists=${bin.exists()} size=${if (bin.exists()) bin.length() else -1} executable=${bin.canExecute()}")
        val libs = libDir(ctx).listFiles()?.map { it.name }?.sorted() ?: emptyList()
        onLine("adb libs (${libs.size} in ${libDir(ctx).absolutePath}): ${libs.joinToString(", ")}")
        val home = homeDir(ctx)
        val writable = Fastboot.runSu(
            "touch '${home.absolutePath}/.diagtest' && rm '${home.absolutePath}/.diagtest' && echo HOME_WRITABLE || echo HOME_NOT_WRITABLE",
            { onLine(it) },
        )
        onLine("adb HOME: ${home.absolutePath} (probe exit ${writable.exitCode})")
        val version = run(ctx, listOf("version"), onLine)
        onLine("adb version: exit ${version.exitCode}")
        val killed = killServer(ctx, onLine)
        onLine("kill-server: exit ${killed.exitCode} — starting a fresh one…")
        val started = startServer(ctx, onLine)
        onLine("start-server: exit ${started.exitCode}")
        daemonCheck(onLine)
        usbVisibility(ctx, onLine)
        val devices = devicesFresh(ctx, onLine)
        val found = hasDevice(devices.output)
        val summary = if (found) {
            "ADB diagnose: device found."
        } else {
            "ADB diagnose: no devices. Server up (exit ${devices.exitCode}), sees nothing. Check the USB lines in the log."
        }
        onLine(summary)
        onLine("=== end ADB diagnose ===")
        return summary
    }
}
