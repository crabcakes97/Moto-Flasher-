// BromGpt.kt - GPT / MBR / PMT read path for the in-app BROM / mtkclient
// port (SPEC section 5.13).
//
// Ground truth: mtkclient/Library/Partitions/gpt.py (331 lines),
// mtkclient/Library/partition.py (206 lines), mtkclient/Library/Partitions/
// mbr.py (320 lines), mtkclient/Library/Partitions/pmt.py (173 lines),
// clone main 118a000.
//
// READ PATH ONLY. There is no GPT write anywhere in this file, and no
// function in this file writes anything to the phone. mtkclient has no
// GPT-write command either (its --gpt_file option only tells it to READ
// the layout from a file); a damaged GPT still needs SP Flash Tool on a PC.
//
// Layout of a GPT, as parsed here (gpt.py gpt_header / gpt_partition):
//   LBA 0            protective MBR (0x55AA signature at offset 0x1FE)
//   LBA 1            GPT header, 92 bytes: "EFI PART", revision 0x00010000,
//                    header size, header CRC32, current/backup LBA,
//                    first/last usable LBA, disk GUID, partition-entry
//                    start LBA, entry count, entry size (128).
//   LBA 2..           partition entries, 128 bytes each: type GUID,
//                    unique GUID, first LBA, last LBA, attribute flags,
//                    name as 72 bytes of UTF-16LE.
// Python never verifies the CRCs (gpt.py parse_gpt reads the header and
// the entries and stops there). This port ADDS both CRC32 checks, because
// the Check-GPT gate must fail closed on a corrupt table: the header CRC
// covers header_size bytes with the CRC field zeroed, and the entries CRC
// at header offset 0x58 covers num_entries * entry_size bytes. A CRC
// mismatch is a parse failure, not a warning.
//
// Partition lookup (partition.py get_gpt) tries, in order:
//   1. "BPI\x00" at offset 0 -> BPI layout (not supported here, fails).
//   2. "EMMC_BOOT" / "UFS_BOOT" at offset 0 -> PMT table instead of GPT.
//   3. 0x55AA MBR signature -> MBR walk; a single MBR entry whose first
//      sector starts with "EFI PART" is the GPT; zero MBR entries means
//      check LBA 1 for "EFI PART"; otherwise the MBR entries themselves.
//   4. Otherwise retry the MBR walk at offset 0x380000.
// The backup GPT fallback (partition.py parse_gpt) applies when the
// primary header signature reads all zero: re-read at
// flashSize - 0x4000.
package com.cronch.motoflasher.brom

import java.util.zip.CRC32

// One parsed GPT header (gpt.py: gpt_header.__init__).
data class GptHeader(
    val signature: String,
    val revision: Int,
    val headerSize: Int,
    val headerCrc32: Long,
    val currentLba: Long,
    val backupLba: Long,
    val firstUsableLba: Long,
    val lastUsableLba: Long,
    val diskGuid: String,
    val partEntryStartLba: Long,
    val numPartEntries: Int,
    val partEntrySize: Int,
    val partEntriesCrc32: Long,
)

// Result of parsing one GPT image. crcHeaderOk / crcEntriesOk are false
// when the stored CRC does not match the computed CRC; callers that gate
// on the GPT must treat either as a failure.
data class GptParseResult(
    val header: GptHeader,
    val partitions: List<GptPartition>,
    val sectorSize: Int,
    val crcHeaderOk: Boolean,
    val crcEntriesOk: Boolean,
)

object BromGpt {
    const val GPT_SIGNATURE = "EFI PART"
    const val GPT_REVISION = 0x00010000
    const val GPT_HEADER_SIZE = 92
    const val GPT_ENTRY_SIZE = 128
    const val MBR_SIGNATURE: Int = 0xAA55
    const val PMT_MAGIC_V3 = "PTv3"
    const val PMT_MAGIC_M = "MPT3"
    const val MBR_FALLBACK_OFFSET: Long = 0x380000L
    const val BACKUP_GPT_OFFSET: Long = 0x4000L

