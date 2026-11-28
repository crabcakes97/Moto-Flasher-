// BromUi.kt — the BROM screen for Moto Flasher 2.0.1 (Penumbra engine).
//
// The BROM half talks to dead / bricked Motos over OTG through the
// bundled Antumbra binary (Penumbra v2.0.0, AGPL-3.0) instead of the
// old in-app mtkclient port, which is deleted. Every BROM operation
// is one Antumbra CLI invocation run as root from a fixed work dir
// (see Penumbra.kt); the app keeps policy in Kotlin and treats
// Antumbra as a dumb engine — same split of duties as the old
// rootusb helper.
//
// Style: neon cards via UiStyle, same as MainActivity. Views built
// programmatically, background work in Thread blocks, UI updates on
// runOnUiThread, all logging through the host logLine + a per-run
// BROM log file in Download/MotoFlasher/.
//
// HARD RULES (unchanged from 1.1.x):
// - No write gates anywhere. Check GPT is advisory only. DA/auth:
//   any file accepted, no checks — the handshake + DA upload inside
//   Antumbra are the check, and they fail loudly if wrong.
// - A confirm dialog still precedes every write, a wake lock is held
//   during writes, a failed write stops the run with no auto-retry.
// - GPT write runs only inside a blankflash run, only when the user
//   checks "Write GPT" (off by default), verified by read-back.
// - No exploit / crasher UI. No DA / auth bundling — both files are
//   picked from storage and stored app-private.
// - Partition selection is per-plan-load, in-memory only: all 11
//   partitions checked by default, GPT unchecked by default.
// - The device partition list comes FROM THE DEVICE's own GPT
//   (antumbra pgpt), never from the scatter file.
// - Finished files publish to Download/MotoFlasher/ (BromDownloads).
// - Root is re-checked FRESH on every write (1.1.19 lesson).
//
// FIRST-TEST WARNING (blocking, on-device, unproven): the bundled
// Antumbra binary has never executed on the S26 FE. Before any BROM
// work, tap "Test Antumbra binary" below — it runs `antumbra
// --version` as root and must print `antumbra 2.0.0`. If it fails,
// the Penumbra route is dead and 1.1.21 stays the fallback.
package com.cronch.motoflasher.brom

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.cronch.motoflasher.Fastboot
import com.cronch.motoflasher.setLed
import com.cronch.motoflasher.UiStyle
import java.io.File

