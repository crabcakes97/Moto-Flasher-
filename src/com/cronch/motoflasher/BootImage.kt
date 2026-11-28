// BootImage.kt — read-only Android boot image header inspector (2.0.13).
//
// Parses the header of an Android boot image (v0-v4, magic "ANDROID!")
// or a vendor boot image (v3/v4, magic "VNDRBOOT") from the first
// bytes of a file and returns plain facts: sizes, page size, cmdline,
// OS version and patch level where the header carries them. It never
// writes anything and never flashes — the Flash tab's inspector card
// only displays and logs what this returns.
//
// Pure Kotlin with no Android imports, so the same file compiles in a
// JVM harness on the build VM and can be checked against a real
// boot.img before the app ever runs it.
//
// Header layouts (AOSP bootimg.h):
// - Discriminator: the u32 at offset 40. 3 or 4 means the v3/v4
//   layout, 1 or 2 the v1/v2 layout, anything else is v0 (where
//   offset 40 is os_version, not a header version).
// - v0: magic[8], kernel_size, kernel_addr, ramdisk_size,
//   ramdisk_addr, second_size, second_addr, tags_addr, page_size,
//   os_version, name[16], cmdline[512], id[32], extra_cmdline[1024].
// - v1/v2: same through page_size, then header_version, os_version,
//   name[16], cmdline[512], id[32], extra_cmdline[1024]; v1 adds
//   recovery_dtbo_size, recovery_dtbo_offset (u64), header_size;
//   v2 adds dtb_size, dtb_addr (u64).
// - v3: magic, kernel_size, ramdisk_size, os_version, header_size,
//   reserved[4], header_version, cmdline[1536]. Page size is fixed
//   at 4096. v4 adds signature_size after the cmdline.
// - vendor_boot: magic, header_version, page_size, kernel_addr,
//   ramdisk_addr, vendor_ramdisk_size, cmdline[2048], tags_addr,
//   name[16], header_size, dtb_size, dtb_addr (u64); v4 adds the
//   ramdisk table size/count/entry size and bootconfig_size.
//
// os_version encoding: major = bits 31..25, minor = 24..18,
// patch = 17..11, patch-level year = bits 10..4 (+2000), month =
// bits 3..0.
package com.cronch.motoflasher

object BootImage {

    /** Parsed facts, one display line each. [isBoot] is false when the
     *  file is neither a boot image nor a vendor boot image. */
    data class Info(val isBoot: Boolean, val lines: List<String>)

    /** Inspect [bytes] (the first bytes of the file; 64 KB is more
     *  than enough for any header) against the full [fileSize]. */
    fun inspect(bytes: ByteArray, fileSize: Long): Info {
        if (bytes.size < 8) {
            return Info(false, listOf("Not an Android boot image: file is only ${bytes.size} bytes."))
        }
        val magic = ascii(bytes, 0, 8)
        return when (magic) {
            "ANDROID!" -> inspectBoot(bytes, fileSize)
            "VNDRBOOT" -> inspectVendorBoot(bytes, fileSize)
            else -> Info(
                false,
                listOf("Not an Android boot image (magic: ${printable(magic)})."),
            )
        }
    }