    // gpt.py: parse() tries sector sizes 512 then 4096, in that order.
    fun parse(data: ByteArray): GptParseResult? {
        for (sectorSize in listOf(512, 4096)) {
            val result = parseWithSectorSize(data, sectorSize)
            if (result != null) return result
        }
        return null
    }

    fun parseWithSectorSize(data: ByteArray, sectorSize: Int): GptParseResult? {
        if (data.size < sectorSize + GPT_HEADER_SIZE) return null
        val headerBytes = data.copyOfRange(sectorSize, sectorSize + GPT_HEADER_SIZE)
        val header = parseHeader(headerBytes) ?: return null
        if (header.signature != GPT_SIGNATURE) return null
        // gpt.py: parse_gpt - unknown revision is a hard error.
        if (header.revision != GPT_REVISION) return null
        val entriesStart = header.partEntryStartLba.toInt() * sectorSize
        val entriesLen = header.numPartEntries * header.partEntrySize
        if (entriesStart < 0 || entriesStart + entriesLen > data.size) return null
        val entriesBytes = data.copyOfRange(entriesStart, entriesStart + entriesLen)
        val crcHeaderOk = headerCrcOk(headerBytes, header.headerSize)
        val crcEntriesOk = crc32(entriesBytes) == header.partEntriesCrc32
        val partitions = parseEntries(entriesBytes, header, sectorSize)
        return GptParseResult(header, partitions, sectorSize, crcHeaderOk, crcEntriesOk)
    }

    // gpt.py: gpt_header.__init__ - 8s signature, dword revision, dword
    // header size, dword CRC32, dword reserved, 3 qwords, 16s disk GUID,
    // qword entry start LBA, dword entry count, dword entry size. The
    // entries CRC32 is the dword at header offset 0x58.
    fun parseHeader(data: ByteArray): GptHeader? {
        if (data.size < GPT_HEADER_SIZE) return null
        val signature = data.copyOfRange(0, 8).toString(Charsets.US_ASCII)
        return GptHeader(
            signature = signature,
            revision = le32(data, 8),
            headerSize = le32(data, 12),
            headerCrc32 = le32(data, 16).toLong() and 0xFFFFFFFFL,
            currentLba = le64(data, 24),
            backupLba = le64(data, 32),
            firstUsableLba = le64(data, 40),
            lastUsableLba = le64(data, 48),
            diskGuid = formatGuid(data.copyOfRange(56, 72)),
            partEntryStartLba = le64(data, 72),
            numPartEntries = le32(data, 80),
            partEntrySize = le32(data, 84),
            partEntriesCrc32 = le32(data, 88).toLong() and 0xFFFFFFFFL,
        )
    }

    // Header CRC: CRC32 over header_size bytes with the CRC field itself
    // (offset 16, 4 bytes) zeroed. Standard GPT rule; Python does not
    // check it, this port does.
    fun headerCrcOk(headerBytes: ByteArray, headerSize: Int): Boolean {
        if (headerBytes.size < headerSize) return false
        val stored = le32(headerBytes, 16).toLong() and 0xFFFFFFFFL
        val zeroed = headerBytes.copyOfRange(0, headerSize)
        zeroed[16] = 0
        zeroed[17] = 0
        zeroed[18] = 0
        zeroed[19] = 0
        return crc32(zeroed) == stored
    }

