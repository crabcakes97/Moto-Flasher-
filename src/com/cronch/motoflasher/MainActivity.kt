package com.cronch.motoflasher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cronch.motoflasher.brom.BromDownloads
import com.cronch.motoflasher.brom.BromUi
import com.cronch.motoflasher.brom.Penumbra
import com.cronch.motoflasher.valproto.LkStaticAnalyzer
import com.cronch.motoflasher.valproto.PresetArgs
import com.cronch.motoflasher.valproto.ValKeygen
import com.cronch.motoflasher.valproto.ValPatcher
import com.cronch.motoflasher.valproto.ValPresets
import com.cronch.motoflasher.valproto.ValRepack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
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
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    // 2.0.1 app shell: one ScrollView per tab, only the active one
    // visible. revealLog() switches to the More tab, where the log lives.
    private lateinit var tabScrolls: Array<ScrollView>
    private lateinit var tabBar: UiStyle.TabBar
    private var currentTab = 0
    private lateinit var topStatusText: TextView
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
        // 2.0.1 app shell: top app bar (logo + title + status), one
        // ScrollView per tab (only the active one is visible), bottom
        // tab bar. Every 2.0.0 section still exists, grouped by tab —
        // nothing was cut, only moved.
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiStyle.BG)
        }
        val appBar = UiStyle.appBar(this, "2.0.11")
        topStatusText = appBar.statusText
        shell.addView(appBar.bar)

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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        tabScrolls.forEach { content.addView(it) }
        shell.addView(content)

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
        statusText = UiStyle.status(this, "Starting…")
        statusCard.addView(statusText)
        homeRoot.addView(statusCard)

        section(homeRoot, "Device")
        card.addView(UiStyle.ghostButton(this, "Refresh") { refreshDevices() })
        card.addView(UiStyle.ghostButton(this, "Getvar all") { getvarAll() })
        deviceText = UiStyle.status(this, "No device checked yet.")
        card.addView(deviceText)

        section(flashRoot, "Flash one partition")
        partInput = UiStyle.input(this, "partition name")
        card.addView(partInput)
        card.addView(UiStyle.actionButton(this, "Pick image") { pickFile(REQ_SINGLE_IMG) })
        card.addView(UiStyle.actionButton(this, "Flash it") { flashSingle() })

        section(flashRoot, "Erase")
        card.addView(UiStyle.dangerButton(this, "Erase partition above") { erasePart() })

        section(flashRoot, "Flash all")
        // 2.0.6: target-slot picker — Active (default, no suffix, same as
        // before) / Slot A / Slot B. The picked slot is applied to the
        // A/B partition names in the flash commands; the checkbox labels
        // below update to show the partition that will actually be flashed.
        card.addView(UiStyle.body(this, "Target slot. Active = current slot."))
        slotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(slotRow)
        refreshSlotRow()
        card.addView(UiStyle.actionButton(this, "Pick folder") { pickFolder() })
        flashAllLabel = UiStyle.status(this, "No folder picked.")
        card.addView(flashAllLabel)
        flashAllBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(flashAllBox)
        card.addView(UiStyle.actionButton(this, "Flash selected") { flashSelected() })
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
        card.addView(UiStyle.body(this, "See Bootloader status."))

        section(moreRoot, "More fastboot")
        card.addView(UiStyle.ghostButton(this, "flashing unlock") { fbCmd(listOf("flashing", "unlock"), "flashing unlock") })
        card.addView(UiStyle.dangerButton(this, "flashing lock") { fbCmd(listOf("flashing", "lock"), "flashing lock") })
        card.addView(UiStyle.ghostButton(this, "flashing get_unlock_ability") { fbCmd(listOf("flashing", "get_unlock_ability"), "get_unlock_ability") })
        card.addView(UiStyle.ghostButton(this, "Reboot to fastbootd") { fbCmd(listOf("reboot", "fastboot"), "reboot fastboot") })
        card.addView(UiStyle.ghostButton(this, "Continue boot") { fbCmd(listOf("continue"), "continue") })

        section(flashRoot, "Quick flash")
        card.addView(UiStyle.body(this, "Tap a partition. Pick its image. Confirm."))
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
        card.addView(UiStyle.body(this, "No format command. Erase only."))
        for (part in listOf("userdata", "cache", "boot", "recovery")) {
            card.addView(UiStyle.dangerButton(this, "Erase $part") { erasePartNamed(part) })
        }

        section(flashRoot, "vbmeta disabler")
        card.addView(UiStyle.body(this, "Empty vbmeta, verification off. Needs unlocked bootloader. Flash first."))
        card.addView(UiStyle.dangerButton(this, "Disable verity: vbmeta") { disableVerity("vbmeta") })
        card.addView(UiStyle.dangerButton(this, "Disable verity: vbmeta_system") { disableVerity("vbmeta_system") })
        card.addView(UiStyle.dangerButton(this, "Disable verity: both") { disableVerity("both") })

        section(valRoot, "Val Protocol")
        // 2.0.10: September warning lives here ONLY (Kyle's order).
        val septVal = UiStyle.status(this, "Can't unlock bootloader with Val on units with the September patch — it's patched.")
        septVal.setStatus(UiStyle.Status.WAIT)
        card.addView(septVal)
        card.addView(UiStyle.body(this, "Pulls the LK, patches it, flashes it back. Patched file is checked before flashing. CERT2 isn't checked in-app."))
        lkStatusText = UiStyle.status(this, "No LK file analyzed yet.")
        card.addView(lkStatusText)
        valFlowText = UiStyle.status(this, "Flow: 1 pull … · 2 analyze … · 3 patch … · 4 flash · 5 unlock … · 6 verify")
        card.addView(valFlowText)
        card.addView(UiStyle.actionButton(this, "Pull LK + analyze") { pullPartition("lk") })
        valPresetInput = UiStyle.input(this, "preset", "unlock-serial")
        card.addView(valPresetInput)
        // 2.0.10: FRP presets as taps that fill the preset box.
        // frp-skip patches the FRP check out. frp-compare sets what the
        // FRP check compares against (value in the box below, default 1).
        card.addView(UiStyle.body(this, "FRP presets. Tap one."))
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
        card.addView(UiStyle.body(this, "Flash the patched LK below. Key goes to the Unlock box. If unlock fails, type the key on a PC."))
        card.addView(UiStyle.actionButton(this, "Pick LK file") { pickFile(REQ_LK_FILE) })
        card.addView(UiStyle.actionButton(this, "Analyze LK file") { analyzeLkFile() })
        card.addView(UiStyle.actionButton(this, "Extract lk payload") { extractLkPayload() })
        lkPartInput = UiStyle.input(this, "partition to extract")
        card.addView(lkPartInput)
        card.addView(UiStyle.actionButton(this, "Extract partition") {
            extractLkPartition(lkPartInput.text.toString().trim())
        })
        card.addView(UiStyle.actionButton(this, "Flash LK to lk") { valFlashLk("lk") })
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
        card.addView(UiStyle.body(this, "Reads partitions off the target. Needs USB debugging, adb allowed, temp root on the target."))
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
        card.addView(UiStyle.body(this, "adb over OTG. Works in Android and TWRP. USB debugging on. Tap Allow on the device. Output in the log."))
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
        adbStatusText = UiStyle.status(this, "No adb command run yet.")
        card.addView(adbStatusText)
        card.addView(UiStyle.ghostButton(this, "Show log") { revealLog() })
        // 2.0.8: Devices used to show an empty list with exit 0 and no
        // explanation. Diagnose runs version → kill-server → fresh
        // start-server → USB visibility → devices, everything logged.
        card.addView(UiStyle.actionButton(this, "Diagnose ADB") { diagnoseAdb() })
        card.addView(UiStyle.ghostButton(this, "Kill adb server") {
            Thread { Adb.killServer(this) { logLine(it) } }.start()
            adbStatusText.text = "adb server killed."
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
        card.addView(UiStyle.body(this, "Tap to fill the box. Then Run."))
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
        logText = TextView(this).apply {
            text = ""
            setTextIsSelectable(true) // long-press to select + copy, no extra button needed
            setTextColor(UiStyle.LOG_TEXT)
            setBackgroundColor(UiStyle.LOG_BG)
            UiStyle.applyFont(this@MainActivity, this)
            textSize = 13f
            setPadding(12, 12, 12, 12)
        }
        logScroll = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 600
            )
        }
        // The log's ScrollView sits inside the More tab's own ScrollView.
        // A nested ScrollView loses the touch fight to its parent, so the
        // log felt unscrollable even though it had a scrollbar. Claim the
        // gesture for the log while the finger is on it; the outer tab
        // scrolls again once the finger lifts.
        logScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        card.addView(logScroll)
        logScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // User scrolled the log up: stop following so the screen stays
            // where they put it. Scroll back to the bottom to follow again.
            // 1.1.15: "near the bottom" counts (32 px) so a line landing
            // just above the edge does not kill follow.
            val atBottom = logText.bottom - (scrollY + logScroll.height) <= 32
            if (followLog != atBottom) {
                followLog = atBottom
                followButton.text = if (followLog) "Follow log: ON" else "Follow log: OFF"
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
        followButton = UiStyle.rowButton(this, "Follow log: ON") {
            followLog = !followLog
            followButton.text = if (followLog) "Follow log: ON" else "Follow log: OFF"
            if (followLog) scrollLogToBottom()
        }
        logRow.addView(followButton)
        card.addView(logRow)
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
        card.addView(UiStyle.body(this, "Argument box is added when needed."))
        extrasRows(LK_OEM_CMDS.map { sub -> "oem $sub" to { extrasOem(sub) } })
        card.addView(UiStyle.sectionTitle(this, "OEM (unproven)"))
        card.addView(UiStyle.body(this, "Strings only. May not run."))
        extrasRows(LK_OEM_STRING_ONLY.map { sub -> "oem $sub" to { extrasOem(sub) } })
        card.addView(UiStyle.body(this, "download / partition / super aren't commands. super is a partition name."))

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

        // About / Credits: short AGPL note — the app bundles the
        // Antumbra binary, so the app itself is AGPL-3.0.
        val aboutCard = UiStyle.card(this)
        aboutCard.addView(UiStyle.sectionTitle(this, "About / Credits"))
        aboutCard.addView(UiStyle.divider(this))
        aboutCard.addView(UiStyle.body(this, "Made with love by crabcakes97"))
        aboutCard.addView(TextView(this).apply {
            text = "github.com/crabcakes97"
            textSize = 14f
            setTextColor(UiStyle.CYAN)
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

        // Startup checks on a background thread.
        Thread {
            val installErr = if (!Fastboot.isInstalled(this)) Fastboot.install(this) else null
            val adbErr = if (!Adb.isInstalled(this)) Adb.install(this) else null
            val antumbraErr = if (!Penumbra.isInstalled(this)) Penumbra.install(this) else null
            rootOk = Fastboot.hasRoot { logLine(it) }
            runOnUiThread {
                val fbState = when {
                    installErr != null -> "fastboot install FAILED: $installErr"
                    else -> "fastboot ready"
                }
                val adbState = when {
                    adbErr != null -> "adb not bundled: $adbErr"
                    else -> "adb ready"
                }
                val penState = when {
                    antumbraErr != null -> "antumbra ${Penumbra.VERSION} install FAILED: $antumbraErr"
                    else -> "antumbra ${Penumbra.VERSION} ready"
                }
                statusText.text = "Root: ${if (rootOk) "YES" else "NO"}\n$fbState\n$adbState\n$penState"
                topStatusText.text = if (rootOk) "Root: YES" else "Root: NO"
                topStatusText.setStatus(if (rootOk) UiStyle.Status.OK else UiStyle.Status.FAIL)
                if (!rootOk) toast("No root. Flashing needs root.")
            }
        }.start()
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
        logText.post {
            logText.post { logScroll.scrollTo(0, logText.bottom) }
        }
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
                logText.text = logBuf.toString()
            } else {
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
        System.setOut(PrintStream(outSink, true, "UTF-8"))
        System.setErr(PrintStream(errSink, true, "UTF-8"))
        try {
            return block()
        } finally {
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

    /** 2.0.1: switch the visible tab (only one tab ScrollView shows). */
    private fun showTab(index: Int) {
        currentTab = index
        tabScrolls.forEachIndexed { i, scroll ->
            scroll.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabBar.select(index)
        // 2.0.8: switching back to the log tab re-scrolls to the bottom
        // when follow is on — lines logged while the tab was hidden
        // never scrolled (the view was GONE, bottom was 0).
        if (index == TAB_MORE && followLog) scrollLogToBottom()
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
                val cur = statusText.text.toString()
                val rest = cur.substringAfter("\n", "")
                statusText.text = "Root: YES" + if (rest.isNotEmpty()) "\n$rest" else ""
                topStatusText.text = "Root: YES"
                topStatusText.setStatus(UiStyle.Status.OK)
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
    }

    private fun releaseWake() {
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    /** Run a fastboot command on a background thread, logging output. */
    private fun fbCmd(args: List<String>, label: String) {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot ${args.joinToString(" ")}")
        Thread {
            val r = Fastboot.run(this, args) { logLine(it) }
            runOnUiThread {
                logLine(if (r.ok) "OK: $label" else "FAILED: $label (exit ${r.exitCode})")
                toast(if (r.ok) "$label: OK" else "$label: FAILED")
            }
        }.start()
    }

    // ---------- device ----------

    private fun refreshDevices() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot devices")
        Thread {
            val r = Fastboot.run(this, listOf("devices")) { logLine(it) }
            runOnUiThread {
                deviceText.text = if (r.output.isBlank()) "No fastboot device.\nPlug in (OTG). Phone in fastboot mode."
                    else "fastboot devices:\n${r.output}"
            }
        }.start()
    }

    private fun getvarAll() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot getvar all")
        Thread {
            val r = Fastboot.run(this, listOf("getvar", "all")) { logLine(it) }
            runOnUiThread { deviceText.text = r.output.ifBlank { "(no output)" } }
        }.start()
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
                    partInput.setText(pending)
                    logLine("Quick-flash: $singleImgName -> $pending")
                    flashSingle()
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
        UiStyle.styledDialog(this, title, message, "Yes, do it", "Cancel", onYes)
    }

    private fun flashSingle() {
        val uri = singleImgUri ?: run { toast("Pick an .img file first"); return }
        val part = partInput.text.toString().trim()
        if (part.isEmpty()) { toast("Type the partition name first"); return }
        if (part == "preloader") {
            toast("fastboot can't flash preloader. Use BROM.")
            return
        }
        if (!writeGate()) return
        Thread {
            val staged = stageFile(uri, singleImgName ?: "image.img") ?: run {
                runOnUiThread { toast("Could not copy the image file") }
                return@Thread
            }
            val (file, sha) = staged
            runOnUiThread {
                confirm(
                    "Flash $part?",
                    "Command: fastboot flash $part ${file.name}\n" +
                        "Size: ${file.length()} bytes\nSHA-256: $sha\n\n" +
                        "No backup. This overwrites the partition. Make sure it's for this phone.",
                ) { runFlashSteps(listOf(Triple("flash", part, file))) }
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
        confirm("Erase $part?",
            "Command: fastboot erase $part\n\nWipes the partition. No undo.") {
            fbCmd(listOf("erase", part), "erase $part")
        }
    }

    // ---------- bootloader unlock + custom key ----------

    private fun bootloaderStatus() {
        if (!Fastboot.isInstalled(this)) { toast("fastboot binary not installed"); return }
        logLine("$ fastboot oem device-info")
        Thread {
            val r = Fastboot.run(this, listOf("oem", "device-info")) { logLine(it) }
            runOnUiThread {
                deviceText.text = ("Bootloader status:\n" + r.output)
            }
        }.start()
    }

    private fun unlockWithKey() {
        val key = unlockKeyInput.text.toString().trim()
        if (key.isEmpty()) { toast("Paste the Motorola unlock key first"); return }
        if (!writeGate()) return
        confirm("Unlock the bootloader?",
            "Command: fastboot oem unlock $key\n\n" +
                "UNLOCKING WIPES THE PHONE. All user data erased. Make sure the key is for this phone.") {
            fbCmd(listOf("oem", "unlock", key), "oem unlock")
        }
    }

    private fun relock() {
        if (!writeGate()) return
        confirm("Relock the bootloader?",
            "Command: fastboot oem lock\n\n" +
                "Relocking on custom images can BRICK it. Stock images only.") {
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
        confirm("Erase custom AVB key?",
            "Command: fastboot erase avb_custom_key\n\nRemoves the custom key.") {
            fbCmd(listOf("erase", "avb_custom_key"), "erase avb_custom_key")
        }
    }

    private var lkImage: LibLk.LkImage? = null
    /** The container the Val flow is working on (pulled or picked), and the
     *  flow's step readout. liblk is the container layer: the patcher and
     *  the repack both consume the LkImage it produces — analyze is not a
     *  standalone viewer, it is step 2 of the flow. */
    private var valFlowFile: File? = null
    private lateinit var valFlowText: TextView
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
        logLine("Quick-flash $part: pick the .img")
        pickFile(REQ_SINGLE_IMG)
    }

    private fun flashTwrp() {
        val uri = twrpImgUri ?: run { toast("Pick a TWRP .img first"); return }
        if (!writeGate()) return
        Thread {
            val staged = stageFile(uri, twrpImgName ?: "twrp.img") ?: run {
                runOnUiThread { toast("Could not copy the TWRP image") }
                return@Thread
            }
            val (file, sha) = staged
            runOnUiThread {
                confirm(
                    "Flash TWRP to $twrpTarget?",
                    "Command: fastboot flash $twrpTarget ${file.name}\n" +
                        "Size: ${file.length()} bytes\nSHA-256: $sha\n\n" +
                        "Overwrites $twrpTarget. No backup.",
                ) { runFlashSteps(listOf(Triple("flash", twrpTarget, file))) }
            }
        }.start()
    }

    private fun bootTwrp() {
        val uri = twrpImgUri ?: run { toast("Pick a TWRP .img first"); return }
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
        confirm("Erase $part?",
            "Command: fastboot erase $part\n\n" +
                "WIPES the partition. userdata = all user data. No undo.") {
            fbCmd(listOf("erase", part), "erase $part")
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
                "Command: fastboot $label\n\nWipes the partition. No undo.") {
                fbCmd(args, label)
            }
        } else {
            fbCmd(args, label)
        }
    }

    /** Run a command behind a confirm dialog (dangerous Extras entries). */
    private fun extrasConfirmRun(args: List<String>, label: String) {
        confirm("Run $label?",
            "Command: fastboot ${args.joinToString(" ")}\n\nCan change or wipe the phone. No undo.") {
            fbCmd(args, label)
        }
    }

    /** Extras `flash`: partition from the argument box, then pick the image. */
    private fun extrasFlash() {
        val part = extrasArgInput.text.toString().trim()
        if (part.isEmpty()) { toast("Type the partition name first"); return }
        if (part == "preloader") {
            toast("fastboot can't flash preloader. Use BROM.")
            return
        }
        pendingPartition = part
        partInput.setText(part)
        logLine("Extras flash $part: pick the .img")
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
                "Command: fastboot $label\n\nCan change or wipe the phone. No undo.") {
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
            if (err != null) { toast(err); logLine(err); adbStatusText.text = err; return }
        }
        adbStatusText.text = "Running ADB diagnose…"
        Thread {
            val summary = Adb.diagnose(this) { logLine(it) }
            runOnUiThread { adbStatusText.text = summary }
        }.start()
    }

    /** Flash the empty vbmeta with verity + verification disabled. */
    private fun disableVerity(part: String) {
        if (!writeGate()) return
        val targets = when (part) {
            "both" -> listOf("vbmeta", "vbmeta_system")
            else -> listOf(part)
        }
        confirm("Disable verification on ${targets.joinToString(" + ")}?",
            "Command: fastboot --disable-verity --disable-verification flash <part> empty-vbmeta.img\n\n" +
                "Flashes an empty vbmeta. Verification off.\n\nNeeds an UNLOCKED bootloader. Flash this first.") {
            Thread {
                acquireWake()
                try {
                    val empty = Vbmeta.makeEmptyVbmeta(cacheDir)
                    logLine("empty vbmeta built: ${empty.length()} bytes sha256=${Fastboot.sha256(empty)}")
                    for (target in targets) {
                        logLine("== disable-verity $target")
                        val r = Fastboot.run(
                            this,
                            listOf("--disable-verity", "--disable-verification", "flash", target, empty.absolutePath),
                        ) { logLine(it) }
                        if (!r.ok) {
                            logLine("FAILED: disable-verity $target (exit ${r.exitCode})")
                            runOnUiThread { toast("Disabler FAILED on $target — see log") }
                            return@Thread
                        }
                        logLine("OK: disable-verity $target")
                    }
                    runOnUiThread { toast("Verification disabled on ${targets.joinToString(", ")}") }
                } finally {
                    releaseWake()
                }
            }.start()
        }
    }

    // ---------- Val Protocol: liblk, LK picker, puller ----------

    /** Render the Val flow's step state from the shared container. */
    private fun updateValFlow() {
        val pulled = if (valFlowFile != null) "done" else "…"
        val analyzed = if (lkImage != null) "done" else "…"
        val patched = if (valPatched) "done" else "…"
        val keyed = if (valUnlockKey != null) "done" else "…"
        valFlowText.text =
            "Flow: 1 pull $pulled · 2 analyze $analyzed · 3 patch $patched · 4 flash · 5 unlock $keyed · 6 verify"
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
                updateValFlow()
            }
        } catch (e: Exception) {
            logLine("ERROR analyzing $name: ${e.message}")
            runOnUiThread {
                lkStatusText.text = "Not an LK/LKS container: ${e.message}"
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
        Thread {
            try {
                val loadErr = CapstoneNative.ensureLoaded(this)
                if (loadErr != null) {
                    logLine("ERROR: $loadErr")
                    runOnUiThread { toast("Capstone failed to load — see log") }
                    return@Thread
                }
                logLine("Capstone ${CapstoneNative.version()} loaded, analyzing ${flowFile?.name ?: "lk.img"}...")
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
                val opts = captureValOutput {
                    ValPresets.buildPatchOptions(
                        PresetArgs(
                            image = flowFile ?: File(valDir, "lk.img"), preset = preset,
                            keyTokenSecret = secret, frpCompareValue = frpValue,
                        ),
                        analysisDir, patchedBin,
                    )
                }
                val run = captureValOutput { ValPatcher.runPatch(image, opts) }
                if (run.exitCode != 0 || run.patchedPayload == null) {
                    logLine("ERROR: patch failed (exit ${run.exitCode}) — the preset refused this LK or a gate was not found. See the analysis dir: ${analysisDir.absolutePath}")
                    runOnUiThread { toast("Patch failed — see log") }
                    return@Thread
                }
                logLine("Patched payload: ${run.patchedPayload.size} bytes")
                val repack = captureValOutput { ValRepack.repack(image, container, run.patchedPayload) }
                val outFile = File(valDir, outName)
                outFile.writeBytes(repack.output)
                for (line in repack.certLog) logLine("CERT2: $line")
                // Refuse to hand over a container liblk itself cannot parse.
                val rechecked = try {
                    LibLk.parse(repack.output)
                } catch (e: Exception) {
                    logLine("ERROR: patched container does NOT re-parse with liblk (${e.message}) — refused, not set as the flow file")
                    runOnUiThread { toast("Patched file failed its re-parse check") }
                    return@Thread
                }
                val sha = Fastboot.sha256(outFile)
                logLine("Patched container: ${outFile.absolutePath} (${outFile.length()} bytes, sha256=$sha)")
                logLine("Partitions after patch: ${rechecked.partitions.map { it.name }.sorted()}")
                logLine("NOTE: repack NOT verified in-app (verify_mtk_image.py is not ported). " +
                    "The container re-parses and CERT2 was re-signed, but run the Python verifier on a PC first if you want the full check.")
                // Publish the finished files to Downloads so Kyle can find
                // them in the Files app. The flow file for flashing must
                // point at the PUBLISHED lk.<preset>.img; if publishing
                // failed, it falls back to the private copy and the log
                // says so loudly.
                val publishedPatched = BromDownloads.publish(this, outFile, "val", outName) { logLine(it) }
                val publishedBin = BromDownloads.publish(this, patchedBin, "val", "lk.patched.bin") { logLine(it) }
                if (publishedBin == null) {
                    logLine("DOWNLOADS WARNING: lk.patched.bin could NOT be published to Download/MotoFlasher/val/ — private copy is at ${patchedBin.absolutePath}")
                }
                val analysisZip = File(valDir, "analysis-$preset.zip")
                if (analysisDir.exists()) {
                    zipDir(analysisDir, analysisZip)
                    val publishedZip = BromDownloads.publish(this, analysisZip, "val", "analysis-$preset.zip") { logLine(it) }
                    if (publishedZip == null) {
                        logLine("DOWNLOADS WARNING: analysis-$preset.zip could NOT be published to Download/MotoFlasher/val/ — private copy is at ${analysisZip.absolutePath}")
                    }
                } else {
                    logLine("DOWNLOADS WARNING: no analysis dir at ${analysisDir.absolutePath} — no analysis zip published")
                }
                if (publishedPatched != null) {
                    logLine("Val finished files published to Download/MotoFlasher/val/: $outName, lk.patched.bin, analysis-$preset.zip")
                    valFlowFile = publishedPatched
                } else {
                    logLine("DOWNLOADS WARNING: $outName could NOT be published to Download/MotoFlasher/val/ — flow file falls back to the private copy at ${outFile.absolutePath}")
                    valFlowFile = outFile
                }
                valPatched = true
                runOnUiThread {
                    toast("Patched: $outName — flash it from the buttons below")
                    updateValFlow()
                }
            } catch (e: Exception) {
                logLine("ERROR patching: ${e.message}")
                runOnUiThread { toast("Patch error — see log") }
            }
        }.start()
    }

    /** Step 5a: read the target's serial number over fastboot. */
    private fun getValSerial() {
        logLine("$ fastboot getvar serialno")
        Thread {
            val r = Fastboot.run(this, listOf("getvar", "serialno")) { logLine(it) }
            val serial = Regex("serialno:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                .find(r.output)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (serial.isEmpty()) {
                logLine("ERROR: no serialno in getvar output (is the phone in fastboot?)")
                runOnUiThread { toast("No serial found — see log") }
                return@Thread
            }
            logLine("serialno: $serial")
            runOnUiThread { valSerialInput.setText(serial) }
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

    /** Flash the flow's container (the analyzed LK) to lk or lk_b.
     *  Falls back to the picked file when nothing was pulled/analyzed. */
    private fun valFlashLk(target: String) {
        if (target !in listOf("lk", "lk_b")) return
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
                        "LK is the bootloader. Bad flash = BRICK until BROM recovery. Only flash an LK for this phone.",
                ) { runFlashSteps(listOf(Triple("flash", target, flowFile))) }
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
                        "LK is the bootloader. Bad flash = BRICK until BROM recovery. Only flash an LK for this phone.",
                ) { runFlashSteps(listOf(Triple("flash", target, file))) }
            }
        }.start()
    }

    private fun adbDevices() {
        if (!Adb.isInstalled(this)) {
            val err = Adb.install(this)
            if (err != null) { toast(err); logLine(err); return }
        }
        logLine("$ adb devices (fresh server)")
        Thread {
            // 2.0.8: fresh server — a stale daemon never sees the device.
            val r = Adb.devicesFresh(this) { logLine(it) }
            runOnUiThread {
                deviceText.text = if (Adb.hasDevice(r.output)) "adb devices:\n${r.output}"
                    else "adb devices: NONE. Run Diagnose ADB."
            }
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
            if (err != null) { toast(err); logLine(err); adbStatusText.text = err; return }
        }
        logLine("$ adb ${args.joinToString(" ")}")
        adbStatusText.text = "Running: adb ${args.joinToString(" ")} …"
        Thread {
            // 2.0.8: `devices` always runs against a FRESH server — a
            // stale daemon owns port 5037 and never sees the device.
            // Anything else runs as-is.
            val r = if (args == listOf("devices") || args == listOf("devices", "-l")) {
                Adb.devicesFresh(this) { logLine(it) }
            } else {
                Adb.run(this, args) { logLine(it) }
            }
            runOnUiThread {
                val out = r.output.trim()
                adbStatusText.text = when {
                    out.contains("no devices/emulators found", ignoreCase = true) ->
                        "$label: no device found.\nPlug in (OTG). USB debugging ON. Tap Allow."
                    (args == listOf("devices") || args == listOf("devices", "-l")) && !Adb.hasDevice(out) ->
                        // 2.0.8: exit 0 with an empty list is NOT success —
                        // the server is up but blind. Say so plainly.
                        "Devices: NONE. Server up, sees nothing.\nRun Diagnose ADB."
                    else -> "$label: exit ${r.exitCode}\n${out.ifBlank { "(no output)" }.take(400)}"
                }
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
        val remoteDir = "/sdcard/MotoFlasher"
        val remoteFile = "$remoteDir/$part.img"
        val outDir = File(getExternalFilesDir(null), "pulls")
        outDir.mkdirs()
        val localFile = File(outDir, "$part.img")
        logLine("Pull $part -> ${localFile.absolutePath}")
        Thread {
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
            if (!r.ok) {
                logLine("FAILED: could not read $part off the target (is it rooted? is USB debugging on?)")
                runOnUiThread { toast("Pull FAILED — see log") }
                return@Thread
            }
            val pull = Adb.run(this, listOf("pull", remoteFile, localFile.absolutePath)) { logLine(it) }
            if (!pull.ok || !localFile.exists() || localFile.length() == 0L) {
                logLine("FAILED: adb pull of $part did not produce a file")
                runOnUiThread { toast("Pull FAILED — see log") }
                return@Thread
            }
            val sha = Fastboot.sha256(localFile)
            logLine("Pulled $part: ${localFile.length()} bytes sha256=$sha")
            runOnUiThread { toast("Pulled $part (${localFile.length()} bytes)") }
            // A pulled LK feeds straight into the flow: liblk parses it and
            // the parsed container becomes the shared object the patch and
            // repack steps consume — no separate "pick file" needed.
            if (part == "lk" || part == "lk_b") {
                valFlowFile = localFile
                analyzeLkBytes(localFile.readBytes(), localFile.name)
            }
        }.start()
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
                                    items.add(FlashItem("flash ${s.partition} <- ${s.filename}", s.partition, s.filename, kid.second, kid.third, risky))
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
                        items.add(FlashItem("flash $part <- $name", part, name, kid.second, kid.third, risky))
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

    /** Merge consecutive `flash <part> <- <base>.img_sparsechunk.N` steps into
     * one checkbox row per partition ("super — 24 chunks, 6.4 GB"). The chunks
     * still flash one by one in order; only the list gets shorter. */
    private fun groupSparseChunks(items: MutableList<FlashItem>) {
        val chunkRe = Regex("""flash (\S+) <- (.+)\.img_sparsechunk\.\d+""")
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
                        "flash $part <- $base.img (${run.size} chunks, $total bytes)",
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
            "flash $target <- $base.img (${item.chunks.size} chunks, ${item.size} bytes)"
        } else {
            "flash $target <- ${item.filename}"
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
        if (selected.isEmpty()) { toast("Tick at least one step first"); return }
        if (!writeGate()) return
        val summary = selected.joinToString("\n") { "• ${it.label}" }
        confirm(
            "Flash ${selected.size} step(s)?",
            "$summary\n\n" +
                "Runs in order. Stops on first failure. No backup.",
        ) {
            Thread {
                acquireWake()
                try {
                    for (item in selected) {
                        val ok = runFlashItem(item)
                        if (!ok) {
                            logLine("STOPPED after failure: ${item.label}")
                            runOnUiThread { toast("Stopped — a step failed. See log.") }
                            return@Thread
                        }
                    }
                    logLine("Flash-all done: ${selected.size} step(s) OK")
                    runOnUiThread { toast("Flash-all done") }
                } finally {
                    releaseWake()
                }
            }.start()
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
                if (target != item.partition) logLine("slot ${slotLabel()}: ${item.partition} -> $target")
                listOf("flash", target, file.absolutePath)
            }
            label.startsWith("erase ") -> listOf("erase", item.partition!!)
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

    /** Run pre-staged single-flash steps (used by flashSingle). */
    private fun runFlashSteps(steps: List<Triple<String, String, File>>) {
        if (!writeGate()) return
        Thread {
            acquireWake()
            try {
                for ((op, part, file) in steps) {
                    logLine("== $op $part <- ${file.name}")
                    val r = Fastboot.run(this, listOf(op, part, file.absolutePath)) { logLine(it) }
                    if (!r.ok) {
                        logLine("FAILED: $op $part (exit ${r.exitCode})")
                        runOnUiThread { toast("Flash FAILED — see log") }
                        return@Thread
                    }
                    logLine("OK: $op $part")
                }
                runOnUiThread { toast("Flash done") }
            } finally {
                releaseWake()
            }
        }.start()
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }
}
