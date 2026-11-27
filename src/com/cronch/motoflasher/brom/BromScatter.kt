// BromScatter.kt - MediaTek scatter parser and blankflash plan builder for
// the in-app BROM / mtkclient port (SPEC section 5.13).
//
// Ground truth: the real Nevada scatter
// ~/workspace/blankflash-nevada/blankflash/
// MT6835_Android_scatter_blankflash.xml (MT6835, project k6835v2_64,
// 91 partition_index entries per storage layout), and the PC MTK GUI's
// parse_blankflash_scatter (~/workspace/val-protocol/ctk_mtk_flash_ui.py:394),
// which this port mirrors exactly:
//
//   - Walk every partition_index element in document order.
//   - Keep entries whose is_download is "true" and whose file_name is a
//     real file (not empty, not "NONE").
//   - Deduplicate by partition name: the scatter lists the EMMC and UFS
//     layouts back to back, so every partition appears twice with the
//     same file. First occurrence wins.
//   - The result for the Nevada package is the 11-partition boot chain in
//     scatter order: preloader, proinfo, spmfw_a, pi_img_a, sspm_a,
//     mcupm_a, gz_a, lk_a, dtbo_a, tee_a, logo_a.
//
// SGPT / PGPT: the scatter lists sgpt and pgpt with is_download=false,
// operation_type INVISIBLE / RESERVED, file_name NONE. SP Flash Tool
// regenerates the GPT from the scatter during its SGPT/PGPT stages; there
// is no file for them and mtkclient has no equivalent step. This port
// reports both stages as NOT available in-app (sgptAvailable /
// pgptAvailable are always false, with a plain reason), never silently
// dropping them from the plan.
//
// File checks: every plan entry's file must exist in the package folder.
// Size and SHA-256 are recorded from the file on disk. A zero-byte file
// is reported as a warning, not an error: the Nevada package ships an
// empty proinfo.bin (0 bytes), so an empty file is a property of the
// package, not a corrupt download. The run itself decides what an empty
// file means; the plan only reports it.
package com.cronch.motoflasher.brom

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// One scatter entry that will be written, in scatter order.
data class ScatterPlanEntry(
    val partition: String,
    val fileName: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    val isBoot1: Boolean, // preloader -> EMMC_BOOT1, no flag needed
    val scatterPartitionSize: Long, // partition_size from the scatter
    val scatterLinearStart: Long, // linear_start_addr from the scatter
    // 1.1.18 (SP Flash Tool way): the same address / size per
    // storage layout in the file ("EMMC", "UFS", ...). The layouts
    // agree on addresses in both known scatters, but the run must
    // use the layout matching the phone's real storage type, never
    // another layout's values. Keys are upper-cased layout names.
    val scatterLinearStartByStorage: Map<String, Long> = emptyMap(),
    val scatterPartitionSizeByStorage: Map<String, Long> = emptyMap(),
) {
    // Address for the phone's storage type (e.g. "ufs"), falling
    // back to the first-layout value when the phone's layout is
    // absent. 0 means the scatter has no address for this entry.
    fun addressFor(storageType: String?): Long {
        if (storageType != null) {
            scatterLinearStartByStorage[storageType.uppercase()]?.takeIf { it != 0L }?.let { return it }
        }
        return scatterLinearStart
    }

    // Partition size (boundary cap) for the phone's storage type,
    // falling back to the first-layout value. 0 means unknown.
    fun sizeFor(storageType: String?): Long {
        if (storageType != null) {
            scatterPartitionSizeByStorage[storageType.uppercase()]?.takeIf { it > 0L }?.let { return it }
        }
        return scatterPartitionSize
    }
}

