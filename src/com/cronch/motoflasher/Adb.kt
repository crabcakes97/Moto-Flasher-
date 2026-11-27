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
 *
 * 2.0.12 — on 2.0.11 the daemon still came up (exit 0) with an EMPTY
 * device list after the retry, while fastboot/BROM over the same OTG
 * link worked. Diagnose now also dumps the kernel's view: every
 * /sys/bus/usb/devices entry (VID/PID/manufacturer/product/serial),
 * each interface's class/subclass/protocol + bound driver (an adb
 * interface is ff/42/01 — this shows whether one exists at all and
 * whether a kernel driver grabbed it), a 5s foreground
 * `adb server nodaemon` probe (the daemon's own USB attach messages,
 * verbatim), the host's sys.usb.config/sys.usb.state, and a dmesg
 * usb tail. Diagnosis only — the Devices flow is unchanged.
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
     * 2.0.12: run the adb server in the FOREGROUND for 5 seconds and
     * log everything it prints — its own USB enumeration/attach
     * messages, which a daemonized server swallows. Uses `timeout 5`
     * when the su shell has it; otherwise background + sleep + kill.
     * Caller must kill-server first (port 5037 must be free) and
     * kill-server again after, then restart normally.
     */
    fun daemonForegroundProbe(ctx: Context, onLine: (String) -> Unit) {
        onLine("--- adb server nodaemon probe (5s foreground) ---")
        val bin = binFile(ctx)
        val lib = libDir(ctx)
        val home = homeDir(ctx)
        val env = "HOME='${home.absolutePath}' ANDROID_USER_HOME='${home.absolutePath}' " +
            "LD_LIBRARY_PATH='${lib.absolutePath}'"
        val S = "${'$'}"
        Fastboot.runSu(
            "if command -v timeout >/dev/null 2>&1; then " +
                "echo '(probe via timeout 5)'; " +
                "$env timeout 5 '${bin.absolutePath}' server nodaemon 2>&1; " +
                "echo \"probe exit: ${S}?\"; " +
                "else " +
                "echo '(no timeout cmd — background + sleep 5 + kill)'; " +
                "$env '${bin.absolutePath}' server nodaemon 2>&1 & PROBE_PID=${S}!; " +
                "sleep 5; kill ${S}PROBE_PID 2>/dev/null; wait ${S}PROBE_PID 2>/dev/null; " +
                "echo \"probe exit: ${S}?\"; " +
                "fi",
            { onLine(it) },
        )
        onLine("--- end nodaemon probe ---")
    }

    /**
     * 2.0.12: the kernel's view of USB, verbatim from the root shell.
     * Every /sys/bus/usb/devices entry with idVendor/idProduct/
     * manufacturer/product/serial (root hubs marked), each interface's
     * class/subclass/protocol + bound driver — an adb interface is
     * ff/42/01, so this shows whether one exists at all and whether a
     * kernel driver grabbed it — then the host's sys.usb.config /
     * sys.usb.state and a dmesg usb tail (empty/restricted is logged
     * plainly by the dmesg readability check).
     */
    fun usbKernelDump(onLine: (String) -> Unit) {
        val S = "${'$'}"
        // 2.0.12 (from Kyle's 2.0.11 log: /dev/bus/usb missing in the
        // root shell while fastboot saw the phone): is usbfs mounted,
        // does sysfs see devices anyway, and is the su shell in a
        // private mount namespace hiding the nodes?
        onLine("--- USB fs + namespace check (root shell) ---")
        Fastboot.runSu(
            "echo 'ls -la /dev/bus/:'; ls -la /dev/bus/ 2>&1; " +
                "echo 'mount | grep -i usb:'; mount 2>&1 | grep -i usb || echo '(no usb mount found)'; " +
                "echo 'ls /sys/bus/usb/devices/:'; ls /sys/bus/usb/devices/ 2>&1; " +
                "echo \"su shell mnt ns: ${S}(readlink /proc/self/ns/mnt 2>&1)\"; " +
                "echo \"init mnt ns: ${S}(readlink /proc/1/ns/mnt 2>&1)\"",
            { onLine(it) },
        )
        onLine("--- USB kernel dump (sysfs, root shell) ---")
        Fastboot.runSu(
            "echo 'devices in /sys/bus/usb/devices:'; ls /sys/bus/usb/devices/ 2>&1; " +
                "for ${S}d in /sys/bus/usb/devices/*; do " +
                "echo \"== ${S}d ==\"; " +
                "case \"${S}d\" in */usb[0-9]*) echo '(root hub)';; esac; " +
                "for ${S}f in idVendor idProduct manufacturer product serial; do " +
                "if [ -r \"${S}d/${S}f\" ]; then echo \"${S}f: ${S}(cat ${S}d/${S}f 2>&1)\"; " +
                "else echo \"${S}f: (unreadable)\"; fi; " +
                "done; " +
                "for ${S}i in ${S}d:*; do " +
                "[ -d \"${S}i\" ] || continue; " +
                "echo \"-- interface ${S}i --\"; " +
                "for ${S}f in bInterfaceClass bInterfaceSubClass bInterfaceProtocol; do " +
                "echo \"${S}f: ${S}(cat ${S}i/${S}f 2>&1)\"; " +
                "done; " +
                "echo \"driver: ${S}(readlink ${S}i/driver 2>&1)\"; " +
                "done; " +
                "done",
            { onLine(it) },
        )
        onLine("--- host USB mode (getprop) ---")
        Fastboot.runSu(
            "echo \"sys.usb.config: ${S}(getprop sys.usb.config 2>&1)\"; " +
                "echo \"sys.usb.state: ${S}(getprop sys.usb.state 2>&1)\"",
            { onLine(it) },
        )
        onLine("--- dmesg usb tail ---")
        Fastboot.runSu(
            "dmesg 2>&1 | grep -i usb | tail -n 60; " +
                "echo '(end dmesg usb lines — empty above = no usb lines in dmesg, or dmesg restricted)'; " +
                "echo 'dmesg last 3 lines (readable check):'; dmesg 2>&1 | tail -n 3",
            { onLine(it) },
        )
        onLine("--- end USB kernel dump ---")
    }

    /**
     * Full ADB diagnosis in one shot, everything logged verbatim:
     * binary path + version, libs present, HOME path + writable?,
     * kill + 5s foreground nodaemon probe + fresh start-server,
     * daemon pid + port 5037 owner, USB bus listing, kernel sysfs USB
     * dump (devices, interfaces, drivers), host sys.usb props, dmesg
     * usb tail, then a fresh-server devices run (with the one retry).
     * Call this when Devices shows an empty list. The whole dump is in
     * the More-tab log, copyable as usual.
     */
    fun diagnose(ctx: Context, onLine: (String) -> Unit): String {
        onLine("=== ADB diagnose (2.0.12 full dump) ===")
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
        onLine("kill-server: exit ${killed.exitCode} — foreground probe next…")
        // 2.0.12: foreground probe with port 5037 free, then kill
        // whatever it left and restart normally.
        daemonForegroundProbe(ctx, onLine)
        val killedAfterProbe = killServer(ctx, onLine)
        onLine("kill-server after probe: exit ${killedAfterProbe.exitCode} — starting a fresh one…")
        val started = startServer(ctx, onLine)
        onLine("start-server: exit ${started.exitCode}")
        daemonCheck(onLine)
        usbVisibility(ctx, onLine)
        usbKernelDump(onLine)
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
