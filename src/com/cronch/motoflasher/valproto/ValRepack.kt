// lk_repack_signed.py — reinsert a patched payload into its MTK container,
// re-sign CERT2 (in-process via ValCert), splice the signed block back.
//
// CONTAINER LAYER: all container structure comes from LibLk.kt
// (com.cronch.motoflasher.LibLk / LkImage) — the parsed container the app
// flow already holds. lk_repack_signed.py's own `class PartHdr` (line 24),
// which duplicated liblk's header parsing, is deliberately NOT ported:
// region boundaries (partition offset, cert attachments, end offsets) are
// read off the LkImage's partitions, and untouched bytes are spliced from
// the original container bytes. The Python tool shells out to
// sign_mtk_cert.py, verify_mtk_image.py and build-part-img.py; here the
// sign + splice run in-process. Verification (verify_mtk_image.py, 660
// lines) is NOT part of this port: repack reports verified=false and the
// caller must run the Python verifier externally until/unless that tool
// is ported.
//
// NOTE on ValCert: sign_mtk_cert.py operates on the signed-block slice
// (payload + its certs) and locates CERT2 by img_type 0x02000002 — a field
// LibLk's LkPartition does not retain — so the sign tool keeps its own
// small header reader for that slice. That is the sign tool's own logic,
// not the repack's container walk; the container walk is what moved here.
package com.cronch.motoflasher.valproto

import com.cronch.motoflasher.LibLk
import java.io.File

object ValRepack {
    class RepackError(message: String) : Exception(message)

    const val PART_HDR_SIZE = 512
    const val MAX_FOLLOWING_CERTS = 2

    /** The signed region of one subimage: its partition header + payload +
     *  up to 2 following cert partitions (mirrors Python find_signed_region). */
    data class SignedRegion(val start: Int, val end: Int, val partition: LibLk.LkPartition)

    /** Locate the named subimage's signed region from the parsed LkImage.
     *  Region end = endOffset of the last attached cert (max 2, as in
     *  Python), or the partition's own endOffset when it has no certs. */
    fun findSignedRegion(image: LibLk.LkImage, imageName: String): SignedRegion {
        val part = image.partitions.firstOrNull { it.name == imageName }
            ?: throw RepackError("subimage not found: '$imageName'")
        val certs = part.certs.take(MAX_FOLLOWING_CERTS)
        val end = certs.lastOrNull()?.endOffset ?: part.endOffset
        return SignedRegion(part.offset, end, part)
    }

    /** Swap the payload into a signed-block slice. Mirrors Python
     *  replace_payload_in_signed_block exactly, including the fixed 512-byte
     *  header offset (Python uses PART_HDR_SIZE, not hdr_sz, even for
     *  extended headers) and the same-size-only rule. */
    fun replacePayloadInSignedBlock(partition: LibLk.LkPartition, signedBlock: ByteArray, payload: ByteArray): ByteArray {
        if (partition.dataSize != payload.size.toLong()) throw RepackError(
            "payload size mismatch: patched=${payload.size} original dsize=${partition.dataSize}. This flow only accepts same-size in-place patches."
        )
        if (signedBlock.size < PART_HDR_SIZE + payload.size) throw RepackError(
            "signed block is shorter than header + payload for '${partition.name}'"
        )
        val out = signedBlock.copyOf()
        payload.copyInto(out, PART_HDR_SIZE)
        return out
    }

    data class RepackResult(
        val output: ByteArray,
        val regionStart: Int,
        val regionEnd: Int,
        val signedBlockSize: Int,
        /** verify_mtk_image.py is not ported; no in-process verification ran. */
        val verified: Boolean = false,
        val certLog: List<String>,
    )

    /** Full repack from the parsed container.
     *
     *  @param lkImage the LkImage parsed from [originalContainer] (same
     *      object the app flow already holds; structure/offsets come from it).
     *  @param originalContainer the original container bytes the lkImage was
     *      parsed from; untouched regions are spliced from here, so the
     *      output is a rebuilt container, not a re-serialized one.
     *  @param patchedPayload same-size patched payload for [imageName].
     *  @return the patched container bytes (rebuilt LkImage), ready to flash.
     *
     *  Steps (Python parity): find region -> swap same-size payload ->
     *  re-sign CERT2 with the legacy BITSTRING wrapper (Python default:
     *  --legacy) -> splice the signed block back (build-part-img.py replace
     *  semantics). */
    fun repack(lkImage: LibLk.LkImage, originalContainer: ByteArray, patchedPayload: ByteArray, imageName: String = "lk", legacyCert: Boolean = true): RepackResult {
        val region = findSignedRegion(lkImage, imageName)
        // Mirror Python find_signed_region's first check: the region must
        // start with a part_hdr_t magic. BFBF-prefixed containers (e.g.
        // lk_legacy.img) keep their header at 0x4040, so offset 0 reads
        // "BFBF" (0x46424642) and Python refuses here; refuse identically
        // instead of slicing a block ValCert.sign then rejects.
        val magic = ValBytes.u32At(originalContainer, region.start)
        if (magic != 0x58881688L) throw RepackError(
            "invalid magic at offset 0x${"%x".format(region.start)}: 0x${"%08x".format(magic)}"
        )
        // 2.0.12: PC-parity repack block (lk_repack_signed.py prints).
        // Strings only — every value here is already computed above.
        println("Subimage : $imageName")
        println("Region : 0x${region.start.toString(16)}-0x${region.end.toString(16)}")
        println("Payload size : ${patchedPayload.size} bytes")
        println("Original dsize : ${region.partition.dataSize} bytes")
        val unsignedPatched = replacePayloadInSignedBlock(
            region.partition,
            originalContainer.copyOfRange(region.start, region.end),
            patchedPayload,
        )
        // 2.0.12: sign_mtk_cert.py's opening line, before ValCert's own log.
        println("Name: $imageName Size: ${unsignedPatched.size}")
        val signResult = ValCert.sign(unsignedPatched, legacyCert)
        val signedBlock = signResult.signed
        val output = originalContainer.copyOfRange(0, region.start) + signedBlock + originalContainer.copyOfRange(region.end, originalContainer.size)
        return RepackResult(output, region.start, region.end, signedBlock.size, verified = false, certLog = signResult.info.log)
    }

    fun repackFiles(originalImage: File, patchedPayload: File, output: File, imageName: String = "lk", legacyCert: Boolean = true): RepackResult {
        val containerBytes = originalImage.readBytes()
        val lkImage = LibLk.parse(containerBytes)
        val result = repack(lkImage, containerBytes, patchedPayload.readBytes(), imageName, legacyCert)
        output.writeBytes(result.output)
        return result
    }
}
