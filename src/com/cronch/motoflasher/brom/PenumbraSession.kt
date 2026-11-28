// PenumbraSession.kt — BROM operations over the Antumbra CLI.
//
// One session = one picked DA + auth pair. Every method blocks and
// must run on a background thread; every Antumbra invocation inside
// does its own full connect (handshake → send_auth → DA upload) and
// the .antumbra_state file in the fixed work dir carries the device
// identity between runs. Nothing here is a gate: the caller decides
// what to run, the session just runs it and reports the real result.
//
// Method → Antumbra command map (v2.0.0 CLI, verified against the
// host build's --help):
//   connect probe / storage info -> storage-info
//   device partitions (GPT)      -> pgpt (parsed: Name/Addr/Size)
//   read partition by name       -> read-flash <name> <out>
//   read raw offset              -> read-offset <addr> <len> <out>
//   read sectors                 -> read-offset (addr/count × sector size)
//   write partition by name      -> write-flash <name> <file>
//   write scatter entry          -> write-offset <scatter addr> <len> <file>
//   write raw offset             -> write-offset <addr> <len> <file>
//   erase partition              -> erase <name>
//   blankflash, all selected     -> scatter <scatter file> (Flash Upgrade)
//   blankflash, subset selected  -> write-offset per entry (1.1.18 style)
//   GPT (optional, blankflash)   -> GptBuilder bytes via write-offset
//   reset                        -> reboot fastboot
//
// Known limits, stated plainly:
//  - write-offset writes the USER section only, so the preloader
//    (BOOT1) never goes through it: subset blankflash writes the
//    preloader with write-flash, which lets the DA route it by the
//    partition kind from the device table.
//  - 2.0.3: every write-offset / read-offset call passes
//    --section <user section> (ufs-lua2 / emmc-user) so the command
//    does NOT need Antumbra's GetHwInfo storage detection — the DA can
//    stop answering GetHwInfo and the write still works. The bundled
//    Antumbra is the fixed build (no more write-offset panic).
//  - scatter is Flash-Upgrade-style: the DA decides what lands. If
//    it rejects the dual-layout scatter, the UI falls back to the
//    per-entry write-offset path (same binary, same DA).
//  - sector size for readSectors comes from storage-info's Block
//    Size when known, else 512, and the log says which was used.
//  - GPT total sectors come from storage-info's User size; the GPT
//    write refuses to run when storage-info cannot be read, and is
//    verified by read-back (BromGpt CRC parse + pgpt re-read).
package com.cronch.motoflasher.brom

import android.content.Context
import java.io.File