    private fun inspectBoot(b: ByteArray, fileSize: Long): Info {
        if (b.size < 44) return Info(false, listOf("Boot image header is truncated (${b.size} bytes read)."))
        val field40 = u32(b, 40)
        val lines = mutableListOf<String>()
        when (field40) {
            3L, 4L -> {
                // v3/v4 layout.
                val kernelSize = u32(b, 8)
                val ramdiskSize = u32(b, 12)
                val osVersion = u32(b, 16)
                val headerSize = u32(b, 20)
                val cmdline = ascii(b, 44, 1536)
                lines.add("Format: Android boot image v$field40")
                lines.add("Kernel: $kernelSize bytes")
                lines.add("Ramdisk: $ramdiskSize bytes")
                lines.add("Page size: 4096 (fixed in v3/v4)")
                lines.add("Header size: $headerSize bytes")
                lines.add("OS version: ${osVersionText(osVersion)}")
                if (field40 == 4L && b.size >= 1584) {
                    lines.add("Signature size: ${u32(b, 1580)} bytes")
                }
                lines.add("Cmdline: ${cmdline.ifEmpty { "(empty)" }}")
            }
            1L, 2L -> {
                // v1/v2 layout.
                val kernelSize = u32(b, 8)
                val kernelAddr = u32(b, 12)
                val ramdiskSize = u32(b, 16)
                val ramdiskAddr = u32(b, 20)
                val secondSize = u32(b, 24)
                val pageSize = u32(b, 36)
                val osVersion = u32(b, 44)
                val name = ascii(b, 48, 16)
                val cmdline = ascii(b, 64, 512)
                val extraCmdline = ascii(b, 608, 1024)
                lines.add("Format: Android boot image v$field40")
                lines.add("Kernel: $kernelSize bytes at 0x${kernelAddr.toString(16)}")
                lines.add("Ramdisk: $ramdiskSize bytes at 0x${ramdiskAddr.toString(16)}")
                lines.add("Second stage: $secondSize bytes")
                lines.add("Page size: $pageSize")
                lines.add("OS version: ${osVersionText(osVersion)}")
                if (name.isNotEmpty()) lines.add("Name: $name")
                if (b.size >= 1648) {
                    lines.add("Recovery DTBO: ${u32(b, 1632)} bytes")
                    lines.add("Header size: ${u32(b, 1644)} bytes")
                }
                if (field40 == 2L && b.size >= 1660) {
                    lines.add("DTB: ${u32(b, 1648)} bytes at 0x${u64(b, 1652).toString(16)}")
                }
                lines.add("Cmdline: ${cmdline.ifEmpty { "(empty)" }}")
                if (extraCmdline.isNotEmpty()) lines.add("Extra cmdline: $extraCmdline")
            }
            else -> {
                // v0 layout: offset 40 is os_version.
                val kernelSize = u32(b, 8)
                val kernelAddr = u32(b, 12)
                val ramdiskSize = u32(b, 16)
                val ramdiskAddr = u32(b, 20)
                val secondSize = u32(b, 24)
                val pageSize = u32(b, 36)
                val osVersion = field40
                val name = ascii(b, 44, 16)
                val cmdline = ascii(b, 60, 512)
                val extraCmdline = ascii(b, 604, 1024)
                lines.add("Format: Android boot image v0")
                lines.add("Kernel: $kernelSize bytes at 0x${kernelAddr.toString(16)}")
                lines.add("Ramdisk: $ramdiskSize bytes at 0x${ramdiskAddr.toString(16)}")
                lines.add("Second stage: $secondSize bytes")
                lines.add("Page size: $pageSize")
                lines.add("OS version: ${osVersionText(osVersion)}")
                if (name.isNotEmpty()) lines.add("Name: $name")
                lines.add("Cmdline: ${cmdline.ifEmpty { "(empty)" }}")
                if (extraCmdline.isNotEmpty()) lines.add("Extra cmdline: $extraCmdline")
            }
        }
        lines.add("File size: $fileSize bytes")
        return Info(true, lines)
    }

    private fun inspectVendorBoot(b: ByteArray, fileSize: Long): Info {
        if (b.size < 2128) return Info(false, listOf("Vendor boot header is truncated (${b.size} bytes read)."))
        val headerVersion = u32(b, 8)
        val pageSize = u32(b, 12)
        val kernelAddr = u32(b, 16)
        val ramdiskAddr = u32(b, 20)
        val ramdiskSize = u32(b, 24)
        val cmdline = ascii(b, 28, 2048)
        val name = ascii(b, 2080, 16)
        val headerSize = u32(b, 2096)
        val dtbSize = u32(b, 2100)
        val dtbAddr = u64(b, 2104)
        val lines = mutableListOf<String>()
        lines.add("Format: Android vendor boot image v$headerVersion")
        lines.add("Vendor ramdisk: $ramdiskSize bytes at 0x${ramdiskAddr.toString(16)}")
        lines.add("Kernel load address: 0x${kernelAddr.toString(16)}")
        lines.add("Page size: $pageSize")
        lines.add("Header size: $headerSize bytes")
        lines.add("DTB: $dtbSize bytes at 0x${dtbAddr.toString(16)}")
        if (name.isNotEmpty()) lines.add("Name: $name")
        if (headerVersion >= 4 && b.size >= 2128) {
            lines.add("Ramdisk table: ${u32(b, 2116)} entries, ${u32(b, 2112)} bytes")
            lines.add("Bootconfig: ${u32(b, 2124)} bytes")
        }
        lines.add("Cmdline: ${cmdline.ifEmpty { "(empty)" }}")
        lines.add("File size: $fileSize bytes")
        return Info(true, lines)
    }

    /** Decode the packed os_version field. 0 means the header does
     *  not carry a version. */
    private fun osVersionText(v: Long): String {
        if (v == 0L) return "not set in header"
        val major = (v shr 25) and 0x7f
        val minor = (v shr 18) and 0x7f
        val patch = (v shr 11) and 0x7f
        val year = ((v shr 4) and 0x7f) + 2000
        val month = v and 0xf
        val base = "$major.$minor.$patch"
        return if (month in 1..12) "$base, patch level $year-${month.toString().padStart(2, '0')}" else base
    }

    private fun u32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0
        return (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)
    }

    private fun u64(b: ByteArray, off: Int): Long {
        if (off + 8 > b.size) return 0
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    /** NUL-terminated ASCII field, trimmed at the first NUL. */
    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        if (off >= b.size) return ""
        val end = minOf(off + len, b.size)
        var cut = end
        for (i in off until end) {
            if (b[i] == 0.toByte()) { cut = i; break }
        }
        return String(b, off, cut - off, Charsets.US_ASCII).trim()
    }

    /** Magic bytes for display: non-printables shown as dots. */
    private fun printable(s: String): String =
        s.map { if (it.code in 32..126) it else '.' }.joinToString("")
}