    // gpt.py: parse_gpt - walk num_part_entries entries; stop at the first
    // entry whose unique GUID is all zero; skip EFI_UNUSED (type dword 0)
    // entries; sector = first_lba, sectors = last_lba - first_lba + 1.
    fun parseEntries(entriesBytes: ByteArray, header: GptHeader, sectorSize: Int): List<GptPartition> {
        val out = mutableListOf<GptPartition>()
        val entrySize = header.partEntrySize
        if (entrySize < GPT_ENTRY_SIZE) return out
        for (idx in 0 until header.numPartEntries) {
            val base = idx * entrySize
            if (base + GPT_ENTRY_SIZE > entriesBytes.size) break
            val entry = entriesBytes.copyOfRange(base, base + GPT_ENTRY_SIZE)
            val unique = entry.copyOfRange(16, 32)
            if (unique.all { it == 0.toByte() }) break
            val typeGuid = entry.copyOfRange(0, 16)
            if (le32(typeGuid, 0) == 0) continue
            val firstLba = le64(entry, 32)
            val lastLba = le64(entry, 40)
            val name = parseUtf16Le(entry.copyOfRange(56, 128))
            if (name.isEmpty()) continue
            out.add(
                GptPartition(
                    name = name,
                    startByte = firstLba * sectorSize,
                    sizeBytes = (lastLba - firstLba + 1) * sectorSize,
                    guid = formatGuid(unique),
                )
            )
        }
        return out
    }

    // gpt.py: parse_gpt GUID formatting - first three fields little-endian
    // integers, fourth field little-endian short, last six bytes as hex:
    // "{:08x}-{:04x}-{:04x}-{:04x}-{}".
    fun formatGuid(bytes: ByteArray): String {
        require(bytes.size == 16)
        val g1 = le32(bytes, 0)
        val g2 = le16(bytes, 4)
        val g3 = le16(bytes, 6)
        val g4 = le16(bytes, 8)
        val g5 = bytes.copyOfRange(10, 16).joinToString("") { "%02x".format(it) }
        return "%08x-%04x-%04x-%04x-%s".format(g1, g2, g3, g4, g5)
    }

