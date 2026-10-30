/*
 * Copyright (C) 2026 The Nomla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package koeln.becker.nomla.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import koeln.becker.nomla.chat.ImageShareExporter
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shares a recording as one ZIP in `cacheDir/shared_diagnostics/` through the app's FileProvider
 * (B13). The directory holds at most the last export: it is cleared before every export, on
 * discard and once at process start. So each export revokes the previous ZIP: an app that was
 * handed its URI can no longer open it.
 *
 * [export], [clear] and [clearAtStart] are serialized, so a double tap or the clear at process
 * start never unlinks a ZIP while it is written, and the clear at process start never unlinks one
 * this process already returned.
 */
object DiagnosticShare {
    const val DIRECTORY = "shared_diagnostics"

    private const val MIME_TYPE = "application/zip"

    private val lock = Any()

    /** The ZIP this process exported last, if it still exists; guarded by [lock]. */
    private var exported: File? = null

    private val NAME_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * Writes [capture] with [review] as `nomla-diagnose-<yyyyMMdd-HHmmss>.zip` (its start, UTC),
     * the only file in the directory, and returns its `content://` URI. Revokes the previous
     * export. Blocking I/O.
     */
    fun export(context: Context, capture: DiagnosticCapture, review: CaptureReview): Uri = synchronized(lock) {
        clear(context)
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val file = File(dir, fileName(capture.startedAtEpochMs))
        var written = false
        try {
            file.outputStream().buffered().use { DiagnosticZip.write(capture, review, AppInfo.current(), it) }
            written = true
        } finally {
            // A half-written ZIP is never left to share.
            if (!written) file.delete()
        }
        exported = file
        FileProvider.getUriForFile(context, context.packageName + ImageShareExporter.AUTHORITY_SUFFIX, file)
    }

    /** Deletes every shared ZIP and leftover, revoking the last export. Blocking I/O. */
    fun clear(context: Context) {
        synchronized(lock) {
            exported = null
            deleteFiles(context) { true }
        }
    }

    /**
     * The clear at process start: deletes what earlier processes left, that is every file but the
     * one this process exported, however late it runs. It reads no clock, so a clock set back or
     * forth cannot spare a leftover or take an export. Blocking I/O.
     */
    fun clearAtStart(context: Context) {
        synchronized(lock) {
            val keep = exported
            deleteFiles(context) { it != keep }
        }
    }

    private fun deleteFiles(context: Context, which: (File) -> Boolean) {
        File(context.cacheDir, DIRECTORY).listFiles()?.filter(which)?.forEach { it.deleteRecursively() }
    }

    private fun fileName(startedAtEpochMs: Long): String =
        "nomla-diagnose-${NAME_TIME.format(Instant.ofEpochMilli(startedAtEpochMs))}.zip"

    /** `ACTION_SEND` of the ZIP at [uri], with a read grant on exactly that URI. */
    fun shareIntent(uri: Uri, subject: String): Intent = Intent(Intent.ACTION_SEND)
        .setType(MIME_TYPE)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the read grant on only for URIs in the clip data.
        .apply { clipData = ClipData.newRawUri(subject, uri) }
}
