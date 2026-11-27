// BromDownloads.kt — finished-file delivery to public Downloads (SPEC 5.13).
//
// Rule: every finished file the user needs to find lands in
// Download/MotoFlasher/<sub>/ so it shows in the Files app. Android/data is
// not accessible natively on Android, so the app-private external dir is
// never a delivery location for finished files.
//
// Primary path is a MediaStore Downloads insert (RELATIVE_PATH
// "Download/MotoFlasher/<sub>/"): it needs no extra permission on
// targetSdk 34, needs no root, and the file is visible in Files at once.
// Fallback, only if the MediaStore insert fails, is a copy through the
// app's existing root shell (Fastboot.runSu): the host is rooted anyway,
// and a finished file must never be silently dropped. The fallback logs
// loudly which path was used so the log always says where the file went.
package com.cronch.motoflasher.brom

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cronch.motoflasher.Fastboot
import java.io.File

object BromDownloads {

    // Public path the user sees in the Files app for a published file.
    fun publicPath(subDir: String, displayName: String): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MotoFlasher/$subDir/$displayName",
        )

    // Publish srcFile to Download/MotoFlasher/<subDir>/<displayName>.
    // Returns the public File on success, or null on failure (the failure
    // is logged loudly by the caller-visible onLog, never swallowed).
    fun publish(
        ctx: Context,
        srcFile: File,
        subDir: String,
        displayName: String,
        onLog: (String) -> Unit,
    ): File? {
        if (!srcFile.exists() || srcFile.length() == 0L) {
            onLog("DOWNLOADS FAILED: source missing or empty: ${srcFile.absolutePath}")
            return null
        }
        val cleanSub = subDir.trim('/').replace("..", "")
        val dest = publicPath(cleanSub, displayName)
        val viaStore = publishViaMediaStore(ctx, srcFile, cleanSub, displayName, dest, onLog)
        if (viaStore != null) return viaStore
        onLog("DOWNLOADS: MediaStore insert failed for $displayName — falling back to root cp")
        return publishViaRoot(srcFile, dest, onLog)
    }

    private fun publishViaMediaStore(
        ctx: Context,
        srcFile: File,
        subDir: String,
        displayName: String,
        dest: File,
        onLog: (String) -> Unit,
    ): File? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/MotoFlasher/$subDir/")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: run {
                onLog("DOWNLOADS FAILED: MediaStore insert returned null for $displayName")
                return null
            }
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                srcFile.inputStream().use { input -> input.copyTo(out) }
            } ?: run {
                onLog("DOWNLOADS FAILED: MediaStore output stream null for $displayName")
                return null
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                ctx.contentResolver.update(uri, done, null, null)
            }
            if (!dest.exists() || dest.length() != srcFile.length()) {
                onLog(
                    "DOWNLOADS FAILED: MediaStore wrote ${dest.length()} bytes, " +
                        "expected ${srcFile.length()} for $displayName",
                )
                return null
            }
            onLog("Download: ${dest.absolutePath} (${dest.length()} bytes)")
            dest
        } catch (e: Exception) {
            onLog("DOWNLOADS FAILED: MediaStore publish of $displayName failed: ${e.message}")
            null
        }
    }

    private fun publishViaRoot(srcFile: File, dest: File, onLog: (String) -> Unit): File? {
        return try {
            val dir = dest.parentFile ?: return null
            val mkdir = Fastboot.runSu("mkdir -p '${shellQuote(dir.absolutePath)}'", {})
            if (!mkdir.ok) {
                onLog("DOWNLOADS FAILED: root mkdir failed for ${dir.absolutePath}: ${mkdir.output}")
                return null
            }
            val cp = Fastboot.runSu(
                "cp '${shellQuote(srcFile.absolutePath)}' '${shellQuote(dest.absolutePath)}'",
                {},
            )
            if (!cp.ok || !dest.exists() || dest.length() != srcFile.length()) {
                onLog(
                    "DOWNLOADS FAILED: root cp failed for ${srcFile.name} " +
                        "(exit ${cp.exitCode}): ${cp.output.trim()}",
                )
                return null
            }
            onLog("Download (via root cp): ${dest.absolutePath} (${dest.length()} bytes)")
            dest
        } catch (e: Exception) {
            onLog("DOWNLOADS FAILED: root cp publish of ${srcFile.name} failed: ${e.message}")
            null
        }
    }

    private fun shellQuote(s: String): String = s.replace("'", "'\\''")
}
