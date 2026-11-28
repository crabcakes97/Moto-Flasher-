package com.cronch.motoflasher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cronch.motoflasher.brom.BromDownloads
import com.cronch.motoflasher.brom.BromUi
import com.cronch.motoflasher.brom.Penumbra
import com.cronch.motoflasher.valproto.LkStaticAnalyzer
import com.cronch.motoflasher.valproto.PresetArgs
import com.cronch.motoflasher.valproto.PyInt
import com.cronch.motoflasher.valproto.PyStr
import com.cronch.motoflasher.valproto.ValKeygen
import com.cronch.motoflasher.valproto.ValPatcher
import com.cronch.motoflasher.valproto.ValPresets
import com.cronch.motoflasher.valproto.ValRepack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Moto Flasher v2.0.1 — flash Motorola phones from this rooted phone over OTG.
 * Fastboot only for writes. Val Protocol built in as: liblk container parsing
 * (Kotlin port), LK picker/extract/flash, the guided unlock flow, the adb
 * puller (pull partitions off a target that has temp root), and the full
 * Val patch flow in-app — Capstone analysis (native), preset patcher,
 * CERT2 repack and keygen (Kotlin ports of lk_static_analyzer.py,
 * lk_patch_partition.py, lk_repack_signed.py, lk_keygen.py). BROM /
 * Penumbra is the section after Val: the bundled Antumbra binary
 * (Penumbra v2.0.0, AGPL-3.0) does handshake + DA + auth + flash over
 * its CLI; DA + auth only, no crasher path, full blankflash incl.
 * optional GPT write (opt-in, verified by read-back). No gates anywhere
 * in the BROM flow. Finished files (Val outputs, BROM reads) publish to
 * Download/MotoFlasher/ via BromDownloads. Crash catcher writes
 * motoflasher-crash-*.txt to Download/MotoFlasher/. BROM opens
 * /dev/bus/usb through root like fastboot and writes BROM logs to
 * Download/MotoFlasher/motoflasher-brom-*.txt (one file per Connect run).
 * New GUI (2.0.1): whole app shell — top bar + bottom tabs (Home /
 * banner, cyan accents, Exo 2 font, rainbow titles.
 */
class MainActivity : Activity() {

    private val REQ_SINGLE_IMG = 1
    private val REQ_BOOT_IMG = 2
    private val REQ_FOLDER = 3
    private val REQ_CUSTOM_KEY = 4
    private val REQ_TWRP_IMG = 5
    private val REQ_LK_FILE = 6
    // 2.0.13: boot image inspector picker (read-only).
    private val REQ_BOOT_INSPECT = 7

    // 2.0.1 tabs: Home / Flash / BROM / Val / More. 2.0.7 adds the ADB tab.
    // 2.0.8 adds the Extras tab (LK commands).
    private val TAB_HOME = 0
    private val TAB_FLASH = 1
    private val TAB_BROM = 2
    private val TAB_VAL = 3
    private val TAB_ADB = 4
    private val TAB_EXTRAS = 5
    private val TAB_MORE = 6

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var deviceCard: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    // 2.0.13: animation + operation state. opCount tracks running
    // flash/BROM/ADB/Val operations (incremented by opStarted,
    // decremented by opFinished — acquireWake/releaseWake hook in
    // too, which covers the BROM writes). It drives the progress
    // strip, the header bolt glow, the status LED pulse, the result
    // edge flash, and gates the Home device watcher so it never
    // fights an operation for the USB device.
    private lateinit var progressStrip: UiStyle.ProgressStripView
    private lateinit var edgeFlash: UiStyle.EdgeFlashView
    private lateinit var boltView: View
    private var boltPulse: ValueAnimator? = null
    private val opCount = AtomicInteger(0)
    // 2.0.13: live device watcher (Home tab). Polls `fastboot
    // devices` quietly every ~3s; logs only on change.
    private val watcherHandler = Handler(Looper.getMainLooper())
    private var watcherRunning = false
    @Volatile private var lastSerial: String? = null
    private val deviceInfoCache = mutableMapOf<String, Pair<String, String>>()
    private val watcherTick = object : Runnable {
        override fun run() {
            watcherPoll()
            if (watcherRunning) watcherHandler.postDelayed(this, 3000)
        }
    }
    // 2.0.13: boot image inspector readout (Flash tab).
    private lateinit var inspectText: TextView
    // 2.0.13: Val flow step chips (pull/analyze/patch/flash/unlock/verify).
    private val valChips = mutableListOf<TextView>()
    private var valChipPulse: ValueAnimator? = null
    private var valActiveChip = -1
    // 2.0.14: verdict check/X next to the Home status line; the
    // shimmer's active card (last card touched) + its animator;
    // flash-all step counter (text + tick animator + oversize list).
    private lateinit var statusMark: UiStyle.CheckMarkView
    private var activeCard: View? = null
    private var shimmerAnimator: ValueAnimator? = null
    private lateinit var flashAllProgressText: TextView
    private var flashAllTick: ValueAnimator? = null
    private val flashAllOversize = mutableListOf<String>()
    @Volatile private var flashAllRunning = false
    // 2.0.14: log filter (display only; logBuf keeps everything) and
    // the Val tab bootloader-status readout.
    private var logFilter: String = ""
    private lateinit var logFilterInput: EditText
    private lateinit var valStatusText: TextView
    // 2.0.14: back up the target partition before single/quick
    // flashes (Flash tab toggle, persisted, default off).
    private var autoBackup = false
    // 2.0.1 app shell: one ScrollView per tab, only the active one
    // visible. revealLog() switches to the More tab, where the log lives.
    private lateinit var tabScrolls: Array<ScrollView>
    private lateinit var tabBar: UiStyle.TabBar
    private var currentTab = 0
    private lateinit var moreScroll: ScrollView
    private lateinit var logCard: LinearLayout
    private lateinit var partInput: EditText
    private lateinit var rawInput: EditText
    private lateinit var unlockKeyInput: EditText
    private lateinit var flashAllBox: LinearLayout
    private lateinit var flashAllLabel: TextView

    private var singleImgUri: Uri? = null
    private var singleImgName: String? = null
    private var bootImgUri: Uri? = null
    private var bootImgName: String? = null
    private var customKeyUri: Uri? = null
    private var customKeyName: String? = null
    private var folderUri: Uri? = null
    private var twrpImgUri: Uri? = null
    private var twrpImgName: String? = null
    private var twrpTarget = "recovery"
    private var lkFileUri: Uri? = null
    private var lkFileName: String? = null
    private var lkExtracted: File? = null
    private var pendingPartition: String? = null
    // 2.0.12: whether the pending picked-file flash gets the slot
    // picker's suffix. Quick-flash = yes; Extras flash = no (raw
    // command tab, the typed name is exact). Reset to true on use.
    private var pendingApplySlot = true
    private lateinit var pullPartInput: EditText
    private lateinit var lkPartInput: EditText
    private lateinit var lkStatusText: TextView
    private lateinit var bromUi: BromUi
    // 2.0.7: ADB tab widgets.
    private lateinit var adbInput: EditText
    private lateinit var adbStatusText: TextView
    // 2.0.8: Extras tab argument box (partition / getvar name / slot),
    // and the Flash-all "add erase step" box.
    private lateinit var extrasArgInput: EditText
    private lateinit var eraseStepInput: EditText

    private data class FlashItem(
        // 2.0.6: label is a var — the slot picker re-labels flash steps
        // (boot -> boot_a) so the checkboxes show the partition that will
        // actually be flashed. Rebuilt from partition/filename, never typed.
        var label: String,
        val partition: String?,
        val filename: String?,
        val uri: Uri?,
        val size: Long,
        val risky: Boolean,
        var checked: Boolean = true,
        val chunks: List<FlashItem> = emptyList(),
    )
    private val flashItems = mutableListOf<FlashItem>()
    private val checkBoxes = mutableListOf<CheckBox>()

    private val logBuf = StringBuilder()
    private var logLines = 0
    private var followLog = true
    private lateinit var followButton: Button
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var rootOk = false

    private val RISKY_PARTS = setOf("lk", "lk_b", "vbmeta", "vbmeta_system", "boot", "bootloader", "preloader")

    // 2.0.6: flash-all target slot. SLOT_ACTIVE = the slot the phone is
    // on now — no suffix, exactly the old behavior. SLOT_A / SLOT_B append
    // _a / _b to the A/B partition names in the flash commands (boot ->
    // boot_a, vbmeta -> vbmeta_b, ...). Session-only; resets to Active on
    // every launch so a stale pick can never surprise-flash the other slot.
    private val SLOT_ACTIVE = 0
    private val SLOT_A = 1
    private val SLOT_B = 2
    private var flashSlot = SLOT_ACTIVE
    private lateinit var slotRow: LinearLayout
    private var flashAllSource = ""

    /** Base names that exist as _a/_b pairs in the Nevada GPT
     *  (MT6835_Android_scatter_blankflash.xml: boot_a/boot_b, dtbo_a,
     *  init_boot_a, vendor_boot_a, vbmeta_a, vbmeta_system_a, lk_a,
     *  logo_a, md1img_a, ...). Anything NOT in this set is a single
     *  partition (super, modem, dsp, preloader, ...) or a logical
     *  partition inside super (system, vendor, product, odm, oem) —
     *  flashing it with a suffix would fail, so it goes unchanged. */
    private val AB_PARTITIONS = setOf(
        "boot", "dtbo", "init_boot", "vendor_boot", "vbmeta", "vbmeta_system",
        "lk", "logo", "md1img",
        "connsys_bt", "connsys_gnss", "connsys_wifi", "dpm", "gz", "mcf_ota",
        "mcupm", "mot_reserved", "pi_img", "scp", "spmfw", "sspm", "tee",
        "tzapp", "vcp",
    )

    /** Effective partition name for the chosen flash-all slot. */
    private fun slotPartition(partition: String): String {
        val suffix = when (flashSlot) {
            SLOT_A -> "_a"
            SLOT_B -> "_b"
            else -> return partition
        }
        val base = partition.lowercase()
        if (base in AB_PARTITIONS && !base.endsWith("_a") && !base.endsWith("_b")) {
            return partition + suffix
        }
        return partition
    }

    private fun slotLabel(): String = when (flashSlot) {
        SLOT_A -> "Slot A"
        SLOT_B -> "Slot B"
        else -> "Active"
    }

    // 2.0.8: LK command tables for the Extras tab, verified by parsing
    // the command tables (not just strings) in BOTH LKs — the factory LK
    // (blankflash-nevada lk.img) and Kyle's neon v2 LK. The tables are
    // IDENTICAL in both; only the CERT2/signature region differs. Full
    // extraction notes: LK_EXTRACTION_2.0.8.md. Neither LK has a
    // `format` command — erase is the replacement. `super` is a
    // PARTITION name in the LK, not a fastboot command (listed with a
    // note; running it shows the real answer).
    private val LK_FASTBOOT_CMDS = listOf(
        "download", "flash", "getvar", "erase", "boot", "reboot",
        "reboot-bootloader", "oem", "continue", "flashing", "set_active",
        "reboot-recovery", "reboot-fastboot", "partition", "super",
    )
    private val LK_OEM_CMDS = listOf(
        "config", "hw", "gpt_test", "fb_mode_set", "fb_mode_clear", "regex",
        "partition", "logdump", "blankflash", "partitions", "show_screen",
        "backlight", "logo", "ramdump", "off-mode-charge", "led", "display",
        "batt-voltage", "hwid", "ab_reset", "cid_prov_req", "shwi_test",
        "ssm_test", "unlock", "lock", "get_unlock_data", "prod_fuse", "read_sv",
    )
    // OEM names found as strings only — NOT referenced by any pointer in
    // a dispatch table, so they may not run. Tapping one runs it and
    // shows the LK's real answer, never a fake success.
    private val LK_OEM_STRING_ONLY = listOf(
        "cdms", "dump_pllk_log", "get_socid", "mrdump", "mrdump_chkimg",
        "mrdump_fallocate", "mrdump_out_set", "norts_mrdump",
        "printk-ratelimit", "ultraflash", "ultraflash_en", "usb2jtag",
        "hwid_list", "hwid_list_all", "locked", "utags_ext", "keystore",
    )
    // OEM subcommands that ask for confirmation first (same rule as
    // every other write in the app: confirm before running).
    private val LK_OEM_DANGEROUS = setOf(
        "unlock", "lock", "blankflash", "ab_reset", "prod_fuse", "cid_prov_req",
    )
    // Set when the Extras `boot` entry opens the file picker, so the
    // picked image boots right away (same confirm as Boot picked image).
    private var pendingExtrasBoot = false

    // Crash catcher (1.1.1): 1.1.0 died on launch with no trace anywhere.
    // Writes motoflasher-crash-<stamp>.txt to Download/MotoFlasher/ so the
    // next crash names itself instead of showing only the system dialog.
    // MediaStore first (scoped storage allows it on targetSdk 34), the
    // app-private folder as fallback. Installed first in onCreate.
    private fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                val trace = StringBuilder().append("Moto Flasher crash $stamp\n")
                trace.append(error.toString()).append("\n")
                error.stackTrace.forEach { trace.append("  at ").append(it.toString()).append("\n") }
                var cause = error.cause
                while (cause != null) {
                    trace.append("Caused by: ").append(cause.toString()).append("\n")
                    cause.stackTrace.forEach { trace.append("  at ").append(it.toString()).append("\n") }
                    cause = cause.cause
                }
                val name = "motoflasher-crash-$stamp.txt"
                val bytes = trace.toString().toByteArray(Charsets.UTF_8)
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MotoFlasher")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                } catch (_: Exception) {
                }
                try {
                    File(getExternalFilesDir(null), name).writeBytes(bytes)
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashCatcher()
        super.onCreate(savedInstanceState)
        // 2.0.8: apply the persisted theme BEFORE building any UI, so
        // every card/button reads the picked palette at build time.
        val prefs = getSharedPreferences("motoflasher", Context.MODE_PRIVATE)
        UiStyle.applyTheme(prefs.getString("theme", "neon") ?: "neon")
        // 2.0.14: Settings (More tab) — read before building any UI
        // so every builder and animation helper sees them from the
        // first frame. Defaults = the pre-2.0.14 behavior.
        UiStyle.animationsEnabled = prefs.getBoolean("animations", true)
        followLog = prefs.getBoolean("follow_at_launch", true)
        autoBackup = prefs.getBoolean("auto_backup", false)
        if (prefs.getBoolean("keep_screen_on", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // 2.0.1 app shell: top app bar (logo + title + status), one
        // ScrollView per tab (only the active one is visible), bottom
        // tab bar. Every 2.0.0 section still exists, grouped by tab —
        // nothing was cut, only moved.
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.0.12: layered page background (vertical gradient,
            // darker at the bottom); panels sit one step lighter.
            background = UiStyle.pageBackground()
        }
        val appBar = UiStyle.appBar(this)
        boltView = appBar.bolt
        shell.addView(appBar.bar)
        // 2.0.13: operation progress strip under the title bar
        // (hidden until an operation runs).
        progressStrip = UiStyle.ProgressStripView(this)
        shell.addView(progressStrip)

        fun tabRoot(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val homeRoot = tabRoot()
        val flashRoot = tabRoot()
        val bromRoot = tabRoot()
        val valRoot = tabRoot()
        val adbRoot = tabRoot()
        val extrasRoot = tabRoot()
        val moreRoot = tabRoot()
        moreScroll = ScrollView(this).apply { addView(moreRoot) }
        tabScrolls = arrayOf(
            ScrollView(this).apply { addView(homeRoot) },
            ScrollView(this).apply { addView(flashRoot) },
            ScrollView(this).apply { addView(bromRoot) },
            ScrollView(this).apply { addView(valRoot) },
            ScrollView(this).apply { addView(adbRoot) },
            ScrollView(this).apply { addView(extrasRoot) },
            moreScroll,
        )
        tabScrolls.forEach { scroll ->
            scroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        tabScrolls.forEachIndexed { i, scroll ->
            scroll.visibility = if (i == TAB_HOME) View.VISIBLE else View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        tabScrolls.forEach { content.addView(it) }
        // 2.0.13: the edge-flash overlay sits on top of the content
        // (never clickable) so an operation result can flash the
        // screen edge green/red without touching any card.
        edgeFlash = UiStyle.EdgeFlashView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            addView(content)
            addView(edgeFlash)
        }
        shell.addView(contentFrame)

        tabBar = UiStyle.tabBar(
            this,
            listOf("Home", "Flash", "BROM", "Val", "ADB", "Extras", "More"),
        ) { index -> showTab(index) }
        shell.addView(tabBar.view)
        setContentView(shell)

        var card: LinearLayout = UiStyle.card(this)
        fun section(target: LinearLayout, t: String) {
            card = UiStyle.card(this)
            card.addView(UiStyle.sectionTitle(this, t))
            card.addView(UiStyle.divider(this))
            target.addView(card)
        }

        val statusCard = UiStyle.card(this)
        statusCard.addView(UiStyle.sectionTitle(this, "Status"))
        statusCard.addView(UiStyle.divider(this))
        // 2.0.14: the status line shares a row with the verdict
        // check/X mark (stroked in when an operation finishes).
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statusText = UiStyle.ledStatus(this, "Starting…")
        statusText.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        statusRow.addView(statusText)
        statusMark = UiStyle.CheckMarkView(this)
        statusRow.addView(statusMark)
        statusCard.addView(statusRow)
        homeRoot.addView(statusCard)

        section(homeRoot, "Device")
        deviceCard = card
        card.addView(UiStyle.ghostButton(this, "Refresh") { refreshDevices() })
        card.addView(UiStyle.ghostButton(this, "Getvar all") { getvarAll() })
        deviceText = UiStyle.ledStatus(this, "No device")
        card.addView(deviceText)

        section(flashRoot, "Flash one partition")
        partInput = UiStyle.input(this, "partition name")
        card.addView(partInput)
        card.addView(UiStyle.actionButton(this, "Pick image") { pickFile(REQ_SINGLE_IMG) })
        card.addView(UiStyle.actionButton(this, "Flash it") { flashSingle() })
        // 2.0.14: optional pre-flash backup (persisted, default off).
        // When on, single/quick flashes pull the target partition to
        // Download/MotoFlasher/backups/ first; a failed backup asks
        // before flashing — it never blocks silently.
        card.addView(UiStyle.styledCheckBox(this, "Back up partition before flashing", autoBackup) { on ->
            autoBackup = on
            prefs.edit().putBoolean("auto_backup", on).apply()
            logLine("Auto-backup ${if (on) "ON" else "OFF"}")
        })

        section(flashRoot, "Erase")
        card.addView(UiStyle.dangerButton(this, "Erase partition above") { erasePart() })

        section(flashRoot, "Flash all")
        // 2.0.6: target-slot picker — Active (default, no suffix, same as
        // before) / Slot A / Slot B. The picked slot is applied to the
        // A/B partition names in the flash commands; the checkbox labels
        // below update to show the partition that will actually be flashed.
        card.addView(UiStyle.body(this, "Target slot for A/B partitions. Active is the phone's current slot."))
        slotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(slotRow)
        refreshSlotRow()
        card.addView(UiStyle.actionButton(this, "Pick folder") { pickFolder() })
        flashAllLabel = UiStyle.status(this, "No folder picked.")
        card.addView(flashAllLabel)
        flashAllBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(flashAllBox)
        card.addView(UiStyle.actionButton(this, "Flash selected") { flashSelected() })
        // 2.0.14: flash-all step counter — ticks up with the
        // determinate progress strip while a run is going.
        flashAllProgressText = UiStyle.status(this, "")
        card.addView(flashAllProgressText)
        // 2.0.8: add an erase step by hand (erase is a first-class step
        // type alongside flash — the Moto has no format command).
        eraseStepInput = UiStyle.input(this, "partition to erase")
        card.addView(eraseStepInput)
        card.addView(UiStyle.dangerButton(this, "Add erase step") { addEraseStep() })

        section(moreRoot, "Reboot / slot")
        card.addView(UiStyle.ghostButton(this, "Reboot to system") { fbCmd(listOf("reboot"), "reboot") })
        card.addView(UiStyle.ghostButton(this, "Reboot to bootloader") { fbCmd(listOf("reboot", "bootloader"), "reboot bootloader") })
        card.addView(UiStyle.ghostButton(this, "Reboot to recovery") { fbCmd(listOf("reboot", "recovery"), "reboot recovery") })
        card.addView(UiStyle.ghostButton(this, "Set slot A") { fbCmd(listOf("set_active", "a"), "set_active a") })
        card.addView(UiStyle.ghostButton(this, "Set slot B") { fbCmd(listOf("set_active", "b"), "set_active b") })
        card.addView(UiStyle.actionButton(this, "Pick image to boot") { pickFile(REQ_BOOT_IMG) })
        card.addView(UiStyle.actionButton(this, "Boot picked image") { bootImage() })

        section(valRoot, "Bootloader unlock")
        card.addView(UiStyle.ghostButton(this, "Bootloader status") { bootloaderStatus() })
        card.addView(UiStyle.ghostButton(this, "Get unlock data") { fbCmd(listOf("oem", "get_unlock_data"), "get_unlock_data") })
        unlockKeyInput = UiStyle.input(this, "unlock key")
        card.addView(unlockKeyInput)
        card.addView(UiStyle.dangerButton(this, "Unlock with key") { unlockWithKey() })
        card.addView(UiStyle.dangerButton(this, "Relock") { relock() })

        section(valRoot, "Custom AVB key")
        card.addView(UiStyle.actionButton(this, "Pick key file") { pickFile(REQ_CUSTOM_KEY) })
        card.addView(UiStyle.actionButton(this, "Flash key") { flashCustomKey() })
        card.addView(UiStyle.dangerButton(this, "Erase key") { eraseCustomKey() })
        card.addView(UiStyle.body(this, "Use Bootloader status to check the result."))

        section(moreRoot, "More fastboot")
        card.addView(UiStyle.ghostButton(this, "flashing unlock") { fbCmd(listOf("flashing", "unlock"), "flashing unlock") })
        card.addView(UiStyle.dangerButton(this, "flashing lock") { fbCmd(listOf("flashing", "lock"), "flashing lock") })
        card.addView(UiStyle.ghostButton(this, "flashing get_unlock_ability") { fbCmd(listOf("flashing", "get_unlock_ability"), "get_unlock_ability") })
        card.addView(UiStyle.ghostButton(this, "Reboot to fastbootd") { fbCmd(listOf("reboot", "fastboot"), "reboot fastboot") })
        card.addView(UiStyle.ghostButton(this, "Continue boot") { fbCmd(listOf("continue"), "continue") })

        section(flashRoot, "Quick flash")
        card.addView(UiStyle.body(this, "Tap a partition, pick its image, then confirm."))
        val quickParts = listOf(
            "boot", "init_boot", "vendor_boot", "recovery", "dtbo",
            "vbmeta", "vbmeta_system", "super", "logo", "modem", "dsp", "lk",
        )
        for (part in quickParts) {
            card.addView(UiStyle.actionButton(this, "Flash $part…") { flashPartitionQuick(part) })
        }

        section(flashRoot, "TWRP")
        card.addView(UiStyle.body(this, "Pick a TWRP image. Flash it or boot it."))
        val twrpTargetText = UiStyle.status(this, "TWRP target: recovery")
        card.addView(twrpTargetText)
        val twrpTargets = listOf("recovery", "boot", "vendor_boot")
        for (t in twrpTargets) {
            card.addView(UiStyle.ghostButton(this, "TWRP target: $t") {
                twrpTarget = t
                twrpTargetText.text = "TWRP target: $t"
                logLine("TWRP target set to $t")
            })
        }
        card.addView(UiStyle.actionButton(this, "Pick TWRP .img") { pickFile(REQ_TWRP_IMG) })
        card.addView(UiStyle.actionButton(this, "Flash TWRP") { flashTwrp() })
        card.addView(UiStyle.actionButton(this, "Boot TWRP") { bootTwrp() })

        section(flashRoot, "Quick erase")
        card.addView(UiStyle.body(this, "This fastboot has no format command. Erase only."))
        for (part in listOf("userdata", "cache", "boot", "recovery")) {
            card.addView(UiStyle.dangerButton(this, "Erase $part") { erasePartNamed(part) })
        }

        section(flashRoot, "vbmeta disabler")
        card.addView(UiStyle.body(this, "Flashes an empty vbmeta with verification off. Needs an unlocked bootloader. Flash this first."))
        card.addView(UiStyle.dangerButton(this, "Disable verity: vbmeta") { disableVerity("vbmeta") })
        card.addView(UiStyle.dangerButton(this, "Disable verity: vbmeta_system") { disableVerity("vbmeta_system") })
        card.addView(UiStyle.dangerButton(this, "Disable verity: both") { disableVerity("both") })

        // 2.0.13: read-only boot image inspector. Pick an .img and
        // the header facts (format, sizes, page size, cmdline, OS
        // version) are logged and shown. Nothing is flashed here.
        section(flashRoot, "Boot image inspector")
        card.addView(UiStyle.body(this, "Reads a boot or vendor boot image header and shows what is inside. Read-only."))
        card.addView(UiStyle.actionButton(this, "Inspect boot image") { pickFile(REQ_BOOT_INSPECT) })
        inspectText = UiStyle.ledStatus(this, "No image inspected yet.")
        card.addView(inspectText)

        section(valRoot, "Val Protocol")
        // 2.0.10: September warning lives here ONLY (Kyle's order).
        val septVal = UiStyle.ledStatus(this, "Can't unlock bootloader with Val on units with the September patch — it's patched.")
        septVal.setStatus(UiStyle.Status.WAIT)
        card.addView(septVal)
        card.addView(UiStyle.body(this, "Pulls the LK, patches it, and flashes it back. The patched file is checked before flashing. CERT2 is not checked in the app."))
        lkStatusText = UiStyle.ledStatus(this, "No LK file analyzed yet.")
        card.addView(lkStatusText)
        // 2.0.14: one-tap bootloader status (securestate / unlocked /
        // frp-state / current-slot) — read-only, shown here + logged.
        card.addView(UiStyle.ghostButton(this, "Bootloader status") { valBootloaderStatus() })
        valStatusText = UiStyle.ledStatus(this, "Bootloader status not checked yet.")
        card.addView(valStatusText)
        // 2.0.13: the flow readout is a row of step chips now —
        // done steps are solid accent, the active step pulses.
        val flowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (label in listOf("Pull", "Analyze", "Patch", "Flash", "Unlock", "Verify")) {
            val chip = UiStyle.stepChip(this, label)
            valChips.add(chip)
            flowRow.addView(chip)
        }
        card.addView(flowRow)
        card.addView(UiStyle.actionButton(this, "Pull LK + analyze") { pullPartition("lk") })
        valPresetInput = UiStyle.input(this, "preset", "unlock-serial")
        card.addView(valPresetInput)
        // 2.0.10: FRP presets as taps that fill the preset box.
        // frp-skip patches the FRP check out. frp-compare sets what the
        // FRP check compares against (value in the box below, default 1).
        card.addView(UiStyle.body(this, "FRP presets. Tap one to fill the preset box."))
        val frpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        frpRow.addView(UiStyle.rowButton(this, "frp-skip") {
            valPresetInput.setText("frp-skip")
            logLine("Val preset set to frp-skip (FRP check patched out)")
        })
        frpRow.addView(UiStyle.rowButton(this, "frp-compare") {
            valPresetInput.setText("frp-compare")
            logLine("Val preset set to frp-compare (FRP compare value from the box below)")
        })
        card.addView(frpRow)
        valFrpValueInput = UiStyle.input(this, "FRP compare value", "1")
        card.addView(valFrpValueInput)
        valSecretInput = UiStyle.input(this, "key token secret", "Valeria")
        card.addView(valSecretInput)
        card.addView(UiStyle.actionButton(this, "Analyze + patch") { runValPatch() })
        valSerialInput = UiStyle.input(this, "device serial")
        card.addView(valSerialInput)
        card.addView(UiStyle.actionButton(this, "Get serial") { getValSerial() })
        valKeyText = UiStyle.status(this, "No unlock key generated yet.")
        card.addView(valKeyText)
        card.addView(UiStyle.actionButton(this, "Generate key") { runValKeygen() })
        card.addView(UiStyle.body(this, "Flash the patched LK below. The key goes to the unlock box. If unlock fails, type the key from a PC."))
        card.addView(UiStyle.actionButton(this, "Pick LK file") { pickFile(REQ_LK_FILE) })
        card.addView(UiStyle.actionButton(this, "Analyze LK file") { analyzeLkFile() })
        card.addView(UiStyle.actionButton(this, "Extract lk payload") { extractLkPayload() })
        lkPartInput = UiStyle.input(this, "partition to extract")
        card.addView(lkPartInput)
        card.addView(UiStyle.actionButton(this, "Extract partition") {
            extractLkPartition(lkPartInput.text.toString().trim())
        })
        card.addView(UiStyle.actionButton(this, "Flash LK to lk") { valFlashLk("lk") })
        // 2.0.14: explicit lk_a too — some devices have no bare "lk"
        // partition, only the _a/_b pair.
        card.addView(UiStyle.actionButton(this, "Flash LK to lk_a") { valFlashLk("lk_a") })
        card.addView(UiStyle.actionButton(this, "Flash LK to lk_b") { valFlashLk("lk_b") })

        // BROM / Penumbra (2.0.0). The whole section lives in
        // BromUi — this activity only hands it the host callbacks it needs
        // (log, toast, confirm, wake lock) and forwards its
        // picker results in onActivityResult below.
        bromUi = BromUi(
            this,
            { s -> logLine(s) },
            { s -> toast(s) },
            { title, message, onYes -> confirm(title, message, onYes) },
            { acquireWake() },
            { releaseWake() },
            { revealLog() },
        )
        bromUi.addTo(bromRoot)

        section(valRoot, "Puller")
        card.addView(UiStyle.body(this, "Reads partitions off the target phone. Needs USB debugging on, adb allowed, and temp root on the target."))
        pullPartInput = UiStyle.input(this, "partition to pull")
        card.addView(pullPartInput)
        card.addView(UiStyle.actionButton(this, "Pull partition") { pullPartition(pullPartInput.text.toString().trim()) })
        card.addView(UiStyle.actionButton(this, "Pull lk") { pullPartition("lk") })
        card.addView(UiStyle.actionButton(this, "Pull lk_b") { pullPartition("lk_b") })
        card.addView(UiStyle.ghostButton(this, "Check target") { adbDevices() })

        // 2.0.7: ADB tab — run adb commands against a device plugged in
        // over OTG. Target is a device booted into normal Android (adbd
        // running, USB debugging on); a custom recovery (TWRP etc.) runs
        // adbd too, so the same commands work there — nothing here checks
        // for a booted OS or any system property first. Output goes to the
        // shared log on the More tab, line by line, exactly as adb prints
        // it (errors included); the tab's status line shows the exit code
        // and a short summary. No device attached = adb says so plainly,
        // never a fake success.
        section(adbRoot, "ADB")
        card.addView(UiStyle.body(this, "adb over OTG. Works in Android and TWRP. Turn on USB debugging and tap Allow on the device. Output goes to the log."))
        adbInput = UiStyle.input(this, "adb args or shell command")
        card.addView(adbInput)
        card.addView(UiStyle.actionButton(this, "Run adb command") { runAdbInput() })
        card.addView(UiStyle.actionButton(this, "Run as shell command") { runAdbShell() })
        val adbPresetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adbPresetRow.addView(UiStyle.rowButton(this, "Devices") { adbRun(listOf("devices"), "Devices") })
        adbPresetRow.addView(UiStyle.rowButton(this, "Reboot") { adbRun(listOf("reboot"), "Reboot") })
        card.addView(adbPresetRow)
        val adbPresetRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        adbPresetRow2.addView(UiStyle.rowButton(this, "Reboot bootloader") { adbRun(listOf("reboot", "bootloader"), "Reboot bootloader") })
        adbPresetRow2.addView(UiStyle.rowButton(this, "Reboot recovery") { adbRun(listOf("reboot", "recovery"), "Reboot recovery") })
        card.addView(adbPresetRow2)
        adbStatusText = UiStyle.ledStatus(this, "No adb command run yet.")
        card.addView(adbStatusText)
        card.addView(UiStyle.ghostButton(this, "Show log") { revealLog() })
        // 2.0.8: Devices used to show an empty list with exit 0 and no
        // explanation. Diagnose runs version → kill-server → fresh
        // start-server → USB visibility → devices, everything logged.
        card.addView(UiStyle.actionButton(this, "Diagnose ADB") { diagnoseAdb() })
        card.addView(UiStyle.ghostButton(this, "Kill adb server") {
            Thread { Adb.killServer(this) { logLine(it) } }.start()
            adbStatusText.text = "adb server killed."
            adbStatusText.setLed(UiStyle.Status.INFO)
        })

        section(moreRoot, "Raw fastboot")
        rawInput = UiStyle.input(this, "e.g. getvar product")
        card.addView(rawInput)
        card.addView(UiStyle.actionButton(this, "Run command") {
            val parts = rawInput.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) { toast("Type a command first"); return@actionButton }
            fbCmd(parts, parts.joinToString(" "))
        })
        // 2.0.8: LK-derived presets — tap to fill the box, then Run.
        // The full runnable list is on the Extras tab.
        card.addView(UiStyle.body(this, "Tap a preset to fill the box, then Run."))
        val rawRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawRow1.addView(UiStyle.gridButton(this, "erase …") { rawInput.setText("erase ") })
        rawRow1.addView(UiStyle.gridButton(this, "getvar all") { rawInput.setText("getvar all") })
        rawRow1.addView(UiStyle.gridButton(this, "reboot") { rawInput.setText("reboot") })
        card.addView(rawRow1)
        val rawRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawRow2.addView(UiStyle.gridButton(this, "reboot bootloader") { rawInput.setText("reboot bootloader") })
        rawRow2.addView(UiStyle.gridButton(this, "reboot recovery") { rawInput.setText("reboot recovery") })
        rawRow2.addView(UiStyle.gridButton(this, "continue") { rawInput.setText("continue") })
        card.addView(rawRow2)
        val rawRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawRow3.addView(UiStyle.gridButton(this, "set_active a") { rawInput.setText("set_active a") })
        rawRow3.addView(UiStyle.gridButton(this, "set_active b") { rawInput.setText("set_active b") })
        rawRow3.addView(UiStyle.gridButton(this, "oem hwid") { rawInput.setText("oem hwid") })
        card.addView(rawRow3)

        section(moreRoot, "Log")
        // 2.0.14: filter box — typing shows only matching lines
        // (case-insensitive); clearing restores the full log. The
        // buffer behind it always keeps everything (Copy/Save use it).
        logFilterInput = UiStyle.input(this, "Filter log")
        logFilterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                logFilter = s?.toString()?.trim().orEmpty()
                refreshLogDisplay()
            }
        })
        card.addView(logFilterInput)
        logText = TextView(this).apply {
            text = ""
            setTextIsSelectable(true) // long-press to select + copy, no extra button needed
            setTextColor(UiStyle.LOG_TEXT)
            setBackgroundColor(UiStyle.LOG_BG)
            // 2.0.12: log is monospace (was Exo 2) — PC-style columns line up.
            typeface = android.graphics.Typeface.MONOSPACE
            // 2.0.14: size from Settings (S 11 / M 13 / L 15).
            textSize = logTextSizeSp(prefs.getString("log_text_size", "M") ?: "M")
            setPadding(12, 12, 12, 12)
        }
        // 2.0.12: the log scrolls on its own. LogScrollView claims the
        // drag gesture on finger-down (see the class below) so the outer
        // tab ScrollView can't steal it — the old touch listener never
        // fired on ACTION_DOWN because the selectable logText consumed
        // the down event first, so dragging on the log scrolled the
        // whole tab instead and the log history was unreachable.
        // Height is dp now (was a raw 600px, ~200dp on a dense screen).
        logScroll = LogScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)
            )
        }
        card.addView(logScroll)
        logScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // 2.0.12 live tail (tail -f): disengage ONLY when the user
            // scrolls UP away from the bottom. Programmatic follow
            // scrolls move DOWN through positions that are not the
            // bottom yet — the old listener flipped follow OFF
            // mid-scroll, so tapping Follow moved the log once and
            // then stopped following (Kyle's bug). Scrolling back to
            // the bottom re-engages. "Near the bottom" counts (32 px).
            val atBottom = logText.bottom - (scrollY + logScroll.height) <= 32
            when {
                atBottom -> if (!followLog) {
                    followLog = true
                    followButton.text = "Follow log: ON"
                }
                scrollY < oldScrollY -> if (followLog) {
                    followLog = false
                    followButton.text = "Follow log: OFF"
                }
            }
        }
        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow.addView(UiStyle.rowButton(this, "Copy log") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("moto-flasher-log", logBuf.toString()))
            toast("Log copied")
        })
        logRow.addView(UiStyle.rowButton(this, "Clear log") {
            logBuf.setLength(0)
            logLines = 0
            logText.text = ""
        })
        // 2.0.14: initial label follows the "Follow log at launch"
        // setting read at startup (followLog field).
        followButton = UiStyle.rowButton(this, if (followLog) "Follow log: ON" else "Follow log: OFF") {
            followLog = !followLog
            followButton.text = if (followLog) "Follow log: ON" else "Follow log: OFF"
            if (followLog) scrollLogToBottom()
        }
        logRow.addView(followButton)
        card.addView(logRow)
        // 2.0.13: save the full log to Download/MotoFlasher/logs/
        // (same publish path as the Val outputs).
        val logRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow2.addView(UiStyle.rowButton(this, "Save log") { saveLog() })
        card.addView(logRow2)
        logCard = card

        // 2.0.8: Extras tab — every command the Nevada LK answers,
        // pulled from the factory LK + the neon LK (identical tables).
        // Tap a command to run it against the Moto; output goes to the
        // log verbatim. Dangerous ones ask first.
        section(extrasRoot, "Extras")
        card.addView(UiStyle.body(this, "Experimental commands."))
        extrasArgInput = UiStyle.input(this, "argument")
        card.addView(extrasArgInput)
        // 2.0.10: gridButton — smaller text, equal-height cells, labels
        // wrap at spaces only. Long OEM names (MRDUMP_FALLOCATE etc.)
        // fit on one line now instead of breaking mid-word and clipping.
        fun extrasRow(first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>?) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(UiStyle.gridButton(this, first.first, first.second))
            if (second != null) row.addView(UiStyle.gridButton(this, second.first, second.second))
            card.addView(row)
        }
        fun extrasRows(entries: List<Pair<String, () -> Unit>>) {
            var i = 0
            while (i < entries.size) {
                extrasRow(entries[i], entries.getOrNull(i + 1))
                i += 2
            }
        }
        card.addView(UiStyle.sectionTitle(this, "Fastboot"))
        val fbEntries = mutableListOf<Pair<String, () -> Unit>>()
        for (cmd in LK_FASTBOOT_CMDS) {
            when (cmd) {
                "flash" -> fbEntries.add("flash" to { extrasFlash() })
                "boot" -> fbEntries.add("boot" to { extrasBoot() })
                "getvar" -> fbEntries.add("getvar" to { extrasArgCmd(listOf("getvar"), "all") })
                "erase" -> fbEntries.add("erase" to { extrasArgCmd(listOf("erase"), null, dangerous = true) })
                "set_active" -> fbEntries.add("set_active" to { extrasArgCmd(listOf("set_active"), null) })
                "flashing" -> {
                    fbEntries.add("flashing unlock" to { extrasConfirmRun(listOf("flashing", "unlock"), "flashing unlock") })
                    fbEntries.add("flashing lock" to { extrasConfirmRun(listOf("flashing", "lock"), "flashing lock") })
                    fbEntries.add("flashing get_unlock_ability" to { fbCmd(listOf("flashing", "get_unlock_ability"), "flashing get_unlock_ability") })
                }
                else -> fbEntries.add(cmd to { fbCmd(listOf(cmd), cmd) })
            }
        }
        extrasRows(fbEntries)
        card.addView(UiStyle.sectionTitle(this, "OEM"))
        card.addView(UiStyle.body(this, "The argument box value is added to the command when needed."))
        extrasRows(LK_OEM_CMDS.map { sub -> "oem $sub" to { extrasOem(sub) } })
        card.addView(UiStyle.sectionTitle(this, "OEM (unproven)"))
        card.addView(UiStyle.body(this, "Found as strings only. They may not run."))
        extrasRows(LK_OEM_STRING_ONLY.map { sub -> "oem $sub" to { extrasOem(sub) } })
        card.addView(UiStyle.body(this, "download, partition, and super are not commands. super is a partition name."))

        // 2.0.8: theme picker — persisted, applied on next launch.
        section(moreRoot, "Theme")
        card.addView(UiStyle.body(this, "Pick a look."))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val curTheme = prefs.getString("theme", "neon") ?: "neon"
        for (name in UiStyle.THEMES) {
            val label = name.replaceFirstChar { it.uppercase() }
            val btn = if (name == curTheme) {
                UiStyle.rowActionButton(this, label) { setTheme(name) }
            } else {
                UiStyle.rowGhostButton(this, label) { setTheme(name) }
            }
            themeRow.addView(btn)
        }
        card.addView(themeRow)

        // 2.0.14: Settings — persisted in SharedPreferences, applied
        // immediately where possible. Toggle state shows as a
        // solid (on) / dim (off) block, same language as the chips.
        section(moreRoot, "Settings")
        fun toggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val tv = UiStyle.body(this, label)
            tv.setPadding(0, 0, 0, 0)
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(tv)
            var state = initial
            val block = UiStyle.toggleBlock(this, state)
            row.addView(block)
            row.setOnClickListener {
                state = !state
                UiStyle.setToggleBlock(block, state)
                onChange(state)
            }
            return row
        }
        card.addView(toggleRow("Animations", UiStyle.animationsEnabled) { on ->
            UiStyle.animationsEnabled = on
            prefs.edit().putBoolean("animations", on).apply()
            applyAnimationsSetting()
            logLine("Animations ${if (on) "ON" else "OFF"}")
        })
        // Log text size: S / M / L, applied live to the log view.
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val sizeLabel = UiStyle.body(this, "Log text size")
        sizeLabel.setPadding(0, 0, 0, 0)
        sizeLabel.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        sizeRow.addView(sizeLabel)
        var curSize = prefs.getString("log_text_size", "M") ?: "M"
        val sizeBlocks = mutableMapOf<String, TextView>()
        for (opt in listOf("S", "M", "L")) {
            val b = UiStyle.choiceBlock(this, opt, opt == curSize)
            b.setOnClickListener {
                curSize = opt
                prefs.edit().putString("log_text_size", opt).apply()
                logText.textSize = logTextSizeSp(opt)
                sizeBlocks.forEach { (k, v) -> UiStyle.setChoiceBlock(v, k == opt) }
                logLine("Log text size $opt")
            }
            sizeBlocks[opt] = b
            sizeRow.addView(b)
        }
        card.addView(sizeRow)
        card.addView(toggleRow("Follow log at launch", followLog) { on ->
            prefs.edit().putBoolean("follow_at_launch", on).apply()
            followLog = on
            followButton.text = if (on) "Follow log: ON" else "Follow log: OFF"
            if (on) scrollLogToBottom()
            logLine("Follow log at launch ${if (on) "ON" else "OFF"}")
        })
        card.addView(toggleRow("Keep screen on", prefs.getBoolean("keep_screen_on", false)) { on ->
            prefs.edit().putBoolean("keep_screen_on", on).apply()
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            logLine("Keep screen on ${if (on) "ON" else "OFF"}")
        })

        // About / Credits: short AGPL note — the app bundles the
        // Antumbra binary, so the app itself is AGPL-3.0.
        val aboutCard = UiStyle.card(this)
        aboutCard.addView(UiStyle.sectionTitle(this, "About / Credits"))
        aboutCard.addView(UiStyle.divider(this))
        aboutCard.addView(UiStyle.body(this, "Made with love by crabcakes97"))
        aboutCard.addView(TextView(this).apply {
            text = "github.com/crabcakes97"
            textSize = 14f
            setTextColor(UiStyle.MAGENTA)
            UiStyle.applyFont(this@MainActivity, this)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, 0, 0, dp(8))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/crabcakes97")))
            }
        })
        aboutCard.addView(UiStyle.body(this, "BROM engine: Antumbra v2.0.0, Penumbra by Shomy (github.com/shomykohai/penumbra). AGPL-3.0."))
        aboutCard.addView(UiStyle.body(this, "liblk by R0rt1z2. Val Protocol by Maikyxd."))
        moreRoot.addView(aboutCard)

        // Startup checks on a background thread. 2.0.13: the STATUS
        // card shows the root line only (Kyle's order) — binary
        // install failures still go to the log so nothing is hidden.
        Thread {
            val installErr = if (!Fastboot.isInstalled(this)) Fastboot.install(this) else null
            val adbErr = if (!Adb.isInstalled(this)) Adb.install(this) else null
            val antumbraErr = if (!Penumbra.isInstalled(this)) Penumbra.install(this) else null
            if (installErr != null) logLine("fastboot install failed: $installErr")
            if (adbErr != null) logLine("adb install failed: $adbErr")
            if (antumbraErr != null) logLine("antumbra ${Penumbra.VERSION} install failed: $antumbraErr")
            rootOk = Fastboot.hasRoot { logLine(it) }
            runOnUiThread {
                statusText.text = "Root: ${if (rootOk) "YES" else "NO"}"
                statusText.setLed(if (rootOk) UiStyle.Status.OK else UiStyle.Status.FAIL)
                if (!rootOk) toast("No root. Flashing needs root.")
            }
        }.start()
        // 2.0.13: start the live device watcher (Home tab polls).
        startWatcher()
    }

    // ---------- helpers ----------

    // 2.0.8: the ONE way the log scrolls to the bottom. Double-posted:
    // the first post runs after layout, the second after any re-measure
    // the first one triggered — so the scroll lands on the true bottom
    // even when lines arrive in a burst. The old code posted once, which
    // could run before the TextView re-measured (one line short), and
    // when the More tab was hidden logText.bottom was 0, so the scroll
    // went nowhere and nothing re-scrolled on return. logLine, the
    // Follow button and showTab all funnel through here.
    private fun scrollLogToBottom() {
        // 2.0.12: re-check followLog inside the post — if the user
        // scrolled up after this was queued, the pending scroll must
        // not yank them back down. All callers already guard on
        // followLog (logLine, Follow button, showTab).
        logText.post {
            logText.post { if (followLog) logScroll.scrollTo(0, logText.bottom) }
        }
    }

    /** 2.0.13: write the full log to Download/MotoFlasher/logs/
     *  log-<yyyyMMdd-HHmmss>.txt via BromDownloads (MediaStore, root
     *  cp fallback) and log the path. The snapshot is taken on the
     *  UI thread, where logBuf is mutated. */
    private fun saveLog() {
        val text = logBuf.toString()
        if (text.isBlank()) { toast("Log is empty"); return }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "log-$stamp.txt"
        Thread {
            val tmp = File(cacheDir, name)
            tmp.writeText(text)
            val published = BromDownloads.publish(this, tmp, "logs", name) { logLine(it) }
            if (published != null) {
                logLine("Log saved: ${published.absolutePath} (${published.length()} bytes)")
                runOnUiThread { toast("Log saved to Download/MotoFlasher/logs/") }
            } else {
                logLine("Log save failed: could not publish $name to Download/MotoFlasher/logs/")
                runOnUiThread { toast("Log save failed — see log") }
            }
        }.start()
    }

    /** 2.0.14: the log as displayed — full buffer, or only the
     *  lines matching the filter (case-insensitive). The buffer
     *  itself always keeps everything (Copy/Save use it raw). */
    private fun filteredLog(): String {
        val all = logBuf.toString()
        if (logFilter.isEmpty()) return all
        val kept = all.split('\n').filter { it.contains(logFilter, ignoreCase = true) }
        return if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n"
    }

    /** 2.0.14: rebuild the log view from the buffer (filter changed). */
    private fun refreshLogDisplay() {
        logText.text = filteredLog()
        if (followLog) scrollLogToBottom()
    }

    /** 2.0.14: log text size picks (Settings): S 11 / M 13 / L 15. */
    private fun logTextSizeSp(pick: String): Float = when (pick) {
        "S" -> 11f
        "L" -> 15f
        else -> 13f
    }

    private fun logLine(s: String) {
        runOnUiThread {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val line = "[$ts] $s\n"
            logBuf.append(line)
            logLines++
            if (logLines > 2000) {
                // Long run (super = hundreds of lines): drop the oldest 500
                // lines so the log view doesn't grow forever. Rare rebuild;
                // normal lines only append, so the screen never re-lays out.
                var cut = 0
                repeat(500) { cut = logBuf.indexOf('\n', cut) + 1 }
                logBuf.delete(0, cut)
                logLines -= 500
                // 2.0.14: rebuild honors the filter.
                logText.text = filteredLog()
            } else if (logFilter.isEmpty() || line.contains(logFilter, ignoreCase = true)) {
                // 2.0.14: with a filter active, only matching new
                // lines appear; the buffer above keeps them all.
                logText.append(line)
            }
            // scrollTo moves ONLY the log's own scroll position. The old code
            // used fullScroll(FOCUS_DOWN), which requests focus on the bottom
            // view — that focus request bubbled up and yanked the whole screen
            // around on every line. And only follow when the user is already
            // at the bottom: if they scrolled up to read, the log stays put.
            // 2.0.8: scrollLogToBottom() (double-post) — see above.
            if (followLog) scrollLogToBottom()
        }
    }

    /** The log's own ScrollView, nested inside the More tab's
     *  ScrollView. On finger-down it tells every parent to keep hands
     *  off the gesture, so a drag that starts on the log scrolls the
     *  log itself; the flag resets on the next gesture, so the tab
     *  scrolls normally again everywhere else. Follow-log auto-scroll
     *  (scrollLogToBottom) is programmatic and unaffected. */
    private class LogScrollView(ctx: Context) : ScrollView(ctx) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    /** Line-buffered sink: accumulates bytes, emits one complete line
     *  per '\n'. Streams valproto's println output into the app log
     *  live, the way the PC tool prints to a terminal. */
    private class ValLineSink(private val emit: (String) -> Unit) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        @Synchronized override fun write(b: Int) {
            when (b) {
                '\n'.code -> flushLine()
                '\r'.code -> Unit
                else -> buf.write(b)
            }
        }
        @Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }
        @Synchronized fun flushLine() {
            if (buf.size() > 0) {
                emit(String(buf.toByteArray(), Charsets.UTF_8))
                buf.reset()
            }
        }
        @Synchronized override fun close() = flushLine()
    }

    /** Run [block] with System.out/System.err redirected into the app
     *  log, line by line, prefixed VAL: / VAL ERR:. Restores the real
     *  streams in finally. logLine only posts to the UI (never prints),
     *  so there is no feedback loop. Keep the window tight: the redirect
     *  is process-global. */
    private fun <T> captureValOutput(block: () -> T): T {
        val origOut = System.out
        val origErr = System.err
        val outSink = ValLineSink { line -> logLine("VAL: $line") }
        val errSink = ValLineSink { line -> logLine("VAL ERR: $line") }
        val outPs = PrintStream(outSink, true, "UTF-8")
        val errPs = PrintStream(errSink, true, "UTF-8")
        System.setOut(outPs)
        System.setErr(errPs)
        try {
            return block()
        } finally {
            // Flush the PrintStreams first: anything printed without a
            // newline sits in their internal buffer, not the sink —
            // without this it would vanish when the streams restore.
            outPs.flush()
            errPs.flush()
            outSink.flushLine()
            errSink.flushLine()
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    // 1.1.15: bring the log box on screen (called when a long BROM run
    // starts, so the log scrolls live in front of the user instead of
    // sitting below the fold). 2.0.1: the log lives on the More tab, so
    // switch there first, then scroll the More tab to the log card.
    // The inner log follow logic above is untouched, so a user who
    // scrolled the log up to read is not yanked — revealLog runs once
    // at run start, not per line.
    private fun revealLog() {
        runOnUiThread {
            showTab(TAB_MORE)
            moreScroll.post { moreScroll.scrollTo(0, logCard.top) }
        }
    }

    /** 2.0.1: switch the visible tab (only one tab ScrollView shows).
     *  2.0.13: the incoming tab fades/slides in (~150ms) and the
     *  tab-bound pulses are refreshed (hidden tabs stop pulsing).
     *  2.0.14: the incoming tab's cards fade/slide in with a short
     *  stagger (~40ms apart, ~200ms each); all of it gated on the
     *  Animations setting. */
    private fun showTab(index: Int) {
        currentTab = index
        tabScrolls.forEachIndexed { i, scroll ->
            scroll.visibility = if (i == index) View.VISIBLE else View.GONE
            if (i != index) settleCards(scroll)
        }
        tabBar.select(index)
        val incoming = tabScrolls[index]
        incoming.animate().cancel()
        if (UiStyle.animationsEnabled) {
            incoming.alpha = 0f
            incoming.translationY = dp(12).toFloat()
            incoming.animate().alpha(1f).translationY(0f).setDuration(150).start()
            animateCardEntrance(incoming)
        } else {
            incoming.alpha = 1f
            incoming.translationY = 0f
            settleCards(incoming)
        }
        refreshPulses()
        // 2.0.8: switching back to the log tab re-scrolls to the bottom
        // when follow is on — lines logged while the tab was hidden
        // never scrolled (the view was GONE, bottom was 0).
        if (index == TAB_MORE && followLog) scrollLogToBottom()
    }

    /** 2.0.14: cancel card animations in a tab and settle every
     *  card to full opacity / no offset (hidden tabs, animations off). */
    private fun settleCards(scroll: ScrollView) {
        val root = scroll.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            c.animate().cancel()
            c.alpha = 1f
            c.translationY = 0f
        }
    }

    /** 2.0.14: card entrance — each card fades/slides in, staggered
     *  ~40ms apart, ~200ms each. UI thread only; purely visual. */
    private fun animateCardEntrance(scroll: ScrollView) {
        val root = scroll.getChildAt(0) as? LinearLayout ?: return
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            c.animate().cancel()
            c.alpha = 0f
            c.translationY = dp(16).toFloat()
            c.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 40L).setDuration(200).start()
        }
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    /** Gate for anything that writes to the target. Returns true if allowed. */
    private fun writeGate(): Boolean {
        if (!Fastboot.isInstalled(this)) {
            toast("fastboot binary not installed")
            return false
        }
        // 1.1.19: rootOk is only checked once at startup and cached. If that
        // check ran before root was ready/granted, it stays false all session
        // even though the user has root NOW. Re-check fresh here when the
        // cached value says no root, so a stale startup check can't block
        // flashing. (su -c id is fast once root is granted.)
        if (!rootOk) {
            rootOk = Fastboot.hasRoot { logLine(it) }
            if (rootOk) {
                statusText.text = "Root: YES"
                statusText.setLed(UiStyle.Status.OK)
                logLine("Root re-check passed — flashing allowed")
            }
        }
        if (!rootOk) {
            toast("No root. Get temp root first.")
            logLine("BLOCKED: no root on the host phone (flashing needs root)")
            return false
        }
        // 2.0.2: the 30% host-battery gate is REMOVED (Kyle's order). The
        // BROM half already had no battery check (1.1.11); now the
        // fastboot half doesn't either — flashing is never blocked on
        // host battery level. The battery % is no longer read at all.
        return true
    }

    private fun acquireWake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoFlasher:flash").apply {
            acquire(30 * 60 * 1000L)
        }
        // 2.0.13: every wake-locked run is an operation (flash-all,
        // single flash, disable-verity, BROM writes) — count it so
        // the progress strip and pulses cover them all.
        opStarted()
    }

    private fun releaseWake() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        opFinished(null)
    }

    // ---------- 2.0.13: operation tracking + animations ----------

    /** An operation started. First one in shows the progress strip
     *  (indeterminate; flash-all switches it to determinate), starts
     *  the header bolt glow and the status LED pulse. UI work is
     *  posted — callers run on background threads. */
    private fun opStarted() {
        if (opCount.incrementAndGet() == 1) {
            runOnUiThread {
                progressStrip.showIndeterminate()
                startBoltPulse()
                // 2.0.14: clear the old verdict mark; start the
                // active-card shimmer for this operation.
                statusMark.clear()
                startShimmer()
                refreshPulses()
            }
        }
    }

    /** An operation finished. Last one out hides the strip, stops
     *  the pulses, and (when the caller knows the verdict) flashes
     *  the screen edge green or red. [success] null = no flash. */
    private fun opFinished(success: Boolean?) {
        if (opCount.decrementAndGet() <= 0) {
            opCount.set(0)
            runOnUiThread {
                progressStrip.hide()
                stopBoltPulse()
                // 2.0.14: shimmer stops with the operation; the
                // verdict mark strokes in next to the status line
                // (only when a verdict actually reached us — BROM
                // writes pass null and get no mark, never a fake one).
                stopShimmer()
                refreshPulses()
                if (success != null) {
                    edgeFlash.flash(if (success) UiStyle.GREEN else UiStyle.RED)
                    statusMark.show(success)
                }
            }
        }
    }

    /** 2.0.14: apply the Animations setting to everything already
     *  running. OFF: stop every pulse/shimmer/glow and settle views
     *  to their static state (the progress strip re-shows static).
     *  ON: resume the tab-bound pulses for the current state. */
    private fun applyAnimationsSetting() {
        runOnUiThread {
            if (!UiStyle.animationsEnabled) {
                stopBoltPulse()
                stopShimmer()
                UiStyle.stopLedPulse(statusText)
                UiStyle.stopLedPulse(deviceText)
                valChipPulse?.cancel()
                valChipPulse = null
                for (chip in valChips) chip.alpha = 1f
                UiStyle.cancelChipFades()
                flashAllTick?.cancel()
                flashAllTick = null
                for (scroll in tabScrolls) settleCards(scroll)
                // Strip stays visible, static — unless flash-all's
                // determinate fill is the one showing.
                if (opCount.get() > 0 && !flashAllRunning) progressStrip.showIndeterminate()
            } else {
                if (opCount.get() > 0) {
                    if (!flashAllRunning) progressStrip.showIndeterminate()
                    startBoltPulse()
                    startShimmer()
                }
                refreshPulses()
            }
        }
    }

    // ---------- 2.0.14: active-card shimmer ----------

    /** While an operation runs, a subtle accent sweep travels along
     *  the active card's top border (the last card the user touched).
     *  UI thread only; stops with the operation. */
    private fun startShimmer() {
        stopShimmer()
        if (!UiStyle.animationsEnabled) return
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val panel = activeCard?.background as? UiStyle.PanelDrawable
                panel?.shimmerPhase = it.animatedValue as Float
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        (activeCard?.background as? UiStyle.PanelDrawable)?.shimmerPhase = -1f
    }

    /** Track the last card touched so the shimmer knows where to
     *  sweep. Cards are the direct children of the visible tab root. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            findCardAt(ev.x, ev.y)?.let { card ->
                if (card !== activeCard) {
                    (activeCard?.background as? UiStyle.PanelDrawable)?.shimmerPhase = -1f
                    activeCard = card
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun findCardAt(x: Float, y: Float): View? {
        val root = tabScrolls.getOrNull(currentTab)?.getChildAt(0) as? LinearLayout ?: return null
        val loc = IntArray(2)
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.background !is UiStyle.PanelDrawable) continue
            c.getLocationInWindow(loc)
            if (x >= loc[0] && x <= loc[0] + c.width && y >= loc[1] && y <= loc[1] + c.height) {
                return c
            }
        }
        return null
    }

    /** Subtle glow pulse on the header bolt while an operation runs
     *  (alpha 0.55..1.0, ~900ms each way). Never runs idle. */
    private fun startBoltPulse() {
        stopBoltPulse()
        // 2.0.14: animations OFF — bolt stays solid.
        if (!UiStyle.animationsEnabled) return
        boltPulse = ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { boltView.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopBoltPulse() {
        boltPulse?.cancel()
        boltPulse = null
        if (::boltView.isInitialized) boltView.alpha = 1f
    }

    /** Start/stop the tab-bound pulses for the current state:
     *  status LED pulses while an operation runs on Home; the Val
     *  active-step chip pulses while the Val tab is visible.
     *  Everything else stays solid. Called on tab switch and on
     *  operation start/finish. */
    private fun refreshPulses() {
        if (opCount.get() > 0 && currentTab == TAB_HOME) {
            UiStyle.startLedPulse(statusText)
        } else {
            UiStyle.stopLedPulse(statusText)
        }
        refreshValChipPulse()
    }

    /** Pulse the Val flow's active step chip (alpha 0.6..1.0) while
     *  the Val tab is visible; solid otherwise. */
    private fun refreshValChipPulse() {
        valChipPulse?.cancel()
        valChipPulse = null
        for (chip in valChips) chip.alpha = 1f
        // 2.0.14: animations OFF — chips stay solid.
        if (!UiStyle.animationsEnabled) return
        if (currentTab != TAB_VAL) return
        val chip = valChips.getOrNull(valActiveChip) ?: return
        valChipPulse = ValueAnimator.ofFloat(0.6f, 1f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { chip.alpha = it.animatedValue as Float }
            start()
        }
    }

    /** Run a fastboot command on a background thread, logging output. */
    private fun fbCmd(args: List<String>, label: String) {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot ${args.joinToString(" ")}")
        opStarted()
        Thread {
            val r = Fastboot.run(this, args) { logLine(it) }
            runOnUiThread {
                logLine(if (r.ok) "OK: $label" else "FAILED: $label (exit ${r.exitCode})")
                toast(if (r.ok) "$label: OK" else "$label: FAILED")
            }
            opFinished(r.ok)
        }.start()
    }

    // ---------- device ----------

    private fun refreshDevices() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot devices")
        opStarted()
        Thread {
            val r = Fastboot.run(this, listOf("devices")) { logLine(it) }
            runOnUiThread {
                deviceText.text = if (r.output.isBlank()) "No fastboot device.\nPlug in over OTG and put the phone in fastboot mode."
                    else "fastboot devices:\n${r.output}"
                deviceText.setLed(if (r.output.isBlank()) UiStyle.Status.FAIL else UiStyle.Status.OK)
            }
            opFinished(null)
        }.start()
    }

    private fun getvarAll() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot getvar all")
        opStarted()
        Thread {
            val r = Fastboot.run(this, listOf("getvar", "all")) { logLine(it) }
            runOnUiThread { deviceText.text = r.output.ifBlank { "(no output)" }; deviceText.setLed(UiStyle.Status.INFO) }
            opFinished(null)
        }.start()
    }

    // ---------- 2.0.13: live device watcher (Home) ----------

    private fun startWatcher() {
        if (watcherRunning) return
        watcherRunning = true
        watcherHandler.post(watcherTick)
    }

    private fun stopWatcher() {
        watcherRunning = false
        watcherHandler.removeCallbacks(watcherTick)
    }

    /** One quiet poll. Skips unless Home is showing, no operation
     *  is running, and no BROM session is active — the watcher must
     *  never fight an operation for the USB device. Logs only on
     *  connect/disconnect; the DEVICE card updates only on change,
     *  so a manual Getvar all readout is not clobbered. */
    private fun watcherPoll() {
        if (currentTab != TAB_HOME) return
        if (opCount.get() > 0) return
        if (::bromUi.isInitialized && bromUi.isBusy()) return
        if (!Fastboot.isInstalled(this)) return
        Thread {
            val r = Fastboot.run(this, listOf("devices")) {}
            val serial = r.output.lines().firstNotNullOfOrNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "fastboot") parts[0] else null
            }
            if (serial == lastSerial) return@Thread
            lastSerial = serial
            if (serial == null) {
                logLine("Device disconnected")
                runOnUiThread {
                    deviceText.text = "No device"
                    deviceText.setLed(UiStyle.Status.INFO)
                }
                return@Thread
            }
            logLine("Device connected: $serial (fastboot)")
            // Slot + lock state, fetched once per serial and cached.
            val info = deviceInfoCache[serial] ?: run {
                val slot = getvarQuiet("current-slot")
                val unlocked = getvarQuiet("unlocked")
                Pair(slot, unlocked).also { deviceInfoCache[serial] = it }
            }
            runOnUiThread {
                val detail = buildString {
                    if (info.first.isNotEmpty()) append("Slot: ${info.first}")
                    if (info.second.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("Unlocked: ${info.second}")
                    }
                }
                deviceText.text = if (detail.isEmpty()) "$serial — fastboot" else "$serial — fastboot\n$detail"
                deviceText.setLed(UiStyle.Status.OK)
                UiStyle.pulseLedOnce(deviceText)
                UiStyle.glowCardBorder(deviceCard)
            }
        }.start()
    }

    /** Quiet single getvar read for the watcher (no log output). */
    private fun getvarQuiet(name: String): String {
        val r = Fastboot.run(this, listOf("getvar", name)) {}
        return Regex("$name:\\s*(\\S+)", RegexOption.IGNORE_CASE)
            .find(r.output)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    // ---------- file picking ----------

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, req)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(i, REQ_FOLDER)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::bromUi.isInitialized && bromUi.onActivityResult(requestCode, resultCode, data)) return
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_SINGLE_IMG -> {
                singleImgUri = uri
                singleImgName = displayName(uri)
                toast("Picked: $singleImgName")
                logLine("Picked single image: $singleImgName")
                val pending = pendingPartition
                if (pending != null) {
                    pendingPartition = null
                    val applySlot = pendingApplySlot
                    pendingApplySlot = true
                    partInput.setText(pending)
                    val effPending = if (applySlot) slotPartition(pending) else pending
                    logLine("Quick flash: $singleImgName to $effPending")
                    flashSingle(applySlot)
                }
            }
            REQ_TWRP_IMG -> {
                twrpImgUri = uri
                twrpImgName = displayName(uri)
                toast("Picked TWRP: $twrpImgName (target: $twrpTarget)")
                logLine("Picked TWRP image: $twrpImgName (target: $twrpTarget)")
            }
            REQ_LK_FILE -> {
                lkFileUri = uri
                lkFileName = displayName(uri)
                lkExtracted = null
                toast("Picked LK file: $lkFileName")
                logLine("Picked LK file: $lkFileName")
                analyzeLkFile()
            }
            REQ_BOOT_IMG -> {
                bootImgUri = uri
                bootImgName = displayName(uri)
                toast("Picked boot image: $bootImgName")
                logLine("Picked boot image: $bootImgName")
                // 2.0.8: Extras `boot` boots the picked image right away.
                if (pendingExtrasBoot) {
                    pendingExtrasBoot = false
                    bootImage()
                }
            }
            REQ_CUSTOM_KEY -> {
                customKeyUri = uri
                customKeyName = displayName(uri)
                toast("Picked custom key: $customKeyName")
                logLine("Picked custom key: $customKeyName")
            }
            REQ_BOOT_INSPECT -> inspectBootImage(uri)
            REQ_FOLDER -> {
                folderUri = uri
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                loadFolder(uri)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "file"
            } ?: (uri.lastPathSegment ?: "file")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "file"
        }
    }

    /** 2.0.13: inspect a picked boot image. Reads only the first
     *  64 KB (every header fits well inside that) plus the file
     *  size, parses with BootImage, logs and displays the facts.
     *  Read-only — nothing is copied for flashing or written. */
    private fun inspectBootImage(uri: Uri) {
        val name = displayName(uri)
        logLine("Inspect boot image: $name")
        Thread {
            val head = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(65536)
                    var total = 0
                    while (total < buf.size) {
                        val n = input.read(buf, total, buf.size - total)
                        if (n < 0) break
                        total += n
                    }
                    buf.copyOf(total)
                }
            } catch (e: Exception) {
                logLine("ERROR reading $name: ${e.message}")
                runOnUiThread {
                    inspectText.text = "Could not read $name: ${e.message}"
                    inspectText.setLed(UiStyle.Status.FAIL)
                }
                return@Thread
            }
            if (head == null) {
                logLine("ERROR reading $name: could not open the file")
                runOnUiThread {
                    inspectText.text = "Could not open $name."
                    inspectText.setLed(UiStyle.Status.FAIL)
                }
                return@Thread
            }
            val size = fileSize(uri) ?: head.size.toLong()
            val info = BootImage.inspect(head, size)
            logLine("Boot image inspection ($name):")
            for (line in info.lines) logLine("  $line")
            runOnUiThread {
                inspectText.text = info.lines.joinToString("\n")
                inspectText.setLed(if (info.isBoot) UiStyle.Status.OK else UiStyle.Status.FAIL)
            }
        }.start()
    }

    /** File size behind a SAF Uri, or null when the provider does
     *  not report one. */
    private fun fileSize(uri: Uri): Long? {
        return try {
            contentResolver.query(uri, arrayOf("_size"), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Copy a SAF Uri into our cache dir so the fastboot binary gets a real path. Returns the file + sha256. */
    private fun stageFile(uri: Uri, name: String): Pair<File, String>? {
        return try {
            val out = File(cacheDir, "stage-$name")
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Pair(out, Fastboot.sha256(out))
        } catch (e: Exception) {
            logLine("ERROR staging $name: ${e.message}")
            null
        }
    }

    // ---------- single flash / boot / erase ----------

    // 2.0.1: every confirm is the themed dialog — dark violet card,
    // rainbow title, Exo 2, magenta Yes / ghost Cancel.
    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        UiStyle.styledDialog(this, title, message, "Confirm", "Cancel", onYes)
    }

    /** 2.0.14: confirm from a background thread — shows the dialog
     *  on the UI thread and blocks until the user answers (any
     *  dismissal counts as "no"). Used by the size check and the
     *  auto-backup failure path inside the flash thread. */
    private fun confirmBlocking(title: String, message: String): Boolean {
        val latch = CountDownLatch(1)
        val answer = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnUiThread {
            UiStyle.styledDialog(this, title, message, "Flash anyway", "Cancel",
                onYes = { answer.set(true); latch.countDown() },
                onNo = { answer.set(false); latch.countDown() })
        }
        latch.await()
        return answer.get()
    }

    /** 2.0.14: partition size from the bootloader
     *  (`getvar partition-size:<part>`, hex). Null when the getvar
     *  fails or returns nothing parseable — callers then proceed
     *  without any dialog (a failed check never blocks a flash). */
    private fun partitionSize(part: String): Long? {
        val r = Fastboot.run(this, listOf("getvar", "partition-size:$part")) { logLine(it) }
        val m = Regex(
            "partition-size:[^:\\s]+:\\s*(0x[0-9a-fA-F]+|\\d+)",
            RegexOption.IGNORE_CASE,
        ).find(r.output) ?: return null
        val raw = m.groupValues[1]
        return try {
            if (raw.startsWith("0x", ignoreCase = true)) raw.substring(2).toLong(16) else raw.toLong()
        } catch (_: Exception) {
            null
        }
    }

    private fun flashSingle(applySlot: Boolean = true) {
        val uri = singleImgUri ?: run { toast("Pick an image file first"); return }
        val part = partInput.text.toString().trim()
        if (part.isEmpty()) { toast("Type the partition name first"); return }
        if (part == "preloader") {
            toast("fastboot cannot flash the preloader. Use BROM.")
            return
        }
        if (!writeGate()) return
        // 2.0.12: the confirm shows the EFFECTIVE partition (slot
        // suffix applied) so the target is visible before confirming.
        val effPart = if (applySlot) slotPartition(part) else part
        Thread {
            val staged = stageFile(uri, singleImgName ?: "image.img") ?: run {
                runOnUiThread { toast("Could not copy the image file") }
                return@Thread
            }
            val (file, sha) = staged
            runOnUiThread {
                confirm(
                    "Flash $effPart?",
                    "Command: fastboot flash $effPart ${file.name}\n" +
                        "Size: ${file.length()} bytes\nSHA-256: $sha\n\n" +
                        "There is no backup. This overwrites the partition. Make sure the image is for this phone.",
                ) { runFlashSteps(listOf(Triple("flash", part, file)), applySlot) }
            }
        }.start()
    }

    private fun bootImage() {
        val uri = bootImgUri ?: run { toast("Pick an image to boot first"); return }
        if (!writeGate()) return
        Thread {
            val staged = stageFile(uri, bootImgName ?: "boot.img") ?: run {
                runOnUiThread { toast("Could not copy the image file") }
                return@Thread
            }
            val (file, _) = staged
            runOnUiThread {
                confirm("Boot ${file.name} without flashing?",
                    "Command: fastboot boot ${file.name}\nSize: ${file.length()} bytes\n\nWrites nothing.") {
                    fbCmd(listOf("boot", file.absolutePath), "boot ${file.name}")
                }
            }
        }.start()
    }

    private fun erasePart() {
        val part = partInput.text.toString().trim()
        if (part.isEmpty()) { toast("Type the partition name first"); return }
        if (!writeGate()) return
        // 2.0.13: erase honors the slot picker like flash does — the
        // confirm and the command use the effective name.
        val effPart = slotPartition(part)
        if (effPart != part) logLine("slot ${slotLabel()}: $part becomes $effPart")
        confirm("Erase $effPart?",
            "Command: fastboot erase $effPart\n\nThis wipes the partition. There is no undo.") {
            fbCmd(listOf("erase", effPart), "erase $effPart")
        }
    }

    // ---------- bootloader unlock + custom key ----------

    private fun bootloaderStatus() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot oem device-info")
        opStarted()
        Thread {
            val r = Fastboot.run(this, listOf("oem", "device-info")) { logLine(it) }
            runOnUiThread {
                deviceText.text = ("Bootloader status:\n" + r.output)
                deviceText.setLed(UiStyle.Status.INFO)
            }
            opFinished(null)
        }.start()
    }

    private fun unlockWithKey() {
        val key = unlockKeyInput.text.toString().trim()
        if (key.isEmpty()) { toast("Paste the Motorola unlock key first"); return }
        if (!writeGate()) return
        confirm("Unlock the bootloader?",
            "Command: fastboot oem unlock $key\n\n" +
                "Unlocking wipes the phone. All user data is erased. Make sure the key is for this phone.") {
            fbCmd(listOf("oem", "unlock", key), "oem unlock")
        }
    }

    private fun relock() {
        if (!writeGate()) return
        confirm("Relock the bootloader?",
            "Command: fastboot oem lock\n\n" +
                "Relocking with custom images can brick the phone. Use stock images only.") {
            fbCmd(listOf("oem", "lock"), "oem lock")
        }
    }

    private fun flashCustomKey() {
        val uri = customKeyUri ?: run { toast("Pick a custom key file first"); return }
        if (!writeGate()) return
        Thread {
            val staged = stageFile(uri, customKeyName ?: "custom_key") ?: run {
                runOnUiThread { toast("Could not copy the key file") }
                return@Thread
            }
            val (file, sha) = staged
            runOnUiThread {
                confirm(
                    "Flash custom AVB key?",
                    "Command: fastboot flash avb_custom_key ${file.name}\n" +
                        "Size: ${file.length()} bytes\nSHA-256: $sha\n\n" +
                        "Installs your key as a trusted AVB key.",
                ) { runFlashSteps(listOf(Triple("flash", "avb_custom_key", file))) }
            }
        }.start()
    }

    private fun eraseCustomKey() {
        if (!writeGate()) return
        // 2.0.13: routed through slotPartition for consistency —
        // avb_custom_key is not an A/B partition, so it is unchanged.
        val effPart = slotPartition("avb_custom_key")
        confirm("Erase custom AVB key?",
            "Command: fastboot erase $effPart\n\nThis removes the custom key.") {
            fbCmd(listOf("erase", effPart), "erase $effPart")
        }
    }

    private var lkImage: LibLk.LkImage? = null
    /** The container the Val flow is working on (pulled or picked), and the
     *  flow's step readout. liblk is the container layer: the patcher and
     *  the repack both consume the LkImage it produces — analyze is not a
     *  standalone viewer, it is step 2 of the flow. */
    private var valFlowFile: File? = null
    /** Original container bytes the flow's LkImage was parsed from — the
     *  repack splices untouched regions from these, so the patched
     *  container is a rebuild, not a re-serialization. */
    private var valContainerBytes: ByteArray? = null
    private var valPatched: Boolean = false
    private var valUnlockKey: String? = null
    private lateinit var valPresetInput: EditText
    private lateinit var valFrpValueInput: EditText
    private lateinit var valSecretInput: EditText
    private lateinit var valSerialInput: EditText
    private lateinit var valKeyText: TextView

    // ---------- partitions / TWRP / erase / vbmeta disabler ----------

    /** One-tap partition flash: pick the file, then it flashes straight to [part]. */
    private fun flashPartitionQuick(part: String) {
        pendingPartition = part
        pendingApplySlot = true
        logLine("Quick flash $part: pick the image")
        pickFile(REQ_SINGLE_IMG)
    }

    private fun flashTwrp() {
        val uri = twrpImgUri ?: run { toast("Pick a TWRP image first"); return }
        if (!writeGate()) return
        Thread {
            val staged = stageFile(uri, twrpImgName ?: "twrp.img") ?: run {
                runOnUiThread { toast("Could not copy the TWRP image") }
                return@Thread
            }
            val (file, sha) = staged
            // 2.0.12: confirm shows the effective (slotted) target.
            val effTarget = slotPartition(twrpTarget)
            runOnUiThread {
                confirm(
                    "Flash TWRP to $effTarget?",
                    "Command: fastboot flash $effTarget ${file.name}\n" +
                        "Size: ${file.length()} bytes\nSHA-256: $sha\n\n" +
                        "This overwrites $effTarget. There is no backup.",
                ) { runFlashSteps(listOf(Triple("flash", twrpTarget, file))) }
            }
        }.start()
    }

    private fun bootTwrp() {
        val uri = twrpImgUri ?: run { toast("Pick a TWRP image first"); return }
        if (!writeGate()) return
        Thread {
            val staged = stageFile(uri, twrpImgName ?: "twrp.img") ?: run {
                runOnUiThread { toast("Could not copy the TWRP image") }
                return@Thread
            }
            val (file, _) = staged
            runOnUiThread {
                confirm("Boot TWRP without flashing?",
                    "Command: fastboot boot ${file.name}\nSize: ${file.length()} bytes\n\nWrites nothing.") {
                    fbCmd(listOf("boot", file.absolutePath), "boot TWRP")
                }
            }
        }.start()
    }

    /**
     * 2.0.8: one-tap erase for a named partition. Replaces formatPart —
     * the Moto's fastboot has NO format command (verified: no `format`
     * in the factory LK's command table or the neon LK's), so
     * `fastboot format` always failed. Erase is what the phone supports.
     */
    private fun erasePartNamed(part: String) {
        if (!writeGate()) return
        // 2.0.13: slot picker applies here too (boot is an A/B
        // partition; userdata/cache/recovery pass through unchanged).
        val effPart = slotPartition(part)
        if (effPart != part) logLine("slot ${slotLabel()}: $part becomes $effPart")
        confirm("Erase $effPart?",
            "Command: fastboot erase $effPart\n\n" +
                "This wipes the partition. Erasing userdata removes all user data. There is no undo.") {
            fbCmd(listOf("erase", effPart), "erase $effPart")
        }
    }

    /** 2.0.8: save the picked theme and rebuild the UI with it. */
    private fun setTheme(name: String) {
        getSharedPreferences("motoflasher", Context.MODE_PRIVATE).edit().putString("theme", name).apply()
        UiStyle.applyTheme(name)
        recreate()
    }

    /** 2.0.8: append a hand-typed erase step to the loaded flash-all list. */
    private fun addEraseStep() {
        val part = eraseStepInput.text.toString().trim()
        if (part.isEmpty()) { toast("Type a partition name first"); return }
        flashItems.add(FlashItem("erase $part", part, null, null, 0, true))
        showFlashItems(flashItems.toList(), flashAllSource.ifBlank { "manual steps" })
        logLine("Added erase step: $part")
    }

    // ---------- 2.0.8: Extras tab runners ----------

    /** Run a command that takes its argument from the Extras box. */
    private fun extrasArgCmd(base: List<String>, default: String? = null, dangerous: Boolean = false) {
        val arg = extrasArgInput.text.toString().trim().ifEmpty { default }
        if (arg.isNullOrEmpty()) { toast("Type an argument first"); return }
        val args = base + arg
        val label = args.joinToString(" ")
        if (dangerous) {
            confirm("Run $label?",
                "Command: fastboot $label\n\nThis wipes the partition. There is no undo.") {
                fbCmd(args, label)
            }
        } else {
            fbCmd(args, label)
        }
    }

    /** Run a command behind a confirm dialog (dangerous Extras entries). */
    private fun extrasConfirmRun(args: List<String>, label: String) {
        confirm("Run $label?",
            "Command: fastboot ${args.joinToString(" ")}\n\nThis can change or wipe the phone. There is no undo.") {
            fbCmd(args, label)
        }
    }

    /** Extras `flash`: partition from the argument box, then pick the image. */
    private fun extrasFlash() {
        val part = extrasArgInput.text.toString().trim()
        if (part.isEmpty()) { toast("Type the partition name first"); return }
        if (part == "preloader") {
            toast("fastboot cannot flash the preloader. Use BROM.")
            return
        }
        pendingPartition = part
        // 2.0.12: Extras is the raw command tab — the typed name is
        // exact, the slot picker does not touch it.
        pendingApplySlot = false
        partInput.setText(part)
        logLine("Extras flash $part: pick the image")
        pickFile(REQ_SINGLE_IMG)
    }

    /** Extras `boot`: pick the image, then boot it right away. */
    private fun extrasBoot() {
        pendingExtrasBoot = true
        logLine("Extras boot: pick the image to boot (no flash)")
        pickFile(REQ_BOOT_IMG)
    }

    /** Extras `oem <sub>`: argument box appended when the subcommand needs one. */
    private fun extrasOem(sub: String) {
        val arg = extrasArgInput.text.toString().trim()
        val args = if (arg.isEmpty()) listOf("oem", sub) else listOf("oem", sub, arg)
        val label = args.joinToString(" ")
        if (sub in LK_OEM_DANGEROUS) {
            confirm("Run $label?",
                "Command: fastboot $label\n\nThis can change or wipe the phone. There is no undo.") {
                fbCmd(args, label)
            }
        } else {
            fbCmd(args, label)
        }
    }

    /** 2.0.8: full ADB diagnosis — version, fresh server, USB visibility, devices. */
    private fun diagnoseAdb() {
        if (!Adb.isInstalled(this)) {
            val err = Adb.install(this)
            if (err != null) { toast(err); logLine(err); adbStatusText.text = err; adbStatusText.setLed(UiStyle.Status.FAIL); return }
        }
        adbStatusText.text = "Running ADB diagnose…"
        adbStatusText.setLed(UiStyle.Status.WAIT)
        opStarted()
        Thread {
            val summary = Adb.diagnose(this) { logLine(it) }
            runOnUiThread { adbStatusText.text = summary; adbStatusText.setLed(if (summary.contains("device found")) UiStyle.Status.OK else UiStyle.Status.FAIL) }
            opFinished(null)
        }.start()
    }

    /** Flash the empty vbmeta with verity + verification disabled. */
    private fun disableVerity(part: String) {
        if (!writeGate()) return
        val targets = when (part) {
            "both" -> listOf("vbmeta", "vbmeta_system")
            else -> listOf(part)
        }
        // 2.0.12: vbmeta/vbmeta_system are A/B partitions — the picked
        // slot applies here too; confirm + logs show effective names.
        val effTargets = targets.map { slotPartition(it) }
        confirm("Disable verification on ${effTargets.joinToString(" + ")}?",
            "Command: fastboot --disable-verity --disable-verification flash <part> empty-vbmeta.img\n\n" +
                "Flashes an empty vbmeta with verification off.\n\nNeeds an unlocked bootloader. Flash this first.") {
            Thread {
                acquireWake()
                try {
                    val empty = Vbmeta.makeEmptyVbmeta(cacheDir)
                    logLine("empty vbmeta built: ${empty.length()} bytes sha256=${Fastboot.sha256(empty)}")
                    for ((raw, target) in targets.zip(effTargets)) {
                        if (target != raw) logLine("slot ${slotLabel()}: $raw becomes $target")
                        logLine("== disable-verity $target")
                        val r = Fastboot.run(
                            this,
                            listOf("--disable-verity", "--disable-verification", "flash", target, empty.absolutePath),
                        ) { logLine(it) }
                        if (!r.ok) {
                            logLine("FAILED: disable-verity $target (exit ${r.exitCode})")
                            runOnUiThread { toast("Disabler failed on $target — see log") }
                            return@Thread
                        }
                        logLine("OK: disable-verity $target")
                    }
                    runOnUiThread { toast("Verification disabled on ${effTargets.joinToString(", ")}") }
                } finally {
                    releaseWake()
                }
            }.start()
        }
    }

    // ---------- Val Protocol: liblk, LK picker, puller ----------

    /** Render the Val flow's step state from the shared container.
     *  2.0.13: chips — done steps solid accent, the first unfinished
     *  step is active (and pulses while the Val tab shows). Flash
     *  and verify have no completion flag in the flow state, so
     *  flash is "active" once patched, and verify stays pending. */
    private fun updateValFlow() {
        val done = listOf(
            valFlowFile != null,   // pull
            lkImage != null,       // analyze
            valPatched,            // patch
            false,                 // flash (no completion flag)
            valUnlockKey != null,  // unlock
            false,                 // verify (verifier held)
        )
        valActiveChip = done.indexOfFirst { !it }
        runOnUiThread {
            valChips.forEachIndexed { i, chip ->
                UiStyle.setChipState(
                    chip,
                    when {
                        done[i] -> UiStyle.ChipState.DONE
                        i == valActiveChip -> UiStyle.ChipState.ACTIVE
                        else -> UiStyle.ChipState.PENDING
                    },
                )
            }
            refreshValChipPulse()
        }
    }

    private fun analyzeLkFile() {
        val uri = lkFileUri ?: run { toast("Pick an LK file first"); return }
        Thread {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run { logLine("ERROR: could not read ${lkFileName}"); return@Thread }
            analyzeLkBytes(bytes, lkFileName ?: "lk.img")
        }.start()
    }

    /** Parse a container's bytes with liblk and make it the flow's container. */
    private fun analyzeLkBytes(bytes: ByteArray, name: String) {
        if (bytes.size > 64 * 1024 * 1024) {
            logLine("ERROR: $name is ${bytes.size} bytes — too big to analyze in-app")
            return
        }
        try {
            val image = LibLk.parse(bytes)
            lkImage = image
            valContainerBytes = bytes
            val desc = LibLk.describe(image)
            logLine("LK analysis ($name):\n$desc")
            runOnUiThread {
                lkStatusText.text = desc
                lkStatusText.setLed(UiStyle.Status.OK)
                updateValFlow()
            }
        } catch (e: Exception) {
            logLine("ERROR analyzing $name: ${e.message}")
            runOnUiThread {
                lkStatusText.text = "Not an LK/LKS container: ${e.message}"
                lkStatusText.setLed(UiStyle.Status.FAIL)
                updateValFlow()
            }
        }
    }

    // Zip a directory (the Val analysis dir) into one file so it can be
    // published to Downloads as a single finished file.
    private fun zipDir(dir: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(dir).path))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Step 3 of the Val flow: Capstone analysis, preset patch, CERT2 repack.
     *  The patched container is re-parsed with liblk before it becomes the
     *  flow file — if it does not parse, it is refused and nothing is set
     *  up for flashing. The repack itself is NOT verified in-app
     *  (verify_mtk_image.py is not ported); the log says so. */
    private fun runValPatch() {
        val image = lkImage ?: run { toast("Pull or pick an LK file first"); return }
        val container = valContainerBytes ?: run { toast("Analyze an LK file first"); return }
        val preset = valPresetInput.text.toString().trim().ifEmpty { "unlock-serial" }
        val secret = valSecretInput.text.toString().trim().ifEmpty { "Valeria" }
        val frpValue = valFrpValueInput.text.toString().trim().toIntOrNull() ?: 1
        val flowFile = valFlowFile
        // Private working dir only — finished files publish to
        // Download/MotoFlasher/val/ below (BromDownloads), never here:
        // Android/data is not accessible natively on Android.
        val valDir = File(filesDir, "val").apply { mkdirs() }
        val analysisDir = File(valDir, "analysis")
        val patchedBin = File(valDir, "lk.patched.bin")
        val outName = "lk.$preset.img"
        logLine("Val patch: preset=$preset secret=$secret frpCompareValue=$frpValue (Capstone analyze first — this takes a while on a phone)")
        opStarted()
        var patchOk = false
        Thread {
            try {
                val loadErr = CapstoneNative.ensureLoaded(this)
                if (loadErr != null) {
                    logLine("ERROR: $loadErr")
                    runOnUiThread { toast("Capstone failed to load — see log") }
                    return@Thread
                }
                logLine("Capstone ${CapstoneNative.version()} loaded, analyzing ${flowFile?.name ?: "lk.img"}...")
                // 2.0.12: step headers mirroring lk_auto_patch.py's
                // "+ command" echoes, so the log reads like a PC run.
                logLine("+ lk_static_analyzer ${flowFile?.name ?: "lk.img"}")
                // 2.0.11: valproto prints PC-style verbose output via
                // println — capture it into the app log live (VAL: lines).
                val analysis = captureValOutput {
                    LkStaticAnalyzer.analyze(image, analysisDir, flowFile?.name ?: "lk.img")
                }
                logLine("Analysis: arch=${analysis.architecture} insns=${analysis.instructionCount} " +
                    "flows=${analysis.flowCount} key-validators=${analysis.keyValidatorCount} " +
                    "frp-checkers=${analysis.frpCheckerCount}")
                // Headlines from the analyzer's main report (summary.txt):
                // counts, arch, best-flow / gate candidates. Full reports
                // ride along in the analysis zip published below.
                val summaryFile = File(analysisDir, "summary.txt")
                if (summaryFile.isFile) {
                    for (line in summaryFile.readLines()) {
                        if (line.startsWith("Address mapping")) break
                        if (line.isNotBlank()) logLine("VAL: $line")
                    }
                }
                // 2.0.12: the two PC analyzer lines summary.txt does not
                // carry — serialno runtime (values from the analysis
                // result) and the output dir.
                val snRuntime = analysis.serialnoRuntime
                if (snRuntime != null) {
                    val conf = (snRuntime.map["confidence"] as? PyInt)?.n
                    val fn = (snRuntime.map["hw_get_serialno_string"] as? PyStr)?.s
                    logLine("VAL: Serialno runtime: $fn (confidence $conf)")
                } else {
                    logLine("VAL: Serialno runtime: none detected")
                }
                logLine("VAL: Output dir : ${analysisDir.absolutePath}")
                val opts = captureValOutput {
                    ValPresets.buildPatchOptions(
                        PresetArgs(
                            image = flowFile ?: File(valDir, "lk.img"), preset = preset,
                            keyTokenSecret = secret, frpCompareValue = frpValue,
                        ),
                        analysisDir, patchedBin,
                    )
                }
                logLine("+ lk_patch_partition (preset $preset)")
                val run = captureValOutput { ValPatcher.runPatch(image, opts) }
                if (run.exitCode != 0 || run.patchedPayload == null) {
                    logLine("ERROR: patch failed (exit ${run.exitCode}) — the preset refused this LK or a gate was not found. See the analysis dir: ${analysisDir.absolutePath}")
                    runOnUiThread { toast("Patch failed — see log") }
                    return@Thread
                }
                logLine("Patched payload: ${run.patchedPayload.size} bytes")
                logLine("+ lk_repack_signed + sign_mtk_cert")
                logLine("VAL: Original image : ${flowFile?.absolutePath ?: "lk.img"}")
                logLine("VAL: Patched payload: ${patchedBin.absolutePath}")
                logLine("VAL: Output image : ${File(valDir, outName).absolutePath}")
                val repack = captureValOutput { ValRepack.repack(image, container, run.patchedPayload) }
                val outFile = File(valDir, outName)
                outFile.writeBytes(repack.output)
                for (line in repack.certLog) logLine("CERT2: $line")
                // 2.0.12: build-part-img block — one line per container
                // image, the replaced region marked, then the write.
                logLine("+ build-part-img (rebuild container)")
                image.partitions.forEachIndexed { i, p ->
                    logLine("VAL: processing image $i: ${p.name}")
                    if (p.name == "lk") logLine("VAL: replacing region 0x${repack.regionStart.toString(16)}..0x${repack.regionEnd.toString(16)} with patched payload")
                }
                logLine("VAL: wrote rebuilt image to ${outFile.absolutePath}")
                logLine("VAL: Write complete: ${outFile.absolutePath}")
                // Refuse to hand over a container liblk itself cannot parse.
                val rechecked = try {
                    LibLk.parse(repack.output)
                } catch (e: Exception) {
                    logLine("ERROR: patched container does not re-parse with liblk (${e.message}) — refused, not set as the flow file")
                    runOnUiThread { toast("Patched file failed its re-parse check") }
                    return@Thread
                }
                val sha = Fastboot.sha256(outFile)
                logLine("Patched container: ${outFile.absolutePath} (${outFile.length()} bytes, sha256=$sha)")
                logLine("Partitions after patch: ${rechecked.partitions.map { it.name }.sorted()}")
                logLine("NOTE: the repack is not verified in the app (verify_mtk_image.py is not ported). " +
                    "The container re-parses and CERT2 was re-signed, but run the Python verifier on a PC first if you want the full check.")
                // Publish the finished files to Downloads so Kyle can find
                // them in the Files app. The flow file for flashing must
                // point at the PUBLISHED lk.<preset>.img; if publishing
                // failed, it falls back to the private copy and the log
                // says so loudly.
                val publishedPatched = BromDownloads.publish(this, outFile, "val", outName) { logLine(it) }
                val publishedBin = BromDownloads.publish(this, patchedBin, "val", "lk.patched.bin") { logLine(it) }
                if (publishedBin == null) {
                    logLine("DOWNLOADS WARNING: lk.patched.bin could not be published to Download/MotoFlasher/val/ — private copy is at ${patchedBin.absolutePath}")
                }
                val analysisZip = File(valDir, "analysis-$preset.zip")
                if (analysisDir.exists()) {
                    zipDir(analysisDir, analysisZip)
                    val publishedZip = BromDownloads.publish(this, analysisZip, "val", "analysis-$preset.zip") { logLine(it) }
                    if (publishedZip == null) {
                        logLine("DOWNLOADS WARNING: analysis-$preset.zip could not be published to Download/MotoFlasher/val/ — private copy is at ${analysisZip.absolutePath}")
                    }
                } else {
                    logLine("DOWNLOADS WARNING: no analysis dir at ${analysisDir.absolutePath} — no analysis zip published")
                }
                if (publishedPatched != null) {
                    logLine("Val finished files published to Download/MotoFlasher/val/: $outName, lk.patched.bin, analysis-$preset.zip")
                    valFlowFile = publishedPatched
                } else {
                    logLine("DOWNLOADS WARNING: $outName could not be published to Download/MotoFlasher/val/ — flow file falls back to the private copy at ${outFile.absolutePath}")
                    valFlowFile = outFile
                }
                valPatched = true
                // 2.0.12: PC final block.
                logLine("VAL: Final image : ${valFlowFile?.absolutePath ?: outFile.absolutePath}")
                logLine("VAL: Workdir : ${valDir.absolutePath}")
                logLine("VAL: Done")
                logLine("VAL: Analysis dir : ${analysisDir.absolutePath}")
                logLine("VAL: Patched bin : ${patchedBin.absolutePath}")
                logLine("VAL: Patched image: ${outFile.absolutePath}")
                patchOk = true
                runOnUiThread {
                    toast("Patched: $outName — flash it from the buttons below")
                    updateValFlow()
                }
            } catch (e: Exception) {
                logLine("ERROR patching: ${e.message}")
                runOnUiThread { toast("Patch error — see log") }
            } finally {
                opFinished(patchOk)
            }
        }.start()
    }

    /** Step 5a: read the target's serial number over fastboot. */
    /** 2.0.14: Val tab bootloader status — securestate, unlocked,
     *  frp-state, current-slot in one tap. Read-only; every getvar
     *  line is logged verbatim and the summary lands in the Val
     *  status area. */
    private fun valBootloaderStatus() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        opStarted()
        Thread {
            val vars = listOf("securestate", "unlocked", "frp-state", "current-slot")
            val values = mutableMapOf<String, String>()
            for (name in vars) {
                logLine("$ fastboot getvar $name")
                val r = Fastboot.run(this, listOf("getvar", name)) { logLine(it) }
                val v = Regex("$name:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                    .find(r.output)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                values[name] = v.ifEmpty { "(no answer)" }
            }
            val summary = vars.joinToString("\n") { "$it: ${values[it]}" }
            logLine("Bootloader status:\n$summary")
            runOnUiThread {
                valStatusText.text = summary
                val unlocked = values["unlocked"]?.lowercase() in listOf("yes", "true", "1") ||
                    values["securestate"]?.contains("unlocked", ignoreCase = true) == true
                valStatusText.setLed(if (unlocked) UiStyle.Status.OK else UiStyle.Status.WAIT)
            }
            opFinished(null)
        }.start()
    }

    private fun getValSerial() {
        logLine("$ fastboot getvar serialno")
        opStarted()
        Thread {
            val r = Fastboot.run(this, listOf("getvar", "serialno")) { logLine(it) }
            val serial = Regex("serialno:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                .find(r.output)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (serial.isEmpty()) {
                logLine("ERROR: no serialno in getvar output (is the phone in fastboot?)")
                runOnUiThread { toast("No serial found — see log") }
                opFinished(false)
                return@Thread
            }
            logLine("serialno: $serial")
            runOnUiThread { valSerialInput.setText(serial) }
            opFinished(true)
        }.start()
    }

    /** Step 5b: generate the unlock key from the serial + secret. */
    private fun runValKeygen() {
        val serial = valSerialInput.text.toString().trim()
        if (serial.isEmpty()) { toast("Get the serial first (fastboot getvar serialno)"); return }
        val secret = valSecretInput.text.toString().trim().ifEmpty { "Valeria" }
        Thread {
            try {
                val result = ValKeygen.generate(
                    ValKeygen.KeygenRequest(secret = secret, serialno = serial, count = 1),
                )
                val key = result.keys.firstOrNull()
                    ?: run { logLine("ERROR: keygen produced no key"); return@Thread }
                valUnlockKey = key
                logLine("Unlock key (secret=$secret, serial=$serial): $key")
                runOnUiThread {
                    valKeyText.text = "Unlock key: $key"
                    unlockKeyInput.setText(key)
                    updateValFlow()
                    toast("Key copied into the Unlock box above")
                }
            } catch (e: Exception) {
                logLine("ERROR keygen: ${e.message}")
                runOnUiThread { toast("Keygen error — see log") }
            }
        }.start()
    }

    /** Save the raw `lk` payload from the analyzed container to a file Kyle can find. */
    /** Save one partition's raw bytes out of the analyzed container. */
    private fun extractLkPartition(name: String, outName: String = "$name.bin") {
        if (name.isEmpty()) { toast("Type the partition name first"); return }
        val image = lkImage ?: run { toast("Analyze an LK file first"); return }
        val part = image.partitions.firstOrNull { it.name == name }
            ?: run { toast("No '$name' partition in this container"); return }
        Thread {
            val outDir = File(getExternalFilesDir(null), "lk")
            outDir.mkdirs()
            val out = File(outDir, outName)
            out.writeBytes(part.data)
            val sha = Fastboot.sha256(out)
            logLine("$name extracted: ${out.absolutePath} (${out.length()} bytes, sha256=$sha)")
            runOnUiThread { toast("$name saved: ${out.name}") }
        }.start()
    }

    private fun extractLkPayload() {
        extractLkPartition("lk", "lk-payload.bin")
    }

    /** Flash the flow's container (the analyzed LK) to lk, lk_a, or
     *  lk_b. Falls back to the picked file when nothing was
     *  pulled/analyzed. */
    private fun valFlashLk(target: String) {
        if (target !in listOf("lk", "lk_a", "lk_b")) return
        if (!writeGate()) return
        val flowFile = valFlowFile
        if (flowFile != null && flowFile.exists()) {
            val sha = Fastboot.sha256(flowFile)
            runOnUiThread {
                confirm(
                    "Flash LK to $target?",
                    "Command: fastboot flash $target ${flowFile.name}\n" +
                        "Source: the analyzed container (${lkImage?.partitions?.size ?: 0} partitions)\n" +
                        "Size: ${flowFile.length()} bytes\nSHA-256: $sha\n\n" +
                        "The LK is the bootloader. A bad flash bricks the phone until BROM recovery. Only flash an LK built for this phone.",
                // 2.0.12: the buttons encode the slot explicitly
                // (lk / lk_a / lk_b) — the picker must not second-guess them.
                ) { runFlashSteps(listOf(Triple("flash", target, flowFile)), applySlot = false) }
            }
            return
        }
        val uri = lkFileUri ?: run { toast("Pick or pull an LK file first"); return }
        Thread {
            val staged = stageFile(uri, lkFileName ?: "lk.img") ?: run {
                runOnUiThread { toast("Could not copy the LK file") }
                return@Thread
            }
            val (file, sha) = staged
            runOnUiThread {
                confirm(
                    "Flash LK to $target?",
                    "Command: fastboot flash $target ${file.name}\n" +
                        "Size: ${file.length()} bytes\nSHA-256: $sha\n\n" +
                        "The LK is the bootloader. A bad flash bricks the phone until BROM recovery. Only flash an LK built for this phone.",
                // 2.0.12: explicit lk / lk_a / lk_b buttons — picker bypassed.
                ) { runFlashSteps(listOf(Triple("flash", target, file)), applySlot = false) }
            }
        }.start()
    }

    private fun adbDevices() {
        if (!Adb.isInstalled(this)) {
            val err = Adb.install(this)
            if (err != null) { toast(err); logLine(err); return }
        }
        logLine("$ adb devices (fresh server)")
        opStarted()
        Thread {
            // 2.0.8: fresh server — a stale daemon never sees the device.
            val r = Adb.devicesFresh(this) { logLine(it) }
            runOnUiThread {
                deviceText.text = if (Adb.hasDevice(r.output)) "adb devices:\n${r.output}"
                    else "adb devices: none found. Run Diagnose ADB."
                deviceText.setLed(if (Adb.hasDevice(r.output)) UiStyle.Status.OK else UiStyle.Status.FAIL)
            }
            opFinished(null)
        }.start()
    }

    /**
     * 2.0.7: run an adb command from the ADB tab, on a background thread.
     * Same engine as the puller (Adb.run: bundled aarch64 adb via the
     * root shell, HOME pointed at the app dir, stdout + stderr captured
     * line by line). No booted-Android-only assumptions — adb talks to
     * whatever adbd answers, normal Android or a custom recovery (TWRP).
     * Every line adb prints goes to the shared log verbatim, including
     * errors; the tab's status line shows the exit code and a short
     * summary. No device attached = adb's own "no devices" output, never
     * a fake success or an empty log passed off as working.
     */
    private fun adbRun(args: List<String>, label: String) {
        if (!Adb.isInstalled(this)) {
            val err = Adb.install(this)
            if (err != null) { toast(err); logLine(err); adbStatusText.text = err; adbStatusText.setLed(UiStyle.Status.FAIL); return }
        }
        logLine("$ adb ${args.joinToString(" ")}")
        adbStatusText.text = "Running: adb ${args.joinToString(" ")} …"
        adbStatusText.setLed(UiStyle.Status.WAIT)
        opStarted()
        Thread {
            // 2.0.8: `devices` always runs against a FRESH server — a
            // stale daemon owns port 5037 and never sees the device.
            // Anything else runs as-is.
            val r = if (args == listOf("devices") || args == listOf("devices", "-l")) {
                Adb.devicesFresh(this) { logLine(it) }
            } else {
                Adb.run(this, args) { logLine(it) }
            }
            opFinished(r.exitCode == 0)
            runOnUiThread {
                val out = r.output.trim()
                adbStatusText.text = when {
                    out.contains("no devices/emulators found", ignoreCase = true) ->
                        "$label: no device found.\nPlug in over OTG, turn on USB debugging, and tap Allow."
                    (args == listOf("devices") || args == listOf("devices", "-l")) && !Adb.hasDevice(out) ->
                        // 2.0.8: exit 0 with an empty list is NOT success —
                        // the server is up but blind. Say so plainly.
                        "Devices: none found. The server is up but sees nothing.\nRun Diagnose ADB."
                    else -> "$label: exit ${r.exitCode}\n${out.ifBlank { "(no output)" }.take(400)}"
                }
                adbStatusText.setLed(
                    when {
                        out.contains("no devices/emulators found", ignoreCase = true) -> UiStyle.Status.FAIL
                        (args == listOf("devices") || args == listOf("devices", "-l")) && !Adb.hasDevice(out) -> UiStyle.Status.FAIL
                        r.exitCode == 0 -> UiStyle.Status.OK
                        else -> UiStyle.Status.FAIL
                    },
                )
                logLine("$label: exit ${r.exitCode}")
            }
        }.start()
    }

    /** Run the ADB tab input box as raw adb args (e.g. `devices`, `shell getprop …`). */
    private fun runAdbInput() {
        val parts = adbInput.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) { toast("Type adb args first, e.g. devices"); return }
        adbRun(parts, parts.joinToString(" "))
    }

    /**
     * Run the ADB tab input box as one shell command on the device
     * (`adb shell <input>`). Passed as a single argument so the device's
     * own shell handles pipes, quotes and redirects.
     */
    private fun runAdbShell() {
        val cmd = adbInput.text.toString().trim()
        if (cmd.isEmpty()) { toast("Type a shell command first, e.g. getprop ro.build.version.release"); return }
        adbRun(listOf("shell", cmd), "adb shell $cmd")
    }

    /**
     * Pull a partition off the TARGET phone. Needs USB debugging on, adb
     * authorized, and temp root on the target: `dd` reads the block device
     * as root on the target, then `adb pull` brings the file home.
     */
    private fun pullPartition(part: String) {
        if (part.isEmpty()) { toast("Type the partition name first"); return }
        if (!Adb.isInstalled(this)) {
            val err = Adb.install(this)
            if (err != null) { toast(err); logLine(err); return }
        }
        if (!rootOk) {
            toast("No root on the host phone — the puller needs root to run adb")
            logLine("BLOCKED: no root on the host phone (puller needs root)")
            return
        }
        logLine("Pull $part")
        opStarted()
        Thread {
            val localFile = pullPartitionBlocking(part)
            if (localFile == null) {
                logLine("FAILED: could not pull $part off the target (is it rooted? is USB debugging on?)")
                runOnUiThread { toast("Pull failed — see log") }
                opFinished(false)
                return@Thread
            }
            val sha = Fastboot.sha256(localFile)
            logLine("Pulled $part: ${localFile.length()} bytes sha256=$sha")
            runOnUiThread { toast("Pulled $part (${localFile.length()} bytes)") }
            // A pulled LK feeds straight into the flow: liblk parses it and
            // the parsed container becomes the shared object the patch and
            // repack steps consume — no separate "pick file" needed.
            if (part == "lk" || part == "lk_a" || part == "lk_b") {
                valFlowFile = localFile
                analyzeLkBytes(localFile.readBytes(), localFile.name)
            }
            opFinished(true)
        }.start()
    }

    /** 2.0.14: blocking pull core shared by the Puller and the
     *  auto-backup path — dd on the target (by-name, then
     *  bootdevice), adb pull home. Returns the local file, or null
     *  on any failure (every step logs verbatim). No toasts or op
     *  counters here; the caller owns those. */
    private fun pullPartitionBlocking(part: String): File? {
        val remoteDir = "/sdcard/MotoFlasher"
        val remoteFile = "$remoteDir/$part.img"
        val outDir = File(getExternalFilesDir(null), "pulls")
        outDir.mkdirs()
        val localFile = File(outDir, "$part.img")
        Adb.run(this, listOf("shell", "mkdir", "-p", remoteDir)) { logLine(it) }
        val dd = { blockDev: String ->
            Adb.run(
                this,
                listOf("shell", "su", "-c", "dd if=$blockDev of=$remoteFile bs=1M"),
            ) { logLine(it) }
        }
        var r = dd("/dev/block/by-name/$part")
        if (!r.ok || r.output.contains("No such file", ignoreCase = true)) {
            logLine("by-name path failed, trying bootdevice path")
            r = dd("/dev/block/bootdevice/by-name/$part")
        }
        if (!r.ok) return null
        val pull = Adb.run(this, listOf("pull", remoteFile, localFile.absolutePath)) { logLine(it) }
        if (!pull.ok || !localFile.exists() || localFile.length() == 0L) return null
        return localFile
    }

    // ---------- flash-all ----------

    private fun loadFolder(treeUri: Uri) {
        Thread {
            try {
                val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
                val kids = mutableListOf<Triple<String, Uri, Long>>()
                contentResolver.query(
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
                val byName = kids.associateBy { it.first }
                val xml = byName["flashfile.xml"]?.let { (_, uri, _) ->
                    contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                }
                val items = mutableListOf<FlashItem>()
                if (xml != null) {
                    val steps = Flashfile.parse(xml)
                    for (s in steps) {
                        when (s.operation) {
                            "flash" -> {
                                val kid = s.filename?.let { byName[it] }
                                if (kid == null) {
                                    items.add(FlashItem("MISSING FILE: ${s.raw}", s.partition, s.filename, null, 0, true, false))
                                } else {
                                    val risky = s.partition?.lowercase() in RISKY_PARTS
                                    items.add(FlashItem("flash ${s.partition}: ${s.filename}", s.partition, s.filename, kid.second, kid.third, risky))
                                }
                            }
                            "erase" -> items.add(FlashItem("erase ${s.partition}", s.partition, null, null, 0, true))
                            // 2.0.8: flashfile.xml `format` steps are mapped
                            // to erase — this fastboot has no format command
                            // (no `format` in either LK's command table).
                            "format" -> {
                                logLine("flashfile.xml: 'format ${s.partition}' is not supported by this fastboot — mapped to erase")
                                items.add(FlashItem("erase ${s.partition}", s.partition, null, null, 0, true))
                            }
                            "oem" -> items.add(FlashItem("oem ${s.varName}", null, null, null, 0, false))
                            "unlock" -> items.add(FlashItem("unlock ${s.varName ?: "(no key in xml)"}", null, null, null, 0, true))
                            "lock" -> items.add(FlashItem("lock bootloader", null, null, null, 0, true))
                            "reboot", "reboot-bootloader" -> items.add(FlashItem("${s.operation}", null, null, null, 0, false))
                            "getvar" -> items.add(FlashItem("getvar ${s.varName}", null, null, null, 0, false))
                            else -> items.add(FlashItem("UNSUPPORTED STEP (will stop run): ${s.raw}", null, null, null, 0, true, false))
                        }
                    }
                } else {
                    val imgs = kids.filter { it.first.lowercase().endsWith(".img") || it.first.lowercase().endsWith(".bin") }
                    for (name in Flashfile.sortFilenames(imgs.map { it.first })) {
                        val kid = byName[name]!!
                        val part = name.substringBeforeLast('.')
                        val risky = part.lowercase() in RISKY_PARTS
                        items.add(FlashItem("flash $part: $name", part, name, kid.second, kid.third, risky))
                    }
                }
                groupSparseChunks(items)
                runOnUiThread { showFlashItems(items, if (xml != null) "flashfile.xml found" else "no flashfile.xml — folder order") }
            } catch (e: Exception) {
                runOnUiThread {
                    flashAllLabel.text = "Could not read folder: ${e.message}"
                    logLine("ERROR reading folder: ${e.message}")
                }
            }
        }.start()
    }

    /** Merge consecutive `flash <part>: <base>.img_sparsechunk.N` steps into
     * one checkbox row per partition ("super — 24 chunks, 6.4 GB"). The chunks
     * still flash one by one in order; only the list gets shorter. */
    private fun groupSparseChunks(items: MutableList<FlashItem>) {
        val chunkRe = Regex("""flash (\S+): (.+)\.img_sparsechunk\.\d+""")
        val grouped = mutableListOf<FlashItem>()
        var i = 0
        while (i < items.size) {
            val m = chunkRe.find(items[i].label)
            if (m == null) {
                grouped.add(items[i]); i++
                continue
            }
            val part = m.groupValues[1]
            val base = m.groupValues[2]
            val run = mutableListOf<FlashItem>()
            var j = i
            while (j < items.size) {
                val mm = chunkRe.find(items[j].label)
                if (mm == null || mm.groupValues[1] != part || mm.groupValues[2] != base) break
                run.add(items[j]); j++
            }
            if (run.size > 1) {
                val total = run.sumOf { it.size }
                grouped.add(
                    FlashItem(
                        "flash $part: $base.img (${run.size} chunks, $total bytes)",
                        part, null, null, total, run.any { it.risky },
                        run.all { it.checked }, run,
                    )
                )
                i = j
            } else {
                grouped.add(items[i]); i++
            }
        }
        items.clear()
        items.addAll(grouped)
    }

    /** Rebuild the slot-picker row: the picked slot is the magenta
     *  action button, the other two are dark ghost buttons. */
    private fun refreshSlotRow() {
        slotRow.removeAllViews()
        val choices = listOf("Active" to SLOT_ACTIVE, "Slot A" to SLOT_A, "Slot B" to SLOT_B)
        for ((label, slot) in choices) {
            val btn = if (slot == flashSlot) {
                UiStyle.rowActionButton(this, label) { setFlashSlot(slot) }
            } else {
                UiStyle.rowGhostButton(this, label) { setFlashSlot(slot) }
            }
            slotRow.addView(btn)
        }
    }

    private fun setFlashSlot(slot: Int) {
        if (slot == flashSlot) return
        flashSlot = slot
        refreshSlotRow()
        refreshFlashItemLabels()
        logLine(
            "Flash-all target slot: ${slotLabel()}" +
                if (slot == SLOT_ACTIVE) " (no suffix — same as before)"
                else " (A/B partitions flash as *${if (slot == SLOT_A) "_a" else "_b"})",
        )
    }

    /** Display label for a flash-all item under the current slot. Flash
     *  steps show the partition that will actually be flashed (boot_a
     *  when Slot A is picked); every other step type is unchanged.
     *  Derived from the item's fields, so calling it twice gives the
     *  same result. */
    private fun displayLabel(item: FlashItem): String {
        val part = item.partition ?: return item.label
        if (!item.label.startsWith("flash ")) return item.label
        val target = slotPartition(part)
        return if (item.chunks.isNotEmpty()) {
            val base = item.chunks.firstOrNull()?.filename?.substringBefore(".img_sparsechunk") ?: part
            "flash $target: $base.img (${item.chunks.size} chunks, ${item.size} bytes)"
        } else {
            "flash $target: ${item.filename}"
        }
    }

    /** Re-label every loaded flash-all item for the current slot, so the
     *  checkboxes show the partition that will actually be flashed.
     *  Checked state is kept (showFlashItems reads item.checked).
     *  No-op when nothing is loaded yet. */
    private fun refreshFlashItemLabels() {
        if (flashItems.isEmpty()) return
        showFlashItems(flashItems.toList(), flashAllSource)
    }

    private fun showFlashItems(items: List<FlashItem>, source: String) {
        flashAllSource = source
        flashItems.clear()
        flashItems.addAll(items)
        checkBoxes.clear()
        flashAllBox.removeAllViews()
        flashAllLabel.text = "$source — ${items.size} steps."
        if (items.isEmpty()) {
            flashAllLabel.text = "$source — no files found."
            return
        }
        for (item in items) {
            // The folder may have been picked after a slot was chosen —
            // label the steps for the current slot at load time too.
            if (item.label.startsWith("flash ")) item.label = displayLabel(item)
            val cb = UiStyle.styledCheckBox(
                this,
                (if (item.risky) "! " else "") + item.label +
                    if (item.size > 0) "  (${item.size} bytes)" else "",
                item.checked,
            ) { checked -> item.checked = checked }
            checkBoxes.add(cb)
            flashAllBox.addView(cb)
        }
        logLine("Folder loaded: ${items.size} steps ($source)")
    }

    private fun flashSelected() {
        val selected = flashItems.filter { it.checked }
        if (selected.isEmpty()) { toast("Select at least one step first"); return }
        if (!writeGate()) return
        val summary = selected.joinToString("\n") { "• ${it.label}" }
        confirm(
            "Flash ${selected.size} step(s)?",
            "$summary\n\n" +
                "Steps run in order. The run stops on the first failure. There is no backup.",
        ) {
            Thread {
                acquireWake()
                flashAllRunning = true
                flashAllOversize.clear()
                try {
                    for ((i, item) in selected.withIndex()) {
                        // 2.0.13: determinate progress — step i of N.
                        // 2.0.14: the counter text ticks up with it.
                        animateFlashAllProgress(i, selected.size)
                        val ok = runFlashItem(item)
                        if (!ok) {
                            logLine("STOPPED after failure: ${item.label}")
                            runOnUiThread { toast("Stopped — a step failed. See log.") }
                            return@Thread
                        }
                    }
                    animateFlashAllProgress(selected.size, selected.size)
                    logLine("Flash-all done: ${selected.size} step(s) OK")
                    runOnUiThread { toast("Flash-all done") }
                } finally {
                    // 2.0.14: one summary line when any file was
                    // bigger than its partition (flashed anyway).
                    if (flashAllOversize.isNotEmpty()) {
                        logLine(
                            "Size check: ${flashAllOversize.size} file(s) bigger than their partition " +
                                "(${flashAllOversize.joinToString(", ")}) — flashed anyway",
                        )
                    }
                    flashAllRunning = false
                    releaseWake()
                }
            }.start()
        }
    }

    /** 2.0.14: flash-all progress — the determinate strip fills to
     *  done/total and the counter text ticks up to match (~250ms).
     *  Animations off: both set instantly. UI work is posted. */
    private fun animateFlashAllProgress(done: Int, total: Int) {
        runOnUiThread {
            val targetFrac = if (total > 0) done.toFloat() / total else 1f
            flashAllTick?.cancel()
            if (!UiStyle.animationsEnabled) {
                progressStrip.showDeterminate(targetFrac)
                flashAllProgressText.text = "Step $done of $total"
                return@runOnUiThread
            }
            val fromStep = flashAllProgressText.tag as? Int ?: 0
            val fromFrac = if (total > 0) fromStep.toFloat() / total else 0f
            flashAllTick = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250
                addUpdateListener {
                    val f = it.animatedValue as Float
                    progressStrip.showDeterminate(fromFrac + (targetFrac - fromFrac) * f)
                    val shown = (fromStep + (done - fromStep) * f).toInt().coerceIn(0, total)
                    flashAllProgressText.text = "Step $shown of $total"
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(anim: android.animation.Animator) {
                        flashAllProgressText.text = "Step $done of $total"
                    }
                })
                start()
            }
            flashAllProgressText.tag = done
        }
    }

    /** Run one flash-all step. Returns false on failure (caller stops the run). */
    private fun runFlashItem(item: FlashItem): Boolean {
        val label = item.label
        logLine("== $label")
        // Unsupported steps stop the run rather than being skipped silently.
        if (label.startsWith("UNSUPPORTED STEP") || label.startsWith("MISSING FILE")) {
            logLine("FAILED: $label")
            return false
        }
        // Grouped sparsechunk item: flash each chunk in order, stop on failure.
        if (item.chunks.isNotEmpty()) {
            for (chunk in item.chunks) {
                if (!runFlashItem(chunk)) return false
            }
            logLine("OK: $label")
            return true
        }
        val args: List<String> = when {
            label.startsWith("flash ") -> {
                val uri = item.uri ?: return false.also { logLine("FAILED: no file for $label") }
                val staged = stageFile(uri, item.filename ?: "image.img")
                    ?: return false.also { logLine("FAILED: could not stage ${item.filename}") }
                val (file, sha) = staged
                logLine("staged ${item.filename} sha256=$sha")
                // 2.0.6: the picked slot, applied here — this is the line
                // that decides the real fastboot partition. A/B names get
                // the suffix (boot -> boot_a); anything else goes unchanged.
                val target = slotPartition(item.partition!!)
                if (target != item.partition) logLine("slot ${slotLabel()}: ${item.partition} becomes $target")
                // 2.0.14: size check per step — sizes are logged;
                // oversize steps are collected for one summary line
                // at the end of the run. No dialog (too noisy here)
                // and the flash proceeds either way.
                val partSize = partitionSize(target)
                if (partSize != null) {
                    logLine("Size check: ${item.filename} ${file.length()} bytes, partition $target $partSize bytes")
                    if (file.length() > partSize) flashAllOversize.add(target)
                }
                listOf("flash", target, file.absolutePath)
            }
            label.startsWith("erase ") -> {
                // 2.0.12: erase of an A/B partition hits the picked
                // slot's copy, same as flash.
                val target = slotPartition(item.partition!!)
                if (target != item.partition) logLine("slot ${slotLabel()}: ${item.partition} becomes $target")
                listOf("erase", target)
            }
            label.startsWith("oem ") -> listOf("oem", label.removePrefix("oem ").trim())
            label.startsWith("unlock") -> {
                val key = label.removePrefix("unlock").trim()
                if (key.isEmpty() || key == "(no key in xml)") {
                    logLine("FAILED: unlock step has no key — put the key in the xml step or unlock from the Unlock section")
                    return false
                }
                listOf("oem", "unlock", key)
            }
            label == "lock bootloader" -> listOf("oem", "lock")
            label == "reboot" -> listOf("reboot")
            label == "reboot-bootloader" -> listOf("reboot", "bootloader")
            label.startsWith("getvar ") -> listOf("getvar", label.removePrefix("getvar ").trim())
            else -> return false.also { logLine("FAILED: unknown step $label") }
        }
        val r = Fastboot.run(this, args) { logLine(it) }
        logLine(if (r.ok) "OK: $label" else "FAILED: $label (exit ${r.exitCode})")
        return r.ok
    }

    /** Run pre-staged single-flash steps (used by flashSingle).
     *  2.0.12: the picked slot applies to every "flash" op here too —
     *  before this, only Flash-all honored the picker, so Slot B +
     *  quick-flash silently flashed the active slot. [applySlot]=false
     *  is the explicit bypass for callers whose partition name already
     *  encodes the slot (valFlashLk's lk / lk_a / lk_b buttons) or is typed
     *  raw on purpose (Extras). Log lines show the effective name. */
    private fun runFlashSteps(steps: List<Triple<String, String, File>>, applySlot: Boolean = true) {
        if (!writeGate()) return
        Thread {
            acquireWake()
            try {
                for ((op, part, file) in steps) {
                    val effPart = if (applySlot && op == "flash") slotPartition(part) else part
                    if (effPart != part) logLine("slot ${slotLabel()}: $part becomes $effPart")
                    if (op == "flash") {
                        // 2.0.14: partition-size check. A failed getvar
                        // (null) never blocks; an oversize file asks
                        // first — declining skips the flash.
                        val partSize = partitionSize(effPart)
                        if (partSize != null) {
                            logLine("Size check: file ${file.length()} bytes, partition $effPart $partSize bytes")
                            if (file.length() > partSize) {
                                val go = confirmBlocking(
                                    "File is bigger than the partition",
                                    "File: ${file.length()} bytes\nPartition $effPart: $partSize bytes\n\nFlash anyway?",
                                )
                                if (!go) {
                                    logLine("Skipped $effPart — file bigger than partition (declined)")
                                    runOnUiThread { toast("Flash skipped — see log") }
                                    return@Thread
                                }
                            }
                        } else {
                            logLine("Size check: no partition-size answer for $effPart — continuing")
                        }
                        // 2.0.14: optional pre-flash backup (toggle,
                        // default off). Pulls the target partition to
                        // Download/MotoFlasher/backups/ first; a failed
                        // backup asks before flashing, never blocks.
                        if (autoBackup) {
                            logLine("Backing up $effPart before flash…")
                            val backupOk = backupPartition(effPart)
                            if (!backupOk) {
                                val go = confirmBlocking(
                                    "Backup failed",
                                    "Could not back up $effPart.\n\nFlash anyway?",
                                )
                                if (!go) {
                                    logLine("Skipped $effPart — backup failed (declined)")
                                    runOnUiThread { toast("Flash skipped — see log") }
                                    return@Thread
                                }
                            }
                        }
                    }
                    logLine("== $op $effPart from ${file.name}")
                    val r = Fastboot.run(this, listOf(op, effPart, file.absolutePath)) { logLine(it) }
                    if (!r.ok) {
                        logLine("FAILED: $op $effPart (exit ${r.exitCode})")
                        runOnUiThread { toast("Flash failed — see log") }
                        return@Thread
                    }
                    logLine("OK: $op $effPart")
                }
                runOnUiThread { toast("Flash done") }
            } finally {
                releaseWake()
            }
        }.start()
    }

    /** 2.0.14: pull [part] off the target and publish it to
     *  Download/MotoFlasher/backups/<part>-<timestamp>.img.
     *  True only when the backup file actually landed. */
    private fun backupPartition(part: String): Boolean {
        if (!Adb.isInstalled(this)) {
            val err = Adb.install(this)
            if (err != null) {
                logLine("Backup failed: $err")
                return false
            }
        }
        val pulled = pullPartitionBlocking(part) ?: run {
            logLine("Backup failed: could not pull $part off the target")
            return false
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val published = BromDownloads.publish(this, pulled, "backups", "$part-$stamp.img") { logLine(it) }
        if (published == null) {
            logLine("Backup failed: could not publish $part backup to Download/MotoFlasher/backups/")
            return false
        }
        logLine("Backup saved: ${published.absolutePath} (${published.length()} bytes)")
        return true
    }

    override fun onDestroy() {
        // 2.0.13: stop the watcher and every animator so nothing
        // outlives the screen.
        stopWatcher()
        if (::progressStrip.isInitialized) progressStrip.hide()
        stopBoltPulse()
        valChipPulse?.cancel()
        valChipPulse = null
        // 2.0.14: shimmer, flash-all tick, chip fades, verdict mark.
        stopShimmer()
        flashAllTick?.cancel()
        flashAllTick = null
        UiStyle.cancelChipFades()
        if (::statusMark.isInitialized) statusMark.clear()
        UiStyle.stopLedPulse(statusText)
        UiStyle.stopLedPulse(deviceText)
        releaseWake()
        super.onDestroy()
    }
}