class BromUi(
    private val activity: Activity,
    private val log: (String) -> Unit,
    private val toastFn: (String) -> Unit,
    private val confirmFn: (String, String, () -> Unit) -> Unit,
    private val acquireWakeFn: () -> Unit,
    private val releaseWakeFn: () -> Unit,
    private val revealLogFn: () -> Unit = {},
) {

    companion object {
        const val REQ_BROM_DA = 11
        const val REQ_BROM_AUTH = 12
        const val REQ_BROM_WRITE_FILE = 13
        const val REQ_BROM_BLANKFLASH_FOLDER = 14
    }

    private var blankflashPlan: ScatterPlan? = null
    private var blankflashScatterFile: File? = null
    private var blankflashFolderUri: Uri? = null
    private fun scatterEntryFor(name: String): ScatterPlanEntry? =
        blankflashPlan?.entries?.firstOrNull { it.partition.equals(name, ignoreCase = true) }
    private var blankflashCheckboxes = mutableListOf<CheckBox>()
    private var blankflashGptCheckbox: CheckBox? = null
    private var devicePartitions = listOf<GptPartition>()
    private var deviceCheckboxes = mutableListOf<CheckBox>()
    private var daFile: File? = null
    private var authFile: File? = null
    private var daSha = ""
    private var authSha = ""
    private var session: PenumbraSession? = null
    private var writeFile: File? = null
    private var writeFileName: String? = null
    private var writeFileSha = ""

    private lateinit var connText: TextView
    private lateinit var bromText: TextView
    private lateinit var gateText: TextView
    private lateinit var readText: TextView
    private lateinit var writeText: TextView
    private lateinit var blankText: TextView
    private lateinit var blankCheckContainer: LinearLayout
    private lateinit var writePartInput: android.widget.EditText
    private lateinit var readPartInput: android.widget.EditText
    private lateinit var deviceGptText: TextView
    private lateinit var deviceCheckContainer: LinearLayout
    private lateinit var rawAddrInput: android.widget.EditText
    private lateinit var rawLenInput: android.widget.EditText

    private val bromLogFile = BromLogFile(activity)

    @Volatile
    private var bromConnecting = false

    /** 2.0.13: true while a connect is running or a BROM session is
     *  open. The Home device watcher checks this so its fastboot
     *  polls never fight a BROM operation for the USB device. */
    fun isBusy(): Boolean = bromConnecting || session != null

    private fun blog(s: String) {
        bromLogFile.append(s)
        log(s)
    }

    @Suppress("DEPRECATION")
    private fun appVersionStamp(): String {
        return try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            "Moto Flasher ${info.versionName} (code ${info.versionCode})"
        } catch (_: Exception) {
            "Moto Flasher (version unknown)"
        }
    }

    private fun section(root: LinearLayout, title: String, body: String? = null): LinearLayout {
        val card = UiStyle.card(activity)
        card.addView(UiStyle.sectionTitle(activity, title))
        card.addView(UiStyle.divider(activity))
        if (body != null) card.addView(UiStyle.body(activity, body))
        root.addView(card)
        return card
    }

    fun addTo(root: LinearLayout) {
        var card = section(
            root, "BROM / Penumbra",
            "Recovery for dead phones over OTG. Uses DA and auth files. The GPT is only rewritten inside a blankflash run, when checked.",
        )
        card.addView(UiStyle.bromButton(activity, "Test Antumbra") { testAntumbraBinary() })
        card.addView(UiStyle.body(activity, "Run this first. It must print 'antumbra 2.0.0'."))

        card = section(
            root, "Connection",
            "Pick the DA and auth files. They are not checked here. The handshake is the check.",
        )
        connText = UiStyle.ledStatus(activity, "No DA or auth file picked yet.")
        card.addView(connText)
        card.addView(UiStyle.actionButton(activity, "Pick DA file") { pickDocument(REQ_BROM_DA) })
        card.addView(UiStyle.actionButton(activity, "Pick auth file") { pickDocument(REQ_BROM_AUTH) })
        card.addView(UiStyle.bromButton(activity, "Connect") { connectSession() })

        card = section(
            root, "BROM device",
            "Plug the target in. It shows up as a preloader first. Detect shows the VID and PID.",
        )
        bromText = UiStyle.ledStatus(activity, "No BROM device detected yet.")
        card.addView(bromText)
        card.addView(UiStyle.ghostButton(activity, "Detect BROM device") { detectDevice() })

        card = section(
            root, "Check GPT",
            "Optional. Confirms the DA is running. If it fails, the DA or auth is wrong, or the GPT is damaged.",
        )
        gateText = UiStyle.status(activity, "Optional. Buttons always work.")
        card.addView(gateText)
        card.addView(UiStyle.actionButton(activity, "Check GPT (pgpt)") { checkGpt() })

        card = section(
            root, "Device partitions",
            "The table is read from the phone. Check the partitions you want, then read, write, or erase.",
        )
        deviceGptText = UiStyle.status(activity, "No GPT read yet. Connect first.")
        card.addView(deviceGptText)
        deviceCheckContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(deviceCheckContainer)
        card.addView(UiStyle.actionButton(activity, "Read partitions") { readDevicePartitions() })
        card.addView(UiStyle.actionButton(activity, "Read selected") { readSelectedDevicePartitions() })
        card.addView(UiStyle.dangerButton(activity, "Write selected") { writeSelectedDevicePartitions() })
        card.addView(UiStyle.dangerButton(activity, "Erase selected") { eraseSelectedDevicePartitions() })

        card = section(
            root, "BROM read",
            "Reads by name. Files go to Download/MotoFlasher/pulls-brom/.",
        )
        readPartInput = UiStyle.input(activity, "partition to read")
        card.addView(readPartInput)
        readText = UiStyle.status(activity, "No BROM read yet.")
        card.addView(readText)
        card.addView(UiStyle.actionButton(activity, "Read partition") { readPartition(readPartInput.text.toString().trim()) })

        card = section(
            root, "BROM write",
            "Pick a partition and a file, then confirm. Writes one partition at a time and stops on failure. Writes are verified by read-back.",
        )
        writePartInput = UiStyle.input(activity, "partition to write")
        card.addView(writePartInput)
        writeText = UiStyle.status(activity, "No write file picked yet.")
        card.addView(writeText)
        card.addView(UiStyle.actionButton(activity, "Pick file to write") { pickDocument(REQ_BROM_WRITE_FILE) })
        card.addView(UiStyle.dangerButton(activity, "Write it") { writePartition() })

        card = section(
            root, "Raw flash",
            "Reads and writes by address (hex or decimal). ro reads bytes, rs reads sectors, wo writes the file picked in BROM write.",
        )
        rawAddrInput = UiStyle.input(activity, "address or start sector")
        card.addView(rawAddrInput)
        rawLenInput = UiStyle.input(activity, "length (bytes or sectors)")
        card.addView(rawLenInput)
        card.addView(UiStyle.actionButton(activity, "Read by offset (ro)") { readRawOffset() })
        card.addView(UiStyle.actionButton(activity, "Read by sector (rs)") { readRawSectors() })
        card.addView(UiStyle.dangerButton(activity, "Write by offset (wo)") { writeRawOffset() })

        card = section(
            root, "Blankflash",
            "Pick a blankflash package folder. All 11 partitions are checked by default. Writing the GPT is the highest-risk step and is verified by read-back. The phone reboots into fastboot at the end.",
        )
        blankText = UiStyle.status(activity, "No blankflash folder picked yet.")
        card.addView(blankText)
        blankCheckContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        card.addView(blankCheckContainer)
        card.addView(UiStyle.actionButton(activity, "Pick blankflash folder") { pickFolder() })
        card.addView(UiStyle.bromButton(activity, "Run blankflash") { runBlankflash() })

        card = section(
            root, "BROM reset",
            "Reboots into fastboot.",
        )
        card.addView(UiStyle.actionButton(activity, "Reset target") { resetTarget() })
    }

    // ---------- file picking (existing SAF pattern) ----------

    private fun pickDocument(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, req)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        activity.startActivityForResult(i, REQ_BROM_BLANKFLASH_FOLDER)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data?.data == null) return false
        val uri = data.data!!
        when (requestCode) {
            REQ_BROM_DA, REQ_BROM_AUTH -> {
                Thread {
                    val name = if (requestCode == REQ_BROM_DA) "da.bin" else "auth.auth"
                    val staged = copyUriToPrivate(uri, name) ?: run {
                        blog("BROM ERROR: could not copy picked file")
                        return@Thread
                    }
                    val sha = Fastboot.sha256(staged)
                    activity.runOnUiThread {
                        if (requestCode == REQ_BROM_DA) {
                            daFile = staged
                            daSha = sha
                        } else {
                            authFile = staged
                            authSha = sha
                        }
                        updateConnText()
                    }
                }.start()
                return true
            }
            REQ_BROM_WRITE_FILE -> {
                Thread {
                    val name = displayName(uri)
                    val staged = copyUriToCache(uri, name) ?: run {
                        blog("BROM ERROR: could not copy write file")
                        return@Thread
                    }
                    val sha = Fastboot.sha256(staged)
                    activity.runOnUiThread {
                        writeFile = staged
                        writeFileName = name
                        writeFileSha = sha
                        writeText.text = "Write file: $name (${staged.length()} bytes, sha256=$sha)"
                        blog("BROM write file picked: $name (${staged.length()} bytes, sha256=$sha)")
                    }
                }.start()
                return true
            }
            REQ_BROM_BLANKFLASH_FOLDER -> {
                blankflashFolderUri = uri
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) {}
                loadBlankflashFolder(uri)
                return true
            }
        }
        return false
    }

    private fun updateConnText() {
        // No DA/auth check at all. The lines show only the file name,
        // size, and SHA-256 (diagnostics for the log). No verdict, no
        // warning, no "known pair". The user picks a DA, the app uses
        // it — the handshake + DA upload inside Antumbra are the check,
        // and they fail loudly if the files are wrong.
        val daLine = if (daFile != null) {
            "DA: ${daFile!!.name} (${daFile!!.length()} bytes, sha256=$daSha)"
        } else "DA: not picked"
        val authLine = if (authFile != null) {
            "Auth: ${authFile!!.name} (${authFile!!.length()} bytes, sha256=$authSha)"
        } else "Auth: not picked"
        connText.text = "$daLine\n$authLine"
        connText.setLed(if (daFile != null && authFile != null) UiStyle.Status.OK else UiStyle.Status.INFO)
        blog("BROM connection:\n$daLine\n$authLine")
    }

    private fun displayName(uri: Uri): String {
        return try {
            activity.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "file"
            } ?: (uri.lastPathSegment ?: "file")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "file"
        }
    }

    private fun copyUriToPrivate(uri: Uri, name: String): File? {
        return try {
            val dir = File(activity.filesDir, "brom").apply { mkdirs() }
            val out = File(dir, name)
            activity.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            out
        } catch (e: Exception) {
            blog("BROM ERROR copying $name to app-private storage: ${e.message}")
            null
        }
    }

    private fun copyUriToCache(uri: Uri, name: String): File? {
        return try {
            val out = File(activity.cacheDir, "brom-stage-$name")
            activity.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            out
        } catch (e: Exception) {
            blog("BROM ERROR staging $name: ${e.message}")
            null
        }
    }

    // ---------- binary self-test (the blocking first test) ----------

    private fun testAntumbraBinary() {
        blog("Penumbra self-test: installing bundled Antumbra ${Penumbra.VERSION} and running --version as root…")
        Thread {
            val installErr = Penumbra.install(activity)
            if (installErr != null) {
                blog("Penumbra self-test FAILED: $installErr")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Antumbra install failed — see log", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val r = Penumbra.version(activity) { blog(it) }
            val ok = r.ok && r.output.contains("antumbra ${Penumbra.VERSION}")
            if (ok) {
                blog("Penumbra self-test OK: the binary executes on this phone and reports antumbra ${Penumbra.VERSION} — the Penumbra route is alive. (BROM flow still unproven: next, Connect with a DA + auth.)")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Antumbra ${Penumbra.VERSION} runs on this phone", Toast.LENGTH_LONG).show()
                }
            } else {
                blog("Penumbra self-test FAILED: --version exited ${r.exitCode} without the version string. The static binary does NOT execute on this phone — route (a) is dead, use 1.1.21 as the fallback and report this.")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Antumbra does not run on this phone — see log", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- detect / connect ----------

    private fun detectDevice() {
        Thread {
            val found = Penumbra.detectMtk(activity) { blog(it) }
            val first = found.firstOrNull()
            activity.runOnUiThread {
                if (first == null) {
                    bromText.text = "No BROM or preloader device found. Plug the target in. It shows up as a preloader first, with no buttons needed. Tap Connect and it grabs the preloader as soon as it appears."
                    bromText.setLed(UiStyle.Status.FAIL)
                    Toast.makeText(activity, "No BROM device found", Toast.LENGTH_SHORT).show()
                } else {
                    val vidPid = "VID %04X PID %04X".format(first.first, first.second)
                    bromText.text = "Found: $vidPid (${if (first.second == Penumbra.BROM_PID) "BROM" else "preloader VCOM"}) — chip: not identified until connect."
                    bromText.setLed(UiStyle.Status.OK)
                }
            }
            if (first == null) {
                blog("BROM detect: no MediaTek BROM/preloader device (VID 0E8D) found")
            } else {
                blog("BROM detect: VID %04X PID %04X".format(first.first, first.second))
            }
        }.start()
    }

    private fun connectSession() {
        val da = daFile ?: run { toastFn("Pick the DA file first"); return }
        val auth = authFile ?: run { toastFn("Pick the auth file first"); return }
        // No DA/auth check here. Whatever was picked is used as-is.
        if (!Penumbra.hasRootFresh { blog(it) }) {
            blog("BROM FAILED: root required — BROM needs root like fastboot. Get temp root on the host phone first, then tap Connect again.")
            activity.runOnUiThread {
                Toast.makeText(activity, "No root — BROM needs root like fastboot", Toast.LENGTH_LONG).show()
            }
            return
        }
        val installErr = Penumbra.install(activity)
        if (installErr != null) {
            blog("BROM FAILED: $installErr")
            return
        }
        if (bromConnecting) {
            toastFn("Connect already running")
            return
        }
        bromConnecting = true
        val logPath = bromLogFile.startRun()
        if (logPath != null) blog("BROM log file: $logPath")
        blog("BROM log — ${appVersionStamp()} — engine: Antumbra ${Penumbra.VERSION} (Penumbra commit ${Penumbra.COMMIT})")
        blog("BROM connect: waiting for a preloader / BROM device (up to 60 s, up to 3 connect cycles) — plug the target in now…")
        activity.runOnUiThread {
            bromText.text = "Waiting for a preloader / BROM device. Plug the target in…"
            bromText.setLed(UiStyle.Status.WAIT)
        }
        revealLogFn()
        Thread {
            try {
                val deadline = SystemClock.uptimeMillis() + 60_000L
                var connected = false
                var cycle = 0
                while (cycle < 3 && SystemClock.uptimeMillis() < deadline && !connected) {
                    cycle++
                    var waitedLog = false
                    var foundDevice = false
                    while (SystemClock.uptimeMillis() < deadline) {
                        val found = Penumbra.detectMtk(activity) { }
                        if (found.isNotEmpty()) { foundDevice = true; break }
                        if (!waitedLog && SystemClock.uptimeMillis() > deadline - 55_000L) {
                            blog("BROM connect: still waiting for the preloader — plug the target in (it shows up as preloader first).")
                            waitedLog = true
                        }
                        Thread.sleep(200)
                    }
                    if (!foundDevice) break
                    activity.runOnUiThread {
                        bromText.text = "Preloader / BROM device seen. Running Antumbra connect (cycle $cycle/3)…"
                        bromText.setLed(UiStyle.Status.WAIT)
                    }
                    val s = PenumbraSession(activity, da, auth)
                    session = s
                    val probe = s.connectProbe { blog(it) }
                    if (!probe.isOk()) {
                        if (isTransportFailure(probe.text())) {
                            blog("BROM connect (cycle $cycle/3): connect lost the transport: ${probe.text()} — re-scanning for the next preloader appearance.")
                            closeSession()
                            continue
                        }
                        blog("BROM FAILED: connect: ${probe.text()} — wrong DA/auth for this phone, or the DA entry did not match (see hw_subcode line above)")
                        closeSession()
                        break
                    }
                    blog("BROM connected. ${probe.text()}")
                    activity.runOnUiThread {
                        bromText.text = "Connected — ${probe.text()}"
                        bromText.setLed(UiStyle.Status.OK)
                    }
                    blog("BROM connected. Next: Check GPT (optional) — or run a read/write/blankflash directly.")
                    connected = true
                }
                if (!connected) {
                    blog("BROM FAILED: connect did not complete in 3 cycles / 60 s — plug the target in and tap Connect again.")
                    activity.runOnUiThread {
                        bromText.text = "Connect failed — no preloader / BROM device found in 60 s (see log)."
                        bromText.setLed(UiStyle.Status.FAIL)
                        Toast.makeText(activity, "Connect failed — see log", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                blog("BROM FAILED: connect stopped: ${e.message} — tap Connect again, it re-scans from scratch")
            } finally {
                bromConnecting = false
            }
        }.start()
    }

    private fun closeSession() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
    }

    private fun isTransportFailure(reason: String): Boolean {
        val r = reason.lowercase()
        return "timed out" in r || "write failed" in r || "could not open" in r ||
            "transport is closed" in r || "no such device" in r ||
            "usb read" in r || "sync error" in r || "no device" in r ||
            "not found" in r
    }

    private fun checkGpt() {
        val s = session ?: run {
            toastFn("Connect first")
            blog("BROM Check GPT: no session — connect first")
            return
        }
        blog("BROM Check GPT: reading GPT via Antumbra pgpt…")
        Thread {
            try {
                val r = s.readDevicePartitions { blog(it) }
                if (!r.isOk()) {
                    blog(
                        "BROM Check GPT FAILED: ${r.text()} — advisory only, writes are not gated on this. " +
                            "Wrong DA/auth, or a damaged GPT (PC + SP Flash Tool only).",
                    )
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Check GPT failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val parts = s.gptPartitions()
                blog("BROM Check GPT OK: ${r.text()} — ${parts.size} partitions in the table")
                for (p in parts) blog("  GPT: ${p.name} start=0x${p.startByte.toString(16)} size=${p.sizeBytes} bytes")
                activity.runOnUiThread {
                    gateText.text = "Check GPT OK — GPT reads (${parts.size} partitions). Advisory only; writes were never gated on this."
                    Toast.makeText(activity, "Check GPT passed — GPT reads OK", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                blog("BROM Check GPT FAILED: ${e.message} — advisory only, writes are not gated on this")
            }
        }.start()
    }

    // ---------- device-GPT picker + raw flash ----------

    private fun parseNum(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return try {
            if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLong(16)
            else t.toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun selectedDevicePartitions(): List<GptPartition> {
        return devicePartitions.filterIndexed { i, _ ->
            deviceCheckboxes.getOrNull(i)?.isChecked == true
        }
    }

    private fun readDevicePartitions() {
        val s = session ?: run {
            toastFn("Connect first")
            blog("BROM device partitions: no session — connect first")
            return
        }
        revealLogFn()
        blog("BROM device partitions: reading the GPT from the device via Antumbra pgpt…")
        Thread {
            try {
                val r = s.readDevicePartitions { blog(it) }
                if (!r.isOk()) {
                    activity.runOnUiThread {
                        deviceGptText.text = "Could not read GPT from device: ${r.text()}"
                        deviceCheckContainer.removeAllViews()
                        deviceCheckboxes.clear()
                        devicePartitions = listOf()
                    }
                    blog("BROM device partitions FAILED: ${r.text()} — no list shown; the scatter list is not shown as the device list")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Device GPT read failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val parts = s.gptPartitions()
                activity.runOnUiThread {
                    devicePartitions = parts
                    deviceGptText.text = "Device GPT: ${parts.size} partitions (from the phone, not the scatter) — check what you want to act on:"
                    deviceCheckContainer.removeAllViews()
                    deviceCheckboxes.clear()
                    for (p in parts) {
                        val box = UiStyle.styledCheckBox(
                            activity,
                            "${p.name} — start 0x${p.startByte.toString(16)} — ${p.sizeBytes} bytes",
                            false,
                        ) {}
                        deviceCheckboxes.add(box)
                        deviceCheckContainer.addView(box)
                    }
                    Toast.makeText(activity, "Device GPT: ${parts.size} partitions", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                blog("BROM device partitions FAILED: ${e.message}")
            }
        }.start()
    }

    private fun readSelectedDevicePartitions() {
        val selected = selectedDevicePartitions()
        if (selected.isEmpty()) {
            toastFn("Select a partition first")
            return
        }
        val s = session ?: run {
            toastFn("Connect first")
            return
        }
        revealLogFn()
        val names = selected.joinToString(", ") { it.name }
        blog("BROM read selected: $names to Download/MotoFlasher/pulls-brom/")
        Thread {
            acquireWakeFn()
            try {
                for (p in selected) {
                    val tmp = File(activity.cacheDir, "brom-read-${p.name}.img")
                    val r = s.readPartition(p.name, tmp) { blog(it) }
                    if (!r.isOk() || !tmp.exists() || tmp.length() == 0L) {
                        blog("BROM read selected FAILED at ${p.name}: ${r.text()} — stopping, no retry")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Read failed at ${p.name} — see log", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    val sha = Fastboot.sha256(tmp)
                    val published = BromDownloads.publish(activity, tmp, "pulls-brom", "${p.name}.img") { blog(it) }
                    if (published == null) {
                        blog("BROM read selected FAILED: ${p.name} was read but could not be published to Downloads — the private copy is at ${tmp.absolutePath}")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Read OK, but saving to Downloads failed — see log", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    blog("BROM read selected OK: ${p.name} saved to ${published.absolutePath} (${published.length()} bytes, sha256=$sha)")
                }
                activity.runOnUiThread {
                    Toast.makeText(activity, "Read ${selected.size} partition(s) to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                blog("BROM read selected FAILED: ${e.message} — no retry")
            } finally {
                releaseWakeFn()
            }
        }.start()
    }

    private fun writeSelectedDevicePartitions() {
        val selected = selectedDevicePartitions()
        if (selected.isEmpty()) {
            toastFn("Select a partition first")
            return
        }
        val file = writeFile ?: run { toastFn("Pick a file first"); return }
        val names = selected.joinToString("\n") { p ->
            "• ${p.name} (start 0x${p.startByte.toString(16)}, ${p.sizeBytes} bytes)"
        }
        val brickHit = selected.any { it.name in BromConstants.BRICK_RISK_PARTITIONS }
        val brickNote = if (brickHit) {
            "\n\nWarning: this partition is bootloader-critical. A bad flash bricks the phone until BROM recovery."
        } else ""
        confirmFn(
            "Write ${selected.size} device partition(s)?",
            "Operation: antumbra write from ${writeFileName ?: file.name}\n" +
                "File: ${file.length()} bytes, SHA-256: $writeFileSha\n$names$brickNote",
        ) {
            val s = session ?: run {
                toastFn("No session. Connect first.")
                return@confirmFn
            }
            revealLogFn()
            Thread {
                acquireWakeFn()
                try {
                    for (p in selected) {
                        blog("BROM write selected: ${p.name} from ${file.name} (${file.length()} bytes)")
                        val r = try {
                            val entry = scatterEntryFor(p.name)
                            if (entry != null) s.writeScatterEntry(entry, file) { blog(it) }
                            else s.writePartition(p.name, file) { blog(it) }
                        } catch (e: Exception) {
                            blog("BROM write selected stopped: ${p.name} failed mid-write (${e.message}) — USB disconnect or DA error. No retry: a partial write must not be re-run blind.")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Write stopped — see log. No retry.", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                        if (!r.isOk()) {
                            blog("BROM write selected FAILED: ${p.name}: ${r.text()} — stopping, no retry")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Write failed at ${p.name} — see log", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                        blog("BROM write selected OK: ${p.name}: ${r.text()}")
                    }
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Wrote ${selected.size} partition(s)", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    releaseWakeFn()
                }
            }.start()
        }
    }

    private fun eraseSelectedDevicePartitions() {
        val selected = selectedDevicePartitions()
        if (selected.isEmpty()) {
            toastFn("Select a partition first")
            return
        }
        val names = selected.joinToString("\n") { p ->
            "• ${p.name} (start 0x${p.startByte.toString(16)}, ${p.sizeBytes} bytes)"
        }
        confirmFn(
            "Erase ${selected.size} device partition(s)?",
            "$names\n\nThis erases the partition. There is no backup. The data is gone.",
        ) {
            val s = session ?: run {
                toastFn("No session. Connect first.")
                return@confirmFn
            }
            revealLogFn()
            Thread {
                acquireWakeFn()
                try {
                    for (p in selected) {
                        blog("BROM erase selected: ${p.name}")
                        val r = try {
                            s.erasePartition(p.name) { blog(it) }
                        } catch (e: Exception) {
                            blog("BROM erase selected stopped: ${p.name} failed mid-erase (${e.message}) — no retry.")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Erase stopped — see log. No retry.", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                        if (!r.isOk()) {
                            blog("BROM erase selected FAILED: ${p.name}: ${r.text()} — stopping, no retry")
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Erase failed at ${p.name} — see log", Toast.LENGTH_LONG).show()
                            }
                            return@Thread
                        }
                        blog("BROM erase selected OK: ${p.name}: ${r.text()}")
                    }
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Erased ${selected.size} partition(s)", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    releaseWakeFn()
                }
            }.start()
        }
    }

    private fun readRawOffset() {
        val addr = parseNum(rawAddrInput.text.toString())
            ?: run { toastFn("Type the address first"); return }
        val len = parseNum(rawLenInput.text.toString())
            ?: run { toastFn("Type the length in bytes first"); return }
        if (len <= 0) { toastFn("Length must be more than 0"); return }
        val s = session ?: run {
            toastFn("Connect first")
            return
        }
        revealLogFn()
        blog("BROM raw read (read-offset): addr=0x${addr.toString(16)} len=$len bytes to Download/MotoFlasher/pulls-brom/ro-0x${addr.toString(16)}.img")
        Thread {
            try {
                val tmp = File(activity.cacheDir, "brom-ro-0x${addr.toString(16)}.img")
                val r = s.readRawOffset(addr, len, tmp) { blog(it) }
                if (!r.isOk() || !tmp.exists() || tmp.length() == 0L) {
                    blog("BROM raw read FAILED: ${r.text()} — no file produced")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Raw read failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val sha = Fastboot.sha256(tmp)
                val published = BromDownloads.publish(activity, tmp, "pulls-brom", "ro-0x${addr.toString(16)}.img") { blog(it) }
                if (published == null) {
                    blog("BROM raw read FAILED: read OK but could not be published to Downloads — the private copy is at ${tmp.absolutePath}")
                    return@Thread
                }
                blog("BROM raw read published: ${published.absolutePath} (${published.length()} bytes, sha256=$sha)")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Raw read to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                blog("BROM raw read FAILED: ${e.message} — no retry")
            }
        }.start()
    }

    private fun readRawSectors() {
        val sector = parseNum(rawAddrInput.text.toString())
            ?: run { toastFn("Type the start sector first"); return }
        val count = parseNum(rawLenInput.text.toString())
            ?: run { toastFn("Type the sector count first"); return }
        if (count <= 0) { toastFn("Sector count must be more than 0"); return }
        val s = session ?: run {
            toastFn("Connect first")
            return
        }
        revealLogFn()
        blog("BROM sector read (read-offset by sector): start sector=$sector sectors=$count to Download/MotoFlasher/pulls-brom/rs-$sector.img")
        Thread {
            try {
                val tmp = File(activity.cacheDir, "brom-rs-$sector.img")
                val r = s.readSectors(sector, count, tmp) { blog(it) }
                if (!r.isOk() || !tmp.exists() || tmp.length() == 0L) {
                    blog("BROM sector read FAILED: ${r.text()} — no file produced")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Sector read failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val sha = Fastboot.sha256(tmp)
                val published = BromDownloads.publish(activity, tmp, "pulls-brom", "rs-$sector.img") { blog(it) }
                if (published == null) {
                    blog("BROM sector read FAILED: read OK but could not be published to Downloads — the private copy is at ${tmp.absolutePath}")
                    return@Thread
                }
                blog("BROM sector read published: ${published.absolutePath} (${published.length()} bytes, sha256=$sha)")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Sector read to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                blog("BROM sector read FAILED: ${e.message} — no retry")
            }
        }.start()
    }

    private fun writeRawOffset() {
        val addr = parseNum(rawAddrInput.text.toString())
            ?: run { toastFn("Type the address first"); return }
        val file = writeFile ?: run { toastFn("Pick a file first"); return }
        confirmFn(
            "Write by offset (write-offset)?",
            "Operation: antumbra write-offset 0x${addr.toString(16)} from ${writeFileName ?: file.name}\n" +
                "Size: ${file.length()} bytes\nSHA-256: $writeFileSha\n\n" +
                "No partition name is checked here — this writes raw bytes " +
                "at the address. Address 0 in the user area is refused " +
                "(that is the GPT).",
        ) {
            val s = session ?: run {
                toastFn("No session. Connect first.")
                return@confirmFn
            }
            revealLogFn()
            Thread {
                acquireWakeFn()
                try {
                    blog("BROM raw write (write-offset): 0x${addr.toString(16)} from ${file.name} (${file.length()} bytes)")
                    val r = try {
                        s.writeRawOffset(addr, file) { blog(it) }
                    } catch (e: Exception) {
                        blog("BROM raw write stopped: failed mid-write (${e.message}) — no retry.")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Raw write stopped — see log. No retry.", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    if (!r.isOk()) {
                        blog("BROM raw write FAILED: ${r.text()} — stopping, no retry")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Raw write failed — see log", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    blog("BROM raw write OK: ${r.text()}")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Raw write done", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    releaseWakeFn()
                }
            }.start()
        }
    }

    // ---------- read / write / blankflash / reset ----------

    private fun readPartition(part: String) {
        if (part.isEmpty()) { toastFn("Type the partition name first"); return }
        val s = session ?: run {
            toastFn("Connect first")
            return
        }
        blog("BROM read: $part to Download/MotoFlasher/pulls-brom/$part.img")
        revealLogFn()
        Thread {
            try {
                val tmp = File(activity.cacheDir, "brom-read-$part.img")
                val r = s.readPartition(part, tmp) { blog(it) }
                if (!r.isOk() || !tmp.exists() || tmp.length() == 0L) {
                    blog("BROM read FAILED: $part: ${r.text()} — no file produced")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Read failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val sha = Fastboot.sha256(tmp)
                blog("BROM read OK: $part (${tmp.length()} bytes, sha256=$sha)")
                val published = BromDownloads.publish(
                    activity, tmp, "pulls-brom", "$part.img",
                ) { blog(it) }
                if (published == null) {
                    blog("BROM read FAILED: $part was read but could not be published to Downloads — the private copy is at ${tmp.absolutePath}")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Read OK, but saving to Downloads failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                blog("BROM read published: ${published.absolutePath} (${published.length()} bytes, sha256=$sha)")
                activity.runOnUiThread {
                    readText.text = "Read $part: ${published.absolutePath} (${published.length()} bytes, sha256=$sha)"
                    Toast.makeText(activity, "Read $part to Downloads", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                blog("BROM read FAILED: $part stopped: ${e.message} — no retry")
            }
        }.start()
    }

    private fun writePartition() {
        val part = writePartInput.text.toString().trim()
        if (part.isEmpty()) { toastFn("Type the partition name first"); return }
        val file = writeFile ?: run { toastFn("Pick the file to write first"); return }
        val isBoot1 = part == "preloader"
        val isBrickRisk = part in BromConstants.BRICK_RISK_PARTITIONS
        val brickNote = if (isBrickRisk) {
            "\n\nWarning: $part is bootloader-critical. A bad flash here " +
                "bricks the phone until BROM recovery, which is this " +
                "feature, with the DA and auth you picked above. Flash only " +
                "a file built for this phone."
        } else ""
        val boot1Note = if (isBoot1) "\nPreloader writes are routed by the DA to the BOOT1 hardware region." else ""
        val scatterEntry = scatterEntryFor(part)
        val scatterNote = if (scatterEntry != null) {
            "\nScatter address: 0x${scatterEntry.scatterLinearStart.toString(16)} " +
                "(writes at the scatter address, no GPT read)"
        } else ""
        confirmFn(
            "Write $part?",
            "Operation: antumbra write $part from ${writeFileName ?: file.name}\n" +
                "Size: ${file.length()} bytes\nSHA-256: $writeFileSha\n" +
                "Writes are verified by read-back.$boot1Note$scatterNote\n\n" +
                "There is no backup of this partition. This overwrites it.$brickNote",
        ) { runSingleWrite(part, file) }
    }

    private fun runSingleWrite(part: String, file: File) {
        val s = session ?: run {
            toastFn("No session. Connect first.")
            return
        }
        revealLogFn()
        Thread {
            acquireWakeFn()
            try {
                blog("BROM write: $part from ${file.name} (${file.length()} bytes)")
                val r = try {
                    val entry = scatterEntryFor(part)
                    if (entry != null) s.writeScatterEntry(entry, file) { blog(it) }
                    else s.writePartition(part, file) { blog(it) }
                } catch (e: Exception) {
                    blog("BROM write stopped: $part failed mid-write (${e.message}) — USB disconnect or DA error. No retry: a partial write must not be re-run blind.")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Write stopped — see log. No retry.", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                if (!r.isOk()) {
                    blog("BROM write FAILED: $part: ${r.text()} — stopping, no retry")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Write failed — see log", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                blog("BROM write OK: $part: ${r.text()}")
                activity.runOnUiThread {
                    Toast.makeText(activity, "Wrote $part", Toast.LENGTH_SHORT).show()
                }
            } finally {
                releaseWakeFn()
            }
        }.start()
    }

    private fun loadBlankflashFolder(treeUri: Uri) {
        Thread {
            try {
                val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                val kids = mutableListOf<Triple<String, Uri, Long>>()
                activity.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1)
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        kids.add(Triple(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size))
                    }
                }
                val stagedDir = File(activity.cacheDir, "brom-blankflash").apply {
                    deleteRecursively()
                    mkdirs()
                }
                var staged = 0
                for ((name, uri, _) in kids) {
                    val out = File(stagedDir, name)
                    try {
                        activity.contentResolver.openInputStream(uri)?.use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        } ?: continue
                        staged++
                    } catch (e: Exception) {
                        blog("BROM blankflash: could not stage $name (${e.message}) — skipped")
                    }
                }
                blog("BROM blankflash: staged $staged files from the picked folder")
                blankflashScatterFile = BromScatter.findScatterFile(stagedDir)
                val result = BromScatter.buildPlan(stagedDir)
                val plan = when (result) {
                    is ScatterPlanResult.Fail -> {
                        blankflashPlan = null
                        activity.runOnUiThread {
                            blankText.text = "Blankflash plan refused: ${result.reason}"
                            blankCheckContainer.removeAllViews()
                            blankflashCheckboxes.clear()
                            blankflashGptCheckbox = null
                        }
                        blog("BROM blankflash plan refused: ${result.reason}")
                        return@Thread
                    }
                    is ScatterPlanResult.Ok -> result.plan
                }
                blankflashPlan = plan
                val planText = plan.entries.joinToString("\n") { e ->
                    "• ${e.partition} from ${e.fileName} (${e.sizeBytes} bytes, sha256=${e.sha256})" +
                        if (e.isBoot1) " [BOOT1]" else ""
                }
                val warnText = if (plan.warnings.isNotEmpty()) {
                    "\nWarnings:\n" + plan.warnings.joinToString("\n") { "• $it" }
                } else ""
                val gptText = "\n${BromScatter.PGPT_NOTE}\n${BromScatter.SGPT_NOTE}\n" +
                    "GPT entries built from scatter: ${plan.gptEntries.size}."
                activity.runOnUiThread {
                    blankText.text = "Blankflash plan (${plan.entries.size}/11 in scatter order, ${plan.project}, ${plan.storageType}):\n$planText$warnText$gptText"
                    blankCheckContainer.removeAllViews()
                    blankflashCheckboxes.clear()
                    for (e in plan.entries) {
                        val box = UiStyle.styledCheckBox(
                            activity,
                            "${e.partition} from ${e.fileName} (${e.sizeBytes} bytes)" +
                                if (e.isBoot1) " [BOOT1]" else "",
                            true,
                        ) {}
                        blankflashCheckboxes.add(box)
                        blankCheckContainer.addView(box)
                    }
                    val gptBox = UiStyle.styledCheckBox(
                        activity,
                        "Write GPT (rebuild from scatter, verified by read-back)",
                        false,
                    ) {}
                    blankflashGptCheckbox = gptBox
                    blankCheckContainer.addView(gptBox)
                }
                blog("BROM blankflash plan (${plan.entries.size}/11, ${plan.project}, ${plan.storageType}):\n$planText$warnText$gptText")
            } catch (e: Exception) {
                activity.runOnUiThread {
                    blankText.text = "Could not read folder: ${e.message}"
                    blog("BROM ERROR reading blankflash folder: ${e.message}")
                }
            }
        }.start()
    }

    private fun runBlankflash() {
        val plan = blankflashPlan ?: run {
            toastFn("Pick a blankflash folder first — no plan loaded")
            blog("BROM blankflash blocked: no plan loaded")
            return
        }
        if (plan.entries.size != BromConstants.BLANKFLASH_ORDER.size) {
            toastFn("Blankflash plan is incomplete — pick a folder with all 11 partitions")
            blog("BROM blankflash blocked: plan has ${plan.entries.size}/11 partitions")
            return
        }
        val selectedEntries = plan.entries.filterIndexed { i, _ ->
            blankflashCheckboxes.getOrNull(i)?.isChecked == true
        }
        val writeGpt = blankflashGptCheckbox?.isChecked == true
        if (selectedEntries.isEmpty() && !writeGpt) {
            toastFn("Select at least one partition or GPT")
            blog("BROM blankflash blocked: nothing selected — check a partition or Write GPT")
            return
        }
        val s = session ?: run {
            toastFn("Connect first")
            return
        }
        val gptLine = if (writeGpt) {
            "• GPT (primary + backup, ${plan.gptEntries.size} entries) — rebuilt from scatter, verified by read-back\n"
        } else {
            "• GPT: skipped (not selected)\n"
        }
        val summary = selectedEntries.joinToString("\n") { e ->
            "• ${e.partition} from ${e.fileName} (${e.sizeBytes} bytes)"
        }
        val title = if (writeGpt) {
            "Run blankflash (GPT + ${selectedEntries.size} partition${if (selectedEntries.size == 1) "" else "s"})?"
        } else {
            "Run blankflash (${selectedEntries.size} partition${if (selectedEntries.size == 1) "" else "s"}, no GPT)?"
        }
        val gptNote = if (writeGpt) {
            "First, the GPT is rebuilt from the scatter and written " +
                "(primary and backup, ${plan.gptEntries.size} entries). This is " +
                "the highest-risk step in the app. A bad GPT breaks the " +
                "partition table, so it is verified by read-back before any " +
                "partition is written, and the run stops if the table does not " +
                "match. Then each partition is written once, in scatter order. "
        } else {
            "No GPT write — partitions only. "
        }
        confirmFn(
            title,
            "$gptLine$summary\n\n" +
                gptNote +
                "The run stops on the first failure. There is no auto-retry. " +
                "A reset runs at the end, so the phone reboots into fastboot.",
        ) {
            revealLogFn()
            val scatterFile = blankflashScatterFile
            Thread {
                acquireWakeFn()
                try {
                    val what = if (writeGpt) "GPT + ${selectedEntries.size} partition(s)" else "${selectedEntries.size} partition(s), no GPT"
                    blog("BROM blankflash: starting $what")
                    val r = try {
                        s.runBlankflash(plan, selectedEntries, writeGpt, scatterFile) { blog(it) }
                    } catch (e: Exception) {
                        blog("BROM blankflash stopped: run failed mid-way (${e.message}) — USB disconnect or DA error. No retry.")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Blankflash stopped — see log. No retry.", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    if (!r.isOk()) {
                        blog("BROM blankflash FAILED: ${r.text()} — stopping, no retry")
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Blankflash stopped — see log", Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    blog("BROM blankflash OK: ${r.text()}")
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Blankflash done — phone reboots into fastboot", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    releaseWakeFn()
                }
            }.start()
        }
    }

    private fun resetTarget() {
        val s = session ?: run {
            toastFn("Connect first")
            return
        }
        blog("BROM reset: resetting target (reboot into fastboot).")
        Thread {
            try {
                val r = s.reset { blog(it) }
                blog("BROM reset: ${r.text()}")
            } catch (e: Exception) {
                blog("BROM reset FAILED: ${e.message}")
            }
        }.start()
    }
}