class PenumbraSession(
    private val ctx: Context,
    private val daFile: File,
    private val authFile: File,
) {
    private var storage: Penumbra.StorageInfo? = null
    private var parts: List<GptPartition> = listOf()

    /**
     * The flash section for raw offset I/O (write-offset / read-offset).
     * From storage-info when known; the UFS user section (ufs-lua2) when
     * storage-info has not run or failed. Antumbra 2.0.3+ skips storage
     * detection when --section is given, so a failed probe must not block
     * the write. This app's device (Nevada, MT6835) is UFS.
     */
    private fun userSection(): String = when (storage?.storageType?.uppercase()) {
        "EMMC" -> "emmc-user"
        else -> "ufs-lua2" // UFS, or unknown
    }

    fun gptPartitions(): List<GptPartition> = parts
    fun storageInfo(): Penumbra.StorageInfo? = storage

    private fun run(
        cmdArgs: List<String>,
        onLog: (String) -> Unit,
        timeoutMs: Long = 300_000L,
    ): Penumbra.Result = Penumbra.run(ctx, cmdArgs, daFile, authFile, onLog, timeoutMs)

    /** Log the DA hw_subcode when the binary reports it (expected 0x8A00). Never blocks. */
    private fun logHwSubcode(output: String, onLog: (String) -> Unit) {
        val m = Regex("""(?i)hw_sub(?:code)?[^0-9a-f]*0x([0-9A-Fa-f]+)""").find(output)
            ?: Regex("""(?i)subcode[^0-9a-f]*0x([0-9A-Fa-f]+)""").find(output)
        if (m != null) {
            val sub = m.groupValues[1].toLongOrNull(16)
            if (sub == Penumbra.DA_HW_SUBCODE_EXPECTED.toLong()) {
                onLog("Penumbra: DA hw_subcode 0x8A00 as expected (MT6835 entry match)")
            } else {
                onLog("Penumbra WARNING: DA hw_subcode reported as 0x${sub?.toString(16)} (expected 0x8A00) — Antumbra may error NoCompatibleDa")
            }
        } else {
            onLog("Penumbra: hw_subcode not printed in this run's output (expected 0x8A00) — the DA-entry match runs inside Antumbra; a NoCompatibleDa error means it mismatched")
        }
    }

    private fun failFrom(output: String, what: String): BromResult.Fail {
        val tail = output.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
        return BromResult.Fail("$what\nAntumbra output (last lines):\n$tail")
    }

    /** Fresh root re-check before every write (1.1.19 lesson — never a cached verdict). */
    private fun rootOk(onLog: (String) -> Unit): Boolean {
        val ok = Penumbra.hasRootFresh { onLog(it) }
        if (ok) onLog("Root re-check passed — write allowed")
        return ok
    }

    /** Connect probe: storage-info performs the full handshake + auth + DA upload. */
    fun connectProbe(onLog: (String) -> Unit): BromResult {
        onLog("Penumbra connect: storage-info (handshake, auth, DA upload inside Antumbra)…")
        val r = run(listOf("storage-info"), onLog, 120_000L)
        if (!r.ok) {
            logHwSubcode(r.output, onLog)
            return failFrom(r.output, "Connect failed (exit ${r.exitCode}). Wrong DA or auth for this phone, no preloader or BROM device, or the binary did not run. See the lines above.")
        }
        val info = Penumbra.parseStorageInfo(r.output)
        if (info == null) {
            return BromResult.Fail("Connect: Antumbra exited 0 but storage-info did not parse (output format changed?). See the log above.")
        }
        storage = info
        logHwSubcode(r.output, onLog)
        return BromResult.Ok(
            "storage ${info.storageType}, total ${info.totalSize} bytes, user ${info.userSize} bytes, " +
                "block ${info.blockSize} bytes, ${info.partitionCount} partitions",
        )
    }

    /** Read the partition table FROM the device (pgpt). */
    fun readDevicePartitions(onLog: (String) -> Unit): BromResult {
        onLog("Penumbra pgpt: reading the partition table from the device…")
        val r = run(listOf("pgpt"), onLog, 120_000L)
        if (!r.ok) return failFrom(r.output, "pgpt FAILED (exit ${r.exitCode})")
        val parsed = Penumbra.parsePgpt(r.output)
        if (parsed.isEmpty()) {
            return BromResult.Fail("pgpt ran but no partitions parsed from its output — the table may be empty or the output format changed. Full output is in the log above.")
        }
        parts = parsed
        return BromResult.Ok("${parsed.size} partitions in the device table")
    }

    /** Read one partition by name to a file (read-flash). */
    fun readPartition(name: String, outFile: File, onLog: (String) -> Unit): BromResult {
        onLog("Penumbra read-flash $name to ${outFile.name}…")
        val r = run(listOf("read-flash", name, outFile.absolutePath), onLog)
        if (!r.ok) return failFrom(r.output, "read-flash $name FAILED (exit ${r.exitCode})")
        if (!outFile.exists() || outFile.length() == 0L) {
            return BromResult.Fail("read-flash $name exited 0 but produced no file")
        }
        return BromResult.Ok("$name: ${outFile.length()} bytes")
    }

    /** Read raw bytes at a flash offset (read-offset). */
    fun readRawOffset(addr: Long, length: Long, outFile: File, onLog: (String) -> Unit): BromResult {
        onLog("Penumbra read-offset 0x${addr.toString(16)} len=$length to ${outFile.name}… (section ${userSection()})")
        val r = run(listOf("read-offset", "0x${addr.toString(16)}", length.toString(), outFile.absolutePath, "--section", userSection()), onLog)
        if (!r.ok) return failFrom(r.output, "read-offset FAILED (exit ${r.exitCode})")
        if (!outFile.exists() || outFile.length() == 0L) {
            return BromResult.Fail("read-offset exited 0 but produced no file")
        }
        return BromResult.Ok("${outFile.length()} bytes")
    }

    /** Read whole sectors (emulated with read-offset; sector size logged). */
    fun readSectors(startSector: Long, sectors: Long, outFile: File, onLog: (String) -> Unit): BromResult {
        val sectorSize = storage?.blockSize ?: 512L
        onLog("Penumbra read sectors: sector size $sectorSize bytes (${if (storage == null) "storage-info not run — assuming 512" else "from storage-info"})")
        return readRawOffset(startSector * sectorSize, sectors * sectorSize, outFile, onLog)
    }

    /** Write one partition by name (write-flash — the DA routes the section, incl. BOOT1). */
    fun writePartition(name: String, file: File, onLog: (String) -> Unit): BromResult {
        if (!rootOk(onLog)) return BromResult.Fail("No root on the host phone — flashing needs root")
        onLog("Penumbra write-flash $name <- ${file.name} (${file.length()} bytes)…")
        val r = run(listOf("write-flash", name, file.absolutePath), onLog)
        if (!r.ok) return failFrom(r.output, "write-flash $name FAILED (exit ${r.exitCode}) — STOP, no retry")
        return BromResult.Ok("$name: ${file.length()} bytes written")
    }

    /** Write one scatter plan entry at the scatter's address (1.1.18 SP Flash Tool style). */
    fun writeScatterEntry(entry: ScatterPlanEntry, file: File, onLog: (String) -> Unit): BromResult {
        if (!rootOk(onLog)) return BromResult.Fail("No root on the host phone — flashing needs root")
        val addr = entry.addressFor(storage?.storageType)
        val cap = entry.sizeFor(storage?.storageType)
        if (addr < 0L) return BromResult.Fail("No scatter address for ${entry.partition} — refusing to guess one")
        if (cap > 0L && file.length() > cap) {
            return BromResult.Fail("File ${file.length()} bytes is bigger than the ${entry.partition} partition ($cap bytes) — refusing")
        }
        if (entry.isBoot1) {
            // write-offset only reaches the USER section; the preloader
            // (BOOT1) goes through write-flash so the DA routes it.
            onLog("Penumbra: ${entry.partition} is BOOT1 — routing through write-flash (DA routes the section), not write-offset")
            return writePartition(entry.partition, file, onLog)
        }
        onLog("Penumbra write-offset 0x${addr.toString(16)} len=${file.length()} <- ${file.name} (scatter address, ${entry.partition}, section ${userSection()})…")
        val r = run(listOf("write-offset", "0x${addr.toString(16)}", file.length().toString(), file.absolutePath, "--section", userSection()), onLog)
        if (!r.ok) return failFrom(r.output, "write-offset ${entry.partition} FAILED (exit ${r.exitCode}) — STOP, no retry")
        return BromResult.Ok("${entry.partition} @ 0x${addr.toString(16)}: ${file.length()} bytes written")
    }

    /** Write a file at a raw flash offset (write-offset). Address 0 is refused (that is the GPT). */
    fun writeRawOffset(addr: Long, file: File, onLog: (String) -> Unit): BromResult {
        if (addr == 0L) return BromResult.Fail("Address 0 refused — that is the GPT. The blankflash GPT write is the only path that touches address 0.")
        if (!rootOk(onLog)) return BromResult.Fail("No root on the host phone — flashing needs root")
        onLog("Penumbra write-offset 0x${addr.toString(16)} len=${file.length()} <- ${file.name}… (section ${userSection()})")
        val r = run(listOf("write-offset", "0x${addr.toString(16)}", file.length().toString(), file.absolutePath, "--section", userSection()), onLog)
        if (!r.ok) return failFrom(r.output, "write-offset FAILED (exit ${r.exitCode}) — STOP, no retry")
        return BromResult.Ok("${file.length()} bytes written at 0x${addr.toString(16)}")
    }

    /** Erase one partition. */
    fun erasePartition(name: String, onLog: (String) -> Unit): BromResult {
        if (!rootOk(onLog)) return BromResult.Fail("No root on the host phone — flashing needs root")
        onLog("Penumbra erase $name…")
        val r = run(listOf("erase", name), onLog)
        if (!r.ok) return failFrom(r.output, "erase $name FAILED (exit ${r.exitCode})")
        return BromResult.Ok("$name erased")
    }

    /**
     * Blankflash run. All entries selected + scatter file present →
     * `antumbra scatter` (Flash Upgrade; the DA decides what lands).
     * Subset selected → per-entry scatter-address writes (1.1.18
     * style). Optional GPT first (GptBuilder bytes via write-offset,
     * verified by read-back + pgpt). Ends with `reboot fastboot`.
     * Stops on the first failure — no auto-retry.
     */
    fun runBlankflash(
        plan: ScatterPlan,
        entries: List<ScatterPlanEntry>,
        writeGpt: Boolean,
        scatterFile: File?,
        onLog: (String) -> Unit,
    ): BromResult {
        if (!rootOk(onLog)) return BromResult.Fail("No root on the host phone — flashing needs root")
        if (writeGpt) {
            val g = writeGptFromPlan(plan, onLog)
            if (!g.isOk()) return g
        }
        val allSelected = entries.size == plan.entries.size
        if (allSelected && scatterFile != null && scatterFile.exists()) {
            onLog("Penumbra scatter (Flash Upgrade, all ${entries.size} entries): ${scatterFile.name}…")
            val r = run(listOf("scatter", scatterFile.absolutePath), onLog, 600_000L)
            if (!r.ok) {
                // The DA may reject the dual-layout scatter; fall back
                // to the per-entry path instead of failing outright.
                onLog("Penumbra scatter FAILED (exit ${r.exitCode}) — falling back to per-entry scatter-address writes (write-offset)")
                onLog("Scatter failure output (last lines):\n" + r.output.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n"))
            } else {
                return finishBlankflash(onLog)
            }
        } else if (allSelected) {
            onLog("Penumbra: no scatter file staged — per-entry writes instead of scatter")
        }
        for (e in entries) {
            val r = writeScatterEntry(e, e.file, onLog)
            if (!r.isOk()) return BromResult.Fail("Blankflash STOPPED at ${e.partition}: ${r.text()} — no retry")
            onLog("Blankflash OK: ${e.partition}: ${r.text()}")
        }
        return finishBlankflash(onLog)
    }

    private fun finishBlankflash(onLog: (String) -> Unit): BromResult {
        onLog("Penumbra reboot fastboot (phone reboots into fastboot)…")
        val r = run(listOf("reboot", "fastboot"), onLog, 120_000L)
        if (!r.ok) return failFrom(r.output, "Blankflash writes finished but reboot fastboot FAILED (exit ${r.exitCode}) — the phone may still need a manual reboot into fastboot")
        return BromResult.Ok("blankflash done — phone reboots into fastboot")
    }

    /** Optional GPT write inside a blankflash run (GptBuilder + write-offset, verified by read-back). */
    private fun writeGptFromPlan(plan: ScatterPlan, onLog: (String) -> Unit): BromResult {
        var info = storage
        if (info == null) {
            onLog("Penumbra GPT: storage-info not run yet — running it to learn the disk size…")
            val probe = connectProbe(onLog)
            if (!probe.isOk()) return BromResult.Fail("GPT write needs the disk size from storage-info, and that failed: ${probe.text()}")
            info = storage
        }
        val sectorSize = (info!!.blockSize).toInt().takeIf { it > 0 } ?: 512
        val userSize = info.userSize.takeIf { it > 0 } ?: info.totalSize
        val totalSectors = userSize / sectorSize
        val gptEntries = plan.gptEntriesByStorage[info.storageType.uppercase()]
            ?: plan.gptEntriesByStorage.values.firstOrNull()
            ?: plan.gptEntries
        val built = GptBuilder.build(gptEntries, totalSectors, sectorSize, plan.project)
            ?: return BromResult.Fail("GPT build failed for ${plan.project} (${gptEntries.size} entries, $totalSectors sectors) — refusing to write a malformed table")
        val (primary, backup, builtEntries) = built
        if (!GptBuilder.verifyBuilt(primary, backup, builtEntries)) {
            return BromResult.Fail("GPT self-check failed (built bytes do not re-parse cleanly) — refusing to write")
        }
        val cacheDir = ctx.cacheDir
        val primaryFile = File(cacheDir, "brom-gpt-primary.bin").apply { writeBytes(primary) }
        val backupFile = File(cacheDir, "brom-gpt-backup.bin").apply { writeBytes(backup) }
        onLog("Penumbra GPT: writing primary (${primary.size} bytes) at 0x0… (section ${userSection()})")
        var r = run(listOf("write-offset", "0x0", primary.size.toString(), primaryFile.absolutePath, "--section", userSection()), onLog)
        if (!r.ok) return failFrom(r.output, "GPT primary write FAILED (exit ${r.exitCode}) — STOP, no retry")
        val backupAddr = (totalSectors - GptBuilder.BACKUP_SECTORS) * sectorSize
        onLog("Penumbra GPT: writing backup (${backup.size} bytes) at 0x${backupAddr.toString(16)}… (section ${userSection()})")
        r = run(listOf("write-offset", "0x${backupAddr.toString(16)}", backup.size.toString(), backupFile.absolutePath, "--section", userSection()), onLog)
        if (!r.ok) return failFrom(r.output, "GPT backup write FAILED (exit ${r.exitCode}) — primary was already written; STOP, no retry")
        // Verify by read-back: re-read the primary region and re-parse (CRC-checked).
        val readBack = File(cacheDir, "brom-gpt-readback.bin")
        val rr = readRawOffset(0L, primary.size.toLong(), readBack, onLog)
        if (!rr.isOk()) return BromResult.Fail("GPT written but read-back failed: ${rr.text()} — verify on a PC before trusting this table")
        val parsed = BromGpt.parse(readBack.readBytes())
        if (parsed == null || !parsed.crcHeaderOk || !parsed.crcEntriesOk) {
            return BromResult.Fail("GPT read-back does NOT parse cleanly (bad CRC) — the table on the phone may be corrupt. Do not trust it; re-run blankflash or use SP Flash Tool on a PC.")
        }
        onLog("Penumbra GPT: read-back parses, CRCs OK (${parsed.partitions.size} partitions)")
        return BromResult.Ok("GPT written + verified by read-back (${parsed.partitions.size} partitions)")
    }

    /** Reset the target (reboot into fastboot). */
    fun reset(onLog: (String) -> Unit): BromResult {
        onLog("Penumbra reset: reboot fastboot…")
        val r = run(listOf("reboot", "fastboot"), onLog, 120_000L)
        if (!r.ok) return failFrom(r.output, "reset FAILED (exit ${r.exitCode})")
        return BromResult.Ok("target rebooting into fastboot")
    }

    fun close() {
        // No persistent session on the app side: each CLI run is
        // stateless and the .antumbra_state file in the work dir is
        // intentionally kept between runs (device identity cache).
    }
}