// The full blankflash plan for one package folder.
data class ScatterPlan(
    val entries: List<ScatterPlanEntry>,
    val platform: String,
    val project: String,
    val storageType: String, // "EMMC" or "UFS" - first layout in the file
    val sgptAvailable: Boolean, // true: backup GPT built + written in-app (full blankflash)
    val sgptNote: String,
    val pgptAvailable: Boolean, // true: primary GPT built + written in-app (full blankflash)
    val pgptNote: String,
    val warnings: List<String>,
    // Every user-area entry in the chosen storage layout, in ascending
    // address order, for GPT construction (GptBuilder). Excludes pgpt,
    // sgpt and flashinfo: the GPT area itself and SP Flash Tool-internal
    // markers with fake 0xFFFFxxxx addresses, not real partitions.
    // preloader / preloader_backup are NOT in here either: they live in
    // the BOOT1 / BOOT2 hardware regions, not the user area.
    // 1.1.17: GPT entries per storage layout in the file. A scatter
    // may carry several layouts (this Nevada scatter has EMMC and UFS
    // with identical tables) while storageType is only the FIRST one;
    // runBlankflash picks the layout matching the phone's real
    // storage type instead of refusing on a label mismatch.
    val gptEntriesByStorage: Map<String, List<GptScatterEntry>> = emptyMap(),
    val gptEntries: List<GptScatterEntry>,
)

sealed class ScatterPlanResult {
    data class Ok(val plan: ScatterPlan) : ScatterPlanResult()
    data class Fail(val reason: String) : ScatterPlanResult()
}

object BromScatter {
    // Full blankflash (Kyle 2026-11-08): the SGPT/PGPT stages are done
    // IN-APP by GptBuilder (primary + backup built from the scatter,
    // written with raw DA offset writes as the first blankflash step).
    // mtkclient has no equivalent; this is new work, not a port. The
    // construction is verified file-only against mtkclient's own GPT
    // parser; the on-device write itself is UNTESTED and is verified
    // by reading the table back after writing.
    const val SGPT_NOTE = "SGPT stage: done in-app - backup GPT built from the scatter by GptBuilder, written only when the Write GPT box is checked, verified by read-back."
    const val PGPT_NOTE = "PGPT stage: done in-app - primary GPT built from the scatter by GptBuilder, written only when the Write GPT box is checked, verified by read-back."

