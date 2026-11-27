// BromApi.kt — shared types for the 2.0.0 BROM half (Penumbra engine).
//
// 2.0.0 replaces the in-app mtkclient port with the bundled Antumbra
// binary (Penumbra v2.0.0, AGPL-3.0 — see LICENSE.penumbra and
// CREDITS.md). The old Kotlin protocol files (BromHandshake, BromDa,
// BromDaLoader, BromPreloader, BromSla, BromCrypto, BromUsb, RootUsb,
// BromSessionImpl) are deleted; the BROM workstreams now go through
// Penumbra.kt (CLI driver) + PenumbraSession.kt (operations). What
// stays from the old API: the result type, the GPT partition shape
// (the device picker shows it), and the constants the UI still needs
// (VID/PID, blankflash order, brick-risk set).
//
// HARD RULES (carried over from 1.1.x, unchanged):
// - no gates anywhere in the BROM flow; blankflash/write buttons are
//   never greyed out; any DA/auth file is accepted, no checks.
// - battery check removed from the BROM half (1.1.11).
// - error messages state the real failure; verbose live-scrolling log.
// - partitions come FROM the device (antumbra pgpt), never the scatter.
// - finished files land in /sdcard/Download/MotoFlasher/.
// - root is re-checked FRESH on every write (the 1.1.19 lesson).
package com.cronch.motoflasher.brom

/** DOCUMENTATION FIRST (Hardware Council verdict): every BROM op carries one. */
enum class DocStatus { VERIFIED, UNTESTED, UNSUPPORTED }

/** One operation result: ok + human-readable detail, or failure + plain reason. */
sealed class BromResult {
    data class Ok(val detail: String) : BromResult()
    data class Fail(val reason: String) : BromResult()
    fun isOk(): Boolean = this is Ok
    fun text(): String = when (this) {
        is Ok -> detail
        is Fail -> "FAILED: $reason"
    }
}

/** One GPT partition as reported by the device (antumbra pgpt). Sizes in bytes. */
data class GptPartition(
    val name: String,
    val startByte: Long,
    val sizeBytes: Long,
    val guid: String,
)

object BromConstants {
    const val VID_MTK: Int = 0x0E8D
    // BROM / preloader PIDs under the MTK VID: preloader 0x2000 is what
    // the phone enumerates as first; BROM 0x0003 appears after DA load.
    val BROM_PIDS: Set<Int> = setOf(0x0003)
    val PRELOADER_PIDS: Set<Int> = setOf(0x2000, 0x6000, 0x2001, 0x201D, 0x20FF, 0x3000)
    // 2.0.2: BATTERY_MIN_PERCENT (30) deleted — the last battery gate
    // (fastboot-half writeGate) is removed, so the constant is dead.

    /** Blankflash scatter order (Nevada package, 11 partitions, slot A only). */
    val BLANKFLASH_ORDER: List<String> = listOf(
        "preloader", "proinfo", "spmfw_a", "pi_img_a", "sspm_a", "mcupm_a",
        "gz_a", "lk_a", "dtbo_a", "tee_a", "logo_a",
    )

    /** Partitions whose writes get the brick-warning confirm (§5.10 rule). */
    val BRICK_RISK_PARTITIONS: Set<String> = setOf(
        "preloader", "lk", "lk_a", "lk_b", "lk2", "boot", "boot_a", "boot_b",
    )

    /** Nevada lk_a scatter address (SP Flash Tool style write). */
    const val NEVADA_LK_A_ADDR: Long = 0x4C400000L
}

class BromException(message: String) : Exception(message)
