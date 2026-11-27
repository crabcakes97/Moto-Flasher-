package com.cronch.motoflasher

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * liblk, ported to Kotlin (parse-only).
 *
 * Upstream: R0rt1z2/liblk by Roger Ortiz (GPL-3.0-or-later) — the minimal
 * LK/LKS container parser used by Val Protocol. This is a straight port of
 * liblk/structures/header.py, structures/partition.py and image.py:
 * same 512-byte little-endian header, same magic values, same partition
 * walk (cert partitions attach to the previous partition, stop at
 * image_list_end or at the first non-magic offset). No patching here —
 * patch generation stays in the Python tool on a PC (Capstone analysis
 * is not portable to this app); this port only reads and extracts.
 *
 * What it does: open an lk.img / LKS container, list its partitions
 * (name, size, load address, certs), and pull the raw `lk` payload out
 * so it can be handed to the patcher or flashed back.
 */
object LibLk {

    const val MAGIC = 0x58881688
    const val EXT_MAGIC = 0x58891689.toInt() // 0x58891689 as signed int

    data class LkPartition(
        val name: String,
        val offset: Int,
        val headerSize: Int,
        val dataSize: Long,
        val memoryAddress: Long,
        val isExtended: Boolean,
        val imageListEnd: Int,
        val alignment: Int,
        val certs: List<LkPartition>,
        val lkAddress: Long?,
        val data: ByteArray,
        val endOffset: Int,
    )

    data class LkImage(
        val version: Int,
        val partitions: List<LkPartition>,
    )

    class InvalidLk(msg: String) : Exception(msg)

    private fun u32(buf: ByteArray, off: Int): Long =
        (buf[off].toLong() and 0xFF) or
            ((buf[off + 1].toLong() and 0xFF) shl 8) or
            ((buf[off + 2].toLong() and 0xFF) shl 16) or
            ((buf[off + 3].toLong() and 0xFF) shl 24)

    private fun u64(buf: ByteArray, off: Int): Long =
        u32(buf, off) or (u32(buf, off + 4) shl 32)

    private fun nameOf(buf: ByteArray, off: Int): String {
        val raw = buf.copyOfRange(off, off + 32)
        val end = raw.indexOf(0).let { if (it < 0) 32 else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }

    private val LOADADDR = byteArrayOf(0x10, 0xFF.toByte(), 0x2F, 0xE1.toByte())
    private val PHYS_OFFSET = byteArrayOf(0x0F, 0x00, 0x00, 0x10)
    private val PHYS_OFFSET_SUB = byteArrayOf(0x2F, 0x01, 0x0F, 0xCB.toByte())

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int, until: Int = hay.size): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in from..until - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Port of LkPartition._detect_load_address. */
    private fun detectLoadAddress(contents: ByteArray, data: ByteArray): Long? {
        val la = indexOf(contents, LOADADDR, 0)
        if (la >= 0 && la + 12 <= contents.size) return u32(contents, la + 8)
        var off = 0
        val limit = minOf(0x400, data.size - 12)
        while (off < limit) {
            off = indexOf(data, PHYS_OFFSET, off, 0x400)
            if (off < 0) break
            if (off + 12 > data.size) break
            if (!data.copyOfRange(off + 8, off + 12).contentEquals(PHYS_OFFSET_SUB)) {
                off += 4; continue
            }
            val ldr = u32(data, off + 4)
            if ((ldr and 0xFF000000L) != 0x58000000L) { off += 4; continue }
            var imm19 = ((ldr shr 5) and 0x7FFFF).toInt()
            if (imm19 and (1 shl 18) != 0) imm19 -= 1 shl 19
            val literalOff = (off + 4) + imm19 * 4
            if (literalOff < 0 || literalOff + 8 > data.size) { off += 4; continue }
            val vaddr = u64(data, literalOff)
            if ((vaddr ushr 48) != 0xFFFFL) { off += 4; continue }
            return vaddr - off
        }
        return null
    }