    // The scatter file inside a blankflash package folder. The Nevada
    // package names it MT6835_Android_scatter_blankflash.xml; match any
    // file ending in _scatter_blankflash.xml.
    fun findScatterFile(packageDir: File): File? {
        if (!packageDir.isDirectory) return null
        return packageDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.endsWith("_scatter_blankflash.xml") }
    }

    // Mirror of parse_blankflash_scatter (ctk_mtk_flash_ui.py:394): the
    // downloadable partitions in scatter order, deduplicated by name.
    // Returns (partitionName, fileName) pairs.
    fun parseScatter(scatterFile: File): List<Pair<String, String>> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(scatterFile)
        val seen = mutableSetOf<String>()
        val items = mutableListOf<Pair<String, String>>()
        val nodes = doc.getElementsByTagName("partition_index")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.childText("partition_name")
            val fileName = element.childText("file_name")
            val downloadable = element.childText("is_download").lowercase()
            if (name.isEmpty() || downloadable != "true") continue
            if (fileName.isEmpty() || fileName == "NONE") continue
            if (name in seen) continue
            seen.add(name)
            items.add(Pair(name, fileName))
        }
        return items
    }

    // Read the platform / project / storage type out of the scatter header
    // (config_version block) and the first storage_type element.
    fun scatterInfo(scatterFile: File): Triple<String, String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(scatterFile)
        var platform = ""
        var project = ""
        val configs = doc.getElementsByTagName("config_version")
        if (configs.length > 0) {
            val element = configs.item(0) as? Element
            if (element != null) {
                platform = element.childText("platform")
                project = element.childText("project")
            }
        }
        var storageType = ""
        val storages = doc.getElementsByTagName("storage_type")
        if (storages.length > 0) {
            storageType = (storages.item(0) as? Element)?.getAttribute("name") ?: ""
        }
        return Triple(platform, project, storageType)
    }

    // 1.1.17: every storage_type name in the file, in file order
    // ("EMMC", "UFS", ...). The plan's storageType is only the first
    // of these; a phone may report a later one.
    fun scatterStorageTypes(scatterFile: File): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(scatterFile)
        val out = mutableListOf<String>()
        val storages = doc.getElementsByTagName("storage_type")
        for (i in 0 until storages.length) {
            val name = (storages.item(i) as? Element)?.getAttribute("name") ?: ""
            if (name.isNotEmpty() && name !in out) out.add(name)
        }
        return out
    }

    // Full scatter details for one partition (region, partition size,
    // linear start address), first occurrence wins. Used for the plan's
    // per-entry scatter fields.
    fun scatterDetails(scatterFile: File): Map<String, ScatterDetail> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(scatterFile)
        val out = mutableMapOf<String, ScatterDetail>()
        val nodes = doc.getElementsByTagName("partition_index")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.childText("partition_name")
            if (name.isEmpty() || name in out) continue
            out[name] = ScatterDetail(
                region = element.childText("region"),
                partitionSize = parseHexOrDec(element.childText("partition_size")),
                linearStart = parseHexOrDec(element.childText("linear_start_addr")),
            )
        }
        return out
    }

    data class ScatterDetail(val region: String, val partitionSize: Long, val linearStart: Long)

    // 1.1.18: scatter details per storage layout in the file:
    // layout name ("EMMC", "UFS") -> partition name -> detail.
    // The layouts agree on addresses in both known scatters, but the
    // run must use the phone's own layout, never another layout's
    // values (Nevada lk_a is 0x4c400000 — the wrong file writes to the
    // wrong address).
    fun scatterDetailsByStorage(scatterFile: File): Map<String, Map<String, ScatterDetail>> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(scatterFile)
        val out = mutableMapOf<String, MutableMap<String, ScatterDetail>>()
        val storages = doc.getElementsByTagName("storage_type")
        for (s in 0 until storages.length) {
            val storage = storages.item(s) as? Element ?: continue
            val name = storage.getAttribute("name")
            if (name.isEmpty()) continue
            val byName = out.getOrPut(name) { mutableMapOf() }
            val nodes = storage.getElementsByTagName("partition_index")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val pname = element.childText("partition_name")
                if (pname.isEmpty() || pname in byName) continue
                byName[pname] = ScatterDetail(
                    region = element.childText("region"),
                    partitionSize = parseHexOrDec(element.childText("partition_size")),
                    linearStart = parseHexOrDec(element.childText("linear_start_addr")),
                )
            }
        }
        return out
    }

    // GPT entries for one storage layout: the user-area entries
    // (region EMMC_USER for EMMC, UFS_LU2 for UFS) in ascending address
    // order, excluding pgpt / sgpt / flashinfo. For the Nevada scatter
    // this is 86 entries (89 UFS_LU2 / EMMC_USER minus the three excluded).
    // The caller picks the layout matching the phone's reported storage
    // type; a mismatch fails the run.
    fun scatterGptEntries(scatterFile: File, storageType: String): List<GptScatterEntry> {
        val userRegion = if (storageType.equals("UFS", ignoreCase = true)) "UFS_LU2" else "EMMC_USER"
        val excluded = setOf("pgpt", "sgpt", "flashinfo")
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(scatterFile)
        val out = mutableListOf<GptScatterEntry>()
        val storages = doc.getElementsByTagName("storage_type")
        for (s in 0 until storages.length) {
            val storage = storages.item(s) as? Element ?: continue
            if (!storage.getAttribute("name").equals(storageType, ignoreCase = true)) continue
            val nodes = storage.getElementsByTagName("partition_index")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                if (element.childText("region") != userRegion) continue
                val name = element.childText("partition_name")
                if (name.isEmpty() || name in excluded) continue
                out.add(
                    GptScatterEntry(
                        name = name,
                        startByte = parseHexOrDec(element.childText("linear_start_addr")),
                        sizeBytes = parseHexOrDec(element.childText("partition_size")),
                    )
                )
            }
        }
        return out.sortedBy { it.startByte }
    }

    // Build the 11-partition blankflash plan from a package folder.
    // Fails closed when the scatter is missing, unparsable, has no
    // downloadable partitions, or the downloadable set is not exactly
    // BromConstants.BLANKFLASH_ORDER in scatter order.
    fun buildPlan(packageDir: File): ScatterPlanResult {
        val scatterFile = findScatterFile(packageDir)
            ?: return ScatterPlanResult.Fail("No *_scatter_blankflash.xml in ${packageDir.path}")
        val parsed: List<Pair<String, String>>
        val details: Map<String, ScatterDetail>
        val detailsByStorage: Map<String, Map<String, ScatterDetail>>
        val info: Triple<String, String, String>
        try {
            parsed = parseScatter(scatterFile)
            details = scatterDetails(scatterFile)
            detailsByStorage = scatterDetailsByStorage(scatterFile)
            info = scatterInfo(scatterFile)
        } catch (e: Exception) {
            return ScatterPlanResult.Fail("Could not parse ${scatterFile.name}: ${e.message}")
        }
        if (parsed.isEmpty()) {
            return ScatterPlanResult.Fail("Scatter ${scatterFile.name} has no downloadable partitions")
        }
        val names = parsed.map { it.first }
        if (names != BromConstants.BLANKFLASH_ORDER) {
            return ScatterPlanResult.Fail(
                "Scatter plan is not the expected 11-partition blankflash order. " +
                    "Got: ${names.joinToString(",")}. " +
                    "Expected: ${BromConstants.BLANKFLASH_ORDER.joinToString(",")}"
            )
        }
        val warnings = mutableListOf<String>()
        val entries = mutableListOf<ScatterPlanEntry>()
        for ((partition, fileName) in parsed) {
            val file = File(packageDir, fileName)
            if (!file.isFile) {
                return ScatterPlanResult.Fail("Missing file for $partition: $fileName not in ${packageDir.path}")
            }
            val size = file.length()
            val sha = sha256(file)
            if (size == 0L) {
                warnings.add("$partition file $fileName is empty (0 bytes) in this package.")
            }
            val detail = details[partition]
            val region = detail?.region ?: ""
            // 1.1.18: per-layout addresses / sizes, so the run uses
            // the phone's own storage layout. Keys upper-cased.
            val linearByStorage = detailsByStorage
                .mapValues { (_, byName) -> byName[partition]?.linearStart ?: 0L }
                .filterValues { it != 0L }
                .mapKeys { (k, _) -> k.uppercase() }
            val sizeByStorage = detailsByStorage
                .mapValues { (_, byName) -> byName[partition]?.partitionSize ?: 0L }
                .filterValues { it > 0L }
                .mapKeys { (k, _) -> k.uppercase() }
            entries.add(
                ScatterPlanEntry(
                    partition = partition,
                    fileName = fileName,
                    file = file,
                    sizeBytes = size,
                    sha256 = sha,
                    isBoot1 = partition == "preloader" || region == "EMMC_BOOT1" || region == "UFS_LU0",
                    scatterPartitionSize = detail?.partitionSize ?: 0L,
                    scatterLinearStart = detail?.linearStart ?: 0L,
                    scatterLinearStartByStorage = linearByStorage,
                    scatterPartitionSizeByStorage = sizeByStorage,
                )
            )
        }
        return ScatterPlanResult.Ok(
            ScatterPlan(
                entries = entries,
                platform = info.first,
                project = info.second,
                storageType = info.third,
                sgptAvailable = true,
                sgptNote = SGPT_NOTE,
                pgptAvailable = true,
                pgptNote = PGPT_NOTE,
                warnings = warnings,
                // 1.1.17: GPT entries for EVERY layout in the file, so
                // runBlankflash can use the phone's real storage type.
                gptEntriesByStorage = try {
                    scatterStorageTypes(scatterFile).associateWith { scatterGptEntries(scatterFile, it) }
                } catch (e: Exception) {
                    return ScatterPlanResult.Fail("Could not read GPT entries from ${scatterFile.name}: ${e.message}")
                },
                gptEntries = try {
                    scatterGptEntries(scatterFile, info.third)
                } catch (e: Exception) {
                    return ScatterPlanResult.Fail("Could not read GPT entries from ${scatterFile.name}: ${e.message}")
                },
            )
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            var read = stream.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                read = stream.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    internal fun parseHexOrDec(text: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return 0L
        return try {
            if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16) else t.toLong(10)
        } catch (e: NumberFormatException) {
            0L
        }
    }

    internal fun Element.childText(tag: String): String {
        val nodes = getElementsByTagName(tag)
        if (nodes.length == 0) return ""
        return nodes.item(0)?.textContent?.trim() ?: ""
    }
}
