// BromLogFile.kt — BROM log files (1.1.3).
//
// Every BROM log line is ALSO appended to
// Download/MotoFlasher/motoflasher-brom-<yyyyMMdd-HHmmss>.txt — one new
// file per Connect run. MediaStore insert first (same pattern as the
// crash catcher in MainActivity), getExternalFilesDir fallback.
// Logging must never crash the flow: every failure is swallowed and
// the file is simply dropped (the on-screen log keeps working).
package com.cronch.motoflasher.brom

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BromLogFile(private val ctx: Context) {
    private val lock = Any()
    private var out: OutputStream? = null
    private var displayPath: String? = null

    // Start a new log file for a Connect run (closes any previous one).
    // Returns the display path, or null when no file could be created.
    fun startRun(): String? {
        synchronized(lock) {
            closeLocked()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "motoflasher-brom-$stamp.txt"
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MotoFlasher")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    val stream = ctx.contentResolver.openOutputStream(uri)
                    if (stream != null) {
                        out = stream
                        displayPath = "/sdcard/Download/MotoFlasher/$name"
                        return displayPath
                    }
                }
            } catch (_: Exception) {
            }
            try {
                val file = File(ctx.getExternalFilesDir(null), name)
                out = FileOutputStream(file)
                displayPath = file.absolutePath
                return displayPath
            } catch (_: Exception) {
            }
            displayPath = null
            return null
        }
    }

    // Append one log entry (may contain newlines — each line gets its
    // own timestamp, matching the on-screen log). No-op when no file
    // is open. Thread-safe: BROM lines come from background threads.
    fun append(line: String) {
        synchronized(lock) {
            val stream = out ?: return
            try {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                for (part in line.split("\n")) {
                    sb.append("[$ts] ").append(part).append("\n")
                }
                stream.write(sb.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (_: Exception) {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                out = null
            }
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
    }
}
