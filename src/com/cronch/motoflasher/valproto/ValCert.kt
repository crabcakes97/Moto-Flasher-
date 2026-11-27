// MTK CERT2 hash override signer.
// Port of tools/sign_mtk_cert.py (DER walker: rewrites two SHA-256 BITSTRINGs
// in CERT2 ASN.1 — hash-only, no private key/RSA) + the DER TLV helpers it
// reuses from tools/parse_mtk_certs.py.
package com.cronch.motoflasher.valproto

import java.security.MessageDigest

object ValCert {
    const val PART_MAGIC = 0x58881688L
    const val PART_HDR_SIZE = 512
    const val IMG_TYPE_GROUP_CERT = 0x02000000L
    const val IMG_TYPE_CERT2 = IMG_TYPE_GROUP_CERT or 0x02L
    const val OID_IMAGE_HASH = "2.16.886.2454.2.1"
    const val OID_IMAGE_HEADER_HASH = "2.16.886.2454.2.4"

    fun roundup(value: Long, align: Long): Long {
        if (align <= 0) return value
        return ((value + align - 1) / align) * align
    }

    data class PartHdr(
        val magic: Long, val dsize: Long, val name: String, val maddr: Long,
        val mode: Long, val extMagic: Long, val hdrSz: Long, val hdrVer: Long,
        val imgType: Long, val imgListEnd: Long, val alignSz: Long,
        val dsizeExtend: Long, val maddrExtend: Long,
    ) {
        fun paddedDataSize(): Long = roundup(dsize, alignSz)
        fun nextOffset(offset: Long): Long = offset + PART_HDR_SIZE + paddedDataSize()
        fun isCert(): Boolean = name.lowercase().startsWith("cert")
    }

    fun parsePartHdr(data: ByteArray, off: Int): PartHdr {
        require(off + PART_HDR_SIZE <= data.size) { "no complete header at offset 0x${off.toString(16)}" }
        val magic = ValBytes.u32At(data, off)
        val dsize = ValBytes.u32At(data, off + 4)
        val nameRaw = data.copyOfRange(off + 8, off + 40)
        val nameEnd = nameRaw.indexOf(0).let { if (it < 0) 32 else it }
        val name = String(nameRaw, 0, nameEnd, Charsets.ISO_8859_1)
        return PartHdr(
            magic, dsize, name,
            ValBytes.u32At(data, off + 40), ValBytes.u32At(data, off + 44),
            ValBytes.u32At(data, off + 48), ValBytes.u32At(data, off + 52),
            ValBytes.u32At(data, off + 56), ValBytes.u32At(data, off + 60),
            ValBytes.u32At(data, off + 64), ValBytes.u32At(data, off + 68),
            ValBytes.u32At(data, off + 72), ValBytes.u32At(data, off + 76),
        )
    }

    data class PartEntry(val idx: Int, val off: Int, val dataOff: Int, val nextOff: Int, val hdr: PartHdr)

    fun parsePartHeaders(data: ByteArray): List<PartEntry> {
        val out = mutableListOf<PartEntry>()
        var off = 0
        var idx = 0
        while (off + PART_HDR_SIZE <= data.size) {
            val hdr = try {
                parsePartHdr(data, off)
            } catch (e: Exception) {
                break
            }
            if (hdr.magic != PART_MAGIC) break
            val dataOffset = off + PART_HDR_SIZE
            val nextOffset = (off + PART_HDR_SIZE + hdr.paddedDataSize()).toInt()
            if (dataOffset + hdr.dsize > data.size) break
            out.add(PartEntry(idx, off, dataOffset, nextOffset, hdr))
            idx++
            off = nextOffset
            if (hdr.imgListEnd != 0L) break
        }
        return out
    }

    // ---- DER TLV (parse_mtk_certs.py) ----
    data class TagInfo(val tagClass: Int, val constructed: Boolean, val tagNum: Int, val tagBytes: ByteArray, val tagLen: Int)

    fun readTag(data: ByteArray, off: Int): TagInfo {
        val b0 = data[off].toInt() and 0xFF
        val tagClass = (b0 and 0xC0) shr 6
        val constructed = (b0 and 0x20) != 0
        var tagNum = b0 and 0x1F
        var tagBytes = byteArrayOf(data[off])
        var i = off + 1
        if (tagNum == 0x1F) {
            tagNum = 0
            while (true) {
                if (i >= data.size) throw IllegalArgumentException("Truncated long-form tag")
                val b = data[i].toInt() and 0xFF
                tagBytes += data[i]
                tagNum = (tagNum shl 7) or (b and 0x7F)
                i++
                if ((b and 0x80) == 0) break
            }
        }
        return TagInfo(tagClass, constructed, tagNum, tagBytes, i - off)
    }