    /** Port of LkPartition.from_bytes — parses one partition at [offset]. */
    private fun parsePartition(contents: ByteArray, offset: Int): LkPartition {
        val startOffset = if (contents.size >= 4 &&
            contents[0] == 'B'.code.toByte() && contents[1] == 'F'.code.toByte() &&
            contents[2] == 'B'.code.toByte() && contents[3] == 'F'.code.toByte()
        ) 0x4040 else 0
        if (startOffset + 512 > contents.size - offset) {
            throw InvalidLk("short header at offset 0x${offset.toString(16)}")
        }
        val h = contents.copyOfRange(offset + startOffset, offset + startOffset + 512)
        val magic = u32(h, 0).toInt()
        if (magic != MAGIC) throw InvalidLk("invalid magic 0x${magic.toString(16)} at offset 0x${offset.toString(16)}")
        val dsize = u32(h, 4)
        val name = nameOf(h, 8)
        val maddr = u32(h, 40)
        val extMagic = u32(h, 48).toInt()
        val hdrSize = u32(h, 52).toInt()
        val hdrVersion = u32(h, 56)
        val imageListEnd = u32(h, 64).toInt()
        val alignment = u32(h, 68).toInt()
        val dsizeExt = u32(h, 72)
        val maddrExt = u32(h, 76)
        val isExtended = extMagic == EXT_MAGIC
        val dataSize = if (isExtended) (dsizeExt shl 32) or dsize else dsize
        val headerSize = if (isExtended) hdrSize else 512
        val memoryAddress = if (isExtended) (maddrExt shl 32) or maddr else maddr
        val align = if (isExtended) alignment else 8
        var endOffset = offset + headerSize + dataSize.toInt()
        if (align != 0 && endOffset % align != 0) endOffset += align - (endOffset % align)
        if (headerSize + dataSize > (contents.size - offset).toLong()) {
            throw InvalidLk("partition $name overruns file")
        }
        val data = contents.copyOfRange(
            offset + headerSize, (offset + headerSize + dataSize).toInt()
        )
        val lkAddress = if (name.lowercase() == "lk") {
            if ((maddr and 0xFFFFFFFFL) == 0xFFFFFFFFL) {
                detectLoadAddress(contents.copyOfRange(offset, contents.size), data)
            } else memoryAddress
        } else null
        return LkPartition(
            name = name, offset = offset, headerSize = headerSize,
            dataSize = dataSize, memoryAddress = memoryAddress,
            isExtended = isExtended, imageListEnd = imageListEnd,
            alignment = align, certs = emptyList(), lkAddress = lkAddress,
            data = data, endOffset = endOffset,
        )
    }

    private fun isEndOfPartitions(contents: ByteArray, offset: Int): Boolean {
        if (offset >= contents.size) return true
        if (contents.size - offset < 512) return true
        return u32(contents, offset).toInt() != MAGIC
    }

    /** Port of LkImage._parse_partitions + _detect_version. */
    fun parse(contents: ByteArray): LkImage {
        val parts = linkedMapOf<String, LkPartition>()
        val certLists = mutableMapOf<String, MutableList<LkPartition>>()
        var lastName = ""
        var offset = 0
        while (offset < contents.size) {
            val part: LkPartition = try {
                parsePartition(contents, offset)
            } catch (e: InvalidLk) {
                if (parts.isNotEmpty()) break else throw e
            }
            if (part.name.startsWith("cert")) {
                if (lastName !in parts) {
                    throw InvalidLk("certificate partition placed before actual partition")
                }
                certLists.getOrPut(lastName) { mutableListOf() }.add(part)
            } else if (part.name !in parts) {
                lastName = part.name
                parts[lastName] = part
            } else {
                throw InvalidLk("duplicate partition name: ${part.name}")
            }
            if (part.isExtended && part.imageListEnd == 1) break
            offset = part.endOffset
            if (!part.isExtended || part.imageListEnd !in 0..1) {
                if (isEndOfPartitions(contents, offset)) break
            }
        }
        val merged = parts.map { (name, p) ->
            p.copy(certs = certLists[name] ?: emptyList())
        }
        val version = if (merged.any { it.name == "aee" || it.name == "bl2_ext" }) 2 else 1
        return LkImage(version = version, partitions = merged)
    }

    /** One-line summary, lk-dump style. */
    fun describe(image: LkImage): String {
        val sb = StringBuilder()
        sb.append("LK container v${image.version}, ${image.partitions.size} partition(s):\n")
        for (p in image.partitions) {
            sb.append("- ${p.name}: ${p.dataSize} bytes")
            if (p.lkAddress != null) sb.append(" @ 0x${java.lang.Long.toUnsignedString(p.lkAddress, 16)}")
            if (p.isExtended) sb.append(" (ext hdr)")
            if (p.certs.isNotEmpty()) {
                sb.append(" certs: ${p.certs.joinToString(",") { it.name }}")
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
