package com.cronch.motoflasher

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a minimal VALID empty vbmeta image for the vbmeta disabler.
 *
 * This is a real AVB vbmeta header (magic "AVB0", version 1.2, no
 * descriptors, no auth/signature data), 256-byte header padded out to
 * 4096 bytes of zeros. It is NOT a signed image — it only works because
 * it is always flashed together with fastboot's
 * --disable-verity --disable-verification flags, which set the
 * VERIFICATION_DISABLED / HASHTREE_DISABLED bits in the header at flash
 * time. The bootloader then skips verification of the images it covers.
 *
 * Needs an unlocked bootloader. Flashing this on a locked phone fails or
 * (on some phones) bricks — the UI warns before every use.
 */
object Vbmeta {

    /** Returns a 4096-byte file holding a valid empty vbmeta image. */
    fun makeEmptyVbmeta(dir: File): File {
        val buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("AVB0".toByteArray(Charsets.US_ASCII)) // magic
        buf.putInt(1) // required_header_version_major
        buf.putInt(2) // required_header_version_minor
        buf.putLong(0) // authentication_data_block_size
        buf.putLong(0) // auxiliary_data_block_size
        buf.putInt(0) // algorithm_type: NONE
        buf.put(ByteArray(32)) // hash
        buf.put(ByteArray(64)) // signature
        buf.put(ByteArray(32)) // public_key
        buf.putLong(0) // public_key_metadata_size
        buf.putLong(0) // descriptors_size
        buf.putLong(0) // rollback_index
        buf.putInt(0) // flags (fastboot sets the disable bits at flash time)
        buf.putInt(0) // rollback_index_location
        val release = "Moto Flasher disabler".toByteArray(Charsets.US_ASCII)
        buf.put(release)
        buf.put(ByteArray(48 - release.size)) // release_string, NUL padded
        val out = File(dir, "empty-vbmeta.img")
        out.writeBytes(buf.array())
        return out
    }
}
