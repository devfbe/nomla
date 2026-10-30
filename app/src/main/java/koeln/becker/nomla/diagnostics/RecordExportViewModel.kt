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

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import koeln.becker.nomla.mumble.diagnostics.DiagnosticCapture
import koeln.becker.nomla.mumble.util.NomlaLog

/** Where the Recording page's ZIP export stands. */
sealed interface ExportState {
    data object Idle : ExportState

    /** A ZIP is being written; Share waits. */
    data object Running : ExportState

    /** The ZIP at [uri] is written and waits to be offered to the share sheet, once. */
    data class Ready(val uri: Uri) : ExportState

    /** The last export failed; the page says so until the next one. */
    data object Failed : ExportState
}

/**
 * The Recording page's export: one at a time, on [ioDispatcher], in the view model's scope, so
 * neither a rotation nor leaving the page loses it. The page collects [state] while started and
 * opens the share sheet for the URI of [readyUri], then calls [shared].
 *
 * An export carries [DiagnosticShare.epoch] from when it was begun: one revoked meanwhile (debug
 * off, discard) ends in [ExportState.Idle], and a finished one is never handed out once revoked.
 *
 * @param exporter writes the ZIP for the given epoch and returns its URI; [DiagnosticShare.export]
 *   outside tests.
 */
class RecordExportViewModel(
    private val app: Application,
    private val ioDispatcher: CoroutineDispatcher,
    private val exporter: (Context, DiagnosticCapture, CaptureReview, Long) -> Uri = DiagnosticShare::export,
) : ViewModel() {
    private val current = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = current.asStateFlow()
    private var job: Job? = null

    /** [DiagnosticShare.epoch] when the last export was begun. */
    private var epoch = DiagnosticShare.epoch()

    /** Writes [capture] with [review] as a ZIP; nothing while an export runs. */
    fun export(capture: DiagnosticCapture, review: CaptureReview) {
        if (current.value == ExportState.Running) return
        current.value = ExportState.Running
        val begun = DiagnosticShare.epoch().also { epoch = it }
        job = viewModelScope.launch {
            current.value = try {
                ExportState.Ready(withContext(ioDispatcher) { exporter(app, capture, review, begun) })
            } catch (e: DiagnosticShare.Revoked) {
                // A reset that cancelled this export may already have begun the next one.
                ensureActive()
                NomlaLog.v(TAG, "diagnostics export revoked: $e")
                ExportState.Idle
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Any failure of the export is reported, never a crash; a cancellation passes on.
                ensureActive()
                failed(e)
            } catch (e: OutOfMemoryError) {
                // The ZIP of a large capture may not fit next to it; that is a failed export too.
                failed(e)
            }
        }
    }

    private fun failed(cause: Throwable): ExportState {
        NomlaLog.w(TAG, "diagnostics export failed: $cause")
        return ExportState.Failed
    }

    /**
     * The URI of the [ExportState.Ready] export to share, or null; one revoked since it was begun is
     * dropped (back to [ExportState.Idle]) instead, since its file is gone.
     */
    fun readyUri(): Uri? {
        val ready = current.value as? ExportState.Ready ?: return null
        if (epoch == DiagnosticShare.epoch()) return ready.uri
        reset()
        return null
    }

    /** The share sheet was opened for the [ExportState.Ready] export. */
    fun shared() {
        if (current.value is ExportState.Ready) current.value = ExportState.Idle
    }

    /** Drops a running or finished export: its capture is gone, or another one came up. */
    fun reset() {
        job?.cancel()
        job = null
        current.value = ExportState.Idle
    }

    private companion object {
        const val TAG = "RecordExport"
    }
}