    // mbr.py: mbr.parse - 0x55AA marker at offset + 0x1FE, then the MBR
    // structure walk (extended partitions at type 0x05 / 0x0F, ext2 label
    // detection at +0x438 for the 0xEF53 magic with the name at +0x488).
    // Entries with zero sectors are dropped; names fall back to the index.
    fun parseMbr(data: ByteArray, offset: Int = 0): List<GptPartition>? {
        if (data.size < offset + 512) return null
        if (le16(data, offset + 0x1FE) != MBR_SIGNATURE) return null
        val raw = mutableListOf<Triple<Long, Long, Int>>()
        for (i in 0 until 4) {
            val base = offset + 0x1BE + i * 16
            val type = data[base + 4].toInt() and 0xFF
            val lba = le32(data, base + 8).toLong() and 0xFFFFFFFFL
            val sectors = le32(data, base + 12).toLong() and 0xFFFFFFFFL
            if (type == 0x05 || type == 0x0F) {
                // Extended partition: walk its EBR chain (mbr.py:242).
                var ebrBase = lba * 512L
                var guard = 0
                while (guard < 64) {
                    guard++
                    if (ebrBase + 512 > data.size) break
                    if (le16(data, (ebrBase + 0x1FE).toInt()) != MBR_SIGNATURE) break
                    val eBase = ebrBase.toInt() + 0x1BE
                    val eType = data[eBase + 4].toInt() and 0xFF
                    val eLba = le32(data, eBase + 8).toLong() and 0xFFFFFFFFL
                    val eSectors = le32(data, eBase + 12).toLong() and 0xFFFFFFFFL
                    if (eSectors != 0L) raw.add(Triple(lba + eLba, eSectors, eType))
                    val nBase = eBase + 16
                    val nLba = le32(data, nBase + 8).toLong() and 0xFFFFFFFFL
                    val nSectors = le32(data, nBase + 12).toLong() and 0xFFFFFFFFL
                    if (nSectors == 0L) break
                    ebrBase = (lba + nLba) * 512L
                }
            } else if (sectors != 0L) {
                raw.add(Triple(lba, sectors, type))
            }
        }
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.first }
        val out = mutableListOf<GptPartition>()
        for ((i, entry) in sorted.withIndex()) {
            val (startSector, sectors, _) = entry
            val byteOff = (startSector * 512L).toInt()
            var name = i.toString()
            if (byteOff + 0x488 + 255 <= data.size && le16(data, byteOff + 0x438) == 0xEF53) {
                val rawName = data.copyOfRange(byteOff + 0x488, byteOff + 0x488 + 255)
                    .toString(Charsets.US_ASCII).replace("/", "")
                    .trim { it == 0.toChar() }
                if (rawName.isNotEmpty()) name = rawName
            }
            out.add(
                GptPartition(
                    name = name,
                    startByte = startSector * 512L,
                    sizeBytes = sectors * 512L,
                    guid = "",
                )
            )
        }
        return out
    }

    // pmt.py: pt_resident - 64-byte name, qword size, qword part_id, qword
    // offset, qword mask_flags (96 bytes per entry). partition.py get_pmt
    // reads 2 * pagesize at flashSize - 2 * blocksize and checks the
    // "PTv3" / "MPT3" magic; entries start at offset 8, up to 128, and
    // stop at the first entry whose first 4 bytes are zero.
    fun parsePmt(data: ByteArray, pageSize: Int): List<GptPartition>? {
        if (data.size < 8) return null
        val magic = data.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        if (magic != PMT_MAGIC_V3 && magic != PMT_MAGIC_M) return null
        val out = mutableListOf<GptPartition>()
        for (pos in 0 until 128) {
            val base = 8 + pos * 96
            if (base + 96 > data.size) break
            val entry = data.copyOfRange(base, base + 96)
            if (entry[0] == 0.toByte() && entry[1] == 0.toByte() &&
                entry[2] == 0.toByte() && entry[3] == 0.toByte()
            ) {
                break
            }
            val name = entry.copyOfRange(0, 64).toString(Charsets.US_ASCII)
                .trim { it == 0.toChar() }
            if (name.isEmpty()) continue
            val size = le64(entry, 64)
            val offset = le64(entry, 80)
            out.add(
                GptPartition(
                    name = name,
                    startByte = offset,
                    sizeBytes = size,
                    guid = "",
                )
            )
        }
        return out
    }

    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    fun parseUtf16Le(bytes: ByteArray): String {
        val chars = CharArray(bytes.size / 2)
        for (i in chars.indices) {
            chars[i] = ((bytes[i * 2].toInt() and 0xFF) or
                ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toChar()
        }
        return String(chars).trim { it == 0.toChar() }
    }

    internal fun le16(data: ByteArray, off: Int): Int {
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }

    internal fun le32(data: ByteArray, off: Int): Int {
        return (data[off].toInt() and 0xFF) or
            ((data[off + 1].toInt() and 0xFF) shl 8) or
            ((data[off + 2].toInt() and 0xFF) shl 16) or
            ((data[off + 3].toInt() and 0xFF) shl 24)
    }

    internal fun le64(data: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((data[off + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }
}

// One scatter entry that becomes a GPT entry: byte start and byte size
// from the scatter's linear_start_addr / partition_size.
data class GptScatterEntry(val name: String, val startByte: Long, val sizeBytes: Long)

// GptBuilder - builds a GPT (primary + backup) from a scatter layout.
// This is NEW work: mtkclient has no GPT-write command (analysis.md
// section 6.5.1), so there is no Python builder to port. The FORMAT is
// fully established from the read side (gpt.py header / entry layout,
// partition.py backup fallback) and the builder is verified file-only
// against mtkclient's own gpt.py parser (see brom-port/
// scatter-expected.md): the built primary parses, all entries match
// the scatter, both CRCs verify, and the backup mirrors the primary.
//
// Layout (standard GPT, sector size 512 for eMMC, 4096 for UFS):
//   LBA 0            protective MBR (type 0xEE, 0x55AA signature)
//   LBA 1            primary header (92 bytes)
//   LBA 2..33        primary entries (128 entries x 128 bytes)
//   first usable LBA 34, last usable LBA total-34
//   LBA total-33..total-2  backup entries (same 128 entries)
//   LBA total-1      backup header
// The scatter reserves 0x8000 bytes (64 sectors) for the primary GPT
// area (pgpt entry); the standard 34-sector primary fits inside it,
// and the first real partition (misc, sector 64) starts after it.
//
// Rules:
//   - Entries are the scatter's user-area entries in ascending LBA
//     order, excluding pgpt / sgpt / flashinfo (the GPT area itself
//     and SP Flash Tool-internal markers with fake 0xFFFFxxxx
//     addresses, not real partitions).
//   - The last real partition, userdata (operation NEEDRESIZE in the
//     scatter), stretches to last usable LBA: its end comes from the
//     DA-reported flash size, not from the scatter's placeholder size.
//   - Type GUID is EFI_BASIC_DATA for every entry: that is what
//     Android GPTs carry for user partitions, and the bootloader
//     resolves by name, not by type. Flag it if a first printgpt
//     shows anything else.
//   - Unique GUIDs are deterministic: SHA-256("gpt-part:" + name),
//     first 16 bytes. Same scatter always builds the same table, and
//     GUIDs are unique within the disk. The disk GUID is
//     SHA-256("gpt-disk:" + project), first 16 bytes.
//   - Both CRCs are computed here and verified on read-back: the
//     header CRC covers header_size bytes with the CRC field zeroed,
//     and the entries CRC (header offset 0x58) covers the full
//     128-entry array. Python never checks either CRC; this port
//     computes and enforces both.
object GptBuilder {
    const val NUM_ENTRIES: Int = 128
    const val ENTRY_SIZE: Int = 128
    const val HEADER_SIZE: Int = 92
    const val FIRST_USABLE_LBA: Long = 34L
    const val PRIMARY_SECTORS: Long = 34L
    const val BACKUP_SECTORS: Long = 33L

    // Type GUID bytes for EFI_BASIC_DATA, stored little-endian per
    // field: EBD0A0A2-B9E5-4433-87C0-68B6B72699C7, i.e. bytes
    // A2 A0 D0 EB | E5 B9 | 33 44 | 87 C0 | 68 B6 B7 26 99 C7.
    // (Fields 2 and 3 are little-endian u16s: 0xB9E5 stores as E5 B9,
    // 0x4433 stores as 33 44.)
    val TYPE_BASIC_DATA: ByteArray = byteArrayOf(
        0xA2.toByte(), 0xA0.toByte(), 0xD0.toByte(), 0xEB.toByte(),
        0xE5.toByte(), 0xB9.toByte(), 0x33.toByte(), 0x44.toByte(),
        0x87.toByte(), 0xC0.toByte(), 0x68.toByte(), 0xB6.toByte(),
        0xB7.toByte(), 0x26.toByte(), 0x99.toByte(), 0xC7.toByte(),
    )

    // Build both regions. Returns (primaryBytes, backupBytes, entries)
    // where entries is the GptPartition list the table carries, in
    // order. Returns null when the layout cannot form a valid GPT
    // (too many entries, a partition outside the disk, total too small).
    fun build(
        scatterEntries: List<GptScatterEntry>,
        totalSectors: Long,
        sectorSize: Int,
        project: String,
    ): Triple<ByteArray, ByteArray, List<GptPartition>>? {
        if (totalSectors <= FIRST_USABLE_LBA + BACKUP_SECTORS) return null
        if (scatterEntries.size > NUM_ENTRIES) return null
        val lastUsable = totalSectors - 34L
        val sorted = scatterEntries.sortedBy { it.startByte }
        val partitions = mutableListOf<GptPartition>()
        val entryBytes = ByteArray(NUM_ENTRIES * ENTRY_SIZE)
        for ((index, scatter) in sorted.withIndex()) {
            val firstLba = scatter.startByte / sectorSize
            val lastLba = if (scatter.name.equals("userdata", ignoreCase = true)) {
                lastUsable
            } else {
                (scatter.startByte + scatter.sizeBytes) / sectorSize - 1
            }
            if (firstLba < FIRST_USABLE_LBA || lastLba < firstLba || lastLba > lastUsable) return null
            val base = index * ENTRY_SIZE
            TYPE_BASIC_DATA.copyInto(entryBytes, base)
            partGuid(scatter.name).copyInto(entryBytes, base + 16)
            putLe64(entryBytes, base + 32, firstLba)
            putLe64(entryBytes, base + 40, lastLba)
            putLe64(entryBytes, base + 48, 0L)
            val nameBytes = scatter.name.toByteArray(Charsets.UTF_16LE)
            val clipped = nameBytes.copyOf(minOf(nameBytes.size, 72))
            clipped.copyInto(entryBytes, base + 56)
            partitions.add(
                GptPartition(
                    name = scatter.name,
                    startByte = firstLba * sectorSize,
                    sizeBytes = (lastLba - firstLba + 1) * sectorSize,
                    guid = BromGpt.formatGuid(partGuid(scatter.name)),
                )
            )
        }
        val entriesCrc = BromGpt.crc32(entryBytes)
        val diskGuid = diskGuid(project)
        val primary = ByteArray((PRIMARY_SECTORS * sectorSize).toInt())
        buildProtectiveMbr(primary, totalSectors)
        val primaryHeader = buildHeader(
            currentLba = 1L, backupLba = totalSectors - 1, entryStartLba = 2L,
            totalSectors = totalSectors, entriesCrc = entriesCrc, diskGuid = diskGuid,
        )
        primaryHeader.copyInto(primary, sectorSize)
        entryBytes.copyInto(primary, 2 * sectorSize)
        val backup = ByteArray((BACKUP_SECTORS * sectorSize).toInt())
        entryBytes.copyInto(backup, 0)
        val backupHeader = buildHeader(
            currentLba = totalSectors - 1, backupLba = 1L, entryStartLba = totalSectors - 33L,
            totalSectors = totalSectors, entriesCrc = entriesCrc, diskGuid = diskGuid,
        )
        backupHeader.copyInto(backup, 32 * sectorSize)
        return Triple(primary, backup, partitions)
    }

    // Verify built bytes with the read-side parser: the primary must
    // parse, both CRCs must verify, and the entries must equal the
    // expected partition list. This is the file-only check the
    // verification matrix runs before any write.
    fun verifyBuilt(primary: ByteArray, backup: ByteArray, expected: List<GptPartition>): Boolean {
        val parsed = BromGpt.parse(primary) ?: return false
        if (!parsed.crcHeaderOk || !parsed.crcEntriesOk) return false
        if (parsed.partitions.size != expected.size) return false
        for (i in expected.indices) {
            val a = parsed.partitions[i]
            val b = expected[i]
            if (a.name != b.name || a.startByte != b.startByte || a.sizeBytes != b.sizeBytes) return false
        }
        val backupSectorSize = backup.size / GptBuilder.BACKUP_SECTORS.toInt()
        val backupHeader = backup.copyOfRange(32 * backupSectorSize, 33 * backupSectorSize)
        if (backupHeader.copyOfRange(0, 8).toString(Charsets.US_ASCII) != BromGpt.GPT_SIGNATURE) return false
        return true
    }

    fun partGuid(name: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(("gpt-part:" + name).toByteArray(Charsets.UTF_8)).copyOf(16)
    }

    fun diskGuid(project: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(("gpt-disk:" + project).toByteArray(Charsets.UTF_8)).copyOf(16)
    }

    // Protective MBR: one entry, type 0xEE, start LBA 1, size
    // total-1 capped at 0xFFFFFFFF, 0x55AA signature at 0x1FE.
    fun buildProtectiveMbr(sector0: ByteArray, totalSectors: Long) {
        sector0[0x1BE + 4] = 0xEE.toByte()
        putLe32(sector0, 0x1BE + 8, 1)
        val size = minOf(totalSectors - 1, 0xFFFFFFFFL)
        putLe32(sector0, 0x1BE + 12, size.toInt())
        sector0[0x1FE] = 0x55.toByte()
        sector0[0x1FF] = 0xAA.toByte()
    }

    // 92-byte GPT header. The entries CRC lives at offset 0x58; the
    // header CRC (offset 0x10) covers the 92 bytes with itself zeroed.
    fun buildHeader(
        currentLba: Long, backupLba: Long, entryStartLba: Long,
        totalSectors: Long, entriesCrc: Long, diskGuid: ByteArray,
    ): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        "EFI PART".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putLe32(header, 8, BromGpt.GPT_REVISION)
        putLe32(header, 12, HEADER_SIZE)
        putLe32(header, 16, 0)
        putLe32(header, 20, 0)
        putLe64(header, 24, currentLba)
        putLe64(header, 32, backupLba)
        putLe64(header, 40, FIRST_USABLE_LBA)
        putLe64(header, 48, totalSectors - 34L)
        diskGuid.copyInto(header, 56)
        putLe64(header, 72, entryStartLba)
        putLe32(header, 80, NUM_ENTRIES)
        putLe32(header, 84, ENTRY_SIZE)
        putLe32(header, 88, entriesCrc.toInt())
        val crc = BromGpt.crc32(header)
        putLe32(header, 16, crc.toInt())
        return header
    }

    internal fun putLe32(data: ByteArray, off: Int, value: Int) {
        data[off] = (value and 0xFF).toByte()
        data[off + 1] = ((value ushr 8) and 0xFF).toByte()
        data[off + 2] = ((value ushr 16) and 0xFF).toByte()
        data[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    internal fun putLe64(data: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) {
            data[off + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }
}

// Reads the partition table off the phone through a supplied flash-read
// function, following partition.py: Partition.get_gpt exactly. The
// readFlash function is (address, length, partType) -> bytes, normally
// DaXml.readFlash. The readPartitionTable function is the DA's
// READ-PARTITION-TABLE command (DaXml.readPartitionTable); partition.py
// uses it whenever the flash head reads "EMMC_BOOT" / "UFS_BOOT", because
// on those layouts the DA's own table is authoritative. When it is null,
// the on-flash PMT parse (Partition.get_pmt) is used instead. Returns the
// raw bytes read plus the partition list, or null when no table could be
// read at all.
class GptReader(
    private val readFlash: (addr: Long, length: Long, partType: String?) -> ByteArray?,
    private val flashSize: Long,
    private val pageSize: Long,
    private val readPartitionTable: (() -> Pair<ByteArray, List<GptPartition>>?)? = null,
) {
    // partition.py: get_gpt - read 2 * pagesize at offset 0, then dispatch
    // on the first bytes. Returns null when nothing parses.
    fun getGpt(partType: String? = "user"): Pair<ByteArray, List<GptPartition>>? {
        // 1.1.17: try the DA's own partition-table command FIRST. It
        // returns the table as XML with no raw flash access, so it
        // works on DAs that reject READ-FLASH (this DA: ERR!UNSUPPORTED
        // on the GPT-head read at addr 0, 1.1.16 log). mtkclient's
        // get_partition_data uses READ-PARTITION-TABLE as its primary
        // table source for the same reason. Falls back to the raw-flash
        // path below when the DA does not support it.
        val viaDa = readPartitionTable?.invoke()
        if (viaDa != null && viaDa.second.isNotEmpty()) return viaDa
        val head = readFlash(0L, 2 * pageSize, partType) ?: return null
        if (head.isEmpty()) return null
        if (head.size >= 4 && head.copyOfRange(0, 4).contentEquals("BPI\u0000".toByteArray(Charsets.US_ASCII))) {
            // BPI layout (partition.py: get_gpt) - not supported by this port.
            return null
        }
        val first9 = if (head.size >= 9) head.copyOfRange(0, 9).toString(Charsets.US_ASCII) else ""
        if (first9 == "EMMC_BOOT" || first9 == "UFS_BOOT") {
            // partition.py: get_gpt calls self.read_pmt() here, which is
            // the DA's READ-PARTITION-TABLE command, not the on-flash PMT.
            val viaDa = readPartitionTable?.invoke()
            if (viaDa != null) return viaDa
            val pmt = readPmt() ?: return null
            return Pair(pmt.first, pmt.second)
        }
        if (head.size >= 512 && BromGpt.le16(head, 0x1FE) == BromGpt.MBR_SIGNATURE) {
            return parseMbr(head, 0L)
        }
        return parseMbr(head, BromGpt.MBR_FALLBACK_OFFSET)
    }

    // partition.py: parse_mbr - walk the MBR at the given offset. A single
    // MBR entry whose first sector starts with "EFI PART" is the GPT;
    // zero entries means check LBA 1 for "EFI PART"; otherwise the MBR
    // entries themselves are the table.
    fun parseMbr(head: ByteArray, offset: Long): Pair<ByteArray, List<GptPartition>>? {
        val mbrData: ByteArray
        val mbr: List<GptPartition>?
        if (offset == 0L) {
            mbrData = head
            mbr = BromGpt.parseMbr(head, 0)
        } else {
            mbrData = readFlash(offset, 2 * pageSize, "user") ?: return null
            mbr = BromGpt.parseMbr(mbrData, 0)
        }
        if (mbr == null) return Pair(mbrData, emptyList())
        if (mbr.size == 1) {
            val entry = mbr[0]
            val hdr = readFlash(entry.startByte, 2 * pageSize, "user")
            if (hdr != null && hdr.size >= 8 &&
                hdr.copyOfRange(0, 8).contentEquals(BromGpt.GPT_SIGNATURE.toByteArray(Charsets.US_ASCII))
            ) {
                return parseGptAt(entry.startByte)
            }
            return Pair(mbrData, mbr)
        }
        if (mbr.isEmpty()) {
            val lba1 = readFlash(pageSize, 2 * pageSize, "user")
            if (lba1 != null && lba1.size >= 8 &&
                lba1.copyOfRange(0, 8).contentEquals(BromGpt.GPT_SIGNATURE.toByteArray(Charsets.US_ASCII))
            ) {
                return parseGptAt(pageSize)
            }
            return Pair(mbrData, mbr)
        }
        val raw = readFlash(offset, 32 * pageSize, "user") ?: mbrData
        return Pair(raw, mbr)
    }

    // partition.py: parse_gpt - read the header at the GPT offset; when
    // the signature reads all zero, retry at flashSize - 0x4000 (backup
    // GPT). Fails when first_usable_lba is 0.
    fun parseGptAt(gptOffset: Long): Pair<ByteArray, List<GptPartition>>? {
        val need = 34 * pageSize
        var raw = readFlash(gptOffset, need, "user") ?: return null
        var parsed = BromGpt.parse(raw)
        if (parsed == null) {
            // Backup GPT fallback: header signature all zero at the
            // primary location. partition.py seeks flashSize - 0x4000.
            val backup = readFlash(flashSize - BromGpt.BACKUP_GPT_OFFSET, need, "user") ?: return null
            parsed = BromGpt.parse(backup)
            if (parsed == null) return null
            raw = backup
        }
        val result = parsed
        if (result.header.firstUsableLba == 0L) return null
        if (!result.crcHeaderOk || !result.crcEntriesOk) return null
        return Pair(raw, result.partitions)
    }

    // partition.py: get_pmt - read 2 * pagesize at
    // flashSize - 2 * blocksize and parse the PMT there.
    fun readPmt(backup: Boolean = false): Pair<ByteArray, List<GptPartition>>? {
        val blockSize = pageSize
        val addr = if (!backup) flashSize - 2 * blockSize else flashSize - 2 * blockSize + blockSize
        val data = readFlash(addr, 2 * pageSize, "user") ?: return null
        val partitions = BromGpt.parsePmt(data, pageSize.toInt()) ?: return null
        return Pair(data, partitions)
    }
}