    /** Returns (length or null for indefinite, length-field byte count). */
    fun readLength(data: ByteArray, off: Int): Pair<Long?, Int> {
        if (off >= data.size) throw IllegalArgumentException("Truncated length")
        val b = data[off].toInt() and 0xFF
        if ((b and 0x80) == 0) return b.toLong() to 1
        val num = b and 0x7F
        if (num == 0) return null to 1
        if (off + 1 + num > data.size) throw IllegalArgumentException("Truncated length bytes")
        var value = 0L
        for (i in 0 until num) value = (value shl 8) or (data[off + 1 + i].toLong() and 0xFF)
        return value to (1 + num)
    }

    fun decodeOid(oidBytes: ByteArray): String {
        if (oidBytes.isEmpty()) return ""
        val first = oidBytes[0].toInt() and 0xFF
        val parts = mutableListOf((first / 40).toString(), (first % 40).toString())
        var value = 0L
        for (b in oidBytes.copyOfRange(1, oidBytes.size)) {
            value = (value shl 7) or ((b.toInt() and 0x7F).toLong())
            if ((b.toInt() and 0x80) == 0) {
                parts.add(value.toString())
                value = 0
            }
        }
        return parts.joinToString(".")
    }

    fun encodeLength(n: Long): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = mutableListOf<Byte>()
        var v = n
        val tmp = mutableListOf<Byte>()
        while (v > 0) {
            tmp.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }
        bytes.add((0x80 or tmp.size).toByte())
        bytes.addAll(tmp)
        return bytes.toByteArray()
    }

    fun encodeOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toInt() }
        require(parts.size >= 2) { "bad oid" }
        val first = 40 * parts[0] + parts[1]
        val out = mutableListOf<Byte>()
        out.add(first.toByte())
        for (p in parts.drop(2)) {
            if (p == 0) {
                out.add(0)
                continue
            }
            var v = p
            val chunk = mutableListOf<Byte>()
            while (v > 0) {
                chunk.add(0, (v and 0x7F).toByte())
                v = v shr 7
            }
            for ((i, b) in chunk.withIndex()) {
                out.add(if (i != chunk.size - 1) (0x80 or b.toInt()).toByte() else b)
            }
        }
        return out.toByteArray()
    }

    fun buildOidTlv(oid: String): ByteArray {
        val b = encodeOid(oid)
        return byteArrayOf(0x06) + encodeLength(b.size.toLong()) + b
    }

    fun buildBitstringTlv(payload: ByteArray): ByteArray {
        val value = byteArrayOf(0) + payload
        return byteArrayOf(0x03) + encodeLength(value.size.toLong()) + value
    }

    data class TlvNode(
        val off: Int, val tagClass: Int, val constructed: Boolean, val tagNum: Int,
        val hdrLen: Int, val length: Long, val value: ByteArray?, val children: List<TlvNode>?,
        val tagBytes: ByteArray,
    )

    fun parseTlvTree(data: ByteArray, baseOff: Int = 0): List<TlvNode> {
        val nodes = mutableListOf<TlvNode>()
        var off = 0
        val len = data.size
        while (off < len) {
            val tag: TagInfo
            val length: Long?
            val lenLen: Int
            try {
                tag = readTag(data, off)
                val (l, ll) = readLength(data, off + tag.tagLen)
                length = l
                lenLen = ll
            } catch (e: Exception) {
                break
            }
            if (length == null) break
            val hdrLen = tag.tagLen + lenLen
            val valOff = off + hdrLen
            val valEnd = (valOff + length).toInt()
            if (valEnd > len) break
            val value = data.copyOfRange(valOff, valEnd)
            val node = TlvNode(
                off = baseOff + off, tagClass = tag.tagClass, constructed = tag.constructed,
                tagNum = tag.tagNum, hdrLen = hdrLen, length = length,
                value = if (tag.constructed) null else value,
                children = if (tag.constructed) parseTlvTree(value, baseOff + valOff) else null,
                tagBytes = tag.tagBytes,
            )
            nodes.add(node)
            off = valEnd
        }
        return nodes
    }

    private fun findBitstringInNode(node: TlvNode?): ByteArray? {
        if (node == null) return null
        if (node.tagClass == 0 && node.tagNum == 3 && node.value != null) return node.value
        node.children?.forEach { child ->
            val res = findBitstringInNode(child)
            if (res != null) return res
        }
        return null
    }

    /** Returns (bitstring bytes incl. unused-bits prefix, OID absolute offset) or (null, null). */
    fun findOidBitstring(der: ByteArray, oidStr: String): Pair<ByteArray?, Int?> {
        val rootNodes = parseTlvTree(der, 0)
        fun walk(nodes: List<TlvNode>): Pair<ByteArray?, Int?> {
            for ((i, node) in nodes.withIndex()) {
                if (node.tagClass == 0 && node.tagNum == 6 && node.value != null) {
                    val name = try {
                        decodeOid(node.value)
                    } catch (e: Exception) {
                        ""
                    }
                    if (name == oidStr) {
                        if (i + 1 < nodes.size) {
                            val bs = findBitstringInNode(nodes[i + 1])
                            if (bs != null) return bs to node.off
                        }
                        if (i + 1 < nodes.size) {
                            for (j in i + 1 until nodes.size) {
                                val bs = findBitstringInNode(nodes[j])
                                if (bs != null) return bs to node.off
                            }
                        }
                        return null to node.off
                    }
                }
                if (node.children != null) {
                    val res = walk(node.children)
                    if (res.first != null) return res
                }
            }
            return null to null
        }
        return walk(rootNodes)
    }

    data class TopTlv(val off: Int, val end: Int, val tagClass: Int, val constructed: Boolean, val tagNum: Int, val tagBytes: ByteArray, val hdrLen: Int, val length: Long, val valOff: Int)

    fun iterTopLevelTlvs(data: ByteArray): List<TopTlv> {
        val out = mutableListOf<TopTlv>()
        var off = 0
        while (off < data.size) {
            val tag = readTag(data, off)
            val (length, lenLen) = readLength(data, off + tag.tagLen)
                ?: throw IllegalArgumentException("indefinite length at relative offset 0x${off.toString(16)}")
            val hdrLen = tag.tagLen + lenLen
            val end = off + hdrLen + length!!.toInt()
            if (end > data.size) throw IllegalArgumentException("truncated TLV at relative offset 0x${off.toString(16)}")
            out.add(TopTlv(off, end, tag.tagClass, tag.constructed, tag.tagNum, tag.tagBytes, hdrLen, length, off + hdrLen))
            off = end
        }
        return out
    }

    fun findOriginalCert2Der(cert2Blob: ByteArray): Pair<ByteArray, Int> {
        for (node in iterTopLevelTlvs(cert2Blob)) {
            if (node.tagClass == 0 && node.constructed && node.tagNum == 0x10) {
                return cert2Blob.copyOfRange(node.off, node.end) to node.off
            }
        }
        throw IllegalArgumentException("original CERT2 DER SEQUENCE (0x30) not found")
    }

    fun buildHashOverrideBlock(headerDigest: ByteArray?, imageDigest: ByteArray?): ByteArray {
        val parts = mutableListOf<ByteArray>()
        if (headerDigest != null) {
            parts.add(buildOidTlv(OID_IMAGE_HEADER_HASH))
            parts.add(buildBitstringTlv(headerDigest))
        }
        if (imageDigest != null) {
            parts.add(buildOidTlv(OID_IMAGE_HASH))
            parts.add(buildBitstringTlv(imageDigest))
        }
        if (parts.isEmpty()) return ByteArray(0)
        val content = parts.fold(ByteArray(0)) { acc, b -> acc + b }
        return byteArrayOf(0xA0.toByte()) + encodeLength(content.size.toLong()) + content
    }

    fun replaceCert2Blob(data: ByteArray, certBlobOff: Int, certHdrOff: Int, certHdr: PartHdr, newBlob: ByteArray): ByteArray {
        val newDsz = newBlob.size.toLong()
        val oldPadded = certHdr.paddedDataSize().toInt()
        val newPadded = roundup(newDsz, certHdr.alignSz).toInt()
        val newBlobPadded = newBlob + ByteArray(newPadded - newBlob.size)
        // Python slice assignment resizes the buffer when lengths differ.
        val out = data.copyOfRange(0, certBlobOff) + newBlobPadded + data.copyOfRange(certBlobOff + oldPadded, data.size)
        val patched = out.copyOf()
        val dszBytes = ValBytes.packU32(newDsz)
        dszBytes.copyInto(patched, certHdrOff + 4)
        return patched
    }

    fun encodeDerRootWithInsertion(der: ByteArray, insertBlock: ByteArray): ByteArray {
        val tag = readTag(der, 0)
        val (lengthOld, lenLen) = readLength(der, tag.tagLen)
            ?: throw IllegalArgumentException("indefinite length at DER root")
        val hdrLen = tag.tagLen + lenLen
        val rest = der.copyOfRange(hdrLen, der.size)
        val newLen = lengthOld!! + insertBlock.size
        return tag.tagBytes + encodeLength(newLen) + insertBlock + rest
    }

    data class SignInfo(
        val cert2Offset: Int, val cert2BlobOff: Int, val cert2Dsz: Long,
        val oldHeaderDigest: ByteArray?, val oldImageDigest: ByteArray?,
        val newHeaderDigest: ByteArray?, val newImageDigest: ByteArray?,
        val log: List<String>,
    )

    data class SignResult(val signed: ByteArray, val info: SignInfo)

    /** Port of sign_mtk_cert.py main() write path (plus its info prints as log lines).
     *  Throws IllegalArgumentException / IllegalStateException on the same
     *  conditions where Python exits 1/2.
     */
    fun sign(data: ByteArray, legacy: Boolean): SignResult {
        val log = mutableListOf<String>()
        val parts = parsePartHeaders(data)
        if (parts.isEmpty()) throw IllegalStateException("No part_hdr_t headers found, exiting")
        var cert2Entry: PartEntry? = null
        for (entry in parts) {
            if (entry.hdr.imgType == IMG_TYPE_CERT2) {
                cert2Entry = entry
                break
            }
        }
        val (cIdx, cOff, cBlobOff, cNextOff, cHdr) = cert2Entry
            ?: throw IllegalStateException("No CERT2 partition header found, exiting")
        val cDsz = cHdr.dsize
        val der = data.copyOfRange(cBlobOff, (cBlobOff + cDsz).toInt())
        log.add("Found CERT2 at offset 0x${"%08x".format(cOff)}, blob_off=0x${"%08x".format(cBlobOff)}, dsz=$cDsz")
        var originalCert2Der: ByteArray? = null
        var originalCert2Rel = 0
        if (legacy) {
            val (origDer, rel) = try {
                findOriginalCert2Der(der)
            } catch (e: Exception) {
                throw IllegalStateException("Legacy mode cannot locate original CERT2 DER: ${e.message}")
            }
            originalCert2Der = origDer
            originalCert2Rel = rel
            log.add("Legacy source CERT2 DER at rel=0x${rel.toString(16)}, size=${origDer.size}")
        }
        var oldHdrDigest: ByteArray? = null
        var oldImgDigest: ByteArray? = null
        val (hdrBit, _) = findOidBitstring(der, OID_IMAGE_HEADER_HASH)
        if (hdrBit == null) {
            log.add("OID 2.16.886.2454.2.4 or its following BIT STRING not found in CERT2")
        } else if (hdrBit.isEmpty()) {
            log.add("Invalid bitstring for img header")
        } else {
            oldHdrDigest = hdrBit.copyOfRange(1, hdrBit.size)
            log.add("Image Header Hash (orig): ${oldHdrDigest.hex()}")
            val algHdr = when (oldHdrDigest.size) {
                32 -> "sha256"
                48 -> "sha384"
                else -> "unknown"
            }
            log.add("Detected header hash algorithm: $algHdr")
        }
        val (imgBit, _) = findOidBitstring(der, OID_IMAGE_HASH)
        if (imgBit == null) {
            log.add("OID 2.16.886.2454.2.1 or its following BIT STRING not found in CERT2")
        } else if (imgBit.isEmpty()) {
            log.add("Invalid bitstring for image")
        } else {
            oldImgDigest = imgBit.copyOfRange(1, imgBit.size)
            log.add("Image Hash (orig): ${oldImgDigest.hex()}")
            val algImg = when (oldImgDigest.size) {
                32 -> "sha256"
                48 -> "sha384"
                else -> "unknown"
            }
            log.add("Detected image hash algorithm: $algImg")
        }
        var targetPart: PartEntry? = null
        for (entry in parts) {
            if (entry.off < cOff) {
                val group = entry.hdr.imgType and 0xff000000L
                if (group != IMG_TYPE_GROUP_CERT) targetPart = entry
            }
        }
        val target = targetPart ?: throw IllegalStateException("No target image partition found before CERT2; cannot compute hashes")
        val imgHdrSz = if (target.hdr.hdrSz != 0L) target.hdr.hdrSz else PART_HDR_SIZE.toLong()
        val headerBytes = data.copyOfRange(target.off, (target.off + imgHdrSz).toInt())
        val paddedSize = target.hdr.paddedDataSize().toInt()
        val dataBytes = data.copyOfRange(target.dataOff, target.dataOff + paddedSize)
        var newHdrDigest: ByteArray? = null
        var newImgDigest: ByteArray? = null
        if (oldHdrDigest != null) {
            val md = MessageDigest.getInstance(if (oldHdrDigest.size == 32) "SHA-256" else "SHA-384")
            newHdrDigest = md.digest(headerBytes)
            log.add("Image Header Hash (calc): ${newHdrDigest.hex()}")
        }
        if (oldImgDigest != null) {
            val md = MessageDigest.getInstance(if (oldImgDigest.size == 32) "SHA-256" else "SHA-384")
            newImgDigest = md.digest(dataBytes)
            log.add("Image Hash (calc): ${newImgDigest.hex()}")
        }
        val insertBlock = buildHashOverrideBlock(newHdrDigest, newImgDigest)
        if (insertBlock.isEmpty()) {
            log.add("No hashes to insert; skipping write.")
            return SignResult(data.copyOf(), SignInfo(cOff, cBlobOff, cDsz, oldHdrDigest, oldImgDigest, newHdrDigest, newImgDigest, log))
        }
        val prefixBlocks = mutableListOf<ByteArray>()
        if (legacy) {
            val legacyBlock = buildBitstringTlv(originalCert2Der!!)
            prefixBlocks.add(legacyBlock)
            log.add("Legacy BIT STRING wrapper size: ${legacyBlock.size}")
        }
        prefixBlocks.add(insertBlock)
        val newBlob = prefixBlocks.fold(ByteArray(0)) { acc, b -> acc + b } + der
        val outBytes = replaceCert2Blob(data, cBlobOff, cOff, cHdr, newBlob)
        val newPadded = roundup(newBlob.size.toLong(), cHdr.alignSz)
        log.add("CERT2 new dsize=${newBlob.size}, padded=$newPadded, align=${cHdr.alignSz}")
        val info = SignInfo(cOff, cBlobOff, cDsz, oldHdrDigest, oldImgDigest, newHdrDigest, newImgDigest, log)
        return SignResult(outBytes, info)
    }

    /** Info-only mode: prints what main() prints without -w. */
    fun describe(data: ByteArray): List<String> {
        val log = mutableListOf<String>()
        val parts = parsePartHeaders(data)
        if (parts.isEmpty()) {
            log.add("No part_hdr_t headers found, exiting")
            return log
        }
        val cert2Entry = parts.firstOrNull { it.hdr.imgType == IMG_TYPE_CERT2 }
            ?: run {
                log.add("No CERT2 partition header found, exiting")
                return log
            }
        val der = data.copyOfRange(cert2Entry.dataOff, (cert2Entry.dataOff + cert2Entry.hdr.dsize).toInt())
        log.add("Found CERT2 at offset 0x${"%08x".format(cert2Entry.off)}, blob_off=0x${"%08x".format(cert2Entry.dataOff)}, dsz=${cert2Entry.hdr.dsize}")
        val (hdrBit, _) = findOidBitstring(der, OID_IMAGE_HEADER_HASH)
        if (hdrBit != null && hdrBit.size >= 1) {
            val oldHdrDigest = hdrBit.copyOfRange(1, hdrBit.size)
            log.add("Image Header Hash (orig): ${oldHdrDigest.hex()}")
            log.add("Detected header hash algorithm: " + when (oldHdrDigest.size) { 32 -> "sha256"; 48 -> "sha384"; else -> "unknown" })
        }
        val (imgBit, _) = findOidBitstring(der, OID_IMAGE_HASH)
        if (imgBit != null && imgBit.size >= 1) {
            val oldImgDigest = imgBit.copyOfRange(1, imgBit.size)
            log.add("Image Hash (orig): ${oldImgDigest.hex()}")
            log.add("Detected image hash algorithm: " + when (oldImgDigest.size) { 32 -> "sha256"; 48 -> "sha384"; else -> "unknown" })
        }
        log.add("No -w specified; finished printing, not writing.")
        return log
    }
}
